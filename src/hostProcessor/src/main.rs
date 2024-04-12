
use std::fs;
use std::ops::Deref;
use std::path::PathBuf;
use std::pin::Pin;
use std::process::{ExitCode, Stdio};
use std::rc::Rc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use display_error_chain::ErrorChainExt;
use gumdrop::Options;
use tokio::io::AsyncWriteExt;
use tokio::net::{UnixListener, UnixStream};
use tokio::net::unix::OwnedWriteHalf;
use tokio::process::Command;
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;
use tokio::task::LocalSet;
use tokio_stream::{Stream, StreamExt, StreamMap};
use tokio_util::bytes::Bytes;
use tokio_util::io::ReaderStream;
use tracing::{debug, error_span, info, Instrument, trace, warn};

use host_processor::framing::{AsyncReadFramed, AsyncWriteFramed};
use host_processor::logging::{self, ResultExt};
use host_processor::processes::Processes;
use host_processor::proto::{ConsoleKind, ExecRequest, ExecResponse, ProcConsole, ProcEvent, ProcFin, Request, RequestEnvelope, Response, ResponseEnvelope};


#[derive(Options)]
struct Args {

	/// settings for log output
	#[options(default = "host_processor=info")]
	log: String,

	/// folder to write socket files
	#[options(free, required, parse(try_from_str))]
	socket_dir: PathBuf
}


fn main() -> ExitCode {

	// parse arguments
	let args = Args::parse_args_default_or_exit();

	// init logging
	let Ok(_) = logging::init(&args.log)
		.log_err()
		else { return ExitCode::FAILURE; };

	let Ok(_) = run(args)
		.log_err()
		else { return ExitCode::FAILURE; };

	// we finished! =)
	ExitCode::SUCCESS
}


#[tracing::instrument(skip_all, level = 5, name = "HostProcessor")]
fn run(args: Args) -> Result<()> {

	// check for errors with the socket folder
	let dir_exists = args.socket_dir.try_exists()
		.context(format!("Failed to check socket folder: {}", args.socket_dir.to_string_lossy()))?;
	if dir_exists {
		// see if it's a folder
		let metadata = fs::metadata(&args.socket_dir)
			.context(format!("Failed to get metadata for socket folder: {}", args.socket_dir.to_string_lossy()))?;
		if !metadata.is_dir() {
			bail!("Socket folder path exists, but is not a folder: {}", args.socket_dir.to_string_lossy());
		}
	} else {
		// try creating it
		fs::create_dir_all(&args.socket_dir)
			.context(format!("Failed to create socket folder: {}", args.socket_dir.to_string_lossy()))?;
	}

	// listen on the socket
	let socket_file = args.socket_dir.join(format!("host-processor-{}", std::process::id()));

	// WARNING: Now that we're listening on the socket, don't exit this function without cleaning it up.
	//          That means no ? operator or any other kind of early returns.

	// start a single-threaded tokio runtime
	let result = tokio::runtime::Builder::new_current_thread()
		.enable_io()
		.enable_time()
		.build()
		.context("Failed to create tokio runtime")
		.log_err();
	if let Ok(runtime) = result {

		let socket_file = socket_file.clone();

		debug!("Async runtime started");

		// run the event loop on the async runtime
		runtime
			.block_on(async move {
				LocalSet::new().run_until(async move {

					event_loop(socket_file).await

				}).await
			}.in_current_span())
			.log_err()
			.ok();

		// give a little time for any remaining tasks to finish
		debug!("Event loop finished, waiting for any remaining tasks ...");
		runtime.shutdown_timeout(Duration::from_millis(500));
		debug!("Async runtime finished");
	}

	// try to cleanup the socket file
	info!("Removing socket file: {}", socket_file.to_string_lossy());
	fs::remove_file(&socket_file)
		.context(format!("Failed to remove socket file: {}", socket_file.to_string_lossy()))
		.warn_err()
		.ok();

	Ok(())
}


async fn event_loop(socket_file: PathBuf) -> Result<()> {

	// start listening on the socket
	let socket = UnixListener::bind(&socket_file)
		.context(format!("Failed to open unix socket at: {}", socket_file.to_string_lossy()))?;
	info!("Opened socket: {}", socket_file.to_string_lossy());

	// init state
	let processes = Rc::new(Mutex::new(Processes::new()));

	// install signal handlers
	let mut sigint = signal(SignalKind::interrupt())
		.context("Failed to install SIGINT handler")?;
	let mut sigterm = signal(SignalKind::terminate())
		.context("Failed to install SIGTERM handler")?;

	// listen for requests
	loop {

		tokio::select! {

			result = socket.accept() => {

				let Ok((conn, _)) = result
					.context("Failed to accept socket connection")
					.warn_err()
					else { continue; };

				let processes = processes.clone();

				// drive the connection in a new task
				tokio::task::spawn_local(async move {
					drive_connection(conn, processes)
						.await
				}.in_current_span());
			}

			_ = sigint.recv() => {
				info!("Received SIGINT, exiting ...");
				break;
			}

			_ = sigterm.recv() => {
				info!("Received SIGTERM, exiting ...");
				break;
			}
		}
	}

	Ok(())
}


#[tracing::instrument(skip_all, level = 5, name = "Connection", fields(id))]
async fn drive_connection(socket: UnixStream, processes: Rc<Mutex<Processes>>) {

	// assign an id to the connection so we can make sense of the log entries
	let id = rand::random::<u32>();
	tracing::Span::current().record("id", id);
	debug!("open");

	// split the socket into read and write halves so we can operate them concurrently
	let (mut socket_read, socket_write) = socket.into_split();
	let socket_write = Rc::new(Mutex::new(socket_write));

	let mut next_request_id: u64 = 1;

	loop {

		// wait for a request
		let msg = match socket_read.read_framed().await {

			// got a message!
			Ok(Some(msg)) => msg,

			// client closed the connection)
			Ok(None) => {
				debug!("socket closed by remote");
				return;
			}

			// some other error
			r => {
				r.context("Failed to read request")
					.warn_err()
					.ok();
				return;
			}
		};

		// assign the request an internal id (separate from the id sent from the client)
		let request_id = next_request_id;
		next_request_id += 1;
		let _span = error_span!("Request", id = request_id).entered();

		// process the request in a task, so other requests on this connection can happen concurrently
		tokio::task::spawn_local({
			let processes = processes.clone();
			let socket_write = socket_write.clone();
			async move {

				trace!("started");

				let request = match RequestEnvelope::decode(msg) {
					Ok(r) => r,
					Err((e, request_id)) => {

						// log the error internally
						Err::<(),_>(e)
							.context("Failed to decode request")
							.warn_err()
							.ok();

						// if request decoding failed to get even the client's request_id,
						// then just use 0 and hope for the best
						let request_id = request_id.unwrap_or(0);

						// send an error response back to the client
						write_response(&socket_write, request_id, Response::Error {
							reason: "Failed to decode request".to_string()
						})
							.await
							.ok();
						return;
					}
				};

				// dispatch the request
				match request.request {

					Request::Ping =>
						dispatch_ping(socket_write, request.id)
							.await,

					Request::Exec(exec) =>
						dispatch_exec(socket_write, request.id, processes, exec)
							.await,

					Request::Status { pid } =>
						dispatch_status(socket_write, request.id, processes, pid)
							.await,

					Request::WriteStdin { pid, chunk } =>
						dispatch_write_stdin(processes, pid, chunk)
							.await,

					Request::CloseStdin { pid } =>
						dispatch_close_stdin(processes, pid)
							.await,

					Request::Kill { pid } =>
						dispatch_kill(processes, pid)
							.await,

					Request::Username { uid } =>
						dispatch_username(socket_write, request.id, uid)
							.await,

					Request::Uid { username } =>
						dispatch_uid(socket_write, request.id, username)
							.await,

					Request::Groupname { gid } =>
						dispatch_groupname(socket_write, request.id, gid)
							.await,

					Request::Gid { groupname } =>
						dispatch_gid(socket_write, request.id, groupname)
							.await,

					Request::Gids { uid } =>
						dispatch_gids(socket_write, request.id, uid)
							.await
				}

				trace!("complete");
			}.in_current_span()
		});
	}
}


async fn write_response(socket: &Mutex<OwnedWriteHalf>, request_id: u32, response: Response) -> Result<(),()> {

	// encode the response
	let msg = ResponseEnvelope {
		id: request_id,
		response
	}
		.encode()
		.context("Failed to encode response")
		.warn_err()?;

	// send it over the socket
	socket.lock()
		.await
		.write_framed(msg)
		.await
		.context("Failed to write response")
		.warn_err()?;

	Ok(())
}


async fn write_proc_event(socket: &Mutex<OwnedWriteHalf>, event: ProcEvent) -> Result<(),()> {

	// encode the event
	let msg = event.encode()
		.context("Failed to encode proc event")
		.warn_err()?;

	// send it over the socket
	socket.lock()
		.await
		.write_framed(msg)
		.await
		.context("Failed to write proc event")
		.warn_err()?;

	Ok(())
}


#[tracing::instrument(skip_all, level = 5, name = "Ping")]
async fn dispatch_ping(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32) {

	trace!("Request");

	// respond with pong
	write_response(&socket, request_id, Response::Pong)
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Exec", fields(pid))]
async fn dispatch_exec(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, processes: Rc<Mutex<Processes>>, request: ExecRequest) {

	trace!("Request: {:?}", &request);

	// spawn the process
	let response = Command::new(request.program)
		.args(request.args)
		.stdin(if request.stream_stdin {
			Stdio::piped()
		} else {
			Stdio::null()
		})
		.stdout(if request.stream_stdout {
			Stdio::piped()
		} else {
			Stdio::null()
		})
		.stderr(if request.stream_stderr {
			Stdio::piped()
		} else {
			Stdio::null()
		})
		.spawn()
		.context("Failed to spawn process")
		.and_then(|proc| {
			// lookup the pid, if any
			match proc.id() {
				Some(pid) => Ok((proc, pid)),
				None => Err(anyhow!("Spawned process had no pid"))
			}
		});
	let (mut proc, pid) = match response {
		Ok(p) => p,
		Err(e) => {

			// send back the error
			write_response(&socket, request_id, Response::Exec(ExecResponse::Failure {
				reason: format!("Failed to start process: {}", e.deref().chain())
			}))
				.await
				.ok();

			return;
		}
	};

	tracing::Span::current().record("pid", pid);
	trace!("Spawned process");

	// track the process for later
	processes.lock()
		.await
		.add(pid, &mut proc);
	if proc.stdin.is_none() {
		trace!("Streaming stdin");
	}

	// NOTE: process is tracked now, don't exit this fn without cleaning it up

	// take stdout,stderr before calling proc.wait(), so the process lib won't try to close them
	let proc_stdout = proc.stdout.take();
	let proc_stderr = proc.stderr.take();

	// send back the pid
	write_response(&socket, request_id, Response::Exec(ExecResponse::Success {
			pid
		}))
		.await
		.ok();

	// stream stdout and/or stderr, if needed
	let mut proc_outputs = StreamMap::<ConsoleKind,Pin<Box<dyn Stream<Item=std::io::Result<Bytes>>>>>::new();
	if let Some(proc_stdout) = proc_stdout {
		trace!("Streaming stdout");
		proc_outputs.insert(ConsoleKind::Stdout, Box::pin(ReaderStream::new(proc_stdout)));
	}
	if let Some(proc_stderr) = proc_stderr {
		trace!("Streaming stderr");
		proc_outputs.insert(ConsoleKind::Stderr, Box::pin(ReaderStream::new(proc_stderr)));
	}
	loop {
		match proc_outputs.next().await {

			Some((kind, Ok(chunk))) => {

				// send back the console chunk
				let Ok(_) = write_proc_event(&socket, ProcEvent::Console(ProcConsole {
					pid,
					kind,
					buf: chunk.to_vec()
				}))
					.await
					else { break; };
			}

			Some((kind, Err(e))) => {
				warn!("Failed to read from {} for pid {}: {}", kind.name(), pid, e.into_chain());
			}

			// both streams closed
			None => {
				trace!("all proc output streams closed");
				break;
			}
		}
	}

	// wait for the process to finish
	trace!("Waiting for process to exit");
	let exit = proc.wait()
		.await
		.context("Failed to wait for exec process")
		.warn_err();
	if let Ok(exit) = exit {

		trace!(code = ?exit.code(), "Process exited");

		// send the final proc event, if needed
		if request.stream_fin {
			write_proc_event(&socket, ProcEvent::Fin(ProcFin {
				pid,
				exit_code: exit.code()
			}))
				.await
				.ok();
		}
	}

	// cleanup the process collection
	processes.lock()
		.await
		.remove(pid)
		.context(format!("Process {} finished, but had no Proc entry to remove", pid))
		.warn_err()
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Status", fields(pid))]
async fn dispatch_status(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, processes: Rc<Mutex<Processes>>, pid: u32) {

	tracing::Span::current().record("pid", pid);
	trace!("Request");

	let is_running = processes.lock()
		.await
		.contains(pid);

	trace!(is_running);

	// send back the response
	write_response(&socket, request_id, Response::Status(is_running))
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "WriteStdin", fields(pid))]
async fn dispatch_write_stdin(processes: Rc<Mutex<Processes>>, pid: u32, chunk: Vec<u8>) {

	tracing::Span::current().record("pid", pid);
	trace!("Request: write {} bytes", chunk.len());

	let mut processes = processes.lock()
		.await;
	let Ok(proc) = processes.get_mut(pid)
		.context("Process not found")
		.warn_err()
		else { return; };

	let Ok(stdin) = proc.stdin.as_mut()
		.context("Process stdin already closed")
		.warn_err()
		else { return; };

	stdin.write_all(chunk.as_ref())
		.await
		.context("Failed to write to stdin")
		.warn_err()
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "CloseStdin", fields(pid))]
async fn dispatch_close_stdin(processes: Rc<Mutex<Processes>>, pid: u32) {

	tracing::Span::current().record("pid", pid);
	trace!("Request");

	let mut processes = processes.lock()
		.await;
	let Ok(proc) = processes.get_mut(pid)
		.context("Process not found")
		.warn_err()
		else { return; };

	// drop stdin to close it
	let _ = proc.stdin.take()
		.context("Process stdin already closed")
		.warn_err()
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Kill", fields(pid))]
async fn dispatch_kill(processes: Rc<Mutex<Processes>>, pid: u32) {

	tracing::Span::current().record("pid", pid);
	trace!("Request");

	let Ok(_) = processes.lock()
		.await
		.get_mut(pid)
		.context("Process not found")
		.warn_err()
		else { return; };

	// send a kill signal (SIGTERM)
	Command::new("kill")
		.arg(pid.to_string())
		.spawn()
		.context("Failed to spawn `kill`")
		.warn_err()
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Username")]
async fn dispatch_username(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, uid: u32) {

	trace!(uid, "Request");

	let username = users::get_user_by_uid(uid)
		.map(|user| user.name().to_string_lossy().to_string());

	trace!(?username);

	// send back the response
	write_response(&socket, request_id, Response::Username(username))
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Uid")]
async fn dispatch_uid(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, username: String) {

	trace!(username, "Request");

	let uid = users::get_user_by_name(&username)
		.map(|user| user.uid());

	trace!(?uid);

	// send back the response
	write_response(&socket, request_id, Response::Uid(uid))
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Groupname")]
async fn dispatch_groupname(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, gid: u32) {

	trace!(gid, "Request");

	let groupname = users::get_group_by_gid(gid)
		.map(|group| group.name().to_string_lossy().to_string());

	trace!(?groupname);

	// send back the response
	write_response(&socket, request_id, Response::Groupname(groupname))
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Gid")]
async fn dispatch_gid(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, groupname: String) {

	trace!(groupname, "Request");

	let gid = users::get_group_by_name(&groupname)
		.map(|group| group.gid());

	trace!(?gid);

	// send back the response
	write_response(&socket, request_id, Response::Gid(gid))
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Gid")]
async fn dispatch_gids(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32, uid: u32) {

	trace!(uid, "Request");

	let gids = users::get_user_by_uid(uid)
		.map(|user| {
			if let Some(groups) = user.groups() {
				groups.iter()
					.map(|group| group.gid())
					.collect::<Vec<_>>()
			} else {
				Vec::new()
			}
		});

	trace!(?gids);

	// send back the response
	write_response(&socket, request_id, Response::Gids(gids))
		.await
		.ok();
}


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
use tracing::{debug, error, error_span, info, Instrument, trace, warn};
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
fn run (args: Args) -> Result<()> {

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

		// assign the request an id
		let request_id = next_request_id;
		next_request_id += 1;
		let _span = error_span!("Request", id = request_id).entered();

		// process the request in a task, so other requests on this connection can happen concurrently
		tokio::task::spawn_local({
			let processes = processes.clone();
			let socket_write = socket_write.clone();
			async move {

				// assign the request an id
				trace!("started");

				let Ok(request) = RequestEnvelope::decode(msg)
					.context("Failed to decode request")
					.warn_err()
					else { return; };

				// dispatch the request
				match request.request {

					Request::Ping =>
						dispatch_ping(socket_write, request.id)
							.await,

					Request::Exec(exec) =>
						dispatch_exec(socket_write, request.id, processes, exec)
							.await,

					Request::WriteStdin { pid, chunk } =>
						dispatch_write_stdin(processes, pid, chunk)
							.await,

					Request::CloseStdin { pid } =>
						dispatch_close_stdin(processes, pid)
							.await,

					r => {
						error!("unhandled request: {:?}", r);
						return;
					}
				}

				trace!("complete");
			}.in_current_span()
		});
	}
}


#[tracing::instrument(skip_all, level = 5, name = "Ping")]
async fn dispatch_ping(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32) {

	trace!("Request: Ping");

	// respond with pong
	let Ok(msg) = ResponseEnvelope {
		id: request_id,
		response: Response::Pong
	}
		.encode()
		.context("Failed to encode pong")
		.warn_err()
		else { return; };
	socket.lock()
		.await
		.write_framed(msg)
		.await
		.context("Failed to write pong")
		.warn_err()
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
			let Ok(response) = ResponseEnvelope {
				id: request_id,
				response: Response::Exec(ExecResponse::Failure {
					reason: format!("Failed to start process: {}", e.deref().chain())
				})
			}
				.encode()
				.context("Failed to encode exec failure response")
				.warn_err()
				else { return; };
			socket.lock()
				.await
				.write_framed(response)
				.await
				.context("Failed to write exec failure response")
				.warn_err()
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

	// take stdout,stderr before calling proc.wait(), so the process lib won't try to close them
	let proc_stdout = proc.stdout.take();
	let proc_stderr = proc.stderr.take();

	// send back the pid
	let Ok(response) = ResponseEnvelope {
		id: request_id,
		response: Response::Exec(ExecResponse::Success {
			pid
		})
	}
		.encode()
		.context("Failed to encode exec success response")
		.warn_err()
		else { return; };
	socket.lock()
		.await
		.write_framed(response)
		.await
		.context("Failed to write exec success response")
		.warn_err()
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

				// send back the pid
				let Ok(event) = ProcEvent::Console(ProcConsole {
					pid,
					kind,
					buf: chunk.to_vec(),
				})
					.encode()
					.context("Failed to encode console event")
					.warn_err()
					else { continue; };
				socket.lock()
					.await
					.write_framed(event)
					.await
					.context("Failed to write console event")
					.warn_err()
					.ok();
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

		// send the final proc event
		let event = ProcEvent::Fin(ProcFin {
			pid,
			exit_code: exit.code()
		});
		let Ok(response) = event.encode()
			.context("Failed to encode proc event")
			.warn_err()
			else { return; };
		socket.lock()
			.await
			.write_framed(response)
			.await
			.context("Failed to write proc event")
			.warn_err()
			.ok();
	}

	// cleanup the process collection
	processes.lock()
		.await
		.remove(pid)
		.context(format!("Process {} finished, but had no Proc entry to remove", pid))
		.warn_err()
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "WriteStdin", fields(pid))]
async fn dispatch_write_stdin(processes: Rc<Mutex<Processes>>, pid: u32, chunk: Vec<u8>) {

	tracing::Span::current().record("pid", pid);
	trace!("Request: WriteStdin: {} bytes", chunk.len());

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
	trace!("Request: CloseStdin");

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

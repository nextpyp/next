
use std::cell::RefCell;
use std::fs;
use std::ops::Deref;
use std::path::PathBuf;
use std::pin::Pin;
use std::process::{ExitCode, Stdio};
use std::rc::Rc;
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use display_error_chain::ErrorChainExt;
use futures_util::{Stream, StreamExt};
use gumdrop::Options;
use tokio::io::AsyncRead;
use tokio::net::{UnixListener, UnixStream};
use tokio::process::Command;
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::oneshot;
use tokio::task::LocalSet;
use tokio_util::bytes::Bytes;
use tokio_util::io::ReaderStream;
use tracing::{debug, info, trace};
use host_processor::framing::{AsyncReadFramed, AsyncWriteFramed};

use host_processor::logging::{self, ResultExt};
use host_processor::processes::Processes;
use host_processor::proto::{ExecRequest, ExecResponse, ProcEvent, ProcFin, Request, RequestEnvelope, Response, ResponseEnvelope};


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
			})
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
	let processes = Rc::new(RefCell::new(Processes::new()));

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

				// handle the request in a new task
				LocalSet::new().run_until(async move {

					handle(conn, processes)
						.await

				}).await;
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
async fn handle(mut socket: UnixStream, processes: Rc<RefCell<Processes>>) {

	// assign an id to the connection so we can make sense of the log entries
	let id = rand::random::<u32>();
	tracing::Span::current().record("id", id);
	debug!("start");

	// wait for a request
	let Ok(msg) = socket.read_framed()
		.await
		.context("Failed to read request")
		.warn_err()
		else { return; };
	let Ok(request) = RequestEnvelope::decode(msg)
		.context("Failed to decode request")
		.warn_err()
		else { return; };

	// dispatch the request
	match request.request {

		Request::Ping =>
			dispatch_ping(socket, request.id)
				.await,

		Request::Exec(exec) =>
			dispatch_exec(socket, request.id, processes, exec)
				.await,

		_ => todo!()
	}

	debug!("end");
}


async fn dispatch_ping(mut socket: UnixStream, request_id: u32) {

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
	socket.write_framed(msg)
		.await
		.context("Failed to write pong")
		.warn_err()
		.ok();
}


async fn dispatch_exec(mut socket: UnixStream, request_id: u32, processes: Rc<RefCell<Processes>>, request: ExecRequest) {

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
			socket.write_framed(response)
				.await
				.context("Failed to write exec failure response")
				.warn_err()
				.ok();

			return;
		}
	};

	trace!("Spawned process: pid={:?}", pid);

	// track the process for later
	processes.borrow_mut()
		.add(pid, &mut proc);

	let stdout = proc.stdout.take();
	let stderr = proc.stderr.take();

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
	socket.write_framed(response)
		.await
		.context("Failed to write exec success response")
		.warn_err()
		.ok();

	// wait for the process to finish in another task, then clean it up
	let (exit_tx, exit_rx) = oneshot::channel();
	tokio::task::spawn_local({
		let processes = processes.clone();
		let span = tracing::span::Span::current();
		async move {
			let _span = span.enter();
			trace!("Waiting for process {} to exit ...", pid);
			let exit = proc.wait()
				.await
				.context("Failed to wait for exec process")
				.warn_err();
			if let Ok(exit) = exit {
				trace!("Process {} exited with code {:?}", pid, exit.code());
				exit_tx.send(exit)
					.map_err(|_| anyhow!("Failed to send exit status to request handler"))
					// no useful error, just gives the exit status back
					.warn_err()
					.ok();
			}
			processes.borrow_mut()
				.remove(pid)
				.context(format!("Process {} finished, but had no Proc entry to remove", pid))
				.warn_err()
				.ok()
		}
	});

	// stream stdout and/or stderr, if needed
	let mut stdout = maybe_pipe_to_stream(stdout);
	let mut stderr = maybe_pipe_to_stream(stderr);
	/* TODO
	loop {
		tokio::select! {

			result = stdout.next() => {
				todo!() // TODO
			}

			result = stderr.next() => {
				todo!() // TODO
			}

			// both streams closed, we're done here
			else => break
		}
	}
	*/

	// wait for the process to finish, if needed
	let Ok(exit) = exit_rx
		.await
		.context("Failed to receive exit status from process watcher task")
		.warn_err()
		else { return; };

	// send the final proc event
	let event = ProcEvent::Fin(ProcFin {
		pid,
		exit_code: exit.code()
	});
	let Ok(response) = event.encode()
		.context("Failed to encode proc event")
		.warn_err()
		else { return; };
	socket.write_framed(response)
		.await
		.context("Failed to write proc event")
		.warn_err()
		.ok();
}


fn maybe_pipe_to_stream<R>(maybe_pipe: Option<R>) -> Pin<Box<dyn Stream<Item=std::io::Result<Bytes>>>>
	where
		R: AsyncRead + 'static
{
	if let Some(pipe) = maybe_pipe {
		// if we have a pipe, convert it to a stream
		Box::pin(ReaderStream::new(pipe))
	} else {
		// otherwise, return an empty stream
		Box::pin(ReaderStream::new([0u8; 0].as_slice()))
	}
}

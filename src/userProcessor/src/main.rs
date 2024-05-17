
use std::{env, fs};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::rc::Rc;
use std::time::Duration;
use std::os::unix::fs::PermissionsExt;

use anyhow::{bail, Context, Result};
use gumdrop::Options;
use tokio::net::{UnixListener, UnixStream};
use tokio::net::unix::OwnedWriteHalf;
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;
use tokio::task::LocalSet;
use tracing::{debug, error, error_span, info, Instrument, trace, warn};

use user_processor::framing::{AsyncReadFramed, AsyncWriteFramed};
use user_processor::logging::{self, ResultExt};
use user_processor::proto::{Request, RequestEnvelope, Response, ResponseEnvelope};


#[derive(Options)]
struct Args {

	#[options(help_flag)]
	help: bool,

	/// settings for log output
	#[options(default = "user_processor=info")]
	log: String,

	/// Omits unnecessary stdout messages
	#[options(default_expr = "false")]
	quiet: bool
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


#[tracing::instrument(skip_all, level = 5, name = "UserProcessor")]
fn run(args: Args) -> Result<()> {

	if !args.quiet {

		// get the name of this command (it often gets renamed since we need multiple copies)
		let user_processor_name = env::args_os()
			.next()
			.map(|s| {
				PathBuf::from(s)
					.file_name()
					.map(|n| n.to_string_lossy().to_string())
			})
			.flatten()
			.context("Failed to query executable name")?;

		// show the current user
		let uid_current = users::get_current_uid();
		let uid_effective = users::get_effective_uid();
		let username_current = users::get_user_by_uid(uid_current)
			.map(|user| user.name().to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		let username_effective = users::get_user_by_uid(uid_effective)
			.map(|user| user.name().to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		if uid_current == uid_effective {
			info!("{} running as {}:{}", user_processor_name, uid_current, username_current);
		} else {
			info!("{} started as {}:{}, but acting as {}:{}", user_processor_name, uid_current, username_current, uid_effective, username_effective);
		}

		// print the cwd, so we can tell if we're in the correct socket folder or not
		match Path::new(".").canonicalize() {
			Ok(cwd) => info!("Started in folder: {}", cwd.to_string_lossy()),
			Err(e) => warn!("Failed to get cwd: {}", e)
		}
	}

	// get the username and choose the socket filename
	let euid = users::get_effective_uid();
	if euid == 0 {
		bail!("user-processor is not allowed to run as root");
	}
	let user = users::get_user_by_uid(euid)
		.context(format!("Failed to lookup username for uid: {}", euid))?;
	let mut socket_filename = OsString::from("user-processor-");
	socket_filename.push(user.name());

	// start a single-threaded tokio runtime
	let result = tokio::runtime::Builder::new_current_thread()
		.enable_io()
		.enable_time()
		.build()
		.context("Failed to create tokio runtime")
		.log_err();
	if let Ok(runtime) = result {

		let socket_filename = socket_filename.clone();

		debug!("Async runtime started");

		// run the event loop on the async runtime
		runtime
			.block_on(async move {
				LocalSet::new().run_until(async move {

					// start listening on the socket
					// NOTE: create the socket in the current folder
					//       the website should have already started this process in the correct socket folder for user processors
					let socket = UnixListener::bind(&socket_filename)
						.context(format!("Failed to open unix socket at: {}", socket_filename.to_string_lossy()))?;
					info!("Opened socket: {}", socket_filename.to_string_lossy());

					// WARNING: Now that we're listening on the socket, don't exit this function without cleaning it up.
					//          That means no ? operator or any other kind of early returns until cleanup.

					// explicitly set the socket as group readable/writable so the website can use it
					let mut result = fs::set_permissions(&socket_filename, fs::Permissions::from_mode(0o770))
						.context("Failed to set file permisisons on socket");
					if let Ok(_) = result {

						// all is well, start the server listener
						result = event_loop(socket)
							.await
					};

					// try to cleanup the socket file
					info!("Removing socket file: {}", socket_filename.to_string_lossy());
					fs::remove_file(&socket_filename)
						.context(format!("Failed to remove socket file: {}", socket_filename.to_string_lossy()))
						.warn_err()
						.ok();

					result
				}).await
			}.in_current_span())
			.log_err()
			.ok();

		// give a little time for any remaining tasks to finish
		debug!("Event loop finished, waiting for any remaining tasks ...");
		runtime.shutdown_timeout(Duration::from_millis(500));
		debug!("Async runtime finished");
	}

	Ok(())
}


async fn event_loop(socket: UnixListener) -> Result<()> {

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

				// drive the connection in a new task
				tokio::task::spawn_local(async move {
					drive_connection(conn)
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
async fn drive_connection(socket: UnixStream) {

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

					Request::Uids =>
						dispatch_uids(socket_write, request.id)
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


#[tracing::instrument(skip_all, level = 5, name = "Ping")]
async fn dispatch_ping(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32) {

	trace!("Request");

	// respond with pong
	write_response(&socket, request_id, Response::Pong)
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Uids")]
async fn dispatch_uids(socket: Rc<Mutex<OwnedWriteHalf>>, request_id: u32) {

	trace!("Request");

	// respond with the current uids
	let response = Response::Uids {
		uid: users::get_current_uid(),
		euid: users::get_effective_uid()
	};
	write_response(&socket, request_id, response)
		.await
		.ok();
}

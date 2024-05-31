
use std::fs;
use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::OsString;
use std::fs::{File, OpenOptions, Permissions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::Duration;
use std::os::unix::fs::PermissionsExt;

use anyhow::{bail, Context, Result};
use async_trait::async_trait;
use gumdrop::Options;
use tokio::net::{UnixListener, UnixStream};
use tokio::net::unix::OwnedWriteHalf;
use tokio::signal::unix::{signal, SignalKind};
use tokio::task::LocalSet;
use tracing::{debug, error_span, info, Instrument, trace, warn};

use crate::framing::{AsyncReadFramed, AsyncWriteFramed};
use crate::logging::ResultExt;
use crate::proto::{ChmodRequest, ReadFileResponse, Request, RequestEnvelope, Response, ResponseEnvelope, WriteFileRequest, WriteFileResponse};


#[derive(Options)]
pub struct Args {

	/// Path to the socket folder
	#[options(free)]
	socket_dir: Option<String>
}


pub fn run(quiet: bool, args: Args) -> Result<()> {

	if !quiet {
		// print the cwd, so we can tell if we're in the correct folder or not
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
	let mut socket_filename = OsString::from(format!("user-processor-{}-", std::process::id()));
	socket_filename.push(user.name());

	// build the full socket path based on the given socket dir, if any
	let socket_path = match args.socket_dir {
		Some(socket_dir) => PathBuf::from(socket_dir),
		None => PathBuf::new()
	}.join(socket_filename);

	// start a single-threaded tokio runtime
	let result = tokio::runtime::Builder::new_current_thread()
		.enable_io()
		.enable_time()
		.build()
		.context("Failed to create tokio runtime")
		.log_err();
	if let Ok(runtime) = result {

		let socket_path = socket_path.clone();

		debug!("Async runtime started");

		// run the event loop on the async runtime
		runtime
			.block_on(async move {
				LocalSet::new().run_until(async move {

					// start listening on the socket
					// NOTE: create the socket in the current folder
					//       the website should have already started this process in the correct socket folder for user processors
					let socket = UnixListener::bind(&socket_path)
						.context(format!("Failed to open unix socket at: {}", socket_path.to_string_lossy()))?;
					info!("Opened socket: {}", socket_path.to_string_lossy());

					// WARNING: Now that we're listening on the socket, don't exit this function without cleaning it up.
					//          That means no ? operator or any other kind of early returns until cleanup.

					// explicitly set the socket as group readable/writable so the website can use it
					let mut result = fs::set_permissions(&socket_path, fs::Permissions::from_mode(0o770))
						.context("Failed to set file permisisons on socket");
					if let Ok(_) = result {

						// all is well, start the server listener
						result = event_loop(socket)
							.await
					};

					// try to cleanup the socket file
					info!("Removing socket file: {}", socket_path.to_string_lossy());
					fs::remove_file(&socket_path)
						.context(format!("Failed to remove socket file: {}", socket_path.to_string_lossy()))
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
	let socket_write = Rc::new(RefCell::new(socket_write));

	let mut next_request_id: u64 = 1;
	let file_writers = Rc::new(RefCell::new(HashMap::<u32,Rc<RefCell<FileWriter>>>::new()));

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
			let file_writers = file_writers.clone();
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
							.await,

					Request::ReadFile { path } =>
						dispatch_read_file(socket_write, request.id, path)
							.await,

					Request::WriteFile(file_write_request) =>
						dispatch_write_file(socket_write, request.id, file_writers.clone(), file_write_request)
							.await,

					Request::Chmod(chmod_request) =>
						dispatch_chmod(socket_write, request.id, chmod_request)
							.await,

					Request::DeleteFile { path } =>
						dispatch_delete_file(socket_write, request.id, path)
							.await
				}

				trace!("complete");
			}.in_current_span()
		});
	}
}


async fn write_response(socket: &RefCell<OwnedWriteHalf>, request_id: u32, response: Response) -> Result<(),()> {

	// encode the response
	let msg = ResponseEnvelope {
		id: request_id,
		response
	}
		.encode()
		.context("Failed to encode response")
		.warn_err()?;

	// send it over the socket
	socket.borrow_mut()
		.write_framed(msg)
		.await
		.context("Failed to write response")
		.warn_err()?;

	Ok(())
}


#[async_trait(?Send)]
trait OrRespondError<T,E> {
	async fn or_respond_error<F>(self, socket: &RefCell<OwnedWriteHalf>, request_id: u32, f: F) -> Option<T>
		where
			F: FnOnce(E) -> String;
}

#[async_trait(?Send)]
impl<T,E> OrRespondError<T,E> for Result<T,E> {

	async fn or_respond_error<F>(self, socket: &RefCell<OwnedWriteHalf>, request_id: u32, f: F) -> Option<T>
		where
			F: FnOnce(E) -> String
	{
		match self {

			Ok(v) => Some(v),

			Err(e) => {
				let reason = f(e);
				warn!("Error response: {}", reason);
				let response = Response::Error {
					reason
				};
				write_response(&socket, request_id, response)
					.await
					.ok();
				None
			}
		}
	}
}

#[async_trait(?Send)]
impl<T> OrRespondError<T,()> for Option<T> {

	async fn or_respond_error<F>(self, socket: &RefCell<OwnedWriteHalf>, request_id: u32, f: F) -> Option<T>
		where
			F: FnOnce(()) -> String
	{
		match self {

			Some(v) => Some(v),

			None => {
				let response = Response::Error {
					reason: f(())
				};
				write_response(&socket, request_id, response)
					.await
					.ok();
				None
			}
		}
	}
}


#[tracing::instrument(skip_all, level = 5, name = "Ping")]
async fn dispatch_ping(socket: Rc<RefCell<OwnedWriteHalf>>, request_id: u32) {

	trace!("Request");

	// respond with pong
	write_response(&socket, request_id, Response::Pong)
		.await
		.ok();
}


#[tracing::instrument(skip_all, level = 5, name = "Uids")]
async fn dispatch_uids(socket: Rc<RefCell<OwnedWriteHalf>>, request_id: u32) {

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


#[tracing::instrument(skip_all, level = 5, name = "ReadFile")]
async fn dispatch_read_file(socket: Rc<RefCell<OwnedWriteHalf>>, request_id: u32, path: String) {

	trace!(path, "Request");

	// try to open the file for reading
	let Some(mut file) = File::open(&path)
		.or_respond_error(&socket, request_id, |e| format!("Failed to open file {}: {}", path, e))
		.await
		else { return };

	// try to get the file size
	let Some(metadata) = file.metadata()
		.or_respond_error(&socket, request_id, |e| format!("Failed to read metadata for file {}: {}", path, e))
		.await
		else { return };

	// file open! send the first response
	let response = Response::ReadFile(ReadFileResponse::Open {
		bytes: metadata.len()
	});
	let Ok(_) = write_response(&socket, request_id, response)
		.await
		else { return };

	// read the file in chunks
	let mut buf = [0u8; 4*1024];
	let mut sequence: u32 = 0;
	loop {

		// read the next chunk
		sequence += 1;
		let Some(bytes_read) = file.read(&mut buf)
			.or_respond_error(&socket, request_id, |e| format!("Failed to read chunk {}: {}", sequence, e))
			.await
			else { return };
		if bytes_read == 0 {
			break;
		}

		// send the chunk back
		let response = Response::ReadFile(ReadFileResponse::Chunk {
			sequence,
			data: buf[0 .. bytes_read].to_vec()
		});
		let Ok(_) = write_response(&socket, request_id, response)
			.await
			else { return };
	}

	// all done, send the close response
	let response = Response::ReadFile(ReadFileResponse::Close {
		sequence
	});
	write_response(&socket, request_id, response)
		.await
		.ok();
}


#[derive(Debug)]
struct FileWriter {
	file: File,
	sequence: u32,
	error: Option<String>
}

impl FileWriter {

	async fn resequence(s: &Rc<RefCell<Self>>, sequence: u32) {

		// wait for the riqht sequence
		while sequence < s.borrow().sequence {
			// NOTE: don't hold a borrow across the await, that could lead to a deadlock
			tokio::task::yield_now()
				.await;
		}

		// increment the sequence counter for next time
		let mut s = s.borrow_mut();
		s.sequence += 1;
	}
}


#[tracing::instrument(skip_all, level = 5, name = "WriteFile")]
async fn dispatch_write_file(
	socket: Rc<RefCell<OwnedWriteHalf>>,
	request_id: u32,
	file_writers: Rc<RefCell<HashMap<u32,Rc<RefCell<FileWriter>>>>>,
	request: WriteFileRequest
) {

	match request {

		WriteFileRequest::Open { path, append } => {

			trace!(path, append, "Open");

			// try to open the file for writing
			let Some(file) = OpenOptions::new()
				.create(true)
				.write(true)
				.append(append)
				.open(&path)
				.or_respond_error(&socket, request_id, |e| format!("Failed to create file for writing {}: {}", path, e))
				.await
				else { return };

			// make a new file writer attached to the request id
			let file_writer = FileWriter {
				file,
				sequence: 1,
				error: None
			};
			file_writers
				.borrow_mut()
				.insert(request_id, Rc::new(RefCell::new(file_writer)));

			// send the opened response
			let response = Response::WriteFile(WriteFileResponse::Opened);
			write_response(&socket, request_id, response)
				.await
				.ok();
		}

		WriteFileRequest::Chunk { sequence, data } => {

			trace!(sequence, "Chunk");

			// get the file writer
			let Some(file_writer) = file_writers.borrow()
				.get(&request_id)
				.map(|w| w.clone())
				else { return };

			// sometimes async tasks can get processesed out of sequence, so explicitly re-sequence here
			FileWriter::resequence(&file_writer, sequence)
				.await;

			if file_writer.borrow().error.is_some() {
				// already had an error, stop writing
				return;
			}

			// write to the file, but save the first error (if any) for later
			let result = file_writer.borrow_mut().file.write(data.as_ref());
			if let Err(e) = result {
				file_writer.borrow_mut().error = Some(e.to_string());
			}

			// no need to respond to the clent ... what is this, TCP?
		}

		WriteFileRequest::Close { sequence } => {

			trace!(sequence, "Close");

			// get the file writer
			let Some(file_writer) = file_writers.borrow_mut()
				.remove(&request_id)
				.or_respond_error(&socket, request_id, |()| "No file open for writing".to_string())
				.await
				else { return };

			// sometimes async tasks can get processesed out of sequence, so explicitly re-sequence here
			FileWriter::resequence(&file_writer, sequence)
				.await;

			// we should have exclusive owneship over the writer now
			let Some(mut file_writer) = Rc::into_inner(file_writer)
				.map(|s| s.into_inner())
				.or_respond_error(&socket, request_id, |()| "File write tasks not finished yet: this is a bug in UserProcessor".to_string())
				.await
				else { return };

			// check for any errors during previous writes
			match file_writer.error.take() {

				Some(err) => {
					let response = Response::Error { reason: err };
					write_response(&socket, request_id, response)
						.await
						.ok();
				}

				None => {
					// all is well, send the closed response
					let response = Response::WriteFile(WriteFileResponse::Closed);
					write_response(&socket, request_id, response)
						.await
						.ok();
				}
			}

			// NOTE: dropping file_writer here will close the file
		}
	}
}


#[tracing::instrument(skip_all, level = 5, name = "Chmod")]
async fn dispatch_chmod(socket: Rc<RefCell<OwnedWriteHalf>>, request_id: u32, request: ChmodRequest) {

	trace!(path = request.path, ops = request.ops_to_string(), "Request");

	let Some(meta) = fs::metadata(&request.path)
		.or_respond_error(&socket, request_id, |e| format!("Failed to read file permissions: {}", e))
		.await
		else { return };
	let mut mode = meta.permissions().mode();

	// change perms via the POSIX mode
	for op in request.ops {
		for bit in op.bits {
			let pos = bit.pos();
			match op.value {
				false => mode &= !(1 << pos),
				true => mode |= 1 << pos
			}
		}
	}

	let Some(()) = fs::set_permissions(&request.path, Permissions::from_mode(mode))
		.or_respond_error(&socket, request_id, |e| format!("Failed to write file permissions: {}", e))
		.await
		else { return };

	write_response(&socket, request_id, Response::Chmod)
		.await
		.ok();
}



#[tracing::instrument(skip_all, level = 5, name = "DeleteFile")]
async fn dispatch_delete_file(socket: Rc<RefCell<OwnedWriteHalf>>, request_id: u32, path: String) {

	trace!(path, "Request");

	let Some(()) = fs::remove_file(&path)
		.or_respond_error(&socket, request_id, |e| format!("Failed to delete file: {}", e))
		.await
		else { return };

	write_response(&socket, request_id, Response::DeleteFile)
		.await
		.ok();
}


use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus};
use std::thread;
use std::time::Duration;

use galvanic_assert::{assert_that, matchers::*};
use nix::sys::signal::{self, Signal};
use nix::unistd::Pid;
use tracing::debug;

use user_processor::framing::{ReadFramed, WriteFramed};
use user_processor::logging;
use user_processor::proto::{Request, RequestEnvelope, Response, ResponseEnvelope};


// NOTE: these tests need `cargo test ... -- --test-threads=1` for the log to make sense


#[test]
fn help() {
	let _logging = logging::init_test();

	let exit = UserProcessor::help();

	assert_that!(&exit.success(), eq(true));
}


#[test]
fn connect() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let socket = user_processor.connect();

	user_processor.disconnect(socket);
	user_processor.stop();
}


#[test]
fn ping_pong() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Ping);

	assert_that!(&response, eq(Response::Pong));

	user_processor.disconnect(socket);
	user_processor.stop();
}


#[test]
fn uids() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Uids);
	assert_that!(&response, eq(Response::Uids {
		uid: users::get_current_uid(),
		euid: users::get_effective_uid()
	}));

	user_processor.disconnect(socket);
	user_processor.stop();
}


const SOCKET_DIR: &str = "/tmp/nextpyp-sockets";


struct UserProcessor {
	proc: Child
}

impl UserProcessor {

	fn bin_path() -> &'static Path {
		let bin_path = Path::new(env!("CARGO_BIN_EXE_user-processor"));
		if !bin_path.exists() {
			panic!("Target binary not found at: {:?}", bin_path);
		}
		bin_path
	}

	fn help() -> ExitStatus {
		Command::new(Self::bin_path())
			.args(["--help"])
			.spawn()
			.expect("Failed to spawn process")
			.wait()
			.expect("Failed to wait for process")
	}

	fn start() -> Self {

		debug!("Starting user processor ...");

		let proc = Command::new(Self::bin_path())
			.args(["--log", "trace"])
			.current_dir(SOCKET_DIR)
			.spawn()
			.expect("Failed to spawn process");

		Self {
			proc
		}
	}

	fn socket_path(&self) -> PathBuf {
		let username = users::get_effective_username()
			.expect("Failed to lookup effective username");
		PathBuf::from(SOCKET_DIR)
			.join(format!("user-processor-{}", username.to_string_lossy()))
	}

	fn connect(&self) -> UnixStream {

		// wait for the socket file to appear, if needed
		let socket_path = self.socket_path();
		for _ in 0 .. 10 {
			if socket_path.exists() {
				break;
			} else {
				thread::sleep(Duration::from_millis(100));
			}
		}

		UnixStream::connect(self.socket_path())
			.expect("Failed to connect to socket")
	}

	fn disconnect(&self, socket: UnixStream) {
		socket.shutdown(Shutdown::Both)
			.unwrap();
	}

	fn interrupt(&self) {
		let pid = Pid::from_raw(self.proc.id() as i32);
		signal::kill(pid, Signal::SIGTERM)
			.expect("Failed to send signal to user processor");
	}

	fn stop(mut self) -> ExitStatus {
		debug!("Stopping user processor ...");
		self.interrupt();
		let exit = self.proc.wait()
			.expect("Failed to wait for process");
		debug!("User processor stopped");
		exit
	}
}

impl Drop for UserProcessor {

	fn drop(&mut self) {
		// if the process is still running, stop it now
		if let Ok(None) = self.proc.try_wait() {
			self.interrupt();
		}
	}
}


fn send(socket: &mut UnixStream, request: Request) -> u32 {

	// encode the request
	let request_id = 5;
	let request = RequestEnvelope {
		id: request_id,
		request
	};
	let msg = request.encode()
		.unwrap();

	// send it
	socket.write_framed(msg)
		.unwrap();

	request_id
}


fn request(socket: &mut UnixStream, request: Request) -> (Response, u32) {

	let request_id = send(socket, request);

	// wait for a response
	let response = socket.read_framed()
		.unwrap();

	// decode the response
	let response = ResponseEnvelope::decode(response)
		.unwrap();

	assert_that!(&response.id, eq(request_id));

	(response.response, request_id)
}

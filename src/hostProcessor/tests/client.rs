
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

use host_processor::framing::{ReadFramed, WriteFramed};
use host_processor::logging;
use host_processor::proto::{ExecRequest, ExecResponse, ProcEvent, ProcFin, Request, RequestEnvelope, Response, ResponseEnvelope};


// NOTE: these tests need `cargo test ... -- --test-threads=1` for the log to make sense


#[test]
fn connect() {
	let _logging = logging::init_test();

	let proc = HostProcessor::start();
	let socket = proc.connect();

	shutdown(socket);
	proc.stop();
}


#[test]
fn ping_pong() {
	let _logging = logging::init_test();

	let proc = HostProcessor::start();
	let mut socket = proc.connect();

	let request = RequestEnvelope {
		id: 5,
		request: Request::Ping
	};
	let msg = request.encode()
		.unwrap();
	socket.write_framed(msg)
		.unwrap();
	let response = socket.read_framed()
		.unwrap();
	let response = ResponseEnvelope::decode(response)
		.unwrap();

	assert_that!(&response, eq(ResponseEnvelope {
		id: request.id,
		response: Response::Pong
	}));

	shutdown(socket);
	proc.stop();
}


#[test]
fn exec() {
	let _logging = logging::init_test();

	let proc = HostProcessor::start();
	let mut socket = proc.connect();

	// send the exec request
	let request = RequestEnvelope {
		id: 42,
		request: Request::Exec(ExecRequest {
			program: "ls".to_string(),
			args: vec!["-al".to_string()],
			stream_stdin: false,
			stream_stdout: false,
			stream_stderr: false,
		})
	};
	let msg = request.encode()
		.unwrap();
	socket.write_framed(msg)
		.unwrap();
	let response = socket.read_framed()
		.unwrap();
	let response = ResponseEnvelope::decode(response)
		.unwrap();

	assert_that!(&response.id, eq(request.id));
	assert_that!(&matches!(response.response, Response::Exec(ExecResponse::Success { .. })), eq(true));

	// wait for the fin event
	let event = socket.read_framed()
		.unwrap();
	let event = ProcEvent::decode(event)
		.unwrap();

	assert_that!(&matches!(event, ProcEvent::Fin(ProcFin { exit_code: Some(0), .. })), eq(true));

	shutdown(socket);
	proc.stop();
}


const SOCKET_DIR: &str = "/tmp/nextpyp-sockets";


struct HostProcessor {
	proc: Child
}

impl HostProcessor {

	fn start() -> Self {

		debug!("Starting host processor ...");

		let bin_path = Path::new(env!("CARGO_BIN_EXE_host-processor"));
		if !bin_path.exists() {
			panic!("Target binary not found at: {:?}", bin_path);
		}

		let proc = Command::new(bin_path)
			.args(["--log", "trace", SOCKET_DIR])
			.spawn()
			.expect("Failed to spawn process");

		Self {
			proc
		}
	}

	fn socket_path(&self) -> PathBuf {
		PathBuf::from(SOCKET_DIR)
			.join(format!("host-processor-{}", self.proc.id()))
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

	fn interrupt(&self) {
		let pid = Pid::from_raw(self.proc.id() as i32);
		signal::kill(pid, Signal::SIGTERM)
			.expect("Failed to send signal to host processor");
	}

	fn stop(mut self) -> ExitStatus {
		debug!("Stopping host processor ...");
		self.interrupt();
		let exit = self.proc.wait()
			.expect("Failed to wait for process");
		debug!("Host processor stopped");
		exit
	}
}

impl Drop for HostProcessor {

	fn drop(&mut self) {
		// if the process is still running, stop it now
		if let Ok(None) = self.proc.try_wait() {
			self.interrupt();
		}
	}
}


fn shutdown(socket: UnixStream) {
	socket.shutdown(Shutdown::Both)
		.unwrap();
}

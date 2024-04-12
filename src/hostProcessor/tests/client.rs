
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
use host_processor::proto::{ExecRequest, ProcFin, Request, RequestEnvelope, Response, ResponseEnvelope};


// NOTE: these tests need `cargo test ... -- --test-threads=1` for the log to make sense


#[test]
fn connect() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let socket = host_processor.connect();

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn ping_pong() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

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

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let pid = exec::launch(&mut socket, 42, ExecRequest {
		program: "ls".to_string(),
		args: vec!["-al".to_string()],
		stream_stdin: false,
		stream_stdout: false,
		stream_stderr: false,
	});

	// wait for the fin event
	let fin = exec::fin(&mut socket, pid);
	assert_that!(&matches!(fin, ProcFin { exit_code: Some(0), .. }), eq(true));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stdout() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let pid = exec::launch(&mut socket, 42, ExecRequest {
		program: "ls".to_string(),
		args: vec!["-al".to_string()],
		stream_stdin: false,
		stream_stdout: true,
		stream_stderr: false,
	});

	// get the stdout
	let (stdout, stderr, fin) = exec::outputs(&mut socket, pid);
	assert_that!(&fin.exit_code, eq(Some(0)));
	assert_that!(&stdout.len(), gt(0));
	assert_that!(&stderr.len(), eq(0));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stderr() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let pid = exec::launch(&mut socket, 42, ExecRequest {
		program: "ls".to_string(),
		args: vec!["/nope/probably/not/a/thing/right?".to_string()],
		stream_stdin: false,
		stream_stdout: false,
		stream_stderr: true,
	});

	// get the stdout
	let (stdout, stderr, fin) = exec::outputs(&mut socket, pid);
	assert_that!(&fin.exit_code, eq(Some(2)));
	assert_that!(&stdout.len(), eq(0));
	assert_that!(&stderr.len(), gt(0));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stdin() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let pid = exec::launch(&mut socket, 42, ExecRequest {
		program: "cat".to_string(),
		args: vec!["-".to_string()],
		stream_stdin: true,
		stream_stdout: true,
		stream_stderr: true,
	});

	// send chunks to stdin
	exec::write_stdin(&mut socket, pid, b"hi");
	exec::close_stdin(&mut socket, pid);

	// get the stdout
	let (stdout, stderr, fin) = exec::outputs(&mut socket, pid);
	assert_that!(&fin.exit_code, eq(Some(0)));
	assert_that!(&String::from_utf8_lossy(stdout.as_ref()).to_string(), eq("hi".to_string()));
	assert_that!(&stderr.len(), eq(0));

	host_processor.disconnect(socket);
	host_processor.stop();
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

	fn disconnect(&self, socket: UnixStream) {
		socket.shutdown(Shutdown::Both)
			.unwrap();
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



mod exec {

	use std::os::unix::net::UnixStream;

	use galvanic_assert::{assert_that, matchers::*};
	use tracing::info;

	use host_processor::framing::{ReadFramed, WriteFramed};
	use host_processor::proto::{ConsoleKind, ExecRequest, ExecResponse, ProcEvent, ProcFin, Request, RequestEnvelope, Response, ResponseEnvelope};


	pub fn launch(socket: &mut UnixStream, id: u32, request: ExecRequest) -> u32 {

		let request = RequestEnvelope {
			id,
			request: Request::Exec(request)
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
		let Response::Exec(response) = response.response
			else { panic!("unexpected response: {:?}", response); };

		match response {
			ExecResponse::Success { pid } => {
				info!(pid, "exec launched");
				pid
			}
			ExecResponse::Failure { reason } => panic!("exec failed: {}", reason)
		}
	}


	pub fn write_stdin(socket: &mut UnixStream, pid: u32, chunk: impl Into<Vec<u8>>) {

		let chunk = chunk.into();
		let chunk_size = chunk.len();

		let request = RequestEnvelope {
			id: 0,
			request: Request::WriteStdin {
				pid,
				chunk
			}
		};
		let msg = request.encode()
			.unwrap();
		socket.write_framed(msg)
			.unwrap();

		info!("exec writing to stdin: {} bytes", chunk_size);
	}


	pub fn close_stdin(socket: &mut UnixStream, pid: u32) {

		let request = RequestEnvelope {
			id: 0,
			request: Request::CloseStdin {
				pid
			}
		};
		let msg = request.encode()
			.unwrap();
		socket.write_framed(msg)
			.unwrap();

		info!("exec closing stdin");
	}


	pub fn outputs(socket: &mut UnixStream, pid: u32) -> (Vec<u8>,Vec<u8>,ProcFin) {

		let mut stdout = Vec::<u8>::new();
		let mut stderr = Vec::<u8>::new();

		loop {

			let event = socket.read_framed()
				.unwrap();
			let event = ProcEvent::decode(event)
				.unwrap();

			match event {

				ProcEvent::Console(console) => {
					assert_that!(&console.pid, eq(pid));
					match console.kind {
						ConsoleKind::Stdout => &mut stdout,
						ConsoleKind::Stderr => &mut stderr
					}.extend(console.buf);
				}

				ProcEvent::Fin(fin) => {
					assert_that!(&fin.pid, eq(pid));
					info!(code = ?fin.exit_code, "exec process exited");
					return (stdout, stderr, fin);
				}
			}
		}
	}


	pub fn fin(socket: &mut UnixStream, pid: u32) -> ProcFin {

		// wait for the fin event
		let event = socket.read_framed()
			.unwrap();
		let event = ProcEvent::decode(event)
			.unwrap();

		match event {
			ProcEvent::Fin(fin) => {
				assert_that!(&fin.pid, eq(pid));
				info!(code = ?fin.exit_code, "exec process exited");
				fin
			},
			_ => panic!("expected fin event, not {:?}", event)
		}
	}
}

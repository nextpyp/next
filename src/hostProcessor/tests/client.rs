
use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus};
use std::{fs, thread};
use std::time::Duration;

use galvanic_assert::{assert_that, matchers::*};
use nix::sys::signal::{self, Signal};
use nix::unistd::Pid;
use tracing::debug;

use host_processor::framing::{ReadFramed, WriteFramed};
use host_processor::logging;
use host_processor::proto::{ExecRequest, ExecStderr, ExecStdin, ExecStdout, Request, RequestEnvelope, Response, ResponseEnvelope};


// NOTE: these tests need `cargo test ... -- --test-threads=1` for the log to make sense


#[test]
fn help() {
	let _logging = logging::init_test();

	let exit = HostProcessor::help();

	assert_that!(&exit.success(), eq(true));
}


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

	let (response, _request_id) = request(&mut socket, Request::Ping);

	assert_that!(&response, eq(Response::Pong));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "ls".to_string(),
		args: vec!["-al".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Ignore,
		stderr: ExecStderr::Ignore,
		stream_fin: true
	});

	// wait for the fin response
	let exit_code = exec::fin(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(0)));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stdout_stream() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "ls".to_string(),
		args: vec!["-al".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Stream,
		stderr: ExecStderr::Ignore,
		stream_fin: true
	});

	// get the stdout
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(0)));
	assert_that!(&stdout.len(), gt(0));
	assert_that!(&stderr.len(), eq(0));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stdout_write() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let out_path = PathBuf::from(SOCKET_DIR).join("out");

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "echo".to_string(),
		args: vec!["hello".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Write {
			path: out_path.to_string_lossy().to_string()
		},
		stderr: ExecStderr::Ignore,
		stream_fin: true
	});

	// get the stdout
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(0)));
	assert_that!(&stdout.len(), eq(0));
	assert_that!(&stderr.len(), eq(0));
	let out = fs::read_to_string(&out_path)
		.unwrap();
	assert_that!(&out.as_str(), eq("hello\n"));

	fs::remove_file(&out_path)
		.ok();

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stderr_stream() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "ls".to_string(),
		args: vec!["/nope/probably/not/a/thing/right?".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Ignore,
		stderr: ExecStderr::Stream,
		stream_fin: true
	});

	// get the stdout
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(2)));
	assert_that!(&stdout.len(), eq(0));
	assert_that!(&stderr.len(), gt(0));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stderr_write() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let err_path = PathBuf::from(SOCKET_DIR).join("err");

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "ls".to_string(),
		args: vec!["/nope/probably/not/a/thing/right?".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Ignore,
		stderr: ExecStderr::Write {
			path: err_path.to_string_lossy().to_string()
		},
		stream_fin: true
	});

	// get the stderr
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(2)));
	assert_that!(&stdout.len(), eq(0));
	assert_that!(&stderr.len(), eq(0));
	let err = fs::read_to_string(&err_path)
		.unwrap();
	assert_that!(&err.len(), gt(0));

	fs::remove_file(&err_path)
		.ok();

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stderr_merge() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let out_path = PathBuf::from(SOCKET_DIR).join("out");

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "ls".to_string(),
		args: vec!["/nope/probably/not/a/thing/right?".to_string()],
		dir: None,
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Write {
			path: out_path.to_string_lossy().to_string()
		},
		stderr: ExecStderr::Merge,
		stream_fin: true
	});

	// get the output
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(2)));
	assert_that!(&stdout.len(), eq(0));
	assert_that!(&stderr.len(), eq(0));
	let out = fs::read_to_string(&out_path)
		.unwrap();
	assert_that!(&out.len(), gt(0));

	fs::remove_file(&out_path)
		.ok();

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_stdin() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let (pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "cat".to_string(),
		args: vec!["-".to_string()],
		dir: None,
		stdin: ExecStdin::Stream,
		stdout: ExecStdout::Stream,
		stderr: ExecStderr::Stream,
		stream_fin: true
	});

	// send chunks to stdin
	exec::write_stdin(&mut socket, pid, b"hi");
	exec::close_stdin(&mut socket, pid);

	// get the stdout
	let (stdout, stderr, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(0)));
	assert_that!(&String::from_utf8_lossy(stdout.as_ref()).to_string(), eq("hi".to_string()));
	assert_that!(&stderr.len(), eq(0));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_dir() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	// send the exec request
	let (_pid, request_id) = exec::launch(&mut socket, ExecRequest {
		program: "pwd".to_string(),
		args: vec![],
		dir: Some("/tmp".to_string()),
		stdin: ExecStdin::Ignore,
		stdout: ExecStdout::Stream,
		stderr: ExecStderr::Ignore,
		stream_fin: true
	});

	// get the stdout
	let (stdout, _, exit_code) = exec::outputs(&mut socket, request_id);
	assert_that!(&exit_code, eq(Some(0)));
	assert_that!(&String::from_utf8_lossy(stdout.as_ref()).to_string(), eq("/tmp\n".to_string()));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_status() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Status { pid: 5 });
	assert_that!(&response, eq(Response::Status(false)));

	// start a process that will keep running until we tell it to stop (by closing stdin)
	let (pid, _request_id) = exec::launch(&mut socket, ExecRequest {
		program: "cat".to_string(),
		args: vec!["-".to_string()],
		dir: None,
		stdin: ExecStdin::Stream,
		stdout: ExecStdout::Ignore,
		stderr: ExecStderr::Ignore,
		stream_fin: false
	});

	let (response, _request_id) = request(&mut socket, Request::Status { pid });
	assert_that!(&response, eq(Response::Status(true)));

	// stop it
	exec::close_stdin(&mut socket, pid);

	// wait a bit for the process to exit
	thread::sleep(Duration::from_millis(200));

	let (response, _request_id) = request(&mut socket, Request::Status { pid });
	assert_that!(&response, eq(Response::Status(false)));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn exec_kill() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Status { pid: 5 });
	assert_that!(&response, eq(Response::Status(false)));

	// start a process that will keep running until we tell it to stop (by sending SIGTERM)
	let (pid, _request_id) = exec::launch(&mut socket, ExecRequest {
		program: "cat".to_string(),
		args: vec!["-".to_string()],
		dir: None,
		stdin: ExecStdin::Stream,
		stdout: ExecStdout::Ignore,
		stderr: ExecStderr::Ignore,
		stream_fin: false
	});

	let (response, _request_id) = request(&mut socket, Request::Status { pid });
	assert_that!(&response, eq(Response::Status(true)));

	// stop it
	exec::kill(&mut socket, pid);

	// wait a bit for the process to exit
	thread::sleep(Duration::from_millis(200));

	let (response, _request_id) = request(&mut socket, Request::Status { pid });
	assert_that!(&response, eq(Response::Status(false)));

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn username() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Username { uid: 0 });
	assert_that!(&response, eq(Response::Username(Some("root".to_string()))));

	let (response, _request_id) = request(&mut socket, Request::Username { uid: u32::MAX - 5 });
	assert_that!(&response, eq(Response::Username(None))); // probably?

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn uid() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Uid { username: "root".to_string() });
	assert_that!(&response, eq(Response::Uid(Some(0))));

	let (response, _request_id) = request(&mut socket, Request::Uid { username: "probably not a user you have, right?".to_string() });
	assert_that!(&response, eq(Response::Uid(None))); // probably?

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn groupname() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Groupname { gid: 1 });
	assert_that!(&response, eq(Response::Groupname(Some("daemon".to_string()))));

	let (response, _request_id) = request(&mut socket, Request::Groupname { gid: u32::MAX - 5 });
	assert_that!(&response, eq(Response::Groupname(None))); // probably?

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn gid() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Gid { groupname: "daemon".to_string() });
	assert_that!(&response, eq(Response::Gid(Some(1))));

	let (response, _request_id) = request(&mut socket, Request::Gid { groupname: "probably not a group you have, right?".to_string() });
	assert_that!(&response, eq(Response::Gid(None))); // probably?

	host_processor.disconnect(socket);
	host_processor.stop();
}


#[test]
fn gids() {
	let _logging = logging::init_test();

	let host_processor = HostProcessor::start();
	let mut socket = host_processor.connect();

	let (response, _request_id) = request(&mut socket, Request::Gids { uid: 0 });
	let Response::Gids(Some(gids)) = response
		else { panic!("no gids returned") };

	// the root user should be in the root group, right?
	assert_that!(&gids.contains(&0), eq(true));

	let (response, _request_id) = request(&mut socket, Request::Gids { uid: u32::MAX - 5 });
	assert_that!(&response, eq(Response::Gids(None))); // probably?

	host_processor.disconnect(socket);
	host_processor.stop();
}


const SOCKET_DIR: &str = "/tmp/nextpyp-sockets";


struct HostProcessor {
	proc: Child
}

impl HostProcessor {

	fn bin_path() -> &'static Path {
		let bin_path = Path::new(env!("CARGO_BIN_EXE_host-processor"));
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

		debug!("Starting host processor ...");

		let proc = Command::new(Self::bin_path())
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



mod exec {

	use std::os::unix::net::UnixStream;

	use galvanic_assert::{assert_that, matchers::*};
	use tracing::info;

	use host_processor::framing::{ReadFramed, WriteFramed};
	use host_processor::proto::{ConsoleKind, ExecRequest, ExecResponse, ProcessEvent, Request, RequestEnvelope, Response, ResponseEnvelope};


	pub fn launch(socket: &mut UnixStream, request: ExecRequest) -> (u32, u32) {

		let (response, request_id) = super::request(socket, Request::Exec(request));

		let Response::Exec(response) = response
			else { panic!("unexpected response: {:?}", response); };

		match response {
			ExecResponse::Success { pid } => {
				info!(pid, "exec launched");
				(pid, request_id)
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


	pub fn outputs(socket: &mut UnixStream, request_id: u32) -> (Vec<u8>,Vec<u8>,Option<i32>) {

		let mut stdout = Vec::<u8>::new();
		let mut stderr = Vec::<u8>::new();

		loop {

			// wait for the next response
			let response = socket.read_framed()
				.unwrap();
			let response = ResponseEnvelope::decode(response)
				.unwrap();

			assert_that!(&response.id, eq(request_id));

			// get the console chunk
			let Response::ProcessEvent(event) = response.response
				else { panic!("unexpected response: {:?}", response); };
			match event {

				ProcessEvent::Console { kind, chunk } => {
					match kind {
						ConsoleKind::Stdout => &mut stdout,
						ConsoleKind::Stderr => &mut stderr
					}.extend(chunk);
				}

				ProcessEvent::Fin { exit_code } => {
					info!(code = ?exit_code, "exec process exited");
					return (stdout, stderr, exit_code);
				}
			}
		}
	}


	pub fn fin(socket: &mut UnixStream, request_id: u32) -> Option<i32> {

		// wait for the next response
		let response = socket.read_framed()
			.unwrap();
		let response = ResponseEnvelope::decode(response)
			.unwrap();

		assert_that!(&response.id, eq(request_id));

		// get the fin
		let Response::ProcessEvent(event) = response.response
			else { panic!("unexpected response: {:?}", response); };
		match event {
			ProcessEvent::Fin { exit_code } => {
				info!(code = ?exit_code, "exec process exited");
				exit_code
			},
			_ => panic!("unexpected exec response: {:?}", event)
		}
	}


	pub fn kill(socket: &mut UnixStream, pid: u32) {
		super::send(socket, Request::Kill { pid });
	}
}

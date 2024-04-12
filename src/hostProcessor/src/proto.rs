
use std::io::Cursor;

use anyhow::{anyhow, bail, Context, Result};
use byteorder::{BigEndian, ReadBytesExt, WriteBytesExt};


#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestEnvelope {
	pub id: u32,
	pub request: Request
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Request {

	Ping,

	/// launch a new process
	Exec(ExecRequest),

	/// query the status of a process launched with Exec
	Status {
		pid: u32
	},

	/// send a chunk to the stdin of a process launched with Exec
	WriteStdin {
		pid: u32,
		chunk: Vec<u8>
	},

	/// send a chunk to the stdin of a process launched with Exec
	CloseStdin {
		pid: u32
	},

	/// send SIGTERM to a process launched with Exec
	Kill {
		pid: u32
	},

	/// lookup the username for a uid
	Username {
		uid: u32
	},

	/// lookup the uid for a username
	Uid {
		username: String
	},

	/// lookup the groupname for a gid
	Groupname {
		gid: u32
	},

	/// lookup the gid for a groupname
	Gid {
		groupname: String
	},

	/// lookup the gids for a uid
	Gids {
		uid: u32
	}
}

impl Request {
	const ID_PING: u32 = 1;
	const ID_EXEC: u32 = 2;
	const ID_STATUS: u32 = 3;
	const ID_WRITE_STDIN: u32 = 4;
	const ID_CLOSE_STDIN: u32 = 5;
	const ID_KILL: u32 = 6;
	const ID_USERNAME: u32 = 7;
	const ID_UID: u32 = 8;
	const ID_GROUPNAME: u32 = 9;
	const ID_GID: u32 = 10;
	const ID_GIDS: u32 = 11;
}


#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecRequest {
	pub program: String,
	pub args: Vec<String>,
	pub stream_stdin: bool,
	pub stream_stdout: bool,
	pub stream_stderr: bool,
	pub stream_fin: bool
}

impl RequestEnvelope {

	pub fn encode(&self) -> Result<Vec<u8>> {
		let mut out = Vec::<u8>::new();

		out.write_u32::<BigEndian>(self.id)?;

		match &self.request {

			Request::Ping => {
				// only need the type id
				out.write_u32::<BigEndian>(Request::ID_PING)?
			}

			Request::Exec(request) => {
				out.write_u32::<BigEndian>(Request::ID_EXEC)?;
				out.write_utf8(&request.program)?;
				out.write_vec(&request.args, |out, item| {
					out.write_utf8(item)
				})?;
				out.write_bool(request.stream_stdin)?;
				out.write_bool(request.stream_stdout)?;
				out.write_bool(request.stream_stderr)?;
				out.write_bool(request.stream_fin)?;
			}

			Request::Status { pid } => {
				out.write_u32::<BigEndian>(Request::ID_STATUS)?;
				out.write_u32::<BigEndian>(*pid)?;
			}

			Request::WriteStdin { pid, chunk } => {
				out.write_u32::<BigEndian>(Request::ID_WRITE_STDIN)?;
				out.write_u32::<BigEndian>(*pid)?;
				out.write_bytes(chunk)?;
			}

			Request::CloseStdin { pid} => {
				out.write_u32::<BigEndian>(Request::ID_CLOSE_STDIN)?;
				out.write_u32::<BigEndian>(*pid)?;
			}

			Request::Kill { pid } => {
				out.write_u32::<BigEndian>(Request::ID_KILL)?;
				out.write_u32::<BigEndian>(*pid)?;
			}

			Request::Username { uid } => {
				out.write_u32::<BigEndian>(Request::ID_USERNAME)?;
				out.write_u32::<BigEndian>(*uid)?;
			}

			Request::Uid { username } => {
				out.write_u32::<BigEndian>(Request::ID_UID)?;
				out.write_utf8(username)?;
			}

			Request::Groupname { gid } => {
				out.write_u32::<BigEndian>(Request::ID_GROUPNAME)?;
				out.write_u32::<BigEndian>(*gid)?;
			}

			Request::Gid { groupname } => {
				out.write_u32::<BigEndian>(Request::ID_GID)?;
				out.write_utf8(groupname)?;
			}

			Request::Gids { uid } => {
				out.write_u32::<BigEndian>(Request::ID_GIDS)?;
				out.write_u32::<BigEndian>(*uid)?;
			}
		}

		Ok(out)
	}

	pub fn decode(msg: impl AsRef<[u8]>) -> Result<Self> {

		let msg = msg.as_ref();
		let mut reader = Cursor::new(msg);

		let request_id = reader.read_u32::<BigEndian>()
			.context("Failed to read request id")?;

		// read the request type id
		let type_id = reader.read_u32::<BigEndian>()
			.context("Failed to read request type id")?;
		// NOTE: we can't match on constants here, so use if/elseif/else instead
		//       see: https://doc.rust-lang.org/unstable-book/language-features/inline-const-pat.html
		let request =
			if type_id == Request::ID_PING {
				Request::Ping
			} else if type_id == Request::ID_EXEC {
				Request::Exec(ExecRequest {
					program: reader.read_utf8()?,
					args: reader.read_vec(|r| r.read_utf8())?,
					stream_stdin: reader.read_bool()?,
					stream_stdout: reader.read_bool()?,
					stream_stderr: reader.read_bool()?,
					stream_fin: reader.read_bool()?
				})
			} else if type_id == Request::ID_STATUS {
				Request::Status {
					pid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Request::ID_WRITE_STDIN {
				Request::WriteStdin {
					pid: reader.read_u32::<BigEndian>()?,
					chunk: reader.read_bytes()?
				}
			} else if type_id == Request::ID_CLOSE_STDIN {
				Request::CloseStdin {
					pid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Request::ID_KILL {
				Request::Kill {
					pid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Request::ID_USERNAME {
				Request::Username {
					uid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Request::ID_UID {
				Request::Uid {
					username: reader.read_utf8()?
				}
			} else if type_id == Request::ID_GROUPNAME {
				Request::Groupname {
					gid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Request::ID_GID {
				Request::Gid {
					groupname: reader.read_utf8()?
				}
			} else if type_id == Request::ID_GIDS {
				Request::Gids {
					uid: reader.read_u32::<BigEndian>()?
				}
			} else {
				bail!("Unrecognized request type id: {}", type_id);
			};

		Ok(RequestEnvelope {
			id: request_id,
			request,
		})
	}
}


#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResponseEnvelope {
	pub id: u32,
	pub response: Response
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Response {

	Pong,
	Exec(ExecResponse),
	Status(bool),

	// no response needed for kill

	Username(Option<String>),
	Uid(Option<u32>),
	Groupname(Option<String>),
	Gid(Option<u32>),
	Gids(Option<Vec<u32>>)
}

impl Response {
	const ID_PONG: u32 = 1;
	const ID_EXEC: u32 = 2;
	const ID_STATUS: u32 = 3;
	const ID_USERNAME: u32 = 4;
	const ID_UID: u32 = 5;
	const ID_GROUPNAME: u32 = 6;
	const ID_GID: u32 = 7;
	const ID_GIDS: u32 = 8;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExecResponse {
	Success {
		pid: u32
	},
	Failure {
		reason: String
	}
}

impl ResponseEnvelope {

	pub fn encode(&self) -> Result<Vec<u8>> {
		let mut out = Vec::<u8>::new();

		out.write_u32::<BigEndian>(self.id)?;

		match &self.response {

			Response::Pong => {
				// only need the type id
				out.write_u32::<BigEndian>(Response::ID_PONG)?;
			}

			Response::Exec(response) => {
				out.write_u32::<BigEndian>(Response::ID_EXEC)?;
				match response {
					ExecResponse::Success { pid} => {
						out.write_u32::<BigEndian>(1)?;
						out.write_u32::<BigEndian>(*pid)?;
					}
					ExecResponse::Failure { reason } => {
						out.write_u32::<BigEndian>(2)?;
						out.write_utf8(reason)?;
					}
				}
			}

			Response::Status(status) => {
				out.write_u32::<BigEndian>(Response::ID_STATUS)?;
				out.write_bool(*status)?;
			}

			Response::Username(username) => {
				out.write_u32::<BigEndian>(Response::ID_USERNAME)?;
				out.write_option(username, |out, username| {
					out.write_utf8(username)
				})?
			}

			Response::Uid(uid) => {
				out.write_u32::<BigEndian>(Response::ID_UID)?;
				out.write_option(uid, |out, uid| {
					out.write_u32::<BigEndian>(*uid)?;
					Ok(())
				})?
			}

			Response::Groupname(groupname) => {
				out.write_u32::<BigEndian>(Response::ID_GROUPNAME)?;
				out.write_option(groupname, |out, groupname| {
					out.write_utf8(groupname)
				})?
			}

			Response::Gid(gid) => {
				out.write_u32::<BigEndian>(Response::ID_GID)?;
				out.write_option(gid, |out, gid| {
					out.write_u32::<BigEndian>(*gid)?;
					Ok(())
				})?
			}

			Response::Gids(gids) => {
				out.write_u32::<BigEndian>(Response::ID_GIDS)?;
				out.write_option(gids, |out, gids| {
					out.write_vec(gids, |out, gid| {
						out.write_u32::<BigEndian>(*gid)?;
						Ok(())
					})
				})?
			}
		}

		Ok(out)
	}

	pub fn decode(msg: impl AsRef<[u8]>) -> Result<Self> {

		let msg = msg.as_ref();
		let mut reader = Cursor::new(msg);

		// read the request id
		let request_id = reader.read_u32::<BigEndian>()
			.context("Failed to read the request id")?;

		// read the request type id
		let type_id = reader.read_u32::<BigEndian>()
			.context("Failed to read response type id")?;
		// NOTE: we can't match on constants here, so use if/elseif/else instead
		//       see: https://doc.rust-lang.org/unstable-book/language-features/inline-const-pat.html
		let response =
			if type_id == Response::ID_PONG {
				Response::Pong
			} else if type_id == Response::ID_EXEC {
				Response::Exec({
					let kind = reader.read_u32::<BigEndian>()?;
					match kind {
						1 => ExecResponse::Success {
							pid: reader.read_u32::<BigEndian>()?
						},
						2 => ExecResponse::Failure {
							reason: reader.read_utf8()?
						},
						_ => bail!("Unrecognized exec response kind: {}", kind)
					}
				})
			} else if type_id == Response::ID_STATUS {
				Response::Status(
					reader.read_bool()?
				)
			} else if type_id == Response::ID_USERNAME {
				Response::Username(
					reader.read_option(|reader| {
						reader.read_utf8()
					})?
				)
			} else if type_id == Response::ID_UID {
				Response::Uid(
					reader.read_option(|reader| {
						let uid = reader.read_u32::<BigEndian>()?;
						Ok(uid)
					})?
				)
			} else if type_id == Response::ID_GROUPNAME {
				Response::Groupname(
					reader.read_option(|reader| {
						reader.read_utf8()
					})?
				)
			} else if type_id == Response::ID_GID {
				Response::Gid(
					reader.read_option(|reader| {
						let gid = reader.read_u32::<BigEndian>()?;
						Ok(gid)
					})?
				)
			} else if type_id == Response::ID_GIDS {
				Response::Gids(
					reader.read_option(|reader| {
						reader.read_vec(|reader| {
							let gid = reader.read_u32::<BigEndian>()?;
							Ok(gid)
						})
					})?
				)
			} else {
				bail!("Unrecognized response type id: {}", type_id);
			};

		Ok(ResponseEnvelope {
			id: request_id,
			response
		})
	}
}


#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProcEvent {
	Console(ProcConsole),
	Fin(ProcFin)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcConsole {
	pub pid: u32,
	pub kind: ConsoleKind,
	pub buf: Vec<u8>
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ConsoleKind {
	Stdout,
	Stderr
}

impl ConsoleKind {

	pub fn name(&self) -> &'static str {
		match self {
			Self::Stdout => "stdout",
			Self::Stderr => "stderr"
		}
	}
}


#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcFin {
	pub pid: u32,
	pub exit_code: Option<i32>
}


impl ProcEvent {

	pub fn encode(&self) -> Result<Vec<u8>> {
		let mut out = Vec::<u8>::new();

		match self {

			ProcEvent::Console(chunk) => {
				out.write_u32::<BigEndian>(1)?;

				out.write_u32::<BigEndian>(chunk.pid)?;

				out.write_u32::<BigEndian>(match chunk.kind {
					ConsoleKind::Stdout => 1,
					ConsoleKind::Stderr => 2
				})?;

				out.write_bytes(chunk.buf.as_slice())?;
			}

			ProcEvent::Fin(fin) => {
				out.write_u32::<BigEndian>(2)?;
				out.write_u32::<BigEndian>(fin.pid)?;
				out.write_option(&fin.exit_code, |out, exit_code| {
					out.write_i32::<BigEndian>(*exit_code)?;
					Ok(())
				})?
			}
		}

		Ok(out)
	}

	pub fn decode(msg: impl AsRef<[u8]>) -> Result<Self> {

		let msg = msg.as_ref();
		let mut reader = Cursor::new(msg);

		let event = reader.read_u32::<BigEndian>()?;
		match event {

			1 => Ok(Self::Console(ProcConsole {
				pid: reader.read_u32::<BigEndian>()?,
				kind: {
					let kind = reader.read_u32::<BigEndian>()?;
					match kind {
						1 => ConsoleKind::Stdout,
						2 => ConsoleKind::Stderr,
						_ => bail!("Unrecognized console kind: {}", kind)
					}
				},
				buf: reader.read_bytes()?
			})),

			2 => Ok(Self::Fin(ProcFin {
				pid: reader.read_u32::<BigEndian>()?,
				exit_code: reader.read_option(|reader| {
					let c = reader.read_i32::<BigEndian>()?;
					Ok(c)
				})?
			})),

			_ => bail!("Unrecognized proc event: {}", event)
		}
	}
}


trait WriteExt {
	fn write_bool(&mut self, b: bool) -> Result<()>;
	fn write_bytes(&mut self, bytes: impl AsRef<[u8]>) -> Result<()>;
	fn write_utf8(&mut self, s: impl AsRef<str>) -> Result<()>;
	fn write_vec<T,F>(&mut self, v: &Vec<T>, f: F) -> Result<()>
		where F: Fn(&mut Self, &T) -> Result<()>;
	fn write_option<T,F>(&mut self, opt: &Option<T>, f: F) -> Result<()>
		where F: Fn(&mut Self, &T) -> Result<()>;
}

impl<W> WriteExt for W
	where
		W: WriteBytesExt
{
	fn write_bool(&mut self, b: bool) -> Result<()> {

		// convert to u8 explicitly (because who knows what `as u8` does ...)
		let i: u8 = if b {
			1
		} else {
			0
		};

		self.write_u8(i)
			.context("Failed to write bool")
	}

	fn write_bytes(&mut self, bytes: impl AsRef<[u8]>) -> Result<()> {

		let bytes = bytes.as_ref();

		// write the size
		let size: u32 = bytes.len()
			.try_into()
			.map_err(|_| anyhow!("Too many bytes: {}, max of {}", bytes.len(), u32::MAX))?;
		self.write_u32::<BigEndian>(size)
			.context("Failed to write bytes size")?;

		// write the bytes
		self.write_all(bytes)
			.context("Failed to write bytes")?;

		Ok(())
	}

	fn write_utf8(&mut self, s: impl AsRef<str>) -> Result<()> {
		self.write_bytes(s.as_ref().as_bytes())
	}

	fn write_vec<T,F>(&mut self, v: &Vec<T>, f: F) -> Result<()>
		where
			F: Fn(&mut Self, &T) -> Result<()>
	{

		// write the vec size first
		let size: u32 = v.len()
			.try_into()
			.map_err(|_| anyhow!("Vec too large: {}, max of {}", v.len(), u32::MAX))?;
		self.write_u32::<BigEndian>(size)
			.context("Failed to write vec size")?;

		// write the vec items
		for item in v {
			f(self, item)?;
		}

		Ok(())
	}

	fn write_option<T,F>(&mut self, opt: &Option<T>, f: F) -> Result<()>
		where
			F: Fn(&mut Self, &T) -> Result<()>
	{
		if let Some(val) = opt {
			self.write_u32::<BigEndian>(1)?;
			f(self, val)?;
		} else {
			self.write_u32::<BigEndian>(2)?;
		}

		Ok(())
	}
}


trait ReadExt {
	fn read_bool(&mut self) -> Result<bool>;
	fn read_bytes(&mut self) -> Result<Vec<u8>>;
	fn read_utf8(&mut self) -> Result<String>;
	fn read_vec<T,F>(&mut self, f: F) -> Result<Vec<T>>
		where F: Fn(&mut Self) -> Result<T>;
	fn read_option<T,F>(&mut self, f: F) -> Result<Option<T>>
		where F: Fn(&mut Self) -> Result<T>;
}

impl<R> ReadExt for R
	where
		R: ReadBytesExt
{
	fn read_bool(&mut self) -> Result<bool> {
		let i = self.read_u8()
			.context("Failed to read bool")?;
		match i {
			0 => Ok(false),
			1 => Ok(true),
			_ => bail!("Unexpected bool encoding: {}", i)
		}
	}

	fn read_bytes(&mut self) -> Result<Vec<u8>> {

		// read the size
		let size = self.read_u32::<BigEndian>()
			.context("Failed to read bytes size")?;

		// read the bytes
		let mut buf = vec![0u8; size as usize];
		self.read_exact(buf.as_mut())
			.context("Failed to read bytes")?;

		Ok(buf)
	}

	fn read_utf8(&mut self) -> Result<String> {

		// read the bytes, then convert to UTF8
		let bytes = self.read_bytes()?;
		Ok(String::from_utf8_lossy(bytes.as_ref()).to_string())
	}

	fn read_vec<T,F>(&mut self, f: F) -> Result<Vec<T>>
		where
			F: Fn(&mut Self) -> Result<T>
	{

		// read the vec length
		let size = self.read_u32::<BigEndian>()
			.context("Failed to read vec size")?;

		// read the vec items
		let mut out = Vec::<T>::with_capacity(size as usize);
		for _ in 0 .. size {
			let item = f(self)?;
			out.push(item);
		}

		Ok(out)
	}

	fn read_option<T,F>(&mut self, f: F) -> Result<Option<T>>
		where
			F: Fn(&mut Self) -> Result<T>
	{
		let signal = self.read_u32::<BigEndian>()?;
		match signal {
			1 => {
				let val = f(self)?;
				Ok(Some(val))
			}
			2 => Ok(None),
			_ => bail!("Unrecognized option signal: {}", signal)
		}
	}
}


#[cfg(test)]
mod test {

	use galvanic_assert::{assert_that, matchers::*};

	use super::*;


	#[test]
	fn request() {

		fn assert_roundtrip(request: Request) {
			let envelope = RequestEnvelope {
				id: 5,
				request
			};
			let msg = envelope.encode()
				.expect("Failed to encode");
			let envelope2 = RequestEnvelope::decode(msg)
				.expect("Failed to decode");
			assert_that!(&envelope2, eq(envelope));
		}

		assert_roundtrip(Request::Ping);

		assert_roundtrip(Request::Exec(ExecRequest {
			program: "program".to_string(),
			args: vec!["arg1".to_string(), "arg2".to_string()],
			stream_stdin: false,
			stream_stdout: true,
			stream_stderr: false,
			stream_fin: true
		}));

		assert_roundtrip(Request::Status {
			pid: 5
		});

		assert_roundtrip(Request::WriteStdin {
			pid: 37,
			chunk: b"a line".to_vec()
		});

		assert_roundtrip(Request::CloseStdin {
			pid: 5
		});

		assert_roundtrip(Request::Kill {
			pid: 42
		});

		assert_roundtrip(Request::Username {
			uid: 5
		});

		assert_roundtrip(Request::Uid {
			username: "bob".to_string()
		});

		assert_roundtrip(Request::Groupname {
			gid: 42
		});

		assert_roundtrip(Request::Gid {
			groupname: "peeps".to_string()
		});

		assert_roundtrip(Request::Gids {
			uid: 7
		});
	}


	#[test]
	fn response() {

		fn assert_roundtrip(response: Response) {
			let envelope = ResponseEnvelope {
				id: 5,
				response
			};
			let msg = envelope.encode()
				.expect("Failed to encode");
			let envelope2 = ResponseEnvelope::decode(msg)
				.expect("Failed to decode");
			assert_that!(&envelope2, eq(envelope));
		}

		assert_roundtrip(Response::Pong);

		assert_roundtrip(Response::Exec(ExecResponse::Success {
			pid: 5
		}));
		assert_roundtrip(Response::Exec(ExecResponse::Failure {
			reason: "nope".to_string()
		}));

		assert_roundtrip(Response::Status(true));
		assert_roundtrip(Response::Status(false));

		assert_roundtrip(Response::Username(Some("bob".to_string())));
		assert_roundtrip(Response::Username(None));

		assert_roundtrip(Response::Uid(Some(32)));
		assert_roundtrip(Response::Uid(None));

		assert_roundtrip(Response::Groupname(Some("peeps".to_string())));
		assert_roundtrip(Response::Groupname(None));

		assert_roundtrip(Response::Gid(Some(5)));
		assert_roundtrip(Response::Gid(None));

		assert_roundtrip(Response::Gids(Some(vec![1, 2, 3])));
		assert_roundtrip(Response::Gids(None));
	}


	#[test]
	fn proc_event() {

		fn assert_roundtrip(event: ProcEvent) {
			let msg = event.encode()
				.expect("Failed to encode proc event");
			let event2 = ProcEvent::decode(msg)
				.expect("Failed to decode proc event");
			assert_that!(&event2, eq(event));
		}

		assert_roundtrip(ProcEvent::Console(ProcConsole {
			pid: 5,
			kind: ConsoleKind::Stdout,
			buf: b"foo bar".to_vec(),
		}));

		assert_roundtrip(ProcEvent::Console(ProcConsole {
			pid: 42,
			kind: ConsoleKind::Stderr,
			buf: b"oh noes!".to_vec(),
		}));

		assert_roundtrip(ProcEvent::Fin(ProcFin {
			pid: 5,
			exit_code: None
		}));
		assert_roundtrip(ProcEvent::Fin(ProcFin {
			pid: 42,
			exit_code: Some(-9)
		}));
	}
}


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

	/// query the uid and euid for this current user
	Uids,

	ReadFile {
		path: String
	},

	WriteFile(WriteFileRequest)
}

impl Request {
	const ID_PING: u32 = 1;
	const ID_UIDS: u32 = 2;
	const ID_READ_FILE: u32 = 3;
	const ID_WRITE_FILE: u32 = 4;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WriteFileRequest {

	Open {
		path: String,
		append: bool
	},

	Chunk {
		sequence: u32,
		data: Vec<u8>
	},

	Close {
		sequence: u32
	}
}

impl WriteFileRequest {
	const ID_OPEN: u32 = 1;
	const ID_CHUNK: u32 = 2;
	const ID_CLOSE: u32 = 3;
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

			Request::Uids => {
				out.write_u32::<BigEndian>(Request::ID_UIDS)?;
			}

			Request::ReadFile { path } => {
				out.write_u32::<BigEndian>(Request::ID_READ_FILE)?;
				out.write_utf8(path)?;
			}

			Request::WriteFile(request) => {
				out.write_u32::<BigEndian>(Request::ID_WRITE_FILE)?;
				match request {
					WriteFileRequest::Open { path, append } => {
						out.write_u32::<BigEndian>(WriteFileRequest::ID_OPEN)?;
						out.write_utf8(path)?;
						out.write_bool(*append)?;
					}
					WriteFileRequest::Chunk { sequence, data } => {
						out.write_u32::<BigEndian>(WriteFileRequest::ID_CHUNK)?;
						out.write_u32::<BigEndian>(*sequence)?;
						out.write_bytes(data)?;
					}
					WriteFileRequest::Close { sequence } => {
						out.write_u32::<BigEndian>(WriteFileRequest::ID_CLOSE)?;
						out.write_u32::<BigEndian>(*sequence)?;
					}
				}
			}
		}

		Ok(out)
	}

	pub fn decode(msg: impl AsRef<[u8]>) -> std::result::Result<Self,(anyhow::Error,Option<u32>)> {

		let msg = msg.as_ref();
		let mut reader = Cursor::new(msg);

		let request_id = reader.read_u32::<BigEndian>()
			.context("Failed to read request id")
			.map_err(|e| (e, None))?;

		// read the request type id
		let type_id = reader.read_u32::<BigEndian>()
			.context("Failed to read request type id")
			.map_err(|e| (e, Some(request_id)))?;
		// NOTE: we can't match on constants here, so use if/elseif/else instead
		//       see: https://doc.rust-lang.org/unstable-book/language-features/inline-const-pat.html
		let request =
			if type_id == Request::ID_PING {
				Request::Ping
			} else if type_id == Request::ID_UIDS {
				Request::Uids
			} else if type_id == Request::ID_READ_FILE {
				Request::ReadFile {
					path: reader.read_utf8().map_err(|e| (e, Some(request_id)))?
				}
			} else if type_id == Request::ID_WRITE_FILE {
				Request::WriteFile({
					let write_file_type_id = reader.read_u32::<BigEndian>().map_err(|e| (e.into(), Some(request_id)))?;
					if write_file_type_id == WriteFileRequest::ID_OPEN {
						WriteFileRequest::Open {
							path: reader.read_utf8().map_err(|e| (e, Some(request_id)))?,
							append: reader.read_bool().map_err(|e| (e, Some(request_id)))?
						}
					} else if write_file_type_id == WriteFileRequest::ID_CHUNK {
						WriteFileRequest::Chunk {
							sequence: reader.read_u32::<BigEndian>().map_err(|e| (e.into(), Some(request_id)))?,
							data: reader.read_bytes().map_err(|e| (e, Some(request_id)))?
						}
					} else if write_file_type_id == WriteFileRequest::ID_CLOSE {
						WriteFileRequest::Close {
							sequence: reader.read_u32::<BigEndian>().map_err(|e| (e.into(), Some(request_id)))?
						}
					} else {
						return Err((anyhow!("Unrecognized write file request type id: {}", write_file_type_id), Some(request_id)));
					}
				})
			} else {
				return Err((anyhow!("Unrecognized request type id: {}", type_id), Some(request_id)));
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

	Error { reason: String },

	Pong,
	Uids {
		uid: u32,
		euid: u32
	},

	ReadFile(ReadFileResponse),
	WriteFile(WriteFileResponse)
}

impl Response {
	const ID_ERROR: u32 = 1;
	const ID_PONG: u32 = 2;
	const ID_UIDS: u32 = 3;
	const ID_READ_FILE: u32 = 4;
	const ID_WRITE_FILE: u32 = 5;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReadFileResponse {

	Open {
		bytes: u64
	},

	Chunk {
		sequence: u32,
		data: Vec<u8>
	},

	Close {
		sequence: u32
	}
}

impl ReadFileResponse {
	const ID_OPEN: u32 = 1;
	const ID_CHUNK: u32 = 2;
	const ID_CLOSE: u32 = 3;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WriteFileResponse {
	Opened,
	Closed
}

impl WriteFileResponse {
	const ID_OPENED: u32 = 1;
	const ID_CLOSED: u32 = 2;
}


impl ResponseEnvelope {

	pub fn encode(&self) -> Result<Vec<u8>> {
		let mut out = Vec::<u8>::new();

		out.write_u32::<BigEndian>(self.id)?;

		match &self.response {

			Response::Error { reason } => {
				out.write_u32::<BigEndian>(Response::ID_ERROR)?;
				out.write_utf8(reason)?;
			}

			Response::Pong => {
				// only need the type id
				out.write_u32::<BigEndian>(Response::ID_PONG)?;
			}

			Response::Uids { uid, euid } => {
				out.write_u32::<BigEndian>(Response::ID_UIDS)?;
				out.write_u32::<BigEndian>(*uid)?;
				out.write_u32::<BigEndian>(*euid)?;
			}

			Response::ReadFile(response) => {
				out.write_u32::<BigEndian>(Response::ID_READ_FILE)?;
				match response {
					ReadFileResponse::Open { bytes } => {
						out.write_u32::<BigEndian>(ReadFileResponse::ID_OPEN)?;
						out.write_u64::<BigEndian>(*bytes)?;
					}
					ReadFileResponse::Chunk { sequence, data } => {
						out.write_u32::<BigEndian>(ReadFileResponse::ID_CHUNK)?;
						out.write_u32::<BigEndian>(*sequence)?;
						out.write_bytes(data)?;
					}
					ReadFileResponse::Close { sequence } => {
						out.write_u32::<BigEndian>(ReadFileResponse::ID_CLOSE)?;
						out.write_u32::<BigEndian>(*sequence)?;
					}
				}
			}

			Response::WriteFile(response) => {
				out.write_u32::<BigEndian>(Response::ID_WRITE_FILE)?;
				match response {
					WriteFileResponse::Opened => {
						out.write_u32::<BigEndian>(WriteFileResponse::ID_OPENED)?;
					}
					WriteFileResponse::Closed => {
						out.write_u32::<BigEndian>(WriteFileResponse::ID_CLOSED)?;
					}
				}
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
			if type_id == Response::ID_ERROR {
				Response::Error {
					reason: reader.read_utf8()?
				}
			} else if type_id == Response::ID_PONG {
				Response::Pong
			} else if type_id == Response::ID_UIDS {
				Response::Uids {
					uid: reader.read_u32::<BigEndian>()?,
					euid: reader.read_u32::<BigEndian>()?
				}
			} else if type_id == Response::ID_READ_FILE {
				Response::ReadFile({
					let read_file_type_id = reader.read_u32::<BigEndian>()?;
					if read_file_type_id == ReadFileResponse::ID_OPEN {
						ReadFileResponse::Open {
							bytes: reader.read_u64::<BigEndian>()?
						}
					} else if read_file_type_id == ReadFileResponse::ID_CHUNK {
						ReadFileResponse::Chunk {
							sequence: reader.read_u32::<BigEndian>()?,
							data: reader.read_bytes()?
						}
					} else if read_file_type_id == ReadFileResponse::ID_CLOSE {
						ReadFileResponse::Close {
							sequence: reader.read_u32::<BigEndian>()?
						}
					} else {
						bail!("Unrecognized read file type id: {}", read_file_type_id);
					}
				})
			} else if type_id == Response::ID_WRITE_FILE {
				Response::WriteFile({
					let write_file_type_id = reader.read_u32::<BigEndian>()?;
					if write_file_type_id == WriteFileResponse::ID_OPENED {
						WriteFileResponse::Opened
					} else if write_file_type_id == WriteFileResponse::ID_CLOSED {
						WriteFileResponse::Closed
					} else {
						bail!("Unrecognized write file type id: {}", write_file_type_id);
					}
				})
			} else {
				bail!("Unrecognized response type id: {}", type_id);
			};

		Ok(ResponseEnvelope {
			id: request_id,
			response
		})
	}
}


trait WriteExt {
	fn write_bool(&mut self, b: bool) -> Result<()>;
	fn write_bytes(&mut self, bytes: impl AsRef<[u8]>) -> Result<()>;
	fn write_utf8(&mut self, s: impl AsRef<str>) -> Result<()>;
	#[allow(unused)]
	fn write_vec<T,F>(&mut self, v: &Vec<T>, f: F) -> Result<()>
		where F: Fn(&mut Self, &T) -> Result<()>;
	#[allow(unused)]
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
	#[allow(unused)]
	fn read_vec<T,F>(&mut self, f: F) -> Result<Vec<T>>
		where F: Fn(&mut Self) -> Result<T>;
	#[allow(unused)]
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

		assert_roundtrip(Request::Uids);

		assert_roundtrip(Request::ReadFile {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::WriteFile(WriteFileRequest::Open {
			path: "foo".to_string(),
			append: false
		}));
		assert_roundtrip(Request::WriteFile(WriteFileRequest::Chunk {
			sequence: 5,
			data: vec![1, 2, 3]
		}));
		assert_roundtrip(Request::WriteFile(WriteFileRequest::Close {
			sequence: 42
		}));
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

		assert_roundtrip(Response::Error {
			reason: "foo".to_string()
		});

		assert_roundtrip(Response::Pong);

		assert_roundtrip(Response::Uids {
			uid: 5,
			euid: 42
		});

		assert_roundtrip(Response::ReadFile(ReadFileResponse::Open {
			bytes: 5
		}));
		assert_roundtrip(Response::ReadFile(ReadFileResponse::Chunk {
			sequence: 42,
			data: vec![1, 2, 3]
		}));
		assert_roundtrip(Response::ReadFile(ReadFileResponse::Close {
			sequence: 7
		}));
	}
}

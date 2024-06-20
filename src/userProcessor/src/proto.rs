
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

	WriteFile(WriteFileRequest),
	Chmod(ChmodRequest),

	DeleteFile {
		path: String
	},

	CreateFolder {
		path: String
	},
	DeleteFolder {
		path: String
	},
	ListFolder {
		path: String
	},

	Stat {
		path: String
	},

	Rename {
		src: String,
		dst: String
	},

	Symlink {
		path: String,
		link: String
	}
}

impl Request {
	const ID_PING: u32 = 1;
	const ID_UIDS: u32 = 2;
	const ID_READ_FILE: u32 = 3;
	const ID_WRITE_FILE: u32 = 4;
	const ID_CHMOD: u32 = 5;
	const ID_DELETE_FILE: u32 = 6;
	const ID_CREATE_FOLDER: u32 = 7;
	const ID_DELETE_FOLDER: u32 = 8;
	const ID_LIST_FOLDER: u32 = 9;
	const ID_STAT: u32 = 10;
	const ID_RENAME: u32 = 11;
	const ID_SYMLINK: u32 = 12;
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChmodRequest {
	pub path: String,
	pub ops: Vec<ChmodOp>
}

impl ChmodRequest {

	pub fn ops_to_string(&self) -> String {
		let mut out = String::new();
		for (i, op) in self.ops.iter().enumerate() {
			if i > 0 {
				out.push_str(", ");
			}
			out.push(match op.value {
				true => '+',
				false => '-'
			});
			out.push('[');
			for (j, bit) in op.bits.iter().enumerate() {
				if j > 0 {
					out.push(',');
				}
				out.push_str(match bit {
					ChmodBit::OtherExecute => "ox",
					ChmodBit::OtherWrite => "ow",
					ChmodBit::OtherRead => "or",
					ChmodBit::GroupExecute => "gx",
					ChmodBit::GroupWrite => "gw",
					ChmodBit::GroupRead => "gr",
					ChmodBit::UserExecute => "ux",
					ChmodBit::UserWrite => "uw",
					ChmodBit::UserRead => "ur",
					ChmodBit::Sticky => "t",
					ChmodBit::SetGid => "us",
					ChmodBit::SetUid => "gs"
				});
			}
			out.push(']');
		}
		out
	}
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChmodOp {
	pub value: bool,
	pub bits: Vec<ChmodBit>
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChmodBit {
	OtherExecute,
	OtherWrite,
	OtherRead,
	GroupExecute,
	GroupWrite,
	GroupRead,
	UserExecute,
	UserWrite,
	UserRead,
	Sticky,
	SetGid,
	SetUid
}

impl ChmodBit {

	pub fn pos(&self) -> u8 {
		match self {
			ChmodBit::OtherExecute => 0,
			ChmodBit::OtherWrite => 1,
			ChmodBit::OtherRead => 2,
			ChmodBit::GroupExecute => 3,
			ChmodBit::GroupWrite => 4,
			ChmodBit::GroupRead => 5,
			ChmodBit::UserExecute => 6,
			ChmodBit::UserWrite => 7,
			ChmodBit::UserRead => 8,
			ChmodBit::Sticky => 9,
			ChmodBit::SetGid => 10,
			ChmodBit::SetUid => 11
		}
	}

	pub fn from(pos: u8) -> Result<Self> {
		match pos {
			0 => Ok(ChmodBit::OtherExecute),
			1 => Ok(ChmodBit::OtherWrite),
			2 => Ok(ChmodBit::OtherRead),
			3 => Ok(ChmodBit::GroupExecute),
			4 => Ok(ChmodBit::GroupWrite),
			5 => Ok(ChmodBit::GroupRead),
			6 => Ok(ChmodBit::UserExecute),
			7 => Ok(ChmodBit::UserWrite),
			8 => Ok(ChmodBit::UserRead),
			9 => Ok(ChmodBit::Sticky),
			10 => Ok(ChmodBit::SetGid),
			11 => Ok(ChmodBit::SetUid),
			_ => bail!("unrecognized chmod bit pos: {}", pos)
		}
	}
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

			Request::Chmod(request) => {
				out.write_u32::<BigEndian>(Request::ID_CHMOD)?;
				out.write_utf8(&request.path)?;
				out.write_vec(&request.ops, |out, op| {
					out.write_bool(op.value)?;
					out.write_vec(&op.bits, |out, bit| {
						out.write_u8(bit.pos())?;
						Ok(())
					})?;
					Ok(())
				})?;
			}

			Request::DeleteFile { path } => {
				out.write_u32::<BigEndian>(Request::ID_DELETE_FILE)?;
				out.write_utf8(path)?;
			}

			Request::CreateFolder { path } => {
				out.write_u32::<BigEndian>(Request::ID_CREATE_FOLDER)?;
				out.write_utf8(path)?;
			}

			Request::DeleteFolder { path } => {
				out.write_u32::<BigEndian>(Request::ID_DELETE_FOLDER)?;
				out.write_utf8(path)?;
			}

			Request::ListFolder { path } => {
				out.write_u32::<BigEndian>(Request::ID_LIST_FOLDER)?;
				out.write_utf8(path)?;
			}

			Request::Stat { path } => {
				out.write_u32::<BigEndian>(Request::ID_STAT)?;
				out.write_utf8(path)?;
			}

			Request::Rename { src, dst } => {
				out.write_u32::<BigEndian>(Request::ID_RENAME)?;
				out.write_utf8(src)?;
				out.write_utf8(dst)?;
			}

			Request::Symlink { path, link } => {
				out.write_u32::<BigEndian>(Request::ID_SYMLINK)?;
				out.write_utf8(path)?;
				out.write_utf8(link)?;
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
			} else if type_id == Request::ID_CHMOD {
				Request::Chmod(ChmodRequest {
					path: reader.read_utf8().map_err(|e| (e, Some(request_id)))?,
					ops: reader.read_vec(|reader| {
						Ok(ChmodOp {
							value: reader.read_bool()?,
							bits: reader.read_vec(|reader| {
								let pos = reader.read_u8()?;
								ChmodBit::from(pos)
							})?
						})
					}).map_err(|e| (e.into(), Some(request_id)))?,
				})
			} else if type_id == Request::ID_DELETE_FILE {
				Request::DeleteFile {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_CREATE_FOLDER {
				Request::CreateFolder {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_DELETE_FOLDER {
				Request::DeleteFolder {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_LIST_FOLDER {
				Request::ListFolder {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_STAT {
				Request::Stat {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_RENAME {
				Request::Rename {
					src: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?,
					dst: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
			} else if type_id == Request::ID_SYMLINK {
				Request::Symlink {
					path: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?,
					link: reader.read_utf8().map_err(|e| (e.into(), Some(request_id)))?
				}
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
		euid: u32,
		suid: u32
	},

	ReadFile(ReadFileResponse),
	WriteFile(WriteFileResponse),
	Chmod,
	DeleteFile,
	CreateFolder,
	DeleteFolder,

	Stat(StatResponse),
	Rename,
	Symlink
}

impl Response {
	const ID_ERROR: u32 = 1;
	const ID_PONG: u32 = 2;
	const ID_UIDS: u32 = 3;
	const ID_READ_FILE: u32 = 4;
	const ID_WRITE_FILE: u32 = 5;
	const ID_CHMOD: u32 = 6;
	const ID_DELETE_FILE: u32 = 7;
	const ID_CREATE_FOLDER: u32 = 8;
	const ID_DELETE_FOLDER: u32 = 9;
	const ID_STAT: u32 = 10;
	const ID_RENAME: u32 = 11;
	const ID_SYMLINK: u32 = 12;
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StatResponse {
	NotFound,
	File {
		size: u64
	},
	Dir,
	Symlink(StatSymlinkResponse),
	Other
}

impl StatResponse {
	const ID_NOT_FOUND: u32 = 1;
	const ID_FILE: u32 = 2;
	const ID_DIR: u32 = 3;
	const ID_SYMLINK: u32 = 4;
	const ID_OTHER: u32 = 5;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StatSymlinkResponse {
	NotFound,
	File {
		size: u64
	},
	Dir,
	Other
}

impl StatSymlinkResponse {
	const ID_NOT_FOUND: u32 = 1;
	const ID_FILE: u32 = 2;
	const ID_DIR: u32 = 3;
	const ID_OTHER: u32 = 4;
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

			Response::Uids { uid, euid, suid } => {
				out.write_u32::<BigEndian>(Response::ID_UIDS)?;
				out.write_u32::<BigEndian>(*uid)?;
				out.write_u32::<BigEndian>(*euid)?;
				out.write_u32::<BigEndian>(*suid)?;
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

			Response::Chmod => {
				out.write_u32::<BigEndian>(Response::ID_CHMOD)?;
			}

			Response::DeleteFile => {
				out.write_u32::<BigEndian>(Response::ID_DELETE_FILE)?;
			}

			Response::CreateFolder => {
				out.write_u32::<BigEndian>(Response::ID_CREATE_FOLDER)?;
			}

			Response::DeleteFolder => {
				out.write_u32::<BigEndian>(Response::ID_DELETE_FOLDER)?;
			}

			Response::Stat(response) => {
				out.write_u32::<BigEndian>(Response::ID_STAT)?;
				match response {
					StatResponse::NotFound => {
						out.write_u32::<BigEndian>(StatResponse::ID_NOT_FOUND)?;
					}
					StatResponse::File { size } => {
						out.write_u32::<BigEndian>(StatResponse::ID_FILE)?;
						out.write_u64::<BigEndian>(*size)?;
					}
					StatResponse::Dir => {
						out.write_u32::<BigEndian>(StatResponse::ID_DIR)?;
					}
					StatResponse::Symlink(inner) => {
						out.write_u32::<BigEndian>(StatResponse::ID_SYMLINK)?;
						match inner {
							StatSymlinkResponse::NotFound => {
								out.write_u32::<BigEndian>(StatSymlinkResponse::ID_NOT_FOUND)?;
							}
							StatSymlinkResponse::File { size } => {
								out.write_u32::<BigEndian>(StatSymlinkResponse::ID_FILE)?;
								out.write_u64::<BigEndian>(*size)?;
							}
							StatSymlinkResponse::Dir => {
								out.write_u32::<BigEndian>(StatSymlinkResponse::ID_DIR)?;
							}
							StatSymlinkResponse::Other => {
								out.write_u32::<BigEndian>(StatSymlinkResponse::ID_OTHER)?;
							}
						}
					}
					StatResponse::Other => {
						out.write_u32::<BigEndian>(StatResponse::ID_OTHER)?;
					}
				}
			}

			Response::Rename => {
				out.write_u32::<BigEndian>(Response::ID_RENAME)?;
			}

			Response::Symlink => {
				out.write_u32::<BigEndian>(Response::ID_SYMLINK)?;
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
					euid: reader.read_u32::<BigEndian>()?,
					suid: reader.read_u32::<BigEndian>()?
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
			} else if type_id == Response::ID_CHMOD {
				Response::Chmod
			} else if type_id == Response::ID_DELETE_FILE {
				Response::DeleteFile
			} else if type_id == Response::ID_CREATE_FOLDER {
				Response::CreateFolder
			} else if type_id == Response::ID_DELETE_FOLDER {
				Response::DeleteFolder
			} else if type_id == Response::ID_STAT {
				Response::Stat({
					let lstat_type_id = reader.read_u32::<BigEndian>()?;
					if lstat_type_id == StatResponse::ID_NOT_FOUND {
						StatResponse::NotFound
					} else if lstat_type_id == StatResponse::ID_FILE {
						StatResponse::File {
							size: reader.read_u64::<BigEndian>()?
						}
					} else if lstat_type_id == StatResponse::ID_DIR {
						StatResponse::Dir
					} else if lstat_type_id == StatResponse::ID_SYMLINK {
						StatResponse::Symlink({
							let stat_type_id = reader.read_u32::<BigEndian>()?;
							if stat_type_id == StatSymlinkResponse::ID_NOT_FOUND {
								StatSymlinkResponse::NotFound
							} else if stat_type_id == StatSymlinkResponse::ID_FILE {
								StatSymlinkResponse::File {
									size: reader.read_u64::<BigEndian>()?
								}
							} else if stat_type_id == StatSymlinkResponse::ID_DIR {
								StatSymlinkResponse::Dir
							} else if stat_type_id == StatSymlinkResponse::ID_OTHER {
								StatSymlinkResponse::Other
							} else {
								bail!("Unrecognized stat type id: {}", stat_type_id);
							}
						})
					} else if lstat_type_id == StatResponse::ID_OTHER {
						StatResponse::Other
					} else {
						bail!("Unrecognized lstat type id: {}", lstat_type_id);
					}
				})
			} else if type_id == Response::ID_RENAME {
				Response::Rename
			} else if type_id == Response::ID_SYMLINK {
				Response::Symlink
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


#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileEntry {
	pub name: String,
	pub kind: FileKind
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileKind {
	Unknown,
	File,
	Dir,
	Symlink,
	Fifo,
	Socket,
	BlockDev,
	CharDev
}

impl FileKind {
	const ID_UNKNOWN: u8 = 0;
	const ID_FILE: u8 = 1;
	const ID_DIR: u8 = 2;
	const ID_SYMLINK: u8 = 3;
	const ID_FIFO: u8 = 4;
	const ID_SOCKET: u8 = 5;
	const ID_BLOCK_DEV: u8 = 6;
	const ID_CHAR_DEV: u8 = 7;
}

const FILES_EOF: u8 = u8::MAX;


pub struct DirListWriter {
	buf: Vec<u8>
}

impl DirListWriter {

	pub fn new() -> Self {
		Self {
			buf: Vec::new()
		}
	}

	pub fn write(&mut self, entry: &FileEntry) -> Result<()> {
		match &entry.kind {
			FileKind::Unknown => self.buf.write_u8(FileKind::ID_UNKNOWN)?,
			FileKind::File => self.buf.write_u8(FileKind::ID_FILE)?,
			FileKind::Dir => self.buf.write_u8(FileKind::ID_DIR)?,
			FileKind::Symlink => self.buf.write_u8(FileKind::ID_SYMLINK)?,
			FileKind::Fifo => self.buf.write_u8(FileKind::ID_FIFO)?,
			FileKind::Socket => self.buf.write_u8(FileKind::ID_SOCKET)?,
			FileKind::BlockDev => self.buf.write_u8(FileKind::ID_BLOCK_DEV)?,
			FileKind::CharDev => self.buf.write_u8(FileKind::ID_CHAR_DEV)?,
		}
		self.buf.write_utf8(&entry.name)?;
		Ok(())
	}

	pub fn close(mut self) -> Result<Vec<u8>> {
		self.buf.write_u8(FILES_EOF)?;
		Ok(self.buf)
	}
}


pub struct DirListReader<T> {
	buf: T
}

impl<T> DirListReader<T>
	where
		T: AsRef<[u8]>
{
	pub fn from(buf: T) -> Self {
		Self {
			buf
		}
	}

	pub fn iter(&self) -> DirListIter {
		DirListIter {
			reader: Cursor::new(self.buf.as_ref())
		}
	}
}

pub struct DirListIter<'a> {
	reader: Cursor<&'a [u8]>
}

impl<'a> Iterator for DirListIter<'a> {

	type Item = Result<FileEntry>;

	fn next(&mut self) -> Option<Self::Item> {

		// read the kind (or the EOF marker)
		let kind_id = match self.reader.read_u8() {
			Ok(i) => i,
			Err(e) => return Some(Err(e).context("Failed to read kind_id"))
		};
		let kind =
			if kind_id == FILES_EOF {
				return None;
			} else if kind_id == FileKind::ID_FILE {
				FileKind::File
			} else if kind_id == FileKind::ID_DIR {
				FileKind::Dir
			} else if kind_id == FileKind::ID_SYMLINK {
				FileKind::Symlink
			} else if kind_id == FileKind::ID_FIFO {
				FileKind::Fifo
			} else if kind_id == FileKind::ID_SOCKET {
				FileKind::Socket
			} else if kind_id == FileKind::ID_BLOCK_DEV {
				FileKind::BlockDev
			} else if kind_id == FileKind::ID_CHAR_DEV {
				FileKind::CharDev
			} else {
				FileKind::Unknown
			};

		let name = match self.reader.read_utf8(){
			Ok(n) => n,
			Err(e) => return Some(Err(e).context("Failed to read filename"))
		};

		Some(Ok(FileEntry {
			name,
			kind
		}))
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

		assert_roundtrip(Request::Chmod(ChmodRequest {
			path: "foo".to_string(),
			ops: vec![]
		}));
		assert_roundtrip(Request::Chmod(ChmodRequest {
			path: "foo".to_string(),
			ops: vec![
				ChmodOp {
					value: true,
					bits: vec![ChmodBit::OtherExecute, ChmodBit::OtherWrite, ChmodBit::OtherRead]
				},
				ChmodOp {
					value: false,
					bits: vec![ChmodBit::GroupExecute, ChmodBit::GroupWrite, ChmodBit::GroupRead]
				},
				ChmodOp {
					value: true,
					bits: vec![ChmodBit::UserExecute, ChmodBit::UserWrite, ChmodBit::UserRead]
				},
				ChmodOp {
					value: false,
					bits: vec![ChmodBit::SetUid, ChmodBit::SetGid, ChmodBit::Sticky]
				}
			]
		}));

		assert_roundtrip(Request::DeleteFile {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::CreateFolder {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::DeleteFolder {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::ListFolder {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::Stat {
			path: "foo".to_string()
		});

		assert_roundtrip(Request::Rename {
			src: "foo".to_string(),
			dst: "bar".to_string()
		});

		assert_roundtrip(Request::Symlink {
			path: "foo".to_string(),
			link: "bar".to_string()
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

		assert_roundtrip(Response::Error {
			reason: "foo".to_string()
		});

		assert_roundtrip(Response::Pong);

		assert_roundtrip(Response::Uids {
			uid: 5,
			euid: 42,
			suid: 7
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

		assert_roundtrip(Response::Chmod);

		assert_roundtrip(Response::DeleteFile);

		assert_roundtrip(Response::CreateFolder);

		assert_roundtrip(Response::DeleteFolder);

		assert_roundtrip(Response::Stat(StatResponse::NotFound));
		assert_roundtrip(Response::Stat(StatResponse::File {
			size: 5
		}));
		assert_roundtrip(Response::Stat(StatResponse::Dir));
		assert_roundtrip(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::NotFound)));
		assert_roundtrip(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::File {
			size: 42
		})));
		assert_roundtrip(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::Dir)));
		assert_roundtrip(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::Other)));
		assert_roundtrip(Response::Stat(StatResponse::Other));

		assert_roundtrip(Response::Rename);

		assert_roundtrip(Response::Symlink);
	}


	#[test]
	fn dirlist() {

		fn assert_roundtrip(files: &Vec<FileEntry>) {

			let mut writer = DirListWriter::new();
			for file in files {
				writer.write(file)
					.unwrap();
			}
			let list = writer.close()
				.unwrap();

			let files_again = DirListReader::from(list)
				.iter()
				.collect::<Result<Vec<_>,_>>()
				.unwrap();

			assert_that!(&&files_again, eq(files));
		}

		assert_roundtrip(&vec![]);

		assert_roundtrip(&vec![
			FileEntry {
				name: "file".to_string(),
				kind: FileKind::File
			}
		]);

		assert_roundtrip(&vec![
			FileEntry {
				name: "unknown".to_string(),
				kind: FileKind::Unknown
			},
			FileEntry {
				name: "file".to_string(),
				kind: FileKind::File
			},
			FileEntry {
				name: "dir".to_string(),
				kind: FileKind::Dir
			},
			FileEntry {
				name: "symlink".to_string(),
				kind: FileKind::Symlink
			},
			FileEntry {
				name: "fifo".to_string(),
				kind: FileKind::Fifo
			},
			FileEntry {
				name: "socket".to_string(),
				kind: FileKind::Socket
			},
			FileEntry {
				name: "block_dev".to_string(),
				kind: FileKind::BlockDev
			},
			FileEntry {
				name: "char_dev".to_string(),
				kind: FileKind::CharDev
			}
		]);
	}
}

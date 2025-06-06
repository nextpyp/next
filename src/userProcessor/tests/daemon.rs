
mod util;


use std::net::Shutdown;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus};
use std::{fs, thread};
use std::os::unix::fs::{PermissionsExt, symlink};
use std::time::Duration;

use galvanic_assert::{assert_that, matchers::*};
use nix::sys::signal::{self, Signal};
use nix::unistd::Pid;
use tracing::debug;

use user_processor::framing::{ReadFramed, WriteFramed};
use user_processor::logging;
use user_processor::proto::{ChmodBit, ChmodOp, ChmodRequest, DirListReader, FileEntry, FileKind, ReadFileResponse, Request, RequestEnvelope, Response, ResponseEnvelope, StatResponse, StatSymlinkResponse, WriteFileRequest, WriteFileResponse};


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
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn ping_pong() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::Ping);

	assert_that!(&response, eq(Response::Pong));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn uids() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::Uids);
	assert_that!(&response, eq(Response::Uids {
		uid: users::get_current_uid(),
		euid: users::get_effective_uid(),
		suid: users::get_current_uid(),
	}));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn read_file() {
	let _logging = logging::init_test();

	// write a file we can read
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("read_file_test");
	fs::write(&path, "hello".to_string())
		.unwrap();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let request_id = 5;
	let response = request(&mut socket, request_id, Request::ReadFile {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Open {
		bytes: 5
	})));

	let response = recv(&mut socket, request_id);
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Chunk {
		sequence: 1,
		data: b"hello".to_vec()
	})));

	let response = recv(&mut socket, request_id);
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Close {
		sequence: 2
	})));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn read_file_large() {
	let _logging = logging::init_test();

	// write a file with arbitrary but recognizable and non-trivial content
	let mut content = vec![0u8; 8*1024*1024];
	for i in 0 .. content.capacity() {
		content.push(i as u8);
	}
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("read_file_large_test");
	fs::write(&path, &content)
		.unwrap();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let request_id = 5;
	let response = request(&mut socket, request_id, Request::ReadFile {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Open {
		bytes: content.len() as u64
	})));

	// read the incoming chunks
	let mut buf = Vec::<u8>::with_capacity(content.len());
	let mut exp_sequence = 0;
	while buf.len() < buf.capacity() {
		exp_sequence += 1;
		let response = recv(&mut socket, request_id);
		let Response::ReadFile(ReadFileResponse::Chunk { sequence, data }) = response
			else { panic!("unexpected response: {:?}", response) };
		assert_that!(&sequence, eq(exp_sequence));
		buf.extend(data);
	}

	exp_sequence += 1;
	let response = recv(&mut socket, request_id);
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Close {
		sequence: exp_sequence
	})));

	// check the total content
	assert_that!(&buf, eq(content));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn write_file() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("write_file_test");
	let request_id = 5;
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: false
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	let mut sequence = 1;
	let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
		sequence,
		data: b"hello".to_vec()
	});
	send(&mut socket, request_id, chunk_request);

	sequence += 1;
	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// check the written file
	let content = fs::read_to_string(&path)
		.unwrap();
	assert_that!(&content.as_str(), eq("hello"));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn write_file_append() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("write_file_append_test");

	// create a new file
	let request_id = 5;
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: false
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
		sequence: 1,
		data: b"hello".to_vec()
	});
	send(&mut socket, request_id, chunk_request);

	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence: 2
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// append to it
	let request_id = 42;
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: true
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
		sequence: 1,
		data: b" world".to_vec()
	});
	send(&mut socket, request_id, chunk_request);

	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence: 2
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// check the written file
	let content = fs::read_to_string(&path)
		.unwrap();
	assert_that!(&content.as_str(), eq("hello world"));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn write_file_large() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("write_file_large_test");
	let request_id = 5;
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: false
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	// create some arbitrary but recognizable and non-trivial content
	let mut content = vec![0u8; 8*1024*1024];
	for i in 0 .. content.capacity() {
		content.push(i as u8);
	}

	// send the chunks!
	let mut sequence = 0;
	let mut to_send = &content[..];
	const CHUNK_SIZE: usize = 32*1024;
	loop {
		let chunk_size = CHUNK_SIZE.min(to_send.len());
		if chunk_size <= 0 {
			break;
		}
		sequence += 1;
		let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
			sequence,
			data: to_send[0..chunk_size].to_vec()
		});
		send(&mut socket, request_id, chunk_request);
		to_send = &to_send[chunk_size..];
	}

	sequence += 1;
	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// check the written file
	let content_again = fs::read(&path)
		.unwrap();
	assert_that!(&content_again, eq(content));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn write_file_rewrite() {
	let _logging = logging::init_test();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	// create the file
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("write_file_test");
	let request_id = 5;
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: false
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	// write to it
	let mut sequence = 1;
	let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
		sequence,
		data: b"hello world".to_vec()
	});
	send(&mut socket, request_id, chunk_request);

	sequence += 1;
	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// open the file again
	let response = request(&mut socket, request_id, Request::WriteFile(WriteFileRequest::Open {
		path: path.to_string_lossy().to_string(),
		append: false
	}));
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Opened)));

	// write a smaller amount to the file this time
	let mut sequence = 1;
	let chunk_request = Request::WriteFile(WriteFileRequest::Chunk {
		sequence,
		data: b"hello".to_vec()
	});
	send(&mut socket, request_id, chunk_request);

	sequence += 1;
	let close_request = Request::WriteFile(WriteFileRequest::Close {
		sequence
	});
	let response = request(&mut socket, request_id, close_request);
	assert_that!(&response, eq(Response::WriteFile(WriteFileResponse::Closed)));

	// check the written file
	let content = fs::read_to_string(&path)
		.unwrap();
	assert_that!(&content.as_str(), eq("hello"));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn chmod() {
	let _logging = logging::init_test();

	// write a file we can modify
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("chmod_test");
	fs::write(&path, "hello".to_string())
		.unwrap();

	let mode = || {
		let mode = fs::metadata(&path)
			.unwrap()
			.permissions()
			.mode();
		// mask down to the low-order bits covered by chmod,
		// since the higher bits contain other data, like file type
		return mode & 0o7777;
	};
	assert_that!(&mode(), eq(0o0664));

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let mut chmod = |operations: Vec<ChmodOp>| {
		let response = request(&mut socket, 5, Request::Chmod(ChmodRequest {
			path: path.to_string_lossy().to_string(),
			ops: operations
		}));
		assert_that!(&response, eq(Response::Chmod));
	};

	chmod(vec![]);
	assert_that!(&mode(), eq(0o0664));

	// make the file world-everything (briefly)
	chmod(vec![
		ChmodOp {
			value: true,
			bits: vec![ChmodBit::OtherExecute, ChmodBit::OtherWrite]
		}
	]);
	assert_that!(&mode(), eq(0o0667));

	// lock out world access
	chmod(vec![
		ChmodOp {
			value: false,
			bits: vec![ChmodBit::OtherExecute, ChmodBit::OtherWrite, ChmodBit::OtherRead]
		}
	]);
	assert_that!(&mode(), eq(0o0660));

	// lock out group access
	chmod(vec![
		ChmodOp {
			value: false,
			bits: vec![ChmodBit::GroupRead, ChmodBit::GroupWrite]
		}
	]);
	assert_that!(&mode(), eq(0o0600));

	// put it all back the way it was
	chmod(vec![
		ChmodOp {
			value: true,
			bits: vec![ChmodBit::GroupRead, ChmodBit::GroupWrite]
		},
		ChmodOp {
			value: true,
			bits: vec![ChmodBit::OtherRead]
		}
	]);
	assert_that!(&mode(), eq(0o0664));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn delete_file() {
	let _logging = logging::init_test();

	// write a file we can delete
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("delete_file_test");
	fs::remove_file(&path)
		.ok();
	fs::write(&path, "hello".to_string())
		.unwrap();

	assert_that!(&path.exists(), eq(true));

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::DeleteFile {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::DeleteFile));

	assert_that!(&path.exists(), eq(false));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn create_folder() {
	let _logging = logging::init_test();

	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("create_folder_test");
	fs::remove_dir_all(&path)
		.ok();

	assert_that!(&path.exists(), eq(false));

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::CreateFolder {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::CreateFolder));

	assert_that!(&path.exists(), eq(true));

	// cleanup
	fs::remove_dir(&path)
		.unwrap();

	assert_that!(&path.exists(), eq(false));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn delete_folder() {
	let _logging = logging::init_test();

	// create a folder we can delete, put stuff in it too
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("delete_folder_test");
	fs::create_dir_all(&path)
		.unwrap();
	fs::write(path.join("file"), "hello")
		.unwrap();

	assert_that!(&path.exists(), eq(true));

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::DeleteFolder {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::DeleteFolder));

	assert_that!(&path.exists(), eq(false));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn list_folder() {
	let _logging = logging::init_test();

	// create a folder we can delete, put stuff in it too
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("list_folder_test");
	fs::create_dir_all(&path)
		.unwrap();
	fs::write(path.join("file"), "hello")
		.unwrap();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let request_id = 5;
	let response = request(&mut socket, request_id, Request::ListFolder {
		path: path.to_string_lossy().to_string()
	});
	const EXP_SIZE: usize = 10;
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Open {
		bytes: EXP_SIZE as u64
	})));

	// read the incoming chunks
	let mut buf = Vec::<u8>::with_capacity(EXP_SIZE);
	let mut exp_sequence = 0;
	while buf.len() < buf.capacity() {
		exp_sequence += 1;
		let response = recv(&mut socket, request_id);
		let Response::ReadFile(ReadFileResponse::Chunk { sequence, data }) = response
			else { panic!("unexpected response: {:?}", response) };
		assert_that!(&sequence, eq(exp_sequence));
		buf.extend(data);
	}

	exp_sequence += 1;
	let response = recv(&mut socket, request_id);
	assert_that!(&response, eq(Response::ReadFile(ReadFileResponse::Close {
		sequence: exp_sequence
	})));

	let entries = DirListReader::from(&buf)
		.iter()
		.collect::<Result<Vec<_>,_>>()
		.unwrap();

	assert_that!(&entries, eq(vec![
		FileEntry {
			name: "file".to_string(),
			kind: FileKind::File
		}
	]));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn copy_folder() {
	let _logging = logging::init_test();

	// create a folder we can delete, put stuff in it too
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let src_path = PathBuf::from(SOCKET_DIR).join("copy_folder_src_test");
	fs::create_dir_all(&src_path)
		.unwrap();
	fs::write(src_path.join("file"), "hello")
		.unwrap();

	let dst_path = PathBuf::from(SOCKET_DIR).join("copy_folder_dst_test");

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::CopyFolder {
		src: src_path.to_string_lossy().to_string(),
		dst: dst_path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::CopyFolder));

	// check dst folder contents
	assert_that!(&dst_path.exists(), eq(true));
	let dst_file = dst_path.join("file");
	assert_that!(&dst_file.exists(), eq(true));
	assert_that!(&fs::read_to_string(&dst_file).unwrap(), eq("hello".to_string()));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn copy_folder_link_internal() {
	let _logging = logging::init_test();

	// create a folder we can delete, put stuff in it too
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let src_path = PathBuf::from(SOCKET_DIR).join("copy_folder_src_li_test");
	fs::create_dir_all(&src_path)
		.unwrap();
	let file_path = src_path.join("file");
	fs::write(&file_path, "hello")
		.unwrap();
	let link_path = src_path.join("link");
	symlink(&file_path, &link_path)
		.unwrap();

	let dst_path = PathBuf::from(SOCKET_DIR).join("copy_folder_dst_li_test");
	fs::remove_dir_all(&dst_path)
		.ok();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::CopyFolder {
		src: src_path.to_string_lossy().to_string(),
		dst: dst_path.to_string_lossy().to_string()
	});
	assert_that!(&matches!(response, Response::Error { .. }), eq(true));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn copy_folder_link_external() {
	let _logging = logging::init_test();

	// create a folder we can delete, put stuff in it too
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let src_path = PathBuf::from(SOCKET_DIR).join("copy_folder_src_le_test");
	fs::create_dir_all(&src_path)
		.unwrap();
	let src_link1 = src_path.join("link");
	let src_link2 = src_path.join("parent");
	symlink(Path::new("/tmp"), &src_link1)
		.unwrap();
	symlink(Path::new("../"), &src_link2)
		.unwrap();

	let dst_path = PathBuf::from(SOCKET_DIR).join("copy_folder_dst_le_test");
	fs::remove_dir_all(&dst_path)
		.ok();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::CopyFolder {
		src: src_path.to_string_lossy().to_string(),
		dst: dst_path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::CopyFolder));

	// check dst folder contents
	assert_that!(&dst_path.exists(), eq(true));

	let dst_link1 = dst_path.join("link");
	let dst_meta1 = fs::symlink_metadata(&dst_link1)
		.unwrap();
	assert_that!(&dst_meta1.is_symlink(), eq(true));
	let link_target1 = fs::read_link(&dst_link1)
		.unwrap();
	assert_that!(&link_target1.as_path(), eq(Path::new("/tmp")));

	let dst_link2 = dst_path.join("parent");
	let dst_meta2 = fs::symlink_metadata(&dst_link2)
		.unwrap();
	assert_that!(&dst_meta2.is_symlink(), eq(true));
	let link_target2 = fs::read_link(&dst_link2)
		.unwrap();
	assert_that!(&link_target2.as_path(), eq(Path::new("../")));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn stat() {
	let _logging = logging::init_test();

	// create stuff we can stat
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("stat_test");
	fs::create_dir_all(&path)
		.unwrap();
	fs::write(path.join("file"), "hello")
		.unwrap();
	symlink(path.join("file"), path.join("link-file"))
		.unwrap();
	symlink(&path, path.join("link-dir"))
		.unwrap();
	symlink(path.join("nope"), path.join("link-broken"))
		.unwrap();

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	// check something that isn't there
	let response = request(&mut socket, 5, Request::Stat {
		path: path.join("nope").to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::NotFound)));

	// check a file
	let response = request(&mut socket, 5, Request::Stat {
		path: path.join("file").to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::File {
		size: 5
	})));

	// check a folder
	let response = request(&mut socket, 5, Request::Stat {
		path: path.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::Dir)));

	// check a symlink to nowhere
	let response = request(&mut socket, 5, Request::Stat {
		path: path.join("link-broken").to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::NotFound))));

	// check a symlink to a file
	let response = request(&mut socket, 5, Request::Stat {
		path: path.join("link-file").to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::File {
		size: 5
	}))));

	// check a symlink to a folder
	let response = request(&mut socket, 5, Request::Stat {
		path: path.join("link-dir").to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Stat(StatResponse::Symlink(StatSymlinkResponse::Dir))));


	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn rename() {
	let _logging = logging::init_test();

	// write a file we can rename
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("rename_test");
	fs::write(&path, "hello".to_string())
		.unwrap();
	let renamed_path = PathBuf::from(SOCKET_DIR).join("renamed");

	assert_that!(&path.exists(), eq(true));

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	let response = request(&mut socket, 5, Request::Rename {
		src: path.to_string_lossy().to_string(),
		dst: renamed_path.file_name().unwrap().to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Rename));

	assert_that!(&path.exists(), eq(false));
	assert_that!(&renamed_path.exists(), eq(true));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


#[test]
fn create_symlink() {
	let _logging = logging::init_test();

	// write a file we can rename
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
	fs::create_dir_all(SOCKET_DIR)
		.unwrap();
	let path = PathBuf::from(SOCKET_DIR).join("symlink_test");
	fs::write(&path, "hello".to_string())
		.unwrap();
	let link = PathBuf::from(SOCKET_DIR).join("linked");

	let user_processor = UserProcessor::start();
	let mut socket = user_processor.connect();

	assert_that!(&link.exists(), eq(false));

	let response = request(&mut socket, 5, Request::Symlink {
		path: path.to_string_lossy().to_string(),
		link: link.to_string_lossy().to_string()
	});
	assert_that!(&response, eq(Response::Symlink));

	assert_that!(&link.exists(), eq(true));

	user_processor.disconnect(socket);
	user_processor.stop();
	fs::remove_dir_all(SOCKET_DIR)
		.ok();
}


const SOCKET_DIR: &str = "/tmp/nextpyp-user-processor";


struct UserProcessor {
	proc: Child
}

impl UserProcessor {

	fn help() -> ExitStatus {
		Command::new(util::bin_path())
			.args(["--help", "daemon"])
			.spawn()
			.expect("Failed to spawn process")
			.wait()
			.expect("Failed to wait for process")
	}

	fn start() -> Self {

		debug!("Starting user processor ...");

		fs::create_dir_all(SOCKET_DIR)
			.unwrap();

		let proc = Command::new(util::bin_path())
			.args(["--log", "trace", "daemon"])
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
			.join(format!("user-processor-{}-{}", self.proc.id(), username.to_string_lossy()))
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


fn request(socket: &mut UnixStream, request_id: u32, request: Request) -> Response {
	send(socket, request_id, request);
	recv(socket, request_id)
}


fn send(socket: &mut UnixStream, request_id: u32, request: Request) {

	// encode the request
	let request = RequestEnvelope {
		id: request_id,
		request
	};
	let msg = request.encode()
		.unwrap();

	// send it
	socket.write_framed(msg)
		.unwrap();
}


fn recv(socket: &mut UnixStream, request_id: u32) -> Response {

	// wait for a response
	let response = socket.read_framed()
		.unwrap();

	// decode it
	let envelope = ResponseEnvelope::decode(response)
		.unwrap();

	assert_that!(&envelope.id, eq(request_id));

	envelope.response
}

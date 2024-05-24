
mod util;


use std::ffi::OsStr;
use std::process::{Command, ExitStatus};

use galvanic_assert::{assert_that, matchers::*};

use user_processor::logging;


#[test]
fn help() {
	let _logging = logging::init_test();
	let exit = run(["--help", "run"]);
	assert_that!(&exit.success(), eq(true));
}


#[test]
fn run_ls() {
	let _logging = logging::init_test();
	let exit = run(["run", "/tmp", "ls", "-al"]);
	assert_that!(&exit.success(), eq(true));
}


fn run(args: impl IntoIterator<Item = impl AsRef<OsStr>>) -> ExitStatus {
	Command::new(util::bin_path())
		.args(args)
		.spawn()
		.expect("Failed to spawn process")
		.wait()
		.expect("Failed to wait for process")
}


mod util;


use crate::util::cmd::{AssertExt, cmd};


#[test]
fn no_args() {

	// need a command
	cmd().assert()
		.print_stdout()
		.failure();
}


#[test]
fn help() {

	// should print the help message
	cmd()
		.arg("--help")
		.assert()
		.print_stderr()
		.success();
}


#[test]
fn version() {

	// should print the version
	cmd()
		.arg("--version")
		.assert()
		.print_stdout()
		.success()
		.stdout(predicates::str::starts_with("nextPYP installgen version "));
}

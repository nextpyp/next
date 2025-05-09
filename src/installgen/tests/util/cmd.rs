
use assert_cmd::assert::Assert;
use assert_cmd::Command;


const BIN_NAME: &'static str = "installgen";


pub fn cmd() -> Command {
	Command::cargo_bin(BIN_NAME)
		.unwrap()
}


pub trait AssertExt {
	fn print_stdout(self) -> Self;
	fn print_stderr(self) -> Self;
}

impl AssertExt for Assert {

	fn print_stdout(self) -> Self {
		println!("STDOUT:\n{}", String::from_utf8_lossy(&self.get_output().stdout));
		self
	}

	fn print_stderr(self) -> Self {
		println!("STDERR:\n{}", String::from_utf8_lossy(&self.get_output().stderr));
		self
	}
}

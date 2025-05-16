
mod util;


use std::path::Path;

use assert_cmd::assert::Assert;
use indoc::indoc;

use crate::util::cmd::{cmd, AssertExt};
use crate::util::install_dir::InstallDir;


fn cmd_config(install_dir: impl AsRef<Path>) -> Assert {
	cmd()
		.current_dir(install_dir)
		.arg("config")
		.assert()
		.print_stderr()
		.print_stdout()
}


#[test]
fn generate_new() {
	let install_dir = InstallDir::new();
	let cmd = cmd_config(&install_dir);
	install_dir.print();
	cmd.success();
	println!("configured:\n{}", install_dir.file_config().read());
}


#[test]
fn use_existing() {
	let install_dir = InstallDir::new();
	install_dir.file_config().write(indoc! { r#"
		[install]
		version = "1.2.3"
	"# });
	let cmd = cmd_config(&install_dir);
	install_dir.print();
	cmd.success();
	println!("configured:\n{}", install_dir.file_config().read());
}

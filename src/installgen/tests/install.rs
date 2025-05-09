
mod util;


use std::path::Path;

use assert_cmd::assert::Assert;
use indoc::indoc;

use crate::util::cmd::{cmd, AssertExt};
use crate::util::install_dir::InstallDir;


fn cmd_install(install_dir: impl AsRef<Path>) -> Assert {
	cmd()
		.current_dir(install_dir)
		.arg("install")
		.assert()
		.print_stderr()
		.print_stdout()
}


#[test]
fn no_config() {
	let install_dir = InstallDir::new();
	cmd_install(&install_dir)
		.failure();
}


#[test]
fn generate_anything() {
	let install_dir = InstallDir::new();
	install_dir.file_config().write(indoc!{ r#"
		[install]
		version = "0.8.0"
	"# });
	let cmd = cmd_install(&install_dir);
	install_dir.print();
	cmd.success();
	println!("installed:\n{}", install_dir.file_install().read());
}


use std::path::Path;

use anyhow::Context;

use crate::config::Config;
use crate::installed::Installed;
use crate::script::Script;


pub fn run() -> Result<(),anyhow::Error> {

	let cwd = Path::new(".")
		.canonicalize()
		.context("Failed to canonicalize cwd")?;
	println!("CWD: {}", cwd.to_string_lossy());

	let installed = Installed::read()
		.context("Failed to read installed info")?;
	let config = Config::read()
		.context("Failed to read config info")?;

	// start building the install script
	let mut script = Script::new();

	// TODO: actually script out the install process, including:
	//       service acct creation (based on config: install.username, install.groupname)
	//       download (based on config: install.source)
	//         from canonical URL, or custom URL
	//         or quick copy/link from local filesystem
	//       subfolder creation and permissions
	//       systemd integration (based on config: install.systemd)
	//       slurm integration (based on config: [slurm])
	//         template download
	//         ssh key generation

	// last install step: script creating the new installed file
	Installed {
		version: config.install.version
	}.script_write(&mut script)
		.context("Failed to generate new installed write script")?;

	// write the install script
	let out_path = "./install";
	script.write(out_path)
		.with_context(|| format!("Failed to write generated install script to: {}", out_path))?;

	Ok(())
}

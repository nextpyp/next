
use std::fs;
use std::path::Path;

use anyhow::Context;
use indoc::formatdoc;

use crate::config::Config;
use crate::gen;


pub fn run() -> Result<(),anyhow::Error> {

	println!("Examining configuration file ...");

	// install into the CWD
	let install_dir = Path::new(".")
		.canonicalize()
		.context("Failed to canonicalize cwd")?;
	println!("Install folder: {}", install_dir.to_string_lossy());

	let config = Config::read()
		.context("Failed to read config info")?;

	match &config {
		None => init_config(&install_dir),
		Some(config) => check_config(config)
	}
}


fn init_config(install_dir: &Path) -> Result<(),anyhow::Error> {

	// guess simple locations for the base folders
	let scratch_dir = install_dir.join("scratch");
	let local_dir = install_dir.join("local");
	let shared_dir = install_dir.join("shared");

	let config = formatdoc! { r#"

		[install]
		version = "{version}"

		[pyp]

		# fast storage for temporary files, ideally local to the compute node
		scratch = "{scratch_dir}"

		# add folder paths into this list to make your data visible to nextPYP
		binds = []


		[web]

		# Storage space for website files, database, etc.
		# For best performance, this should be on a filesystem local to the web server.
		localDir = "{local_dir}"

		# Storage space for files shared between the web server and the data processing jobs.
		# This area should have a lot of available space for large files
		# and should be writable by the account running nextpyp.
		sharedDir = "{shared_dir}"

		# authentication mode: how to log into the website
		# disable authentication entirely for single-user mode
		auth = "none"
	"#,
		version = gen::VERSION,
		scratch_dir = scratch_dir.to_string_lossy(),
		local_dir = local_dir.to_string_lossy(),
		shared_dir = shared_dir.to_string_lossy()
	};

	let config_path = install_dir.join("config.toml");
	fs::write(&config_path, &config)
		.with_context(|| format!("Failed to write config file to: {}", config_path.to_string_lossy()))?;
	println!("Wrote new configuration file to: {}", config_path.to_string_lossy());

	Ok(())
}


fn check_config(_config: &Config) -> Result<(),anyhow::Error> {

	// TODO: anything useful to do here?

	println!("Using exiting configuration file.");

	Ok(())
}

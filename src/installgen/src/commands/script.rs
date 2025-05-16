
use std::path::Path;

use anyhow::Context;

use crate::config::Config;
use crate::script::Script;


pub fn run() -> Result<(),anyhow::Error> {

	println!("Generating script ...");

	// install into the CWD
	let install_dir = Path::new(".")
		.canonicalize()
		.context("Failed to canonicalize cwd")?;
	println!("Install folder: {}", install_dir.to_string_lossy());

	let config = Config::read_expect()
		.context("Failed to read config info")?;

	let script = build(&install_dir, &config)?;

	// write the install script
	let out_path = install_dir.join("nextpyp");
	script.write(&out_path)
		.with_context(|| format!("Failed to write generated install script to: {}", out_path.to_string_lossy()))?;

	Ok(())
}


pub fn build(
	install_dir: &Path,
	config: &Config
) -> Result<Script,anyhow::Error> {

	// start building the install script
	let mut script = Script::new();

	// pick an install mode
	match &config.install.account {
		Some(config_account) => todo!(),
		None => build_user(install_dir, config, &mut script)?
	}

	Ok(script)
}


fn build_user(
	install_dir: &Path,
	config: &Config,
	script: &mut Script
) -> Result<(),anyhow::Error> {

	todo!()
}

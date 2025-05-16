
use std::path::Path;

use anyhow::Context;

use crate::config::{Config, ConfigAccount};
use crate::download::choose_download_fn;
use crate::installed::{Installed, InstalledMode};
use crate::script::Script;


pub fn run() -> Result<(),anyhow::Error> {

	println!("Generating installation script ...");

	// install into the CWD
	let install_dir = Path::new(".")
		.canonicalize()
		.context("Failed to canonicalize cwd")?;
	println!("Install folder: {}", install_dir.to_string_lossy());

	// gather all the info
	let installed = Installed::read()
		.context("Failed to read installed info")?;
	println!("Installed: {:#?}", &installed);
	let config = Config::read_expect()
		.context("Failed to read config info")?;
	println!("Config: {:#?}", &config);

	let script = build(&install_dir, installed.as_ref(), &config)?;

	// write the install script
	let out_path = install_dir.join("install");
	script.write(&out_path)
		.with_context(|| format!("Failed to write generated install script to: {}", out_path.to_string_lossy()))?;

	Ok(())
}


pub fn build(
	install_dir: &Path,
	installed: Option<&Installed>,
	config: &Config
) -> Result<Script,anyhow::Error> {

	// start building the install script
	let mut script = Script::new();

	// pick an install mode
	match &config.install.account {
		Some(config_account) => build_service(install_dir, installed, config, config_account, &mut script)?,
		None => build_user(install_dir, installed, config, &mut script)?
	}

	Ok(script)
}


fn build_user(
	install_dir: &Path,
	installed: Option<&Installed>,
	config: &Config,
	script: &mut Script
) -> Result<(),anyhow::Error> {

	// get required configuration
	let local_dir = config.web.local_dir.as_ref()
		.context("Missing configuration: web.localDir")?;
	let shared_dir = config.web.shared_dir.as_ref()
		.context("Missing configuration: web.sharedDir")?;
	let scratch_dir = config.pyp.scratch.as_ref()
		.context("Missing configuration: pyp.scratch")?;

	let old_version = match &installed {
		Some(installed) => installed.version.to_string(),
		None => "".to_string()
	};

	// TODO: storage folder?
	// TODO: free space checks?

	let download_fn = choose_download_fn(config)
		.context("Failed to choose a download fn")?;

	let new_installed = Installed {
		version: config.install.version.clone(),
		mode: InstalledMode::User,
		local_dir: local_dir.clone(),
		shared_dir: shared_dir.clone(),
		scratch_dir: scratch_dir.clone()
	};

	script.print_template(
		Script::INSTALL_USER,
		vec![
			("old_version", old_version),
			("new_version", config.install.version.to_string()),
			("local_dir", local_dir.to_string_lossy().to_string()),
			("shared_dir", shared_dir.to_string_lossy().to_string()),
			("scratch_dir", scratch_dir.to_string_lossy().to_string())
		],
		vec![
			("__DOWNLOAD_FN__", download_fn),
			("__INSTALLED_FILE__", new_installed.to_string())
		]
	);

	Ok(())
}


fn build_service(
	install_dir: &Path,
	installed: Option<&Installed>,
	config: &Config,
	config_account: &ConfigAccount,
	script: &mut Script
) -> Result<(),anyhow::Error> {

	// TODO: require config

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

	todo!()
	/* TODO
	Ok(Installed {
		version: config.install.version.clone(),
		mode: InstalledMode::Service {
			username: config_account.username.clone(),
			groupname: config_account.groupname.clone(),
		},
		local_dir,
		shared_dir,
		scratch_dir
	})
	*/
}

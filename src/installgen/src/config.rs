
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context};
use semver::Version;
use toml::Table;


/// Config is the contents of config.toml
/// Describes the next desired state of the system, but not necessarily the current state
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
	pub install: ConfigInstall,
	pub pyp: ConfigPyp,
	pub web: ConfigWeb
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigInstall {
	pub version: Version,
	pub account: Option<ConfigAccount>
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ConfigPyp {
	pub scratch: Option<PathBuf>
	// TODO: binds
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ConfigWeb {
	pub local_dir: Option<PathBuf>,
	pub shared_dir: Option<PathBuf>
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConfigAccount {
	pub username: String,
	pub groupname: String
}

impl Config {

	pub fn read() -> Result<Option<Self>,anyhow::Error> {

		// the config file should be in the CWD
		let path = Path::new("./config.toml");
		let exists = path.try_exists()
			.with_context(|| format!("Failed to check for existence of config file at: {}", path.to_string_lossy()))?;
		if !exists {
			return Ok(None);
		}

		let toml = fs::read_to_string(path)
			.with_context(|| format!("Failed to read config file at: {}", path.to_string_lossy()))?
			.parse::<Table>()
			.with_context(|| format!("Failed to parse config file at: {}", path.to_string_lossy()))?;

		// read the install section
		let install = {

			let toml_install = toml.get("install")
				.context("Missing [install] section from config file")?
				.as_table()
				.context("install key is not a table")?;

			// read the version, and parse it
			let version_str = toml_install.get("version")
				.context("Missing install.version")?
				.as_str()
				.context("install.version was not a string")?;
			let version = version_str.parse::<Version>()
				.with_context(|| format!("Failed to parse version: {}", version_str))?;

			// look for a service account
			let account = {
				if let Some(toml_account) = toml_install.get("account") {
					let toml_account = toml_account.as_table()
						.context("install.account was not a table")?;
					let username = toml_account.get("username")
						.context("Missing install.account.username")?
						.as_str()
						.context("install.account.username was not a string")?
						.to_string();
					let groupname = match toml.get("groupname") {
						Some(groupname) => groupname.as_str()
							.context("install.account.groupname was not a string")?
							.to_string(),
						None => detect_groupname(&username)?
					};
					Some(ConfigAccount { username, groupname })
				} else {
					None
				}
			};

			ConfigInstall {
				version,
				account
			}
		};

		// read the pyp section
		let pyp = match toml.get("pyp") {

			None => ConfigPyp {
				scratch: None
			},

			Some(toml_pyp) => {

				let toml_pyp = toml_pyp.as_table()
					.context("pyp key was not a table")?;

				let scratch = match toml_pyp.get("scratch") {
					Some(p) => {
						let p = p.as_str()
							.context("pyp.scratch was not a string")?;
						Some(PathBuf::from(p))
					}
					None => None
				};

				ConfigPyp {
					scratch
				}
			}
		};

		// read the web section
		let web = match toml.get("web") {

			None => ConfigWeb {
				local_dir: None,
				shared_dir: None
			},

			Some(toml_web) => {

				let toml_web = toml_web.as_table()
					.context("web key was not a table")?;

				let local_dir = match toml_web.get("localDir") {
					Some(p) => {
						let p = p.as_str()
							.context("web.localDir was not a string")?;
						Some(PathBuf::from(p))
					}
					None => None
				};

				let shared_dir = match toml_web.get("sharedDir") {
					Some(p) => {
						let p = p.as_str()
							.context("web.sharedDir was not a string")?;
						Some(PathBuf::from(p))
					}
					None => None
				};

				ConfigWeb {
					local_dir,
					shared_dir
				}
			}
		};

		Ok(Some(Config {
			install,
			pyp,
			web
		}))
	}

	pub fn read_expect() -> Result<Self,anyhow::Error> {
		Self::read()?
			.context("config.toml not found. Try creating one with `installgen config`.")
	}
}


fn detect_groupname(username: impl AsRef<str>) -> Result<String,anyhow::Error> {

	let username = username.as_ref();

	// look for a group with the same name as the username
	let group = nix::unistd::Group::from_name(username)
		.with_context(|| format!("Failed to lookup group by name: {}", username))?;
	match group {
		Some(group) => Ok(group.name.clone()),
		None => bail!("No group found whose name matches the serivce account username: `{}`. Set install.account.groupname explicitly.", username)
	}
}

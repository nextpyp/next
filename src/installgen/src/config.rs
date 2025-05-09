
use std::fs;
use std::path::Path;

use anyhow::Context;
use semver::Version;
use toml::Table;


/// Config is the contents of config.toml
/// Describes the next desired state of the system, but not necessarily the current state
pub struct Config {
	pub install: ConfigInstall
}

pub struct ConfigInstall {
	pub version: Version
}

impl Config {

	pub fn read() -> Result<Self,anyhow::Error> {

		// the config file should be in the CWD
		let path = Path::new("./config.toml");
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

			ConfigInstall {
				version
			}
		};

		Ok(Config {
			install
		})
	}
}

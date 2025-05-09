
use std::fs;
use std::path::Path;

use anyhow::Context;
use indoc::indoc;
use semver::Version;
use toml::Table;

use crate::script::Script;

// the installed file should be in the CWD
const PATH: &'static str = "./installed.toml";


/// Installed is the current state of the system, independent of the contents of config.toml
pub struct Installed {
	pub version: Version
	// TODO: service account? group?
}

impl Installed {

	pub fn read() -> Result<Option<Self>,anyhow::Error> {

		let path = Path::new(PATH);
		let exists = path.try_exists()
			.with_context(|| format!("Failed to check for existence of installed file at: {}", path.to_string_lossy()))?;
		if !exists {
			return Ok(None);
		}

		let toml = fs::read_to_string(path)
			.with_context(|| format!("Failed to read installed file at: {}", path.to_string_lossy()))?
			.parse::<Table>()
			.with_context(|| format!("Failed to parse installed file at: {}", path.to_string_lossy()))?;

		// read the version, and parse it
		let version_str = toml.get("version")
			.context("Missing version")?
			.as_str()
			.context("version was not a string")?;
		let version = version_str.parse::<Version>()
			.with_context(|| format!("Failed to parse version: {}", version_str))?;

		Ok(Some(Installed {
			version
		}))
	}

	pub fn script_write(&self, script: &mut Script) -> Result<(),anyhow::Error> {

		script.println(format!(indoc! { r#"

			# generate a new `installed` file
			tee "{path}" > /dev/null << __EOF__
			version = "{version}"
			__EOF__
		"# },
			path = PATH,
			version = self.version.to_string()
		));

		Ok(())
	}
}

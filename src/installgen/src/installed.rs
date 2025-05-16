
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context};
use indoc::indoc;
use semver::Version;
use toml::{Table, Value};

use crate::script::Script;


// the installed file should be in the CWD
const PATH: &'static str = "./installed.toml";


/// Installed is the current state of the system, independent of the contents of config.toml
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Installed {
	pub version: Version,
	pub mode: InstalledMode,
	pub local_dir: PathBuf,
	pub shared_dir: PathBuf,
	pub scratch_dir: PathBuf
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstalledMode {
	User,
	Service {
		username: String,
		groupname: String
	}
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

		// read the paths
		let local_dir = toml.get("local_dir")
			.context("Missing local_dir")?
			.as_str()
			.context("local_dir was not a string")?;
		let local_dir = PathBuf::from(local_dir);

		let shared_dir = toml.get("shared_dir")
			.context("Missing shared_dir")?
			.as_str()
			.context("shared_dir was not a string")?;
		let shared_dir = PathBuf::from(shared_dir);

		let scratch_dir = toml.get("scratch_dir")
			.context("Missing scratch_dir")?
			.as_str()
			.context("scratch_dir was not a string")?;
		let scratch_dir = PathBuf::from(scratch_dir);

		// read the mode
		let mode = toml.get("mode")
			.context("Missing mode")?;
		let mode = match mode {

			Value::String(mode) => {
				if mode == "user" {
					InstalledMode::User
				} else {
					bail!("Unrecognized mode: {}", mode);
				}
			}

			Value::Table(tbl) => {
				let username = tbl.get("username")
					.context("Missing mode.username")?
					.as_str()
					.context("mode.username was not a string")?
					.to_string();
				let groupname = tbl.get("groupname")
					.context("Missing mode.groupname")?
					.as_str()
					.context("mode.groupname was not a string")?
					.to_string();
				InstalledMode::Service { username, groupname }
			}

			other => bail!("Unrecognized mode: {:?}", other)
		};

		Ok(Some(Installed {
			version,
			mode,
			local_dir,
			shared_dir,
			scratch_dir
		}))
	}

	// TODO: guess? based on 0.7.0 filesystem clues? like the current install script would do

	pub fn to_string(&self) -> String {

		let mut tbl = Table::new();

		tbl.insert("version".to_string(), Value::String(self.version.to_string()));
		tbl.insert("mode".to_string(), match &self.mode {
			InstalledMode::User => Value::String("user".to_string()),
			InstalledMode::Service { username, groupname } => Value::Table({
				let mut t = Table::new();
				t.insert("username".to_string(), Value::String(username.to_string()));
				t.insert("groupname".to_string(), Value::String(groupname.to_string()));
				t
			})
		});
		tbl.insert("local_dir".to_string(), Value::String(self.local_dir.to_string_lossy().to_string()));
		tbl.insert("shared_dir".to_string(), Value::String(self.shared_dir.to_string_lossy().to_string()));
		tbl.insert("scratch_dir".to_string(), Value::String(self.scratch_dir.to_string_lossy().to_string()));

		tbl.to_string()
	}
}

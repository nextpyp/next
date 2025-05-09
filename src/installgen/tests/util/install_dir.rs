
use std::fs;
use std::path::{Path, PathBuf};

use assert_fs::TempDir;
use galvanic_assert::{assert_that, matchers::*};


pub struct InstallDir {
	dir: TempDir
}

impl InstallDir {

	pub fn new() -> InstallDir {
		Self {
			dir: TempDir::new()
				.expect("Failed to make temp folder")
		}
	}

	pub fn file(&self, path: impl AsRef<Path>) -> InstallFile {
		InstallFile {
			_dir: self,
			path: self.dir.path().join(path.as_ref())
		}
	}

	pub fn file_config(&self) -> InstallFile {
		self.file("config.toml")
	}

	pub fn file_install(&self) -> InstallFile {
		self.file("install")
	}

	pub fn file_installed(&self) -> InstallFile {
		self.file("installed")
	}

	pub fn print(&self) {
		let path = self.dir.path();
		println!("Install Folder: {}", path.to_string_lossy());
		let dir = fs::read_dir(path)
			.expect(&format!("Failed to read dir: {}", path.to_string_lossy()));
		for entry in dir {
			match entry {
				Ok(entry) => println!("\t{}", entry.file_name().to_string_lossy()),
				Err(e) => println!("\tError: {}", e)
			}
		}
	}
}

impl AsRef<Path> for InstallDir {
	fn as_ref(&self) -> &Path {
		self.dir.path()
	}
}


pub struct InstallFile<'d> {
	_dir: &'d InstallDir,
	path: PathBuf
}

impl<'d> InstallFile<'d> {

	pub fn path(&self) -> &Path {
		&self.path
	}

	pub fn write(&self, txt: impl AsRef<str>) {
		fs::write(self.path(), txt.as_ref())
			.expect(&format!("Failed to write file: {}", self.path().to_string_lossy()));
	}

	pub fn exists(&self) -> bool {
		self.path.exists()
	}

	pub fn read(&self) -> String {
		fs::read_to_string(self.path())
			.expect(&format!("Failed to read file: {}", self.path().to_string_lossy()))
	}

	pub fn assert_eq(&self, exp: impl AsRef<str>) {
		let obs = self.read();
		let exp = exp.as_ref();
		assert_that!(&obs.as_str(), eq(exp));
	}
}

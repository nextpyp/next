
use std::fs;
use std::path::Path;


macro_rules! embed_script {
	($var:ident, $path:literal) => {
		pub const $var: &'static str = include_str!($path);
	}
}


pub struct Script {
	lines: Vec<String>
}

impl Script {

	// NOTE: script paths here are relative to this source file's parent folder, ie the /src folder
	embed_script!(INSTALL_USER, "../scripts/install_user.sh");
	embed_script!(DOWNLOAD_WGET, "../scripts/download_wget.sh");
	embed_script!(NEXTPYP_USER, "../scripts/nextpyp_user.sh");


	pub fn new() -> Self {
		Self {
			lines: vec![]
		}
	}

	pub fn println(&mut self, line: impl Into<String>) {
		self.lines.push(line.into());
	}

	pub fn print_template(
		&mut self,
		template: &'static str,
		mut vars: Vec<(&str,String)>,
		mut blocks: Vec<(&str,String)>
	) {

		fn find_var<'a>(line: &str, vars: &mut Vec<(&'a str,String)>) -> Option<(&'a str,String)> {
			for i in 0 .. vars.len() {
				let (k, _v) = &vars[i];
				if line.trim_start().starts_with(&format!("{}=", k)) {
					return Some(vars.swap_remove(i));
				}
			}
			None
		}

		fn find_block<'a>(line: &str, blocks: &mut Vec<(&'a str,String)>) -> Option<(&'a str,String)> {
			for i in 0 .. blocks.len() {
				let (k, _v) = &blocks[i];
				if line == *k {
					return Some(blocks.swap_remove(i));
				}
			}
			None
		}

		for line in template.lines() {

			// find the variable assignment for this line, if any
			if line.starts_with("initialized=") {
				self.println("initialized=yup");
				continue;
			} else if let Some((k, v)) = find_var(line, &mut vars) {
				self.println(format!("{}{}={}", indent_of(line), k, v));
				continue;
			}

			// find the block definition for this line, if any
			if let Some((_k, v)) = find_block(line, &mut blocks) {
				self.println(v);
				continue;
			}

			self.println(line);
		}
	}

	pub fn to_string(&self) -> String {
		self.lines.join("\n")
	}

	pub fn write(&self, path: impl AsRef<Path>) -> Result<(),std::io::Error> {
		fs::write(path, self.to_string())
	}
}


fn indent_of(line: &str) -> &str {
	let mut end = 0_usize;
	for c in line.chars() {
		if c == ' ' || c == '\t' {
			end += c.len_utf8();
		} else {
			break;
		}
	}
	&line[0..end]
}

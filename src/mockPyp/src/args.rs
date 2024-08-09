
use std::collections::{HashMap, VecDeque};

use anyhow::{Context, Result};


pub struct Args {
	args: HashMap<String,String>
}

impl Args {

	pub fn from(raw: VecDeque<String>) -> Self {

		// parse the arguments as a key-value map
		let mut args = HashMap::<String,String>::new();
		let mut iter = raw.iter();
		loop {
			let Some(arg) = iter.next()
				.map(String::as_str)
				else { break; };

			const PREDECESSOR: &str = "-";
			if arg.starts_with(PREDECESSOR) {

				// trim off the - and look for an = in the middle
				let arg = &arg[PREDECESSOR.len()..];
				let mut parts = arg.splitn(2, "=");

				// the argument key is always the part
				let Some(key) = parts.next()
					else { continue; };

				// the value is either the part after the first =, or the next argument
				let Some(value) = parts.next()
					.or_else(|| iter.next().map(String::as_str))
					else { continue; };
				args.insert(key.to_string(), value.to_string());
			}
		}

		Self {
			args
		}
	}

	pub fn find(&self, name: impl AsRef<str>) -> Option<&str> {
		self.args.get(name.as_ref())
			.map(String::as_ref)
	}

	pub fn require(&self, name: impl AsRef<str>) -> Result<&str> {
		let name = name.as_ref();
		self.find(name)
			.context(format!("Missing required argument: {}", name))
	}
}

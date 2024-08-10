
use std::collections::{HashMap, VecDeque};

use anyhow::{bail, Context, Result};


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

	pub fn get(&self, name: impl AsRef<str>) -> Arg<Option<&str>> {
		let name = name.as_ref();
		Arg {
			name: name.to_string(),
			value: self.args.get(name)
				.map(String::as_ref)
		}
	}
}


pub struct Arg<T> {
	name: String,
	value: T
}

impl<T> Arg<Option<T>> {

	pub fn require(self) -> Result<Arg<T>> {
		let name = self.name;
		let value = self.value
			.context(format!("Missing required argument: {}", name))?;
		Ok(Arg {
			name,
			value
		})
	}
}

impl<'a> Arg<&'a str> {

	pub fn data_mode(&self) -> Result<DataMode> {
		match self.value {
			"spr" => Ok(DataMode::Spr),
			"tomo" => Ok(DataMode::Tomo),
			_ => bail!("Unrecognized data_mode: {}", self.value)
		}
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataMode {
	Spr,
	Tomo
}

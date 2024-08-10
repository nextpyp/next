
use std::collections::{HashMap, VecDeque};
use std::str::FromStr;
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

	pub fn get_from_group(&self, group: impl AsRef<str>, name: impl AsRef<str>) -> Arg<Option<&str>> {
		self.get(&format!("{}_{}", group.as_ref(), name.as_ref()))
	}
}


pub struct Arg<T> {
	name: String,
	value: T
}

impl<T> Arg<T> {

	pub fn map<F,R>(self, f: F) -> Arg<R>
	where
		F: FnOnce(T) -> R
	{
		Arg {
			name: self.name,
			value: f(self.value)
		}
	}

	pub fn try_map<F,R>(self, f: F) -> Result<Arg<R>>
		where
			F: FnOnce(T) -> Result<R>
	{
		let value = f(self.value)
			.context(format!("Failed to map argument: {}", self.name))?;
		Ok(Arg {
			name: self.name,
			value
		})
	}

	pub fn value(self) -> T {
		self.value
	}
}

impl<T> Arg<Option<T>> {

	pub fn require(self) -> Result<Arg<T>> {
		self.try_map(|value| {
			value
				.context("Argument is required")
		})
	}

	pub fn or(self, default: T) -> Arg<T> {
		self.map(|value| {
			value.unwrap_or(default)
		})
	}
}

impl<'a> Arg<Option<&'a str>> {

	pub fn into_u32(self) -> Result<Arg<Option<u32>>> {
		self.try_map(|value| {
			value.map(|value| {
				u32::from_str(value)
					.context(format!("value was not an i32: {}", value))
			})
			.transpose()
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

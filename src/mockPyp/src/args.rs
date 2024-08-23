
use std::collections::{HashMap, VecDeque};
use std::path::PathBuf;
use std::str::FromStr;
use anyhow::{bail, Context, Result};
use toml::{Table, Value};

pub struct Args {
	args: HashMap<String,ArgValue>
}

impl Args {

	pub fn from(raw: VecDeque<String>) -> Self {

		// parse the arguments as a key-value map
		let mut args = HashMap::<String,ArgValue>::new();
		let mut iter = raw.iter().peekable();
		loop {
			let Some(arg) = iter.next()
				.map(String::as_str)
				else { break; };

			const PREDECESSOR: &str = "-";
			if arg.starts_with(PREDECESSOR) {

				// trim off the - and look for an = in the middle
				let arg = &arg[PREDECESSOR.len()..];
				if arg.is_empty() {
					continue;
				}
				let mut parts = arg.splitn(2, "=");

				// the argument key is always the part
				let Some(key) = parts.next()
					else { continue; };

				// the value is either the part after the first =, or the next argument,
				// or nothing if the next argument is another flag or there is no next argument
				let (key, value) = match parts.next() {

					// arg has a part atfer the =, so it's a string value
					Some(value) => {
						(key.to_string(), ArgValue::String(value.to_string()))
					},

					// arg has no part after the =, look ahead to the next arg
					None => {
						let value = iter.peek()
							.take_if(|next| !next.starts_with(PREDECESSOR));
						match value {

							Some(value) => {
								// next "arg" is actually value for this arg
								(key.to_string(), ArgValue::String(value.to_string()))
							}

							None => {
								// no value in the next arg, so this is a bool flag
								const NO_FLAG: &str = "no-";
								if key.starts_with(NO_FLAG) {
									(key[NO_FLAG.len()..].to_string(), ArgValue::Bool(false))
								} else {
									(key.to_string(), ArgValue::Bool(true))
								}
							}
						}
					}
				};
				args.insert(key, value);
			}
		}

		Self {
			args
		}
	}

	pub fn get(&self, full_id: impl AsRef<str>) -> Arg<Option<&ArgValue>> {
		let name = full_id.as_ref();
		Arg {
			name: name.to_string(),
			value: self.args.get(name)
		}
	}

	pub fn get_from_group(&self, group_id: impl AsRef<str>, arg_id: impl AsRef<str>) -> Arg<Option<&ArgValue>> {
		self.get(full_id(group_id, arg_id))
	}

	pub fn get_mock(&self, block_id: impl AsRef<str>, arg_id: impl AsRef<str>) -> Arg<Option<&ArgValue>> {
		self.get(format!("{}_mock_{}", block_id.as_ref().replace('-', "_"), arg_id.as_ref()))
	}

	pub fn set_string(&mut self, group_id: impl AsRef<str>, arg_id: impl AsRef<str>, value: impl Into<String>) {
		self.args.insert(full_id(group_id, arg_id), ArgValue::String(value.into()));
	}

	pub fn set_bool(&mut self, group_id: impl AsRef<str>, arg_id: impl AsRef<str>, value: bool) {
		self.args.insert(full_id(group_id, arg_id), ArgValue::Bool(value));
	}

	pub fn set_default(&mut self, args_config: &ArgsConfig, group_id: impl AsRef<str>, arg_id: impl AsRef<str>) -> Result<()> {
		let group_id = group_id.as_ref();
		let arg_id = arg_id.as_ref();
		let default = args_config.default_value(&full_id(group_id, arg_id))?;
		self.set_from_group(group_id, arg_id, default);
		Ok(())
	}

	pub fn set(&mut self, full_id: impl AsRef<str>, value: ArgValue) {
		self.args.insert(full_id.as_ref().to_string(), value);
	}

	pub fn set_from_group(&mut self, group_id: impl AsRef<str>, arg_id: impl AsRef<str>, value: ArgValue) {
		self.set(full_id(group_id, arg_id), value);
	}
}


#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArgValue {
	String(String),
	Bool(bool)
}


type ArgsHashMapIter<'a> = std::collections::hash_map::Iter<'a,String,ArgValue>;
type ArgsMapFn = for<'a> fn((&'a String, &'a ArgValue)) -> Arg<&'a ArgValue>;

impl<'a> IntoIterator for &'a Args {

	type Item = Arg<&'a ArgValue>;
	type IntoIter = std::iter::Map<ArgsHashMapIter<'a>,ArgsMapFn>;

	// TODO: someday, this will get out of nightly rustc and make our lives much easier
	//type IntoIter = impl Iter<Item=Arg<&'a ArgValue>> + 'a;

	fn into_iter(self) -> Self::IntoIter {
		self.args.iter()
			.map(|(name, value)| {
				Arg {
					name: name.to_string(),
					value
				}
			})
	}
}



pub struct Arg<T> {
	name: String,
	value: T
}

impl<T> Arg<T> {

	pub fn name(&self) -> &str {
		self.name.as_str()
	}

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

	pub fn name_value(self) -> (String, T) {
		(self.name, self.value)
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

impl<'a> Arg<Option<&'a ArgValue>> {

	pub fn try_map_option<T,F>(self, f: F) -> Result<Arg<Option<T>>>
		where
			F: FnOnce(Arg<&'a ArgValue>) -> Result<Arg<T>>
	{
		match self.value {

			Some(value) => {
				let arg = Arg {
					name: self.name,
					value
				};
				let arg = f(arg)?;
				Ok(Arg {
					name: arg.name,
					value: Some(arg.value)
				})
			}

			None =>
				Ok(Arg {
					name: self.name,
					value: None
				})
		}
	}

	pub fn into_str(self) -> Result<Arg<Option<&'a str>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_str)
	}

	pub fn into_bool(self) -> Result<Arg<Option<bool>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_bool)
	}

	pub fn into_u32(self) -> Result<Arg<Option<u32>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_u32)
	}

	pub fn into_u64(self) -> Result<Arg<Option<u64>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_u64)
	}

	pub fn into_i64(self) -> Result<Arg<Option<i64>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_i64)
	}

	pub fn into_f64(self) -> Result<Arg<Option<f64>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_f64)
	}

	pub fn into_f64_2(self) -> Result<Arg<Option<(f64,f64)>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_f64_2)
	}

	pub fn into_data_mode(self) -> Result<Arg<Option<DataMode>>> {
		self.try_map_option(<Arg<&'a ArgValue>>::into_data_mode)
	}

	pub fn or_default(self, args_config: &ArgsConfig) -> Result<Arg<ArgValue>> {
		let full_id = self.name.clone();
		self.try_map(|value| {
			match value {
				Some(v) => Ok(v.clone()),
				None => args_config.default_value(&full_id)
			}
		})
	}
}


impl<'a> Arg<&'a ArgValue> {

	fn try_map_string<T,F>(self, f: F) -> Result<Arg<T>>
		where F: FnOnce(&'a str) -> Result<T>
	{
		self.try_map(|value| {
			match value {
				ArgValue::String(value) => f(value),
				ArgValue::Bool(_) => bail!("boolean flag value was not transformable")
			}
		})
	}

	pub fn into_str(self) -> Result<Arg<&'a str>> {
		self.try_map_string(|value| Ok(value))
	}

	pub fn into_bool(self) -> Result<Arg<bool>> {
		self.try_map(|value| {
			match value {
				ArgValue::String(_) => bail!("value was not a boolean flag"),
				ArgValue::Bool(value) => Ok(*value)
			}
		})
	}

	pub fn into_u32(self) -> Result<Arg<u32>> {
		self.try_map_string(|value| {
			u32::from_str(value)
				.context(format!("value was not a u32: {}", value))
		})
	}

	pub fn into_u64(self) -> Result<Arg<u64>> {
		self.try_map_string(|value| {
			u64::from_str(value)
				.context(format!("value was not a u64: {}", value))
		})
	}

	pub fn into_i64(self) -> Result<Arg<i64>> {
		self.try_map_string(|value| {
			i64::from_str(value)
				.context(format!("value was not an i64: {}", value))
		})
	}

	pub fn into_f64(self) -> Result<Arg<f64>> {
		self.try_map_string(|value| {
			f64::from_str(value)
				.context(format!("value was not an f64: {}", value))
		})
	}

	pub fn into_f64_2(self) -> Result<Arg<(f64,f64)>> {
		self.try_map_string(|value| {
			let mut parts = value.splitn(2, ',');
			let x = parts.next()
				.context(format!("f64 pair has no x: {}", value))?;
			let x = f64::from_str(x)
				.context(format!("x was not an f64: {}", value))?;
			let y = parts.next()
				.context(format!("f64 pair has no y: {}", value))?;
			let y = f64::from_str(y)
				.context(format!("y was not an f64: {}", value))?;
			Ok((x, y))
		})
	}

	pub fn into_data_mode(self) -> Result<Arg<DataMode>> {
		self.try_map_string(|value| {
			match value {
				"spr" => Ok(DataMode::Spr),
				"tomo" => Ok(DataMode::Tomo),
				_ => bail!("Unrecognized data_mode: {}", value)
			}
		})
	}

	pub fn into_path(self) -> Result<Arg<PathBuf>> {
		self.try_map_string(|value| {
			PathBuf::from_str(value)
				.context(format!("value was not a valid path: {}", value))
		})
	}
}


impl Arg<ArgValue> {

	fn as_ref(&self) -> Arg<&ArgValue> {
		Arg {
			name: self.name.clone(),
			value: &self.value
		}
	}

	fn try_map_string<T,F>(self, f: F) -> Result<Arg<T>>
		where F: FnOnce(String) -> Result<T>
	{
		self.try_map(|value| {
			match value {
				ArgValue::String(value) => f(value),
				ArgValue::Bool(_) => bail!("boolean flag value was not transformable")
			}
		})
	}

	pub fn into_string(self) -> Result<Arg<String>> {
		self.try_map_string(|value| Ok(value))
	}

	pub fn into_bool(self) -> Result<Arg<bool>> {
		self.as_ref().into_bool()
	}

	pub fn into_u32(self) -> Result<Arg<u32>> {
		self.as_ref().into_u32()
	}

	pub fn into_u64(self) -> Result<Arg<u64>> {
		self.as_ref().into_u64()
	}

	pub fn into_i64(self) -> Result<Arg<i64>> {
		self.as_ref().into_i64()
	}

	pub fn into_f64(self) -> Result<Arg<f64>> {
		self.as_ref().into_f64()
	}

	pub fn into_f64_2(self) -> Result<Arg<(f64,f64)>> {
		self.as_ref().into_f64_2()
	}

	pub fn into_data_mode(self) -> Result<Arg<DataMode>> {
		self.as_ref().into_data_mode()
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DataMode {
	Spr,
	Tomo
}


/// a very simple take on reading the list of arguments to pyp and their types
pub struct ArgsConfig {
	args: HashMap<String,ArgConfig>
}

impl ArgsConfig {

	pub fn from(toml: impl AsRef<str>) -> Result<ArgsConfig> {

		let mut args = HashMap::<String,ArgConfig>::new();

		let config = toml.as_ref().parse::<Table>()
			.context("Failed to parse TOML")?;

		let tabs = config.get("tabs")
			.context("Missing tabs section")?
			.as_table()
			.context("tabs is not table")?;
		for (tab_id, tab) in tabs {

			// skip metadata
			if tab_id.starts_with('_') {
				continue;
			}

			let tab = tab.as_table()
				.context(format!("tab {} is not a table", tab_id))?;

			for (arg_id, arg) in tab {

				// skip tab metadata
				if arg_id.starts_with('_') {
					continue;
				}

				let arg = arg.as_table()
					.context(format!("arg {}.{} is not a table", tab_id, arg_id))?;

				// read the type
				let arg_type = arg.get("type")
					.context(format!("missing type for arg: {}.{}", tab_id, arg_id))?
					.as_str()
					.context(format!("arg type not string for arg: {}.{}", tab_id, arg_id))?;
				let arg_type = match arg_type {
					"bool" => ArgType::Bool,
					"int" => ArgType::Int,
					"float" => ArgType::Float,
					"float2" => ArgType::Float2,
					"str" => ArgType::Str,
					"enum" => ArgType::Enum {
						values: arg.get("enum")
							.context(format!("enum missing values for arg: {}.{}", tab_id, arg_id))?
							.as_table()
							.context(format!("enum not table for arg: {}.{}", tab_id, arg_id))?
							.into_iter()
							.map(|(key, _)| key.to_string())
							.collect::<Vec<_>>()
					},
					"path" => ArgType::Path,
					_ => bail!("Unrecognized arg type {} for arg: {}.{}", arg_type, tab_id, arg_id)
				};

				// read the default value, if any
				let default = match arg.get("default") {
					None => None,
					Some(arg_default) => {
						let value = read_config_value(tab_id.as_str(), arg_id.as_str(), &arg_type, arg_default)
							.context(format!("invalid default for: {}.{}", tab_id, arg_id))?;
						Some(value)
					}
				};

				let arg_config = ArgConfig {
					group_id: tab_id.to_string(),
					arg_id: arg_id.to_string(),
					arg_type,
					default
				};

				args.insert(arg_config.full_id(), arg_config);
			}
		}

		Ok(ArgsConfig {
			args
		})
	}

	pub fn get(&self, full_id: impl AsRef<str>) -> Option<&ArgConfig> {
		self.args.get(full_id.as_ref())
	}

	pub fn get_from_group(&self, group_id: impl AsRef<str>, arg_id: impl AsRef<str>) -> Option<&ArgConfig> {
		self.get(full_id(group_id, arg_id))
	}

	pub fn default_value(&self, from_full_id: &str) -> Result<ArgValue> {

		let arg_config = self.get(from_full_id)
			.context(format!("arg config not found: {}", from_full_id))?
			.default.as_ref()
			.context(format!("arg config has no default value: {}", from_full_id))?;

		match arg_config {
			ArgConfigValue::Bool(v) => Ok(ArgValue::Bool(*v)),
			ArgConfigValue::Int(v) => Ok(ArgValue::String(v.to_string())),
			ArgConfigValue::Float(v) => Ok(ArgValue::String(v.to_string())),
			ArgConfigValue::Float2(x, y) => Ok(ArgValue::String(format!("{},{}", x, y))),
			ArgConfigValue::Str(v)
			| ArgConfigValue::Enum(v)
			| ArgConfigValue::Path(v) => Ok(ArgValue::String(v.clone())),
			ArgConfigValue::Ref { src_group_id, src_arg_id, .. } => {
				// recurse to ref source
				self.default_value(&full_id(src_group_id, src_arg_id))
					.context(format!("failed to follow ref {}.{} to find default", src_group_id, src_arg_id))
			}
		}
	}

}


fn full_id(group_id: impl AsRef<str>, arg_id: impl AsRef<str>) -> String {
	format!("{}_{}", group_id.as_ref(), arg_id.as_ref())
}


fn read_config_value(group_id: &str, arg_id: &str, arg_type: &ArgType, value: &Value) -> Result<ArgConfigValue> {

	// check for reference values
	if let Some(table) = value.as_table() {
		if let Some(ref_value) = table.get("ref") {
			let src_arg_id = ref_value.as_str()
				.context("default ref value was not a string")?;
			return Ok(ArgConfigValue::Ref {
				src_group_id: group_id.to_string(),
				src_arg_id: src_arg_id.to_string(),
				dst_group_id: group_id.to_string(),
				dst_arg_id: arg_id.to_string()
			});
		}
	}

	// get the literal value
	Ok(match &arg_type {
		ArgType::Bool => value.as_bool()
			.context("default value was not a bool")
			.map(ArgConfigValue::Bool)?,
		ArgType::Int => value.as_integer()
			.context("default value was not an int")
			.map(ArgConfigValue::Int)?,
		ArgType::Float => value.as_float()
			.or_else(|| value.as_integer().map(|i| i as f64))
			.context("default value was not a float")
			.map(ArgConfigValue::Float)?,
		ArgType::Float2 => {
			let arr = value.as_array()
				.context("default value was not an array")?;
			let x = arr.get(0)
				.context("default float2 missing x value")?;
			let x = x.as_float()
				.or_else(|| x.as_integer().map(|i| i as f64))
				.context("default float2 x was not a float")?;
			let y = arr.get(1)
				.context("default float2 missing y value")?;
			let y = y.as_float()
				.or_else(|| y.as_integer().map(|i| i as f64))
				.context("default float2 y was not a float")?;
			ArgConfigValue::Float2(x, y)
		}
		ArgType::Str => value.as_str()
			.context("default value was not a str")
			.map(|s| s.to_string())
			.map(ArgConfigValue::Str)?,
		ArgType::Enum { values } => {
			let value = value.as_str()
				.context("default value was not a str (for enum)")?
				.to_string();
			if !values.contains(&value) {
				bail!("default enum value {:?} was not in list {:?}", value, values);
			}
			ArgConfigValue::Enum(value)
		}
		ArgType::Path => value.as_str()
			.context("default value was not a str (for path)")
			.map(|s| s.to_string())
			.map(ArgConfigValue::Path)?
	})
}


pub struct ArgConfig {
	pub group_id: String,
	pub arg_id: String,
	pub arg_type: ArgType,
	pub default: Option<ArgConfigValue>
}

impl ArgConfig {

	pub fn group_id(&self) -> &str {
		self.group_id.as_str()
	}

	pub fn arg_id(&self) -> &str {
		self.arg_id.as_str()
	}

	pub fn arg_type(&self) -> &ArgType {
		&self.arg_type
	}

	pub fn full_id(&self) -> String {
		full_id(&self.group_id, &self.arg_id)
	}
}


pub enum ArgType {
	Bool,
	Int,
	Float,
	Float2,
	Str,
	Enum {
		values: Vec<String>
	},
	Path
}

pub enum ArgConfigValue {
	Bool(bool),
	Int(i64),
	Float(f64),
	Float2(f64, f64),
	Str(String),
	Enum(String),
	Path(String),
	Ref {
		src_group_id: String,
		src_arg_id: String,
		dst_group_id: String,
		dst_arg_id: String
	}
}

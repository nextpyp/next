
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::str::FromStr;
use anyhow::{bail, Context, Result};
use toml::{Table, Value};


pub struct Args {
	args: HashMap<String,ArgValue>
}

impl Args {

	pub fn read(path: impl AsRef<Path>, args_config: &ArgsConfig) -> Result<Self> {

		let path = path.as_ref();
		let contents = fs::read_to_string(path)
			.context(format!("Failed to read file to string: {}", path.to_string_lossy()))?;

		// parse the TOML
		let toml = contents.parse::<Table>()
			.context("Failed to parse TOML")?;

		let mut args = HashMap::<String,ArgValue>::new();

		for key in toml.keys() {

			let val = toml.get(key)
				.context(format!("Missing value for key: {}", key))?;

			// transform the value
			let val = match args_config.get(key) {

				// have a defined argument: enforce the configured type
				Some(arg_config) => match &arg_config.arg_type {

					ArgType::Bool => ArgValue::Bool(match val {
						Value::Boolean(b) => *b,
						_ => bail!("Unexpected type {} for arg {}, expected bool", val.type_str(), key)
					}),

					ArgType::Int => ArgValue::Int(match val {
						Value::Integer(i) => *i,
						_ => bail!("Unexpected type {} for arg {}, expected int", val.type_str(), key)
					}),

					ArgType::Float => ArgValue::Float(match val {
						Value::Float(f) => *f,
						Value::Integer(i) => *i as f64,
						_ => bail!("Unexpected type {} for arg {}, expected float", val.type_str(), key)
					}),

					ArgType::Float2 => ArgValue::Float2(match val {
						Value::Array(a) => {
							let x = match a.get(0) {
								None => bail!("Missing x coordinate for arg {}", key),
								Some(Value::Float(f)) => *f,
								Some(Value::Integer(i)) => *i as f64,
								Some(v) => bail!("Unexpected type {} for x coordinate for arg {}, expected float", v.type_str(), key)
							};
							let y = match a.get(1) {
								None => bail!("Missing y coordinate for arg {}", key),
								Some(Value::Float(f)) => *f,
								Some(Value::Integer(i)) => *i as f64,
								Some(v) => bail!("Unexpected type {} for y coordinate for arg {}, expected float", v.type_str(), key)
							};
							(x, y)
						},
						_ => bail!("Unexpected type {} for arg {}, expected array", val.type_str(), key)
					}),

					ArgType::Str => ArgValue::Str(match val {
						Value::String(s) => s.clone(),
						_ => bail!("Unexpected type {} for arg {}, expected string", val.type_str(), key)
					}),

					ArgType::Enum { values } => ArgValue::Str(match val {
						Value::String(e) => {
							if !values.contains(e) {
								bail!("Unexpected value {} for enum arg {}, expected one of [{:?}]", e, key, values);
							}
							e.clone()
						},
						_ => bail!("Unexpected type {} for arg {}, expected string", val.type_str(), key)
					}),

					ArgType::Path => ArgValue::Str(match val {
						Value::String(p) => p.clone(),
						_ => bail!("Unexpected type {} for arg {}, expected string", val.type_str(), key)
					})
				},

				// don't have a defined argument: just try to guess the type based on the value
				None => match val {
					Value::String(s) => ArgValue::Str(s.clone()),
					Value::Integer(i) => ArgValue::Int(*i),
					Value::Float(f) => ArgValue::Float(*f),
					Value::Boolean(b) => ArgValue::Bool(*b),
					Value::Datetime(_) => bail!("Unsupported arg type: datetime"),
					Value::Array(_) => bail!("Unsupported arg type: array"), // TODO: could support float2 here if needed?
					Value::Table(_) => bail!("Unsupported arg type: table")
				}
			};

			args.insert(key.clone(), val);
		}

		Ok(Self {
			args
		})
	}

	pub fn write(&self, path: impl AsRef<Path>) -> Result<()> {

		// build the TOML
		let mut toml = String::new();
		for (key, val) in &self.args {
			toml.push_str(key.as_str());
			toml.push_str(" = ");
			toml.push_str(&match val {
				ArgValue::Bool(b) => match b {
					true => "true".to_string(),
					false => "false".to_string()
				},
				ArgValue::Int(i) => format!("{}", i),
				ArgValue::Float(f) => format!("{}", f),
				ArgValue::Float2((x, y)) => format!("[{}, {}]", x, y),
				ArgValue::Str(s) => format!("\"{}\"", s),
				ArgValue::Enum(e) => format!("\"{}\"", e),
				ArgValue::Path(p) => format!("\"{}\"", p)
			});
			toml.push_str("\n");
		}

		let path = path.as_ref();
		fs::write(path, toml)
			.context(format!("Failed to write string to file: {}", path.to_string_lossy()))?;

		Ok(())
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

	pub fn set_all(&mut self, other: &Self) {
		for (key, value) in &other.args {
			self.args.insert(key.clone(), value.clone());
		}
	}
}


#[derive(Debug, Clone, PartialEq)]
pub enum ArgValue {
	Bool(bool),
	Int(i64),
	Float(f64),
	Float2((f64, f64)),
	Str(String),
	Enum(String),
	Path(String)
}

impl ArgValue {

	fn type_name(&self) -> &'static str {
		match self {
			ArgValue::Bool(_) => "Bool",
			ArgValue::Int(_) => "Int",
			ArgValue::Float(_) => "Float",
			ArgValue::Float2(_) => "Float2",
			ArgValue::Str(_) => "Str",
			ArgValue::Enum(_) => "Enum",
			ArgValue::Path(_) => "Path"
		}
	}
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

	pub fn into_str(self) -> Result<Arg<&'a str>> {
		self.try_map(|value| {
			match value {
				ArgValue::Str(s) => Ok(s.as_ref()),
				_ => bail!("value was a(n) {}, not a Str", value.type_name())
			}
		})
	}

	pub fn into_bool(self) -> Result<Arg<bool>> {
		self.try_map(|value| {
			match value {
				ArgValue::Bool(b) => Ok(*b),
				_ => bail!("value was a(n) {}, not a Bool", value.type_name())
			}
		})
	}

	pub fn into_u32(self) -> Result<Arg<u32>> {
		self.try_map(|value| {
			match value {
				ArgValue::Int(i) => Ok(*i as u32),
				_ => bail!("value was a(n) {}, not an Int", value.type_name())
			}
		})
	}

	pub fn into_u64(self) -> Result<Arg<u64>> {
		self.try_map(|value| {
			match value {
				ArgValue::Int(i) => Ok(*i as u64),
				_ => bail!("value was a(n) {}, not an Int", value.type_name())
			}
		})
	}

	pub fn into_i64(self) -> Result<Arg<i64>> {
		self.try_map(|value| {
			match value {
				ArgValue::Int(i) => Ok(*i),
				_ => bail!("value was a(n) {}, not an Int", value.type_name())
			}
		})
	}

	pub fn into_f64(self) -> Result<Arg<f64>> {
		self.try_map(|value| {
			match value {
				ArgValue::Float(i) => Ok(*i),
				_ => bail!("value was a(n) {}, not a Float", value.type_name())
			}
		})
	}

	pub fn into_f64_2(self) -> Result<Arg<(f64,f64)>> {
		self.try_map(|value| {
			match value {
				ArgValue::Float2((x, y)) => Ok((*x, *y)),
				_ => bail!("value was a(n) {}, not a Float2", value.type_name())
			}
		})
	}

	pub fn into_data_mode(self) -> Result<Arg<DataMode>> {
		self.try_map(|value| {
			match value {
				ArgValue::Str(s) => match s.as_str() {
					"spr" => Ok(DataMode::Spr),
					"tomo" => Ok(DataMode::Tomo),
					_ => bail!("Unrecognized data_mode: {}", s)
				},
				_ => bail!("value was a(n) {}, not a Str", value.type_name())
			}
		})
	}

	pub fn into_path(self) -> Result<Arg<PathBuf>> {
		self.try_map(|value| {
			match value {
				ArgValue::Str(s) => PathBuf::from_str(s)
					.context(format!("value was not a valid path: {}", s)),
				_ => bail!("value was a(n) {}, not a Str", value.type_name())
			}
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

	pub fn into_string(self) -> Result<Arg<String>> {
		let v = self.as_ref().into_str()?;
		Ok(v.map(|v| v.to_string()))
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
			ArgConfigValue::Int(v) => Ok(ArgValue::Int(*v)),
			ArgConfigValue::Float(v) => Ok(ArgValue::Float(*v)),
			ArgConfigValue::Float2(x, y) => Ok(ArgValue::Float2((*x, *y))),
			ArgConfigValue::Str(v) => Ok(ArgValue::Str(v.clone())),
			ArgConfigValue::Enum(v) => Ok(ArgValue::Enum(v.clone())),
			ArgConfigValue::Path(v) => Ok(ArgValue::Path(v.clone())),
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


use std::env;
use std::path::Path;
use std::time::SystemTime;
use anyhow::{bail, Context, Result};
use serde_json::{json, Map, Value};

use crate::args::{Args, ArgsConfig, ArgType};
use crate::metadata::{Micrograph, TiltSeries};


pub struct Web {
	host: String,
	token: String,
	id: String
}

impl Web {

	pub fn new() -> Result<Self> {

		// read the environment for job config
		let host = env::var("NEXTPYP_WEBHOST")
			.context("no NEXTPYP_WEBHOST")?;
		let token = env::var("NEXTPYP_TOKEN")
			.context("no NEXTPYP_TOKEN")?;
		let id = env::var("NEXTPYP_WEBID")
			.context("no NEXTPYP_WEBID")?;

		Ok(Self {
			host,
			token,
			id
		})
	}

	fn json_rpc(&self, method: impl AsRef<str>, params: Value) -> Result<Value> {

		let method = method.as_ref();

		if method != "log" {
			tracing::debug!("JSON RPC: {}", method);
		}

		let client = reqwest::blocking::Client::new();
		let response = client.post(format!("{}/pyp", &self.host))
			.json(&json!({
				"id": 5, // chosen by fair die roll, guaranteed to be random
				"token": &self.token,
				"method": method,
				"params": params,
			}))
			.send()
			.context("Failed to send request to website")?;

		if method != "log" {
			tracing::debug!("\tresponse: HTTP status={}", response.status());
		}

		let mut response = response
			.json::<Value>()
			.context("JSON RPC HTTP response was not valid JSON")?
			.into_object()
			.context("JSON RPC response was not an object")?;

		// look for errors in the response
		if let Some(err) = response.get("error") {
			let err = err.as_object()
				.context("JSON RPC error response was not an object")?;
			let msg = err.get("message")
				.context("JSON RPC error response has no message")?
				.as_str()
				.context("JSON RPC error response was not a string")?;
			bail!("JSON RPC call {} failed: {}", method, msg);
		}

		// get the response result
		let result = response.get_mut("result")
			.context("JSON RPC call response missing result")?
			.take();

		Ok(result)
	}

	pub fn slurm_started(&self, array_element: Option<u32>) -> Result<()> {

		// we have optional parameters here, so need to use map-building syntax instead of the nice json!() macro =(
		let mut request = Map::<String,Value>::new();
		request.insert("webid".to_string(), self.id.clone().into());

		if let Some(array_element) = array_element {
			request.insert("arrayid".to_string(), array_element.into());
		}

		self.json_rpc("slurm_started", request.into())?;

		Ok(())
	}

	pub fn slurm_ended(&self, array_element: Option<u32>, exit_code: Option<u32>) -> Result<()> {

		// we have optional parameters here, so need to use map-building syntax instead of the nice json!() macro =(
		let mut request = Map::<String,Value>::new();
		request.insert("webid".to_string(), self.id.clone().into());

		if let Some(array_element) = array_element {
			request.insert("arrayid".to_string(), array_element.into());
		}

		if let Some(exit_code) = exit_code {
			request.insert("exit_code".to_string(), exit_code.into());
		}

		self.json_rpc("slurm_ended", request.into())?;

		Ok(())
	}

	pub fn submit_cluster_job(&self,
		web_name: impl Into<String>,
		cluster_name: impl Into<String>,
		commands: &Commands,
		dir: Option<&Path>,
		env: Option<Vec<(String,String)>>,
		args: Option<Vec<String>>,
		deps: Option<Vec<String>>
	) -> Result<()> {

		// we have optional parameters here, so need to use map-building syntax instead of the nice json!() macro =(
		let mut request = Map::<String,Value>::new();
		request.insert("webid".to_string(), self.id.clone().into());
		request.insert("web_name".to_string(), web_name.into().into());
		request.insert("cluster_name".to_string(), cluster_name.into().into());
		request.insert("commands".to_string(), match commands {
			Commands::Script { commands, array_size, bundle_size } => {
				let mut c = Map::<String,Value>::new();
				c.insert("type".to_string(), "script".into());
				c.insert("commands".to_string(), commands.clone().into());
				if let Some(array_size) = array_size {
					c.insert("array_size".to_string(), array_size.clone().into());
				}
				if let Some(bundle_size) = bundle_size {
					c.insert("bundle_size".to_string(), bundle_size.clone().into());
				}
				c.into()
			}
			Commands::Grid { commands, bundle_size } => {
				let mut c = Map::<String,Value>::new();
				c.insert("type".to_string(), "grid".into());
				c.insert("commands".to_string(), commands.clone().into());
				if let Some(bundle_size) = bundle_size {
					c.insert("bundle_size".to_string(), bundle_size.clone().into());
				}
				c.into()
			}
		});
		request.insert("dir".to_string(), match dir {
			Some(dir) => dir.to_string_lossy().into(),
			None => env::current_dir()
				.context("Failed to get cwd")?
				.to_string_lossy()
				.into()
		});
		request.insert("env".to_string(), match env {
			Some(env) => env.into_iter()
				.map(|(key, value)| vec![key, value])
				.collect::<Vec<_>>()
				.into(),
			None => Vec::<Value>::with_capacity(0).into()
		});
		if let Some(args) = args {
			request.insert("args".to_string(), args.into());
		}
		if let Some(deps) = deps {
			request.insert("deps".to_string(), deps.into());
		}

		self.json_rpc("slurm_sbatch", request.into())?;

		Ok(())
	}

	pub fn write_parameters(&self, args: &Args, args_config: &ArgsConfig) -> Result<()> {

		let mut parameters = Map::<String,Value>::new();

		fn add_param(params: &mut Map<String,Value>, name_value: (String, impl Into<Value>)) {
			let (name, value) = name_value;
			params.insert(name, value.into());
		}

		for arg in args {

			// only send configured arguments
			let Some(arg_config) = args_config.get(arg.name())
				else { continue; };

			match arg_config.arg_type() {
				ArgType::Bool =>
					add_param(&mut parameters, arg.into_bool()?.name_value()),
				ArgType::Int =>
					add_param(&mut parameters, arg.into_i64()?.name_value()),
				ArgType::Float =>
					add_param(&mut parameters, arg.into_f64()?.name_value()),
				ArgType::Float2 => {
					let (name, (x, y)) = arg.into_f64_2()?.name_value();
					add_param(&mut parameters, (name, vec![x, y]))
				}
				ArgType::Str
				| ArgType::Enum { .. }
				| ArgType::Path =>
					add_param(&mut parameters, arg.into_str()?.name_value()),
			}
		}

		self.json_rpc("write_parameters", json!({
			"webid": &self.id,
			"parameters": parameters
		}))?;

		Ok(())
	}

	pub fn write_tilt_series(&self, tilt_series: &TiltSeries) -> Result<()> {

		let mut args = Map::<String,Value>::new();
		args.ins("webid", self.id.as_str());
		args.ins("tiltseries_id", tilt_series.tilt_series_id.as_str());

		if let Some(ctf) = &tilt_series.ctf {
			args.ins("ctf", vec![
				ctf.mean_defocus,
				ctf.cc,
				ctf.defocus1,
				ctf.defocus2,
				ctf.angast,
				ctf.ccc,
				ctf.x.0,
				ctf.y.0,
				ctf.z.0,
				ctf.pixel_size.0,
				ctf.voltage,
				ctf.binning_factor as f64,
				ctf.cccc,
				ctf.counts
			]);
		}

		if let Some(xf) = &tilt_series.xf {
			args.ins("xf", xf.samples.iter()
				.map(|s| vec![
					s.mat00,
					s.mat01,
					s.mat10,
					s.mat11,
					s.x,
					s.y
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(avgrot) = &tilt_series.avgrot {
			args.ins("avgrot", avgrot.samples.iter()
				.map(|s| vec![
					s.spatial_freq,
					s.avg_rot_no_astig,
					s.avg_rot,
					s.ctf_fit,
					s.cross_correlation,
					s.two_sigma
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(drift) = &tilt_series.drift {
			let m = args.get_or_ins_object("metadata")?;
			m.ins("tilts", drift.tilts.clone());
			m.ins("drift", drift.drifts
				.iter()
				.map(|positions| {
					positions.iter()
						.map(|pos| {
							vec![pos.x, pos.y]
						})
						.collect::<Vec<_>>()
				})
				.collect::<Vec<_>>()
			);
			m.ins("ctf_values", drift.ctf_values.iter()
				.map(|ctf| {
					vec![
						ctf.index as f64,
						ctf.defocus1,
						ctf.defocus2,
						ctf.astigmatism,
						ctf.cc,
						ctf.resolution
					]
				})
				.collect::<Vec<_>>()
			);
			m.ins("ctf_profiles", drift.ctf_profiles.iter()
				.map(|tilt| {
					// write this one struct-of-arrays style
					vec![
						tilt.samples.iter()
							.map(|s| s.spatial_freq)
							.collect::<Vec<_>>(),
						tilt.samples.iter()
							.map(|s| s.avg_rot_no_astig)
							.collect::<Vec<_>>(),
						tilt.samples.iter()
							.map(|s| s.avg_rot)
							.collect::<Vec<_>>(),
						tilt.samples.iter()
							.map(|s| s.ctf_fit)
							.collect::<Vec<_>>(),
						tilt.samples.iter()
							.map(|s| s.cross_correlation)
							.collect::<Vec<_>>(),
						tilt.samples.iter()
							.map(|s| s.two_sigma)
							.collect::<Vec<_>>()
					]
				})
				.collect::<Vec<_>>()
			);
			m.ins("tilt_axis_angle", drift.tilt_axis_angle);
		}

		if let Some(virions) = &tilt_series.virions {
			let m = args.get_or_ins_object("metadata")?;
			m.ins("virion_coordinates", virions.iter()
				.map(|p| vec![
					Value::from(p.particle.x.0),
					Value::from(p.particle.y.0),
					Value::from(p.particle.z.0),
					Value::from(p.particle.r.0),
					Value::from(p.threshold)
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(spikes) = &tilt_series.spikes {
			let m = args.get_or_ins_object("metadata")?;
			m.ins("spike_coordinates", spikes.iter()
				.map(|p| {
					let mut coords = vec![
						Value::from(p.x.0),
						Value::from(p.y.0),
						Value::from(p.z.0),
						Value::from(p.r.0)
					];
					if let Some(threshold) = p.threshold {
						coords.push(Value::from(threshold))
					}
					coords
				})
				.collect::<Vec<_>>()
			);
		}

		self.json_rpc("write_tiltseries", Value::Object(args))?;

		Ok(())
	}

	pub fn write_micrograph(&self, micrograph: &Micrograph) -> Result<()> {

		let mut args = Map::<String,Value>::new();
		args.ins("webid", self.id.as_str());
		args.ins("micrograph_id", micrograph.micrograph_id.as_str());

		if let Some(ctf) = &micrograph.ctf {
			args.ins("ctf", vec![
				ctf.mean_defocus,
				ctf.cc,
				ctf.defocus1,
				ctf.defocus2,
				ctf.angast,
				ctf.ccc,
				ctf.x.0,
				ctf.y.0,
				ctf.z.0,
				ctf.pixel_size.0,
				ctf.voltage,
				ctf.binning_factor as f64,
				ctf.cccc,
				ctf.counts
			]);
		}

		if let Some(xf) = &micrograph.xf {
			args.ins("xf", xf.samples.iter()
				.map(|s| vec![
					s.mat00,
					s.mat01,
					s.mat10,
					s.mat11,
					s.x,
					s.y
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(avgrot) = &micrograph.avgrot {
			args.ins("avgrot", avgrot.samples.iter()
				.map(|s| vec![
					s.spatial_freq,
					s.avg_rot_no_astig,
					s.avg_rot,
					s.ctf_fit,
					s.cross_correlation,
					s.two_sigma
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(particles) = &micrograph.particles {
			args.ins("boxx", particles.iter()
				.map(|p| vec![
					Value::from(p.x.0),
					Value::from(p.y.0),
					Value::from(p.r.0)
				])
				.collect::<Vec<_>>()
			);
		}

		self.json_rpc("write_micrograph", Value::Object(args))?;

		Ok(())
	}

	pub fn write_tomo_drgn_convergence(&self, iteration: u32) -> Result<()> {

		let mut args = Map::<String,Value>::new();
		args.ins("webid", self.id.as_str());

		args.ins("iteration", iteration);

		self.json_rpc("write_tomo_drgn_convergence", Value::Object(args))?;

		Ok(())
	}

	pub fn log(&self, timestamp: SystemTime, level: i32, path: &str, line: u32, msg: String) -> Result<()> {

		// convert the system time to a millisecond-precision timestamp
		let timestamp_ms = match timestamp.duration_since(SystemTime::UNIX_EPOCH) {
			Ok(d) => d.as_millis() as u64, // NOTE: this can't overflow a u64 in our lifetime
			Err(_) => 0 // system clock is set before the unix epoch: probably wrong. unless time travel
		};

		let mut args = Map::<String,Value>::new();
		args.ins("webid", self.id.as_str());

		args.ins("timestamp", timestamp_ms);
		args.ins("level", level);
		args.ins("path", path);
		args.ins("line", line);
		args.ins("msg", msg);

		self.json_rpc("log", Value::Object(args))?;

		Ok(())
	}
}


trait ValueEx {
	fn into_object(self) -> Option<Map<String,Value>>;
}

impl ValueEx for Value {

	fn into_object(self) -> Option<Map<String,Value>> {
		match self {
			Value::Object(o) => Some(o),
			_ => None
		}
	}
}


trait MapEx {
	fn ins(&mut self, key: impl AsRef<str>, value: impl Into<Value>) -> &mut Value;
	fn get_or_ins_object(&mut self, key: impl AsRef<str>) -> Result<&mut Map<String,Value>>;
}

impl MapEx for Map<String,Value> {

	fn ins(&mut self, key: impl AsRef<str>, value: impl Into<Value>) -> &mut Value {
		let key = key.as_ref();
		self.insert(key.to_string(), value.into());
		self.get_mut(key)
			.expect("missing value we just added")
	}

	fn get_or_ins_object(&mut self, key: impl AsRef<str>) -> Result<&mut Map<String,Value>> {
		let key = key.as_ref();

		/*
			Alas, the dreaded rustc limitation named "NLL Problem Case #3" prevents the simple solution here.
			This code is quite safe, but rustc isn't smart enough (yet!) to do some kinds of flow-sensitive lifetime analysis.
			=(
		match self.get_mut(key) {
			Some(value) => value,
			None => self.ins("metadata", Value::Object(Map::new()))
		}
			.as_object_mut()
			.context(format!("key {} was not an object", key))
		*/

		// so we have to do something more complicated instead
		if !self.contains_key(key) {
			self.ins(key, Value::Object(Map::new()));
		}

		self.get_mut(key)
			.expect("we just added it")
			.as_object_mut()
			.context(format!("key {} was not an object", key))
	}
}


pub enum Commands {

	Script {
		commands: Vec<String>,
		array_size: Option<u32>,
		bundle_size: Option<u32>
	},

	#[allow(unused)]
	Grid {
		commands: Vec<Vec<String>>,
		bundle_size: Option<u32>
	}
}

impl Commands {

	pub fn mock_pyp(cmd: impl AsRef<str>, args_path: &Path) -> String {
		format!("RUST_BACKTRACE=1 /usr/bin/mock-pyp {} -params_file=\"{}\"", cmd.as_ref(), args_path.to_string_lossy())
	}
}


#[macro_export]
macro_rules! log {

	($web:expr, $level:literal, $msg:expr) => {
		{
			let result = $web.log(std::time::SystemTime::now(), $level, file!(), line!(), $msg);
			if let Err(e) = result {
				use std::ops::Deref;
				use display_error_chain::ErrorChainExt;
				tracing::error!("Failed to send log message to website: {}", e.deref().chain());
			}
		}
	};

	($level:literal, $msg:expr) => {
		match $crate::web::Web::new() {
			Ok(web) => $crate::web::log!(web, $level, $msg),
			Err(e) => {
				use std::ops::Deref;
				use display_error_chain::ErrorChainExt;
				tracing::error!("Failed to create Web instance: {}", e.deref().chain());
			}
		}
	};
}


#[macro_export]
macro_rules! error {

		($web:expr, $fmt:literal $($arg:tt)*) => {
		{
			tracing::error!($fmt $($arg)*);
			$crate::log!($web, 40, format!($fmt $($arg)*));
		}
	};

	($fmt:literal $($arg:tt)*) => {
		{
			tracing::error!($fmt $($arg)*);
			$crate::log!(40, format!($fmt $($arg)*));
		}
	};
}


#[macro_export]
macro_rules! warn {

	($web:expr, $fmt:literal $($arg:tt)*) => {
		{
			tracing::warn!($fmt $($arg)*);
			$crate::log!($web, 30, format!($fmt $($arg)*));
		}
	};

	($fmt:literal $($arg:tt)*) => {
		{
			tracing::warn!($fmt $($arg)*);
			$crate::log!(30, format!($fmt $($arg)*));
		}
	};
}


#[macro_export]
macro_rules! info {

	($web:expr, $fmt:literal $($arg:tt)*) => {
		{
			tracing::info!($fmt $($arg)*);
			$crate::log!($web, 20, format!($fmt $($arg)*));
		}
	};

	($fmt:literal $($arg:tt)*) => {
		{
			tracing::info!($fmt $($arg)*);
			$crate::log!(20, format!($fmt $($arg)*));
		}
	};
}


#[macro_export]
macro_rules! debug {

	($web:expr, $fmt:literal $($arg:tt)*) => {
		{
			tracing::debug!($fmt $($arg)*);
			$crate::log!($web, 10, format!($fmt $($arg)*));
		}
	};

	($fmt:literal $($arg:tt)*) => {
		{
			tracing::debug!($fmt $($arg)*);
			$crate::log!(10, format!($fmt $($arg)*));
		}
	};
}


#[macro_export]
macro_rules! progress {

	($web:expr, $fmt:literal $($arg:tt)*) => {
		{
			tracing::info!($fmt $($arg)*);
			$crate::log!($web, -10, format!($fmt $($arg)*));
		}
	};

	($fmt:literal $($arg:tt)*) => {
		{
			tracing::info!($fmt $($arg)*);
			$crate::log!(-10, format!($fmt $($arg)*));
		}
	};
}

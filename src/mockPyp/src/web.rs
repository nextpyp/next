
use std::env;

use anyhow::{bail, Context, Result};
use serde_json::{json, Map, Value};
use tracing::info;

use crate::args::{Args, ArgsConfig, ArgType};
use crate::metadata::TiltSeries;


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

		info!("JSON RPC: {}", method);

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

		info!("\tresponse: HTTP status={}", response.status());

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
				ctf.x,
				ctf.y,
				ctf.z,
				ctf.pixel_size,
				ctf.voltage,
				ctf.binning_factor,
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
					p.particle.x,
					p.particle.y,
					p.particle.z,
					p.particle.r,
					p.threshold
				])
				.collect::<Vec<_>>()
			);
		}

		if let Some(spikes) = &tilt_series.spikes {
			let m = args.get_or_ins_object("metadata")?;
			m.ins("spike_coordinates", spikes.iter()
				.map(|p| vec![
					p.x,
					p.y,
					p.z,
					p.r
				])
				.collect::<Vec<_>>()
			);
		}

		self.json_rpc("write_tiltseries", Value::Object(args))?;

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

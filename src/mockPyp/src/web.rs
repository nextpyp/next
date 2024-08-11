
use std::env;

use anyhow::{bail, Context, Result};
use serde_json::{json, Map, Value};
use tracing::info;
use crate::args::{Args, ArgsConfig, ArgType};

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
}


trait JsonEx {
	fn into_object(self) -> Option<Map<String,Value>>;
}

impl JsonEx for Value {

	fn into_object(self) -> Option<Map<String,Value>> {
		match self {
			Value::Object(o) => Some(o),
			_ => None
		}
	}
}

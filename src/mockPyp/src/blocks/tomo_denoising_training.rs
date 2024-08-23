
use std::fs;

use anyhow::{bail, Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};


pub const BLOCK_ID: &'static str = "tomo-denoising-train";


pub fn run(args: Args, args_config: ArgsConfig) -> Result<()> {

	// TODO: update this to use the new training tab arg
	let method = args.get("tomo_denoise_method")
		.into_str()?
		.value();
	match method {
		Some("none") | None => info!("No training method"),
		Some("isonet-train") => run_isonet(args, args_config)?,
		Some(method) => bail!("unrecognized training method: {}", method)
	}

	Ok(())
}


fn run_isonet(_args: Args, _args_config: ArgsConfig) -> Result<()> {

	// generate the model file
	fs::write("model.h5", "H5 model file")
		.context("Failed to write model file")?;
	info!("write model.h5");

	Ok(())
}

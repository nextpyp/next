
use std::fs;

use anyhow::{bail, Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::svg::{Rgb, SvgImage};

pub const BLOCK_ID: &'static str = "tomo-denoising-train";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

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


fn run_isonet(_args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// generate the model file
	fs::write("model.h5", "H5 model file")
		.context("Failed to write model file")?;
	info!("write model.h5");

	// create subfolders
	fs::create_dir_all("train")
		.context("Failed to create train dir")?;

	// draw the training results image
	let mut img = SvgImage::new(512, 512);
	img.draw().fill_rect(0, 0, 512, 512, Rgb(128, 128, 128));
	img.draw().text_lines(32, Rgb(255, 255, 255), [
		format!("Block: {}", BLOCK_ID),
		"Type: Training Results".to_string()
	]);
	img.save("train/training_loss.svgz")?;

	Ok(())
}

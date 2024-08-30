
use std::fs;

use anyhow::{Context, Result};

use crate::args::{Args, ArgsConfig};
use crate::svg::{Rgb, SvgImage};


pub const BLOCK_ID: &'static str = "tomo-milo-train";


pub fn run(_args: Args, _args_config: ArgsConfig) -> Result<()> {

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
	img.save("train/milo_training.svgz")?;

	Ok(())
}

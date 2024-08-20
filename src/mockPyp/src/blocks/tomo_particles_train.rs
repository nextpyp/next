
use std::fs;

use anyhow::{Context, Result};
use image::Rgb;

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};


pub const BLOCK_ID: &'static str = "tomo-particles-train";


pub fn run(_args: Args, _args_config: ArgsConfig) -> Result<()> {

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate the output image
	let mut img = Image::new(512, 512);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise();
	img.draw().text_lines(32, Rgb([255, 255, 255]), [
		format!("Block: {}", BLOCK_ID),
		"Type: Output".to_string()
	]);
	img.save("webp/out.webp")?;

	Ok(())
}

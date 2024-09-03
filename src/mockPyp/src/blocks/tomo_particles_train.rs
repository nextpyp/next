
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::particles::read_tomo_particles;
use crate::svg::{Rgb, SvgImage};


pub const BLOCK_ID: &'static str = "tomo-particles-train";


pub fn run(_args: Args, _args_config: ArgsConfig) -> Result<()> {

	// try to read the submitted particles
	let tilt_series_particles = read_tomo_particles(None)?;

	let num_particles = tilt_series_particles.iter()
		.map(|(_, tilt_series)| tilt_series.len())
		.sum::<usize>();
	info!("Read {} particles from {} tilt series", num_particles, tilt_series_particles.len());

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

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

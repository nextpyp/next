
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::particles::read_next_tomo_particles;
use crate::svg::{Rgb, SvgImage};


pub const BLOCK_ID: &'static str = "tomo-particles-train";


pub fn run(_args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// try to read the manual particles, if any
	match read_next_tomo_particles()? {
		Some(tilt_series_particles) => {
			let num_particles = tilt_series_particles.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!("Read {} manual particles from {} tilt series", num_particles, tilt_series_particles.len());
		}
		_ => info!("No manual particles")
	}

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;
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

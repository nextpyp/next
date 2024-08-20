
use std::fs;

use anyhow::{Context, Result};
use image::{Rgb, Rgba};

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};


pub const BLOCK_ID: &'static str = "tomo-milo";


pub fn run(_args: Args, _args_config: ArgsConfig) -> Result<()> {

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	const RESULTS_IMG_SIZE: u32 = 512;

	// generate the results 2D image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([255, 255, 255]));
	for _ in 0 .. 20 {
		// pick a uniformly random sub-image to draw onto
		const SUB_SIZE: u32 = 16;
		let x = fastrand::u32(0 .. RESULTS_IMG_SIZE - SUB_SIZE);
		let y = fastrand::u32(0 .. RESULTS_IMG_SIZE - SUB_SIZE);
		let mut sub_img = img.sub_image(
			x,
			y,
			SUB_SIZE,
			SUB_SIZE
		);
		sub_img.draw().fill(Rgb([128, 128, 128]));
		sub_img.draw().noise();
	}
	img.draw().text_lines(32, Rgb([0, 0, 0]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 2D Results".to_string()
	]);
	img.save("webp/results_2d.webp")?;

	// generate the results 3D image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise();
	for _ in 0 .. 20 {
		// pick a uniformly random spot to draw a circle
		const RADIUS: u32 = 8;
		let x = fastrand::u32(RADIUS .. RESULTS_IMG_SIZE - RADIUS);
		let y = fastrand::u32(RADIUS .. RESULTS_IMG_SIZE - RADIUS);
		img.draw().fill_circle_blended(x, y, RADIUS, Rgba([255, 0, 0, 64]));
	}
	img.draw().text_lines(32, Rgb([255, 255, 255]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 3D Results".to_string()
	]);
	img.save("webp/results_3d.webp")?;

	// generate a generic downloadable file
	fs::write("download.dat", "download me!")
		.context("Failed to write the downloadable file")?;

	Ok(())
}


use std::fs;

use anyhow::{Context, Result};
use image::{Rgb, Rgba};

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};
use crate::info;
use crate::metadata::TiltSeries;
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-milo";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// create subfolders
	fs::create_dir_all("train")
		.context("Failed to create train dir")?;
	fs::create_dir_all("log")
		.context("Failed to create log dir")?;

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
		sub_img.draw().noise(&DEFAULT_NOISE);
	}
	img.draw().text_lines(32, Rgb([0, 0, 0]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 2D Results".to_string()
	]);
	img.save(web, "train/2d_visualization_out.webp")?;

	// generate the results 2D labels image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([255, 255, 255]));
	sample_circles(&mut img, RESULTS_IMG_SIZE);
	img.draw().text_lines(32, Rgb([0, 0, 0]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 2D Results Labels".to_string()
	]);
	img.save(web, "train/2d_visualization_labels.webp")?;

	// generate the results 3D image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise(&DEFAULT_NOISE);
	sample_circles(&mut img, RESULTS_IMG_SIZE);
	img.draw().text_lines(32, Rgb([255, 255, 255]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 3D Visualization Out".to_string()
	]);
	img.save(web, "train/3d_visualization_out.webp")?;

	// generate the downloadable file
	fs::write("train/interactive_info_parquet.gzip", "just GZIP things")
		.context("Failed to write the gzip file")?;

	web.write_parameters(&args, &args_config)?;

	// generate tomograms for each tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: None,
			xf: None,
			avgrot: None,
			drift: None,
			virions: None,
			spikes: None
		};

		// generate the per-tilt-series 3D visualization image
		let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(&DEFAULT_NOISE);
		sample_circles(&mut img, RESULTS_IMG_SIZE);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: 3D Visualization".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series)
		]);
		img.save(web, format!("train/{}_3d_visualization.webp", &tilt_series.tilt_series_id))?;

		// write the log file
		let log_path = format!("log/{}.log", &tilt_series.tilt_series_id);
		fs::write(&log_path, format!("Things happened for tilt series {}", &tilt_series.tilt_series_id))
			.context(format!("Failed to write log file: {}", &log_path))?;
		info!(web, "Wrote log file: {}", &log_path);

		// tell the website
		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}


fn sample_circles(img: &mut Image, size: u32) {
	for _ in 0 .. 20 {
		// pick a uniformly random spot to draw a circle
		const RADIUS: u32 = 8;
		let x = fastrand::u32(RADIUS .. size - RADIUS);
		let y = fastrand::u32(RADIUS .. size - RADIUS);
		img.draw().fill_circle_blended(x, y, RADIUS, Rgba([255, 0, 0, 64]));
	}
}

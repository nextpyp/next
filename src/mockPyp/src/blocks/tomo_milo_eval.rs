
use std::fs;

use anyhow::{Context, Result};
use image::{Rgb, Rgba};

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};
use crate::metadata::{Ctf, TiltSeries};
use crate::particles::sample_particle_3d;
use crate::rand::sample_ctf;
use crate::scale::ValueA;
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-milo";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let num_particles = args.get_mock(BLOCK_ID, "num_particles")
		.into_u32()?
		.or(20)
		.value();

	// create subfolders
	fs::create_dir_all("train")
		.context("Failed to create train dir")?;

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
	img.save("train/2d_visualization_out.webp")?;

	// generate the results 2D labels image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([255, 255, 255]));
	img.draw().text_lines(32, Rgb([0, 0, 0]), [
		format!("Block: {}", BLOCK_ID),
		"Type: 2D Results Labels".to_string()
	]);
	img.save("train/2d_visualization_labels.webp")?;

	// generate the results 3D image
	let mut img = Image::new(RESULTS_IMG_SIZE, RESULTS_IMG_SIZE);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise(&DEFAULT_NOISE);
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
	img.save("train/3d_visualization_out.webp")?;

	// generate the downloadable file
	fs::write("train/milopyp_interactive.tbz", "just TBZ things")
		.context("Failed to write the tbz file")?;

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// generate particles for each tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate particles
		let radius = ValueA(500.0)
			.to_unbinned(pp_args.pixel_size);

		let particles = (0 .. num_particles)
			.map(|_| sample_particle_3d(pp_args.tomogram_dims, radius))
			.collect::<Vec<_>>();

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions: None,
			spikes: Some(particles)
		};

		// tell the website
		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

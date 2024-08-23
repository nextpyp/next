
use std::fs;

use anyhow::{Context, Result};
use image::{Rgb, Rgba};

use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::TiltSeries;
use crate::rand::{sample_ctf, sample_particle_3d};
use crate::scale::{ToValueF, ToValueU, ValueA};
use crate::web::Web;

pub const BLOCK_ID: &'static str = "tomo-milo";


pub fn run(mut args: Args, args_config: ArgsConfig) -> Result<()> {

	// get mock args
	let num_tilt_series = args.get_mock(BLOCK_ID, "num_tilt_series")
		.into_u32()?
		.or(4)
		.value();
	let tomogram_width = args.get_mock(BLOCK_ID, "tomogram_width")
		.into_u32()?
		.or(8192)
		.value()
		.to_unbinned();
	let tomogram_height = args.get_mock(BLOCK_ID, "tomogram_height")
		.into_u32()?
		.or(8192)
		.value()
		.to_unbinned();
	let num_particles = args.get_mock(BLOCK_ID, "num_particles")
		.into_u32()?
		.or(20)
		.value();

	// get pyp args (and write defaults not defined by the config)
	let pixel_size = args.get("scope_pixel")
		.into_f64()?
		.or(2.15)
		.value()
		.to_a();
	args.set("scope_pixel", ArgValue::String(pixel_size.0.to_string()));
	let tomogram_depth = args.get_from_group("tomo_rec", "thickness")
		.or_default(&args_config)?
		.into_u32()?
		.value()
		.to_unbinned();
	let tomogram_binning = args.get_from_group("tomo_rec", "binning")
		.or_default(&args_config)?
		.into_u32()?
		.value();

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

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// generate particles for each tilt series
	for tilt_series_i in 0 .. num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate particles
		let radius = ValueA(500.0)
			.to_unbinned(pixel_size)
			.to_binned(tomogram_binning);

		let particles = (0 .. num_particles)
			.map(|_| sample_particle_3d(
				tomogram_width.to_binned(tomogram_binning),
				tomogram_height.to_binned(tomogram_binning),
				tomogram_depth.to_binned(tomogram_binning),
				radius
			))
			.collect::<Vec<_>>();

		let tilt_series = TiltSeries {
			tilt_series_id,

			// sample some CTF parameters, but get the structural bits from the args
			ctf: Some(sample_ctf(
				tomogram_width.to_f(),
				tomogram_height.to_f(),
				tomogram_depth.to_f(),
				pixel_size,
				tomogram_binning
			)),

			// omit all the tilt series metadata
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

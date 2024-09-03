
use std::fs;

use anyhow::{bail, Context, Result};
use image::Rgb;
use tracing::info;

use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::TiltSeries;
use crate::rand::{Gaussian, sample_ctf};
use crate::scale::{ToValueF, ToValueU};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-denoising-eval";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

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

	// set default arg values that the website will use, but we won't
	args.set_default(&args_config, "ctf", "min_res")?;

	// look for the model file, if needed
	// TODO: update this to use the new training tab arg
	let method = args.get("tomo_denoise_method")
		.into_str()?
		.value();
	match method {
		Some("isonet-predict") => {
			// look for the model file
			let model_path = args.get("tomo_denoise_isonet_model")
				.require()?
				.into_path()?
				.value();
			match model_path.exists() {
				false => bail!("Missing model: {}", model_path.to_string_lossy()),
				true => info!("Found model: {}", model_path.to_string_lossy())
			}
		}
		_ => () // no model needed
	}

	// tell the website
	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	fs::create_dir_all("mrc")
		.context("Failed to create mrc dir")?;
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for tilt_series_i in 0 .. num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

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

			// skip all the other metadata
			xf: None,
			avgrot: None,
			drift: None,
			virions: None,
			spikes: None
		};

		// use less noise than preprocessing, har har!
		let noise = Gaussian::new(0.0, 10.0);

		// generate the tilt series image
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise_gaussian(&noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series)
		]);
		img.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;

		// generate the sides image
		let mut img = Image::new(512, 1024);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise_gaussian(&noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Sides".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series)
		]);
		img.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;

		// generate the rec file
		fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
			.context("Failed to write rec file")?;

		// write the reconstruction montage
		const SLICE_FACTOR: u32 = 2;
		let tomogram_slices = (tomogram_depth.to_binned(tomogram_binning).0/SLICE_FACTOR) as usize;
		Image::montage(tomogram_slices, 512, 512, |slice_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise_gaussian(&noise);
			tile.draw().tile_border(2, slice_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", BLOCK_ID),
				"Type: Reconstruction Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
				format!("Slice: {} of {}", slice_i + 1, tomogram_slices)
			]);
			Ok(())
		}).context("Failed to make tomogram montage")?
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

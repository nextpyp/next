
use std::fs;

use anyhow::{Context, Result};
use image::Rgb;
use tracing::info;

use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::TiltSeries;
use crate::particles::{read_manual_tomo_virions, sample_tomo_virions};
use crate::rand::sample_ctf;
use crate::scale::{TomogramDimsUnbinned, ToValueF, ToValueU};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-segmentation-closed";


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
	let virion_radius = args.get_mock(BLOCK_ID, "virion_radius")
		.into_f64()?
		.or(1000.0)
		.value()
		.to_a();
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
		.or_default(args_config)?
		.into_u32()?
		.value()
		.to_unbinned();
	let tomogram_binning = args.get_from_group("tomo_rec", "binning")
		.or_default(args_config)?
		.into_u32()?
		.value();
	let tomogram_dims = TomogramDimsUnbinned::new(tomogram_width, tomogram_height, tomogram_depth);

	// set default arg values that the website will use, but we won't
	args.set_default(&args_config, "ctf", "min_res")?;

	// tell the website about the params
	let web = Web::new()?;
	web.write_parameters(args, args_config)?;

	// try to read the submitted particles, or sample new ones
	let virion_radius = virion_radius
		.to_unbinned(pixel_size);
	let default_threshold = 1;
	let tilt_series_virions = read_manual_tomo_virions(virion_radius, default_threshold)?
		.map(|tilt_series_virions| {
			let num_particles = tilt_series_virions.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!("Read {} manual virions from {} tilt series", num_particles, tilt_series_virions.len());
			tilt_series_virions
		})
		.unwrap_or_else(|| {
			info!("No manual particles, sampled new ones");
			sample_tomo_virions(num_tilt_series, num_particles, tomogram_dims, virion_radius, default_threshold)
		});

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for (tilt_series_id, virions) in tilt_series_virions {

		// generate segmentation images
		for virioni in 0 .. virions.len() {

			const SQUARE_SIZE: u32 = 120;

			// draw the segmentation image
			let mut img = Image::new(SQUARE_SIZE*9, SQUARE_SIZE*3);
			img.draw().fill(Rgb([128, 128, 128]));
			img.draw().noise();
			for thresholdi in 0 .. 9 {
				for stacki in 0 .. 3 {
					let mut square = img.sub_image(
						thresholdi*SQUARE_SIZE,
						stacki*SQUARE_SIZE,
						SQUARE_SIZE,
						SQUARE_SIZE
					);
					square.draw().border(2, Rgb([255, 255, 255]));
					square.draw().text_lines(16, Rgb([255, 255, 255]), [
						format!("Threshold: {}", thresholdi),
						format!("Stack: {}", stacki)
					])
				}
			}
			img.save(format!("webp/{}_vir{:0>4}_binned_nad.webp", &tilt_series_id, virioni))?;
		}

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
			virions: Some(virions),
			spikes: None
		};

		// generate the tilt series image
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise();
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
		]);
		img.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;

		// generate the sides image
		let mut img = Image::new(512, 1024);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise();
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Sides".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
		]);
		img.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;

		// write the reconstruction montage
		const SLICE_FACTOR: u32 = 2;
		let tomogram_slices = (tomogram_depth.to_binned(tomogram_binning).0/SLICE_FACTOR) as usize;
		Image::montage(tomogram_slices, 512, 512, |slice_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise();
			tile.draw().tile_border(2, slice_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", BLOCK_ID),
				"Type: Reconstruction Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Slice: {} of {}", slice_i + 1, tomogram_slices)
			]);
			Ok(())
		}).context("Failed to make tomogram montage")?
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

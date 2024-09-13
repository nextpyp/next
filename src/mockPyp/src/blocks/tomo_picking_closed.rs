
use std::fs;

use anyhow::{Context, Result};
use image::Rgb;
use tracing::warn;

use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::TiltSeries;
use crate::particles::{sample_particle_3d, sample_virion};
use crate::rand::sample_ctf;
use crate::scale::{TomogramDimsUnbinned, ToValueF, ToValueU};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-picking-closed";


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
	let num_virions = args.get_mock(BLOCK_ID, "num_virions")
		.into_u32()?
		.or(20)
		.value();
	let virion_radius = args.get_mock(BLOCK_ID, "virion_radius")
		.into_f64()?
		.or(1000.0)
		.value()
		.to_a();
	let num_spikes = args.get_mock(BLOCK_ID, "num_spikes")
		.into_u32()?
		.or(40)
		.value();
	let spike_radius = args.get_mock(BLOCK_ID, "spike_radius")
		.into_f64()?
		.or(500.0)
		.value()
		.to_a();

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
	let tomogram_dims = TomogramDimsUnbinned::new(tomogram_width, tomogram_height, tomogram_depth)
		.to_binned(tomogram_binning);
	let virion_binning = args.get_from_group("tomo_vir", "binn")
		.or_default(args_config)?
		.into_u32()?
		.value();
	let virion_dims = tomogram_dims
		.clone()
		.with_additional_binning(virion_binning);

	let virion_radius = virion_radius
		.to_unbinned(pixel_size)
		.to_binned(tomogram_binning)
		.with_additional_binning(virion_binning);
	let spike_radius = spike_radius
		.to_unbinned(pixel_size)
		.to_binned(tomogram_binning);

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for tilt_series_i in 0 .. num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate virions
		let virions = (0 .. num_virions)
			.map(|_| sample_virion(virion_dims, virion_radius, 1))
			.collect::<Vec<_>>();

		// generate spikes, if needed
		let tomo_srf_detect_method = args.get("tomo_srf_detect_method")
			.or_default(&args_config)?
			.into_string()?
			.value();
		let spikes = match tomo_srf_detect_method.as_ref() {
			"template" | "mesh" => {
				let spikes = (0 .. num_spikes)
					.map(|_| sample_particle_3d(tomogram_dims, spike_radius))
					.collect();
				Some(spikes)
			},
			method => {
				warn!("unrecognied method: {}", method);
				None
			}
		};

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
			virions: Some(virions),
			spikes,
		};

		// generate the tilt series image
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise();
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series)
		]);
		img.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;

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
				format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
				format!("Slice: {} of {}", slice_i + 1, tomogram_slices)
			]);
			Ok(())
		}).context("Failed to make tomogram montage")?
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		// generate the sides image
		let mut img = Image::new(512, 1024);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise();
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Sides".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series)
		]);
		img.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;

		// tell the website
		let web = Web::new()?;
		web.write_parameters(&args, &args_config)?;
		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}


use std::fs;
use anyhow::{Context, Result};
use image::Rgb;

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};
use crate::metadata::{TiltSeries, TiltSeriesDrifts};
use crate::rand::{interpolate_tilt_angle, sample_avgrot, sample_ctf, sample_drift_ctf, sample_drifts, sample_particle_3d, sample_virion, sample_xf};
use crate::web::Web;


const GROUP_ID: &'static str = "tomo_preprocessing";


pub fn run(mut args: Args, args_config: ArgsConfig) -> Result<()> {

	// get args
	let num_tilt_series = args.get_from_group(GROUP_ID, "num_tilt_series")
		.into_u32()?
		.or(4)
		.value();
	let num_tilts = args.get_from_group(GROUP_ID, "num_tilts")
		.into_u32()?
		.or(4)
		.value();
	let tomogram_width = args.get_from_group(GROUP_ID, "tomogram_width")
		.into_u32()?
		.or(1024)
		.value();
	let tomogram_height = args.get_from_group(GROUP_ID, "tomogram_height")
		.into_u32()?
		.or(1024)
		.value();
	let tomogram_depth = args.get_from_group(GROUP_ID, "tomogram_depth")
		.into_u32()?
		.or(256)
		.value();
	let tomogram_binning = args.get_from_group(GROUP_ID, "tomogram_binning")
		.into_u32()?
		.or(2)
		.value();
	let num_virions = args.get_from_group(GROUP_ID, "num_virions")
		.into_u32()?
		.or(5)
		.value();
	let num_spikes = args.get_from_group(GROUP_ID, "num_spikes")
		.into_u32()?
		.or(10)
		.value();

	// set default arg values that the website will use, but we won't
	args.set_default(&args_config, "ctf", "min_res")?;

	// create subfolders
	fs::create_dir_all("mrc")
		.context("Failed to create mrc dir")?;
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for tilt_series_i in 0 .. num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate the tilts
		let mut drift = TiltSeriesDrifts {
			tilts: vec![],
			drifts: vec![],
			ctf_values: vec![],
			ctf_profiles: vec![],
			tilt_axis_angle: 85.3,
		};
		for tilt_i in 0 .. num_tilts {
			drift.tilts.push(interpolate_tilt_angle(45, tilt_i, num_tilts) as f64);
			drift.drifts.push(sample_drifts(4));
			drift.ctf_values.push(sample_drift_ctf(tilt_i));
			drift.ctf_profiles.push(sample_avgrot(4));

			// TODO: write tilt images
		}

		// generate virions, if needed
		let tomo_vir_method = args.get("tomo_vir_method")
			.into_str()?
			.value();
		let virions = match tomo_vir_method {
			Some("auto") => {
				let radius = args.get("tomo_vir_rad")
					.into_f64()?
					.or(500.0)
					.value();
				let virions = (0 .. num_virions)
					.map(|_| sample_virion(tomogram_width, tomogram_height, tomogram_depth, radius))
					.collect();
				Some(virions)
			},
			_ => None
		};

		// generate spikes, if needed
		let tomo_spk_method = args.get("tomo_spk_method")
			.into_str()?
			.value();
		let spikes = match tomo_spk_method {
			Some("auto") => {
				let radius = args.get("tomo_spk_rad")
					.into_f64()?
					.or(75.0)
					.value();
				let spikes = (0 .. num_spikes)
					.map(|_| sample_particle_3d(tomogram_width, tomogram_height, tomogram_depth, radius))
					.collect();
				Some(spikes)
			},
			_ => None
		};

		let tilt_series = TiltSeries {
			tilt_series_id,

			// sample some CTF parameters, but get the structural bits from the args
			ctf: Some(sample_ctf(
				tomogram_width as f64,
				tomogram_height as f64,
				tomogram_depth as f64,
				5.42,
				tomogram_binning as f64
			)),

			// sample some metadata
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),

			drift: Some(drift),
			virions,
			spikes
		};

		// generate the tilt series image
		let mut img = Image::new(tomogram_width/tomogram_binning, tomogram_height/tomogram_binning);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise();
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series)
		]);
		img.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;

		// generate the rec file
		fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
			.context("Failed to write rec file")?;

		// write the raw tilts montage
		{
			let tile_width = 256u32;
			let tile_height = 256u32;
			Image::montage(num_tilts as usize, tile_width, tile_height, |tilt_i, mut tile| {
				tile.draw().fill(Rgb([128, 128, 128]));
				tile.draw().noise();
				tile.draw().text_lines(32, Rgb([255, 255, 255]), [
					"Type: Raw Tilt Montage".to_string(),
					format!("Id: {}", &tilt_series.tilt_series_id),
					format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
					format!("Tilt: {} of {}", tilt_i + 1, num_tilts)
				]);
				Ok(())
			}).context("Failed to make raw tilts montage")?
				.save(format!("webp/{}_raw.webp", &tilt_series.tilt_series_id))?;
		}

		// write the aligned tilts montage
		{
			let tile_width = 256u32;
			let tile_height = 256u32;
			Image::montage(num_tilts as usize, tile_width, tile_height, |tilt_i, mut tile| {
				tile.draw().fill(Rgb([128, 128, 128]));
				tile.draw().noise();
				tile.draw().text_lines(32, Rgb([255, 255, 255]), [
					"Type: Aligned Tilt Montage".to_string(),
					format!("Id: {}", &tilt_series.tilt_series_id),
					format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
					format!("Tilt: {} of {}", tilt_i + 1, num_tilts)
				]);
				Ok(())
			}).context("Failed to make aligned tilts montage")?
				.save(format!("webp/{}_ali.webp", &tilt_series.tilt_series_id))?;
		}

		// write the tilt 2D CTFs montage
		{
			let tile_width = 256u32;
			let tile_height = 256u32;
			Image::montage(num_tilts as usize, tile_width, tile_height, |tilt_i, mut tile| {
				tile.draw().fill(Rgb([128, 128, 128]));
				tile.draw().noise();
				tile.draw().text_lines(32, Rgb([255, 255, 255]), [
					"Type: Tilt 2D CTF Montage".to_string(),
					format!("Id: {}", &tilt_series.tilt_series_id),
					format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
					format!("Tilt: {} of {}", tilt_i + 1, num_tilts)
				]);
				Ok(())
			}).context("Failed to make tilts CTF montage")?
				.save(format!("webp/{}_2D_ctftilt.webp", &tilt_series.tilt_series_id))?;
		}

		// write the reconstruction montage
		{
			let tile_width = tomogram_width/tomogram_binning;
			let tile_height = tomogram_height/tomogram_binning;
			let num_slices = (tomogram_depth/2) as usize;
			Image::montage(num_slices, tile_width, tile_height, |slice_i, mut tile| {
				tile.draw().fill(Rgb([128, 128, 128]));
				tile.draw().noise();
				tile.draw().text_lines(32, Rgb([255, 255, 255]), [
					"Type: Reconstruction Montage".to_string(),
					format!("Id: {}", &tilt_series.tilt_series_id),
					format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
					format!("Slice: {} of {}", slice_i + 1, num_slices)
				]);
				Ok(())
			}).context("Failed to make tomogram montage")?
				.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;
		}

		// tell the website
		let web = Web::new()?;
		web.write_parameters(&args, &args_config)?;
		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}


use std::fs;

use anyhow::{bail, Context, Result};
use image::Rgb;
use tracing::info;

use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::{TiltSeries, TiltSeriesDrifts};
use crate::particles::{sample_particle_3d, sample_virion};
use crate::rand::{interpolate_tilt_angle, sample_avgrot, sample_ctf, sample_drift_ctf, sample_drifts, sample_xf};
use crate::scale::{TomogramDimsUnbinned, ToValueF, ToValueU};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-session";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	// get mock args
	let num_tilt_series = args.get_mock(BLOCK_ID, "num_tilt_series")
		.into_u32()?
		.or(4)
		.value();
	let num_tilts = args.get_mock(BLOCK_ID, "num_tilts")
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
		.or(5)
		.value();
	let num_spikes = args.get_mock(BLOCK_ID, "num_spikes")
		.into_u32()?
		.or(10)
		.value();
	let tilt_angle_magnitude = args.get_mock(BLOCK_ID, "tilt_angle_magnitude")
		.into_u32()?
		.or(45)
		.value();
	let spike_radius = args.get_mock(BLOCK_ID, "spike_rad")
		.into_f64()?
		.or(100.0)
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
	let additional_virion_binning = args.get_from_group("tomo_vir", "binn")
		.or_default(&args_config)?
		.into_u32()?
		.value();
	let tomogram_dims = TomogramDimsUnbinned::new(tomogram_width, tomogram_height, tomogram_depth)
		.to_binned(tomogram_binning);

	let spike_radius = spike_radius
		.to_unbinned(pixel_size)
		.to_binned(tomogram_binning);

	// set default arg values that the website will use, but we won't
	args.set_default(&args_config, "ctf", "min_res")?;

	// create subfolders
	fs::create_dir_all("mrc")
		.context("Failed to create mrc dir")?;
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// tell the website
	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

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
			drift.tilts.push(interpolate_tilt_angle(tilt_angle_magnitude, tilt_i, num_tilts) as f64);
			drift.drifts.push(sample_drifts(4));
			drift.ctf_values.push(sample_drift_ctf(tilt_i));
			drift.ctf_profiles.push(sample_avgrot(4));
		}

		// generate virions, if needed
		let tomo_vir_method = args.get("tomo_vir_method")
			.into_str()?
			.value()
			.take_if(|method| *method != "none" && *method != "manual");
		let virions = match tomo_vir_method {
			None => None,
			Some(method) => {
				info!("Generating virions: method={}", method);
				match method {
					"auto" => {
						let radius = args.get("tomo_vir_rad")
							.into_f64()?
							.or(150.0)
							.value()
							.to_a()
							.to_unbinned(pixel_size)
							.to_binned(tomogram_binning);
						let virions = (0 .. num_virions)
							.map(|_| sample_virion(
								tomogram_dims.with_additional_binning(additional_virion_binning),
								radius.with_additional_binning(additional_virion_binning),
								1
							))
							.collect();
						Some(virions)
					}
					_ => bail!("unrecognized virions method: {}", method)
				}
			}
		};

		// generate spikes, if needed
		let tomo_vir_detect_method = args.get("tomo_vir_detect_method")
			.into_str()?
			.value()
			.take_if(|method| *method != "none");
		let tomo_spk_method = args.get("tomo_spk_method")
			.into_str()?
			.value()
			.take_if(|method| *method != "none" && *method != "manual");
		let spikes = match (tomo_vir_detect_method, tomo_spk_method) {

			(None, None) => None,

			// match virion spikes first to prefer that method over the other one
			(Some(method), _) => match method {
				"template" | "mesh" => {
					info!("Generating virion spikes, method={}", method);
					let spikes = (0 .. num_spikes)
						.map(|_| sample_particle_3d(tomogram_dims, spike_radius))
						.collect();
					Some(spikes)
				},
				_ => bail!("unrecognized spikes method: {}", method)
			}

			(None, Some(method)) => match method {
				"auto" | "virions" => {
					info!("Generating particles: method={}", method);
					let radius = args.get("tomo_spk_rad")
						.into_f64()?
						.or(75.0)
						.value()
						.to_a()
						.to_unbinned(pixel_size)
						.to_binned(tomogram_binning);
					let spikes = (0 .. num_spikes)
						.map(|_| sample_particle_3d(tomogram_dims, radius))
						.collect();
					Some(spikes)
				}
				_ => bail!("unrecognized spikes method: {}", method)
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

			// sample some metadata
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),

			drift: Some(drift),
			virions,
			spikes
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

		// generate the rec file
		fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
			.context("Failed to write rec file")?;

		// write the raw tilts montage
		Image::montage(num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise();
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", BLOCK_ID),
				"Type: Raw Tilt Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(tilt_angle_magnitude, tilt_i as u32, num_tilts), tilt_i + 1, num_tilts)
			]);
			Ok(())
		}).context("Failed to make raw tilts montage")?
			.save(format!("webp/{}_raw.webp", &tilt_series.tilt_series_id))?;

		// write the aligned tilts montage
		Image::montage(num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise();
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", BLOCK_ID),
				"Type: Aligned Tilt Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(tilt_angle_magnitude, tilt_i as u32, num_tilts), tilt_i + 1, num_tilts)
			]);
			Ok(())
		}).context("Failed to make aligned tilts montage")?
			.save(format!("webp/{}_ali.webp", &tilt_series.tilt_series_id))?;

		// write the tilt 2D CTFs montage
		Image::montage(num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise();
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", BLOCK_ID),
				"Type: Tilt 2D CTF Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(tilt_angle_magnitude, tilt_i as u32, num_tilts), tilt_i + 1, num_tilts)
			]);
			Ok(())
		}).context("Failed to make tilts CTF montage")?
			.save(format!("webp/{}_2D_ctftilt.webp", &tilt_series.tilt_series_id))?;

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

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

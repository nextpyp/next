
use std::fs;

use anyhow::{bail, Context, Result};

use crate::info;
use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries, TiltSeriesDrifts};
use crate::particles::{sample_particle_3d, sample_virion};
use crate::rand::{sample_avgrot, sample_ctf, sample_drift_ctf, sample_drifts, sample_xf};
use crate::scale::ToValueF;
use crate::tomography;
use crate::tomography::{interpolate_tilt_angle, PreprocessingArgs};
use crate::tomography::images::DEFAULT_NOISE;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-session";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let num_virions = args.get_mock(BLOCK_ID, "num_virions")
		.into_u32()?
		.or(5)
		.value();
	let num_spikes = args.get_mock(BLOCK_ID, "num_spikes")
		.into_u32()?
		.or(10)
		.value();
	let spike_radius = args.get_mock(BLOCK_ID, "spike_rad")
		.into_f64()?
		.or(100.0)
		.value()
		.to_a()
		.to_unbinned(pp_args.pixel_size);

	// create subfolders
	fs::create_dir_all("mrc")
		.context("Failed to create mrc dir")?;
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;
	fs::create_dir_all("log")
		.context("Failed to create log dir")?;

	// tell the website
	web.write_parameters(&args, &args_config)?;

	// generate tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate the tilts
		let mut drift = TiltSeriesDrifts {
			tilts: vec![],
			drifts: vec![],
			ctf_values: vec![],
			ctf_profiles: vec![],
			tilt_axis_angle: 85.3,
		};
		for tilt_i in 0 .. pp_args.num_tilts {
			drift.tilts.push(interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i, pp_args.num_tilts) as f64);
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
				info!(web, "Generating virions: method={}", method);
				match method {
					"auto" => {
						let radius = args.get("tomo_vir_rad")
							.into_f64()?
							.or(150.0)
							.value()
							.to_a()
							.to_unbinned(pp_args.pixel_size);
						let virions = (0 .. num_virions)
							.map(|_| sample_virion(pp_args.tomogram_dims, radius, 1))
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
					info!(web, "Generating virion spikes, method={}", method);
					let spikes = (0 .. num_spikes)
						.map(|_| sample_particle_3d(pp_args.tomogram_dims, spike_radius))
						.collect();
					Some(spikes)
				},
				_ => bail!("unrecognized spikes method: {}", method)
			}

			(None, Some(method)) => match method {
				"auto" | "virions" => {
					info!(web, "Generating particles: method={}", method);
					let radius = args.get("tomo_spk_rad")
						.into_f64()?
						.or(75.0)
						.value()
						.to_a()
						.to_unbinned(pp_args.pixel_size);
					let spikes = (0 .. num_spikes)
						.map(|_| sample_particle_3d(pp_args.tomogram_dims, radius))
						.collect();
					Some(spikes)
				}
				_ => bail!("unrecognized spikes method: {}", method)
			}
		};

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),
			drift: Some(drift),
			virions,
			spikes
		};

		// generate the rec file
		fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
			.context("Failed to write rec file")?;

		// generate images
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
		tomography::images::raw_tilts_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_raw.webp", &tilt_series.tilt_series_id))?;
		tomography::images::aligned_tilts_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_ali.webp", &tilt_series.tilt_series_id))?;
		tomography::images::twod_ctf_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_2D_ctftilt.webp", &tilt_series.tilt_series_id))?;
		tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		// write the log file
		let log_path = format!("log/{}.log", &tilt_series.tilt_series_id);
		fs::write(&log_path, format!("Things happened for tilt series {}", &tilt_series.tilt_series_id))
			.context(format!("Failed to write log file: {}", &log_path))?;
		info!(web, "Wrote log file: {}", &log_path);

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

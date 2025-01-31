
use std::fs;

use anyhow::{Context, Result};

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries};
use crate::particles::sample_particle_3d;
use crate::rand::sample_ctf;
use crate::scale::ValueA;
use crate::{info, tomography};
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-picking-open";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let num_particles = args.get_mock(BLOCK_ID, "num_particles")
		.into_u32()?
		.or(20)
		.value();

	// tell the website
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;
	fs::create_dir_all("log")
		.context("Failed to create log dir")?;

	// generate tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate particles, if needed
		let tomo_src_detect_method = args.get("tomo_srf_detect_method")
			.into_str()?
			.value();
		let particles = match tomo_src_detect_method {
			Some("template") | Some("mesh") => {
				let radius = ValueA(500.0)
					.to_unbinned(pp_args.pixel_size);
				let particles = (0 .. num_particles)
					.map(|_| sample_particle_3d(pp_args.tomogram_dims, radius))
					.collect();
				Some(particles)
			},
			_ => None
		};

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions: None,
			spikes: particles
		};

		// generate images
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
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

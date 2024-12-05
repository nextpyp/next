
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries};
use crate::particles::{sample_particle_3d, sample_virion};
use crate::rand::sample_ctf;
use crate::scale::ToValueF;
use crate::tomography;
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-picking";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let num_particles = args.get_mock(BLOCK_ID, "num_particles")
		.into_u32()?
		.or(20)
		.value();

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	let tomo_pick_method = args.get("tomo_pick_method")
		.into_str()?
		.value();
	info!("pick method: {:?}", tomo_pick_method);

	// generate tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate particles, if needed
		let (virions, spikes) = match tomo_pick_method {

			Some("auto") => {
				let radius = args.get("tomo_pick_rad")
					.into_f64()?
					.or(500.0)
					.value()
					.to_a()
					.to_unbinned(pp_args.pixel_size);
				let spikes = (0 .. num_particles)
					.map(|_| sample_particle_3d(pp_args.tomogram_dims, radius))
					.collect();
				(None, Some(spikes))
			}

			Some("virions") => {
				let radius = args.get("tomo_vir_rad")
					.into_f64()?
					.or(100.0)
					.value()
					.to_a()
					.to_unbinned(pp_args.pixel_size);
				let threshold = args.get_mock(BLOCK_ID, "threshold")
					.into_u32()?
					.or(1)
					.value();
				let virions = (0 .. num_particles)
					.map(|_| sample_virion(pp_args.tomogram_dims, radius, threshold))
					.collect();
				(Some(virions), None)
			}

			_ => (None, None)
		};

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions,
			spikes
		};

		// generate images
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
		tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		// tell the website
		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}


use std::fs;

use anyhow::{Context, Result};

use crate::warn;
use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries};
use crate::particles::{sample_particle_3d, sample_virion};
use crate::rand::sample_ctf;
use crate::scale::ToValueF;
use crate::tomography;
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-picking-closed";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let num_virions = args.get_mock(BLOCK_ID, "num_virions")
		.into_u32()?
		.or(20)
		.value();
	let virion_radius = args.get_mock(BLOCK_ID, "virion_radius")
		.into_f64()?
		.or(1000.0)
		.value()
		.to_a()
		.to_unbinned(pp_args.pixel_size);
	let num_spikes = args.get_mock(BLOCK_ID, "num_spikes")
		.into_u32()?
		.or(40)
		.value();
	let spike_radius = args.get_mock(BLOCK_ID, "spike_radius")
		.into_f64()?
		.or(500.0)
		.value()
		.to_a()
		.to_unbinned(pp_args.pixel_size);

	// tell the website
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		// generate virions
		let virions = (0 .. num_virions)
			.map(|_| sample_virion(pp_args.tomogram_dims, virion_radius, 1))
			.collect::<Vec<_>>();

		// generate spikes, if needed
		let tomo_srf_detect_method = args.get("tomo_srf_detect_method")
			.or_default(&args_config)?
			.into_string()?
			.value();
		let spikes = match tomo_srf_detect_method.as_ref() {
			"template" | "mesh" => {
				let spikes = (0 .. num_spikes)
					.map(|_| sample_particle_3d(pp_args.tomogram_dims, spike_radius))
					.collect();
				Some(spikes)
			},
			method => {
				warn!(web, "unrecognied method: {}", method);
				None
			}
		};

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions: Some(virions),
			spikes
		};

		// generate images
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
		tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(web, format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

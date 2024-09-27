
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries};
use crate::particles::{read_next_tomo_virions, sample_tomo_virions};
use crate::rand::sample_ctf;
use crate::scale::ToValueF;
use crate::tomography;
use crate::tomography::images::DEFAULT_NOISE;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-segmentation-closed";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// get mock args
	let virion_radius = args.get_mock(BLOCK_ID, "virion_radius")
		.into_f64()?
		.or(1000.0)
		.value()
		.to_a()
		.to_unbinned(pp_args.pixel_size);
	let num_particles = args.get_mock(BLOCK_ID, "num_particles")
		.into_u32()?
		.or(20)
		.value();

	// tell the website about the params
	let web = Web::new()?;
	web.write_parameters(args, args_config)?;

	// try to read the submitted particles, or sample new ones
	let default_threshold = 1;
	let tilt_series_virions = read_next_tomo_virions(default_threshold)?
		.map(|tilt_series_virions| {
			let num_particles = tilt_series_virions.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!("Read {} manual virions from {} tilt series", num_particles, tilt_series_virions.len());
			tilt_series_virions
		})
		.unwrap_or_else(|| {
			info!("No manual particles, sampled new ones");
			sample_tomo_virions(pp_args.num_tilt_series, num_particles, pp_args.tomogram_dims, virion_radius, default_threshold)
		});

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

	// generate tilt series
	for (tilt_series_id, virions) in tilt_series_virions {

		// generate segmentation images
		for virioni in 0 .. virions.len() {
			tomography::images::segmentation(&DEFAULT_NOISE)
				.save(format!("webp/{}_vir{:0>4}_binned_nad.webp", &tilt_series_id, virioni))?;
		}

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions: Some(virions),
			spikes: None
		};

		// generate images
		let tilt_series_i = 0; // TODO: any way to preserve the order here?
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
		tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

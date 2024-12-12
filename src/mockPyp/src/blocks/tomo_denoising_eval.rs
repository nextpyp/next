
use std::fs;

use anyhow::{bail, Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries};
use crate::rand::{Gaussian, sample_ctf};
use crate::tomography;
use crate::tomography::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-denoising-eval";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

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
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

		let tilt_series = TiltSeries {
			tilt_series_id,
			ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
			xf: None,
			avgrot: None,
			drift: None,
			virions: None,
			spikes: None
		};

		// generate the rec file
		fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
			.context("Failed to write rec file")?;

		// use less noise than preprocessing, har har!
		let noise = Gaussian::new(0.0, 10.0);

		// generate images
		tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &noise)
			.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
		tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &noise)
			.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
		tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &noise)
			.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

		web.write_tilt_series(&tilt_series)?;
	}

	Ok(())
}

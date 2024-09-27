
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, TiltSeries, TiltSeriesDrifts};
use crate::rand::{sample_avgrot, sample_ctf, sample_drift_ctf, sample_drifts, sample_xf};
use crate::tomography;
use crate::tomography::{interpolate_tilt_angle, PreprocessingArgs};
use crate::tomography::images::DEFAULT_NOISE;
use crate::web::{Commands, Web};


pub const BLOCK_ID: &'static str = "tomo-pure-preprocessing";


pub fn run(args: &mut Args, args_config: &ArgsConfig, array_element: Option<u32>) -> Result<()> {

	let split_mode = args.get_mock(BLOCK_ID, "split_mode")
		.into_bool()?
		.or(false)
		.value();

	match (split_mode, array_element) {

		// in a split-mode array job, run the array element
		(true, Some(array_element)) => run_array(args, args_config, array_element),

		// not in an array job, but we should launch one
		(true, None) => launch(args, args_config),

		// not in split mode, just run everything
		(false, _) => run_all(args, args_config)
	}
}


fn run_all(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	info!("run all");

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	// send params to the website
	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// generate tilt series
	for tilt_series_i in 0 .. pp_args.num_tilt_series {
		generate_tilt(tilt_series_i, &pp_args, &web)?;
	}

	Ok(())
}


fn launch(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	info!("launch");

	let num_tilt_series = args.get_mock(BLOCK_ID, "num_tilt_series")
		.into_u32()?
		.or(4)
		.value();

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// send the split job
	web.submit_cluster_job(
		"Split",
		"tomoppre_split",
		&Commands::Script {
			commands: vec![
				Commands::mock_pyp("pyp", args)
			],
			array_size: Some(num_tilt_series),
			bundle_size: None
		},
		None,
		None,
		None,
		None
	)?;

	Ok(())
}


fn run_array(args: &mut Args, args_config: &ArgsConfig, array_element: u32) -> Result<()> {

	info!("run array: {}", array_element);

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	let web = Web::new()?;
	
	// generate tilt series
	let tilt_series_i = array_element - 1;
	generate_tilt(tilt_series_i, &pp_args, &web)?;

	Ok(())
}


fn generate_tilt(tilt_series_i: u32, pp_args: &PreprocessingArgs, web: &Web) -> Result<()> {

	let tilt_series_id = format!("tilt_series_{}", tilt_series_i);

	// create subfolders
	fs::create_dir_all("mrc")
		.context("Failed to create mrc dir")?;
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;

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

	let tilt_series = TiltSeries {
		tilt_series_id,
		ctf: Some(sample_ctf(Ctf::from_tomo_preprocessing(&pp_args))),
		xf: Some(sample_xf(fastrand::usize(4..=8))),
		avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),
		drift: Some(drift),
		virions: None,
		spikes: None
	};

	// generate the rec file
	fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
		.context("Failed to write rec file")?;

	// generate images
	tomography::images::tilt_series(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}.webp", &tilt_series.tilt_series_id))?;
	tomography::images::sides(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;
	tomography::images::raw_tilts_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}_raw.webp", &tilt_series.tilt_series_id))?;
	tomography::images::aligned_tilts_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}_ali.webp", &tilt_series.tilt_series_id))?;
	tomography::images::twod_ctf_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}_2D_ctftilt.webp", &tilt_series.tilt_series_id))?;
	tomography::images::reconstruction_montage(BLOCK_ID, &tilt_series, tilt_series_i, &pp_args, &DEFAULT_NOISE)
		.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

	web.write_tilt_series(&tilt_series)?;

	Ok(())
}

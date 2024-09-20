
use std::fs;
use anyhow::{Context, Result};
use image::Rgb;
use tracing::info;
use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::metadata::{TiltSeries, TiltSeriesDrifts};
use crate::rand::{interpolate_tilt_angle, sample_avgrot, sample_ctf, sample_drift_ctf, sample_drifts, sample_xf};
use crate::scale::{TomogramDimsUnbinned, ToValueF, ToValueU, ValueA};
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

	let pp_args = PreprocessingArgs::from(args, args_config)?;

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

	let pp_args = PreprocessingArgs::from(args, args_config)?;

	let web = Web::new()?;
	
	// generate tilt series
	let tilt_series_i = array_element - 1;
	generate_tilt(tilt_series_i, &pp_args, &web)?;

	Ok(())
}



struct PreprocessingArgs {
	num_tilt_series: u32,
	num_tilts: u32,
	tilt_angle_magnitude: u32,
	pixel_size: ValueA,
	tomogram_dims: TomogramDimsUnbinned,
	tomogram_binning: u32
}

impl PreprocessingArgs {

	fn from(args: &mut Args, args_config: &ArgsConfig) -> Result<PreprocessingArgs> {

		let pp_args = PreprocessingArgs {
			num_tilt_series: args.get_mock(BLOCK_ID, "num_tilt_series")
				.into_u32()?
				.or(4)
				.value(),
			num_tilts: args.get_mock(BLOCK_ID, "num_tilts")
				.into_u32()?
				.or(4)
				.value(),
			tilt_angle_magnitude: args.get_mock(BLOCK_ID, "tilt_angle_magnitude")
				.into_u32()?
				.or(45)
				.value(),
			pixel_size: args.get("scope_pixel")
				.into_f64()?
				.or(2.15)
				.value()
				.to_a(),
			tomogram_dims: TomogramDimsUnbinned {
				width: args.get_mock(BLOCK_ID, "tomogram_width")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned(),
				height: args.get_mock(BLOCK_ID, "tomogram_height")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned(),
				depth: args.get_from_group("tomo_rec", "thickness")
					.or_default(&args_config)?
					.into_u32()?
					.value()
					.to_unbinned()
			},
			tomogram_binning: args.get_from_group("tomo_rec", "binning")
				.or_default(&args_config)?
				.into_u32()?
				.value()
		};

		// write defaults not defined by the config
		args.set("scope_pixel", ArgValue::String(pp_args.pixel_size.0.to_string()));

		// set default arg values that the website will use, but we won't
		args.set_default(&args_config, "ctf", "min_res")?;

		Ok(pp_args)
	}
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

		// sample some CTF parameters, but get the structural bits from the args
		ctf: Some(sample_ctf(
			pp_args.tomogram_dims.width.to_f(),
			pp_args.tomogram_dims.height.to_f(),
			pp_args.tomogram_dims.depth.to_f(),
			pp_args.pixel_size,
			pp_args.tomogram_binning
		)),

		// sample some metadata
		xf: Some(sample_xf(fastrand::usize(4..=8))),
		avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),

		drift: Some(drift),
		virions: None,
		spikes: None
	};

	// generate the tilt series image
	let mut img = Image::new(512, 512);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise();
	img.draw().text_lines(32, Rgb([255, 255, 255]), [
		format!("Block: {}", BLOCK_ID),
		"Type: Output".to_string(),
		format!("Id: {}", &tilt_series.tilt_series_id),
		format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series)
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
		format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series)
	]);
	img.save(format!("webp/{}_sides.webp", &tilt_series.tilt_series_id))?;

	// generate the rec file
	fs::write(format!("mrc/{}.rec", &tilt_series.tilt_series_id), "this is a rec file")
		.context("Failed to write rec file")?;

	// write the raw tilts montage
	Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
		tile.draw().fill(Rgb([128, 128, 128]));
		tile.draw().noise();
		tile.draw().tile_border(2, tilt_i);
		tile.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Raw Tilt Montage".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
			format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
		]);
		Ok(())
	}).context("Failed to make raw tilts montage")?
		.save(format!("webp/{}_raw.webp", &tilt_series.tilt_series_id))?;

	// write the aligned tilts montage
	Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
		tile.draw().fill(Rgb([128, 128, 128]));
		tile.draw().noise();
		tile.draw().tile_border(2, tilt_i);
		tile.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Aligned Tilt Montage".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
			format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
		]);
		Ok(())
	}).context("Failed to make aligned tilts montage")?
		.save(format!("webp/{}_ali.webp", &tilt_series.tilt_series_id))?;

	// write the tilt 2D CTFs montage
	Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
		tile.draw().fill(Rgb([128, 128, 128]));
		tile.draw().noise();
		tile.draw().tile_border(2, tilt_i);
		tile.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Tilt 2D CTF Montage".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
			format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
		]);
		Ok(())
	}).context("Failed to make tilts CTF montage")?
		.save(format!("webp/{}_2D_ctftilt.webp", &tilt_series.tilt_series_id))?;

	// write the reconstruction montage
	const SLICE_FACTOR: u32 = 2;
	let tomogram_slices = (pp_args.tomogram_dims.depth.to_binned(pp_args.tomogram_binning).0/SLICE_FACTOR) as usize;
	Image::montage(tomogram_slices, 512, 512, |slice_i, mut tile| {
		tile.draw().fill(Rgb([128, 128, 128]));
		tile.draw().noise();
		tile.draw().tile_border(2, slice_i);
		tile.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", BLOCK_ID),
			"Type: Reconstruction Montage".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
			format!("Slice: {} of {}", slice_i + 1, tomogram_slices)
		]);
		Ok(())
	}).context("Failed to make tomogram montage")?
		.save(format!("webp/{}_rec.webp", &tilt_series.tilt_series_id))?;

	web.write_tilt_series(&tilt_series)?;

	Ok(())
}

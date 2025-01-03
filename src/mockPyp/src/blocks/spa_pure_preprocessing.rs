
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, Micrograph};
use crate::progress::ProgressBar;
use crate::rand::{sample_avgrot, sample_ctf, sample_xf};
use crate::single_particle;
use crate::single_particle::images::DEFAULT_NOISE;
use crate::single_particle::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "sp-pure-preprocessing";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, BLOCK_ID)?;

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	fs::create_dir_all("webp")
		.context("Failed to create webp dir")?;
	fs::create_dir_all("log")
		.context("Failed to create log dir")?;

	// do some progress stuff
	let mut progress = ProgressBar::new(100);
	for i in 0 .. progress.total() {
		if i % 5 == 0 {
			progress.report();
		}
		progress.update(1);
	}
	progress.report();

	// generate micrographs
	for micrograph_i in 0 .. pp_args.num_micrographs {
		let micrograph_id = format!("micrograph_{}", micrograph_i);

		let micrograph = Micrograph {
			micrograph_id,
			ctf: Some(sample_ctf(Ctf::from_spa_preprocessing(&pp_args))),
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),
			particles: None
		};

		// generate images
		single_particle::images::micrograph(BLOCK_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}.webp", &micrograph.micrograph_id))?;
		single_particle::images::ctf_find(BLOCK_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_ctffit.webp", &micrograph.micrograph_id))?;

		// write the log file
		let log_path = format!("log/{}.log", &micrograph.micrograph_id);
		fs::write(&log_path, format!("Things happened for micrograph {}", &micrograph.micrograph_id))
			.context(format!("Failed to write log file: {}", &log_path))?;
		info!("Wrote log file: {}", &log_path);

		// tell the website
		web.write_micrograph(&micrograph)?;
	}

	Ok(())
}

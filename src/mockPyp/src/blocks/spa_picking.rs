
use std::fs;

use anyhow::{Context, Result};
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::metadata::{Ctf, Micrograph};
use crate::particles::sample_particle_2d;
use crate::rand::{sample_avgrot, sample_ctf, sample_xf};
use crate::scale::ToValueF;
use crate::single_particle;
use crate::single_particle::images::DEFAULT_NOISE;
use crate::single_particle::PreprocessingArgs;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "sp-picking";


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

	let detect_method = args.get("detect_method")
		.into_str()?
		.value();
	info!("pick method: {:?}", detect_method);

	// generate micrographs
	for micrograph_i in 0 .. pp_args.num_micrographs {
		let micrograph_id = format!("micrograph_{}", micrograph_i);

		// generate particles, if needed
		let particles = match detect_method {

			Some("auto") => {
				let radius = args.get("detect_rad")
					.into_f64()?
					.or(100.0)
					.value()
					.to_a()
					.to_unbinned(pp_args.pixel_size);
				let particles = (0 .. num_particles)
					.map(|_| sample_particle_2d(pp_args.micrograph_dims, radius))
					.collect();
				Some(particles)
			}

			_ => None
		};

		let micrograph = Micrograph {
			micrograph_id,
			ctf: Some(sample_ctf(Ctf::from_spa_preprocessing(&pp_args))),
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),
			particles
		};

		// generate images
		single_particle::images::micrograph(BLOCK_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}.webp", &micrograph.micrograph_id))?;
		single_particle::images::ctf_find(BLOCK_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(format!("webp/{}_ctffit.webp", &micrograph.micrograph_id))?;

		// tell the website
		web.write_micrograph(&micrograph)?;
	}

	Ok(())
}

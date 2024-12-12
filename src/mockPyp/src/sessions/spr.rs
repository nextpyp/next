
use std::fs;
use std::path::Path;

use anyhow::{bail, Context, Result};
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


const GROUP_ID: &str = "stream_spr";


pub fn run(args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let pp_args = PreprocessingArgs::from(args, args_config, GROUP_ID)?;

	// create subfolders
	let session_group = args.get("stream_session_group")
		.require()?
		.into_str()?
		.value();
	let session_name = args.get("stream_session_name")
		.require()?
		.into_str()?
		.value();
	let dir = Path::new(&session_group).join(&session_name);
	fs::create_dir_all(dir.join("mrc"))
		.context("Failed to create mrc dir")?;
	fs::create_dir_all(dir.join("webp"))
		.context("Failed to create webp dir")?;
	fs::create_dir_all(dir.join("log"))
		.context("Failed to create log dir")?;

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// generate micrographs
	for micrograph_i in 0 .. pp_args.num_micrographs {
		let micrograph_id = format!("micrograph_{}", micrograph_i);

		// generate particles, if needed
		let detect_method = args.get("detect_method")
			.into_str()?
			.value()
			.take_if(|method| *method != "none");
		let particles = match detect_method {

			None => None,

			Some(method) => match method {
				"auto" | "all" => {
					info!("Generating particles: method={}", method);
					let num_particles = args.get_mock(GROUP_ID, "num_particles")
						.into_u32()?
						.or(20)
						.value();
					let radius = args.get_mock(GROUP_ID, "particle_radius")
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
				_ => bail!("unrecognized particles method: {}", method)
			}
		};

		let micrograph = Micrograph {
			micrograph_id,
			ctf: Some(sample_ctf(Ctf::from_spa_preprocessing(&pp_args))),
			xf: Some(sample_xf(fastrand::usize(4..=8))),
			avgrot: Some(sample_avgrot(fastrand::usize(4..=8))),
			particles
		};

		// generate images
		single_particle::images::micrograph(GROUP_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(dir.join(format!("webp/{}.webp", &micrograph.micrograph_id)))?;
		single_particle::images::ctf_find(GROUP_ID, &micrograph, micrograph_i, &pp_args, &DEFAULT_NOISE)
			.save(dir.join(format!("webp/{}_ctffit.webp", &micrograph.micrograph_id)))?;

		// write the log file
		let log_path = dir.join(format!("log/{}.log", &micrograph.micrograph_id));
		fs::write(&log_path, format!("Things happened for micrograph {}", &micrograph.micrograph_id))
			.context(format!("Failed to write log file: {}", &log_path.to_string_lossy()))?;
		info!("Wrote log file: {}", &log_path.to_string_lossy());

		// tell the website
		web.write_micrograph(&micrograph)?;
	}

	Ok(())
}

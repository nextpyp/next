
use anyhow::Result;

use crate::info;
use crate::args::{Args, ArgsConfig};
use crate::particles::{read_next_tomo_particles};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-coarse-refinement";


pub fn run(web: &Web, _args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// try to read the manual particles, if any
	match read_next_tomo_particles()? {
		Some(tilt_series_particles) => {
			let num_particles = tilt_series_particles.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!(web, "Read {} manual particles from {} tilt series", num_particles, tilt_series_particles.len());
		}
		_ => info!(web, "No manual particles")
	}

	// TODO: generate reconstructions

	Ok(())
}

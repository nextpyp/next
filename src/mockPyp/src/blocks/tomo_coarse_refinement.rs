
use anyhow::Result;
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::particles::{read_manual_tomo_particles};
use crate::scale::ValueBinnedF;

pub const BLOCK_ID: &'static str = "tomo-coarse-refinement";


pub fn run(_args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// try to read the manual particles, if any
	match read_manual_tomo_particles(ValueBinnedF(1000.0))? {
		Some(tilt_series_particles) => {
			let num_particles = tilt_series_particles.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!("Read {} manual particles from {} tilt series", num_particles, tilt_series_particles.len());
		}
		_ => info!("No manual particles")
	}

	// TODO: generate reconstructions

	Ok(())
}


use anyhow::Result;
use tracing::info;

use crate::args::{Args, ArgsConfig};
use crate::particles::read_tomo_particles;


pub const BLOCK_ID: &'static str = "tomo-segmentation-closed";


pub fn run(_args: Args, _args_config: ArgsConfig) -> Result<()> {

	// try to read the submitted particles
	let tilt_series_particles = read_tomo_particles()?;

	let num_particles = tilt_series_particles.iter()
		.map(|(_, tilt_series)| tilt_series.len())
		.sum::<usize>();
	info!("Read {} particles from {} tilt series", num_particles, tilt_series_particles.len());

	// TODO: what to do here?

	Ok(())
}

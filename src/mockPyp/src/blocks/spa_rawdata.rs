
use anyhow::Result;

use crate::args::{Args, ArgsConfig};
use crate::single_particle;
use crate::single_particle::images::DEFAULT_NOISE;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "sp-rawdata";


pub fn run(web: &Web, args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// get args
	let size = args.get_mock(BLOCK_ID, "image_size")
		.into_u32()?
		.or(512)
		.value();

	// generate the gain-corrected image
	single_particle::images::gain_corrected(BLOCK_ID, size, &DEFAULT_NOISE)
		.save(web, "gain_corrected.webp")?;

	Ok(())
}

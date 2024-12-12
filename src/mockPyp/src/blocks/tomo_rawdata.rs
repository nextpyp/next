
use anyhow::Result;

use crate::args::{Args, ArgsConfig};
use crate::tomography;
use crate::tomography::images::DEFAULT_NOISE;


pub const BLOCK_ID: &'static str = "tomo-rawdata";


pub fn run(args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// get args
	let size = args.get_mock(BLOCK_ID, "image_size")
		.into_u32()?
		.or(512)
		.value();

	// generate the gain-corrected image
	tomography::images::gain_corrected(BLOCK_ID, size, &DEFAULT_NOISE)
		.save("gain_corrected.webp")?;

	Ok(())
}

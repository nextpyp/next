
use anyhow::Result;
use tracing::info;

use crate::args::Args;


pub fn run(args: Args) -> Result<()> {

	let mode = args.require("data_mode")?;
	info!("mode: {}", mode);

	// TODO: NEXTTIME: generate some outputs
	//   split on single-particle or tomography
	//   find some image generation libraries

	Ok(())
}

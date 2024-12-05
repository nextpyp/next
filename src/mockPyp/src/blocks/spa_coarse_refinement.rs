
use anyhow::Result;

use crate::args::{Args, ArgsConfig};


pub const BLOCK_ID: &'static str = "sp-coarse-refinement";


pub fn run(_args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// TODO: try to read the manual particles, if any
	// TODO: generate reconstructions

	Ok(())
}


use anyhow::Result;

use crate::args::{Args, ArgsConfig};
use crate::web::Web;


pub const BLOCK_ID: &'static str = "sp-coarse-refinement";


pub fn run(_web: &Web, _args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// TODO: try to read the manual particles, if any
	// TODO: generate reconstructions

	Ok(())
}


mod tomo_rawdata;
mod tomo_preprocessing;
mod tomo_pure_preprocessing;
mod tomo_denoising;
mod tomo_picking;
mod tomo_picking_open;
mod tomo_picking_closed;
mod tomo_picking_model;


use anyhow::{anyhow, Result};

use crate::args::{Args, ArgsConfig};


pub fn run(block_id: &str, args: Args, args_config: ArgsConfig) -> Result<()> {
	// NOTE: can't match on constants, so use if-else here
	if block_id == tomo_rawdata::BLOCK_ID {
		tomo_rawdata::run(args, args_config)
	} else if block_id == tomo_preprocessing::BLOCK_ID {
		tomo_preprocessing::run(args, args_config)
	} else if block_id == tomo_pure_preprocessing::BLOCK_ID {
		tomo_pure_preprocessing::run(args, args_config)
	} else if block_id == tomo_denoising::BLOCK_ID {
		tomo_denoising::run(args, args_config)
	} else if block_id == tomo_picking::BLOCK_ID {
		tomo_picking::run(args, args_config)
	} else if block_id == tomo_picking_open::BLOCK_ID {
		tomo_picking_open::run(args, args_config)
	} else if block_id == tomo_picking_closed::BLOCK_ID {
		tomo_picking_closed::run(args, args_config)
	} else if block_id == tomo_picking_model::BLOCK_ID {
		tomo_picking_model::run(args, args_config)
	} else {
		Err(anyhow!("unrecognized block id: {}", block_id))
	}
}

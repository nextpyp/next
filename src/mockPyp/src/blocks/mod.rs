
mod tomo_rawdata;
mod tomo_preprocessing;
mod tomo_pure_preprocessing;
mod tomo_denoising_training;
mod tomo_denoising_eval;
mod tomo_denoising;
mod tomo_picking;
mod tomo_segmentation_open;
mod tomo_segmentation_closed;
mod tomo_picking_open;
mod tomo_picking_closed;
mod tomo_milo_train;
mod tomo_milo_eval;
mod tomo_particles_eval;
mod tomo_particles_train;
mod tomo_coarse_refinement;
mod tomo_import;
mod tomo_reliondata;
mod tomo_session;
mod spa_rawdata;
mod spa_preprocessing;
mod spa_pure_preprocessing;
mod spa_session;


use anyhow::{anyhow, Result};

use crate::args::{Args, ArgsConfig};


pub fn run(block_id: &str, args: &mut Args, args_config: &ArgsConfig, array_element: Option<u32>) -> Result<()> {
	// NOTE: can't match on constants, so use if-else here
	if block_id == tomo_rawdata::BLOCK_ID {
		tomo_rawdata::run(args, args_config)
	} else if block_id == tomo_preprocessing::BLOCK_ID {
		tomo_preprocessing::run(args, args_config)
	} else if block_id == tomo_pure_preprocessing::BLOCK_ID {
		tomo_pure_preprocessing::run(args, args_config, array_element)
	} else if block_id == tomo_denoising_training::BLOCK_ID {
		tomo_denoising_training::run(args, args_config)
	} else if block_id == tomo_denoising_eval::BLOCK_ID {
		tomo_denoising_eval::run(args, args_config)
	} else if block_id == tomo_denoising::BLOCK_ID {
		tomo_denoising::run(args, args_config)
	} else if block_id == tomo_picking::BLOCK_ID {
		tomo_picking::run(args, args_config)
	} else if block_id == tomo_segmentation_open::BLOCK_ID {
		tomo_segmentation_open::run(args, args_config)
	} else if block_id == tomo_segmentation_closed::BLOCK_ID {
		tomo_segmentation_closed::run(args, args_config)
	} else if block_id == tomo_picking_open::BLOCK_ID {
		tomo_picking_open::run(args, args_config)
	} else if block_id == tomo_picking_closed::BLOCK_ID {
		tomo_picking_closed::run(args, args_config)
	} else if block_id == tomo_milo_train::BLOCK_ID {
		tomo_milo_train::run(args, args_config)
	} else if block_id == tomo_milo_eval::BLOCK_ID {
		tomo_milo_eval::run(args, args_config)
	} else if block_id == tomo_particles_train::BLOCK_ID {
		tomo_particles_train::run(args, args_config)
	} else if block_id == tomo_particles_eval::BLOCK_ID {
		tomo_particles_eval::run(args, args_config)
	} else if block_id == tomo_coarse_refinement::BLOCK_ID {
		tomo_coarse_refinement::run(args, args_config)
	} else if block_id == tomo_import::BLOCK_ID {
		tomo_import::run(args, args_config)
	} else if block_id == tomo_reliondata::BLOCK_ID {
		tomo_reliondata::run(args, args_config)
	} else if block_id == tomo_session::BLOCK_ID {
		tomo_session::run(args, args_config)
	} else if block_id == spa_rawdata::BLOCK_ID {
		spa_rawdata::run(args, args_config)
	} else if block_id == spa_preprocessing::BLOCK_ID {
		spa_preprocessing::run(args, args_config)
	} else if block_id == spa_pure_preprocessing::BLOCK_ID {
		spa_pure_preprocessing::run(args, args_config)
	} else if block_id == spa_session::BLOCK_ID {
		spa_session::run(args, args_config)
	} else {
		Err(anyhow!("unrecognized block id: {}", block_id))
	}
}


use anyhow::Result;
use image::Rgb;

use crate::args::{Args, ArgsConfig};
use crate::image::Image;
use crate::web::Web;

const GROUP_ID: &'static str = "tomo_preprocessing";


pub fn run(args: Args, args_config: ArgsConfig) -> Result<()> {

	// get args
	let mode = args.get("data_mode")
		.require()?
		.into_data_mode()?;

	let web = Web::new()?;
	web.write_parameters(&args, &args_config)?;

	// TODO: generate tilt series

	Ok(())
}

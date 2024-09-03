
use anyhow::Result;
use image::Rgb;

use crate::args::{Args, ArgsConfig};
use crate::image::{Image, ImageDrawing};


pub const BLOCK_ID: &'static str = "tomo-rawdata";


pub fn run(args: &mut Args, _args_config: &ArgsConfig) -> Result<()> {

	// get args
	let size = args.get_mock(BLOCK_ID, "image_size")
		.into_u32()?
		.or(512)
		.value();

	// generate the gain-corrected image
	let mut img = Image::new(size, size);
	img.draw().fill(Rgb([128, 128, 128]));
	img.draw().noise();
	img.draw().text_lines(32, Rgb([255, 255, 255]), [
		format!("Block: {}", BLOCK_ID),
		"Type: Gain Corrected".to_string(),
	]);
	img.save("gain_corrected.webp")?;

	Ok(())
}

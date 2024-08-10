
use anyhow::Result;
use image::Rgb;

use crate::args::Args;
use crate::image::Image;


pub fn run(args: Args) -> Result<()> {

	let mode = args.get("data_mode")
		.require()?
		.data_mode()?;

	// generate the gain-corrected image
	let mut img = Image::new(512, 512);
	img.fill(Rgb([128, 128, 128]));
	img.noise();
	img.text(16, 16, 32, Rgb([255, 255, 255]), format!("Mode: {:?}", mode));
	img.save("gain_corrected.webp")?;

	Ok(())
}


use anyhow::Result;
use image::Rgb;

use crate::args::Args;
use crate::image::Image;


const GROUP_ID: &'static str = "tomo_rawdata_mock";


pub fn run(args: Args) -> Result<()> {

	// get args
	let mode = args.get("data_mode")
		.require()?
		.data_mode()?;
	let size = args.get_from_group(GROUP_ID, "image_size")
		.into_u32()?
		.or(512)
		.value();

	// generate the gain-corrected image
	let mut img = Image::new(size, size);
	img.fill(Rgb([128, 128, 128]));
	img.noise();
	img.text(16, 16, 32, Rgb([255, 255, 255]), format!("Mode: {:?}", mode));
	img.save("gain_corrected.webp")?;

	Ok(())
}

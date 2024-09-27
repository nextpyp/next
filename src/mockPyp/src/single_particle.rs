
use crate::args::{Args, ArgsConfig, ArgValue};
use crate::metadata::Micrograph;
use crate::scale::{MicrographDimsUnbinned, ToValueF, ToValueU, ValueA};


pub struct PreprocessingArgs {
	pub num_micrographs: u32,
	pub pixel_size: ValueA,
	pub micrograph_dims: MicrographDimsUnbinned,
	pub micrograph_binning: u32
}

impl PreprocessingArgs {

	pub fn from(args: &mut Args, args_config: &ArgsConfig, block_id: &str) -> anyhow::Result<PreprocessingArgs> {

		let pp_args = PreprocessingArgs {
			num_micrographs: args.get_mock(block_id, "num_micrographs")
				.into_u32()?
				.or(4)
				.value(),
			pixel_size: args.get("scope_pixel")
				.into_f64()?
				.or(2.15)
				.value()
				.to_a(),
			micrograph_dims: MicrographDimsUnbinned {
				width: args.get_mock(block_id, "micrograph_width")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned(),
				height: args.get_mock(block_id, "micrograph_height")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned()
			},
			micrograph_binning: args.get_mock(block_id, "micrograph_binning")
				.into_u32()?
				.or(2)
				.value()
		};

		// write defaults not defined by the config
		args.set("scope_pixel", ArgValue::String(pp_args.pixel_size.0.to_string()));

		// set default arg values that the website will use, but we won't
		args.set_default(&args_config, "ctf", "min_res")?;

		Ok(pp_args)
	}
}


pub mod images {

	use image::Rgb;

	use crate::image::{Image, ImageDrawing};
	use crate::rand::Gaussian;

	use super::*;


	pub const DEFAULT_NOISE: Gaussian = Gaussian::new(0.0, 30.0);


	pub fn gain_corrected(block_id: &str, size: u32, noise: &Gaussian) -> Image {
		let mut img = Image::new(size, size);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: Gain Corrected".to_string(),
		]);
		img
	}

	pub fn micrograph(block_id: &str, micrograph: &Micrograph, micrograph_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: Output".to_string(),
			format!("Id: {}", &micrograph.micrograph_id),
			format!("Micrograph: {} of {}", micrograph_i + 1, pp_args.num_micrographs)
		]);
		img
	}

	pub fn ctf_find(block_id: &str, micrograph: &Micrograph, micrograph_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: CTF Find".to_string(),
			format!("Id: {}", &micrograph.micrograph_id),
			format!("Micrograph: {} of {}", micrograph_i + 1, pp_args.num_micrographs)
		]);
		img
	}
}

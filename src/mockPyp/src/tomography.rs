
use crate::args::{Args, ArgsConfig, ArgValue};
use crate::scale::{TomogramDimsUnbinned, ToValueF, ToValueU, ValueA};


pub struct PreprocessingArgs {
	pub num_tilt_series: u32,
	pub num_tilts: u32,
	pub tilt_angle_magnitude: u32,
	pub pixel_size: ValueA,
	pub tomogram_dims: TomogramDimsUnbinned,
	pub tomogram_binning: u32
}

impl PreprocessingArgs {

	pub fn from(args: &mut Args, args_config: &ArgsConfig, block_id: &str) -> anyhow::Result<PreprocessingArgs> {

		let pp_args = PreprocessingArgs {
			num_tilt_series: args.get_mock(block_id, "num_tilt_series")
				.into_u32()?
				.or(4)
				.value(),
			num_tilts: args.get_mock(block_id, "num_tilts")
				.into_u32()?
				.or(4)
				.value(),
			tilt_angle_magnitude: args.get_mock(block_id, "tilt_angle_magnitude")
				.into_u32()?
				.or(45)
				.value(),
			pixel_size: args.get("scope_pixel")
				.into_f64()?
				.or(2.15)
				.value()
				.to_a(),
			tomogram_dims: TomogramDimsUnbinned {
				width: args.get_mock(block_id, "tomogram_width")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned(),
				height: args.get_mock(block_id, "tomogram_height")
					.into_u32()?
					.or(8192)
					.value()
					.to_unbinned(),
				depth: args.get_from_group("tomo_rec", "thickness")
					.or_default(&args_config)?
					.into_u32()?
					.value()
					.to_unbinned()
			},
			tomogram_binning: args.get_from_group("tomo_rec", "binning")
				.or_default(&args_config)?
				.into_u32()?
				.value()
		};

		// write defaults not defined by the config
		args.set("scope_pixel", ArgValue::Float(pp_args.pixel_size.0));

		// set default arg values that the website will use, but we won't
		args.set_default(&args_config, "ctf", "min_res")?;

		Ok(pp_args)
	}
}


/// interpolates the tilt angle evenly in [-magnitude,magnitude]
pub fn interpolate_tilt_angle(magnitude: u32, tilt_i: u32, num_tilts: u32) -> i32 {
	if num_tilts == 0 {
		return 0;
	}
	(tilt_i*magnitude*2/(num_tilts - 1)) as i32 - magnitude as i32
}


pub mod images {

	use image::Rgb;

	use crate::image::{Image, ImageDrawing};
	use crate::metadata::TiltSeries;
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

	pub fn tilt_series(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series)
		]);
		img
	}

	pub fn sides(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: Output".to_string(),
			format!("Id: {}", &tilt_series.tilt_series_id),
			format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series)
		]);
		img
	}

	pub fn raw_tilts_montage(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise(noise);
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", block_id),
				"Type: Raw Tilt Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
			]);
		})
	}

	pub fn aligned_tilts_montage(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise(noise);
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", block_id),
				"Type: Aligned Tilt Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
			]);
		})
	}

	pub fn twod_ctf_montage(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		Image::montage(pp_args.num_tilts as usize, 512, 512, |tilt_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise(noise);
			tile.draw().tile_border(2, tilt_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", block_id),
				"Type: Tilt 2D CTF Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
				format!("Tilt: {}° ({} of {})", interpolate_tilt_angle(pp_args.tilt_angle_magnitude, tilt_i as u32, pp_args.num_tilts), tilt_i + 1, pp_args.num_tilts)
			]);
		})
	}

	pub fn reconstruction_montage(block_id: &str, tilt_series: &TiltSeries, tilt_series_i: u32, pp_args: &PreprocessingArgs, noise: &Gaussian) -> Image {
		const SLICE_FACTOR: u32 = 2;
		let tomogram_slices = (pp_args.tomogram_dims.depth.to_binned(pp_args.tomogram_binning).0/SLICE_FACTOR) as usize;
		Image::montage(tomogram_slices, 512, 512, |slice_i, mut tile| {
			tile.draw().fill(Rgb([128, 128, 128]));
			tile.draw().noise(noise);
			tile.draw().tile_border(2, slice_i);
			tile.draw().text_lines(32, Rgb([255, 255, 255]), [
				format!("Block: {}", block_id),
				"Type: Reconstruction Montage".to_string(),
				format!("Id: {}", &tilt_series.tilt_series_id),
				format!("Tilt Series: {} of {}", tilt_series_i + 1, pp_args.num_tilt_series),
				format!("Slice: {} of {}", slice_i + 1, tomogram_slices)
			]);
		})
	}

	pub fn segmentation(noise: &Gaussian) -> Image {
		const SQUARE_SIZE: u32 = 120;
		let mut img = Image::new(SQUARE_SIZE*9, SQUARE_SIZE*3);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		for thresholdi in 0 .. 9 {
			for stacki in 0 .. 3 {
				let mut square = img.sub_image(
					thresholdi*SQUARE_SIZE,
					stacki*SQUARE_SIZE,
					SQUARE_SIZE,
					SQUARE_SIZE
				);
				square.draw().border(2, Rgb([255, 255, 255]));
				square.draw().text_lines(16, Rgb([255, 255, 255]), [
					format!("Threshold: {}", thresholdi),
					format!("Stack: {}", stacki)
				])
			}
		}
		img
	}
}

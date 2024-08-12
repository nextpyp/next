
use std::ops::{Deref, DerefMut};
use std::path::Path;
use std::sync::LazyLock;

use ab_glyph::{FontRef, PxScale};
use anyhow::{Context, Result};
use image::{GenericImage, Pixel, Rgb, RgbImage};
use imageproc::definitions::Clamp;
use imageproc::drawing::draw_text_mut;
use tracing::info;

use crate::rand::Gaussian;


static FONT: LazyLock<FontRef> = LazyLock::new(|| {
	FontRef::try_from_slice(include_bytes!("UbuntuMono.ttf"))
		.expect("failed to load embedded font")
});


type Color = Rgb<u8>;


fn clamp_channel<T>(c: T) -> u8
	where
		u8: Clamp<T>
{
	// ugh, rust is just nasty sometimes ...
	<<Color as Pixel>::Subpixel as Clamp<T>>::clamp(c)
}


pub trait ImageDrawing {
	fn fill(&mut self, color: Color);
	fn noise(&mut self);
	fn text(&mut self, x: u32, y: u32, size: u32, color: Color, text: impl AsRef<str>);
	fn text_lines(&mut self, size: u32, color: Color, lines: impl IntoIterator<Item=impl AsRef<str>>);
	// other drawing commands: https://docs.rs/imageproc/latest/imageproc/drawing/index.html
}


impl<T> ImageDrawing for T
	where
		T: GenericImage<Pixel=Color>
{

	fn fill(&mut self, color: Color) {
		let (w, h) = self.dimensions();
		for y in 0 .. h {
			for x in 0 .. w {
				self.put_pixel(x, y, color);
			}
		}
	}

	fn noise(&mut self) {

		// tragically, the library's `gaussian_noise_mut` function can't operate on a GenericImage,
		// so we'll just have to implement our own gaussian noise here =(
		// it's easy enough tho

		let dist = Gaussian::new(0.0, 30.0);
		let (w, h) = self.dimensions();
		for y in 0 .. h {
			for x in 0 .. w {
				let sample = dist.sample();
				let mut p = self.get_pixel(x, y);
				for c in p.channels_mut() {
					*c = clamp_channel(*c as i32 + sample as i32);
				}
				self.put_pixel(x, y, p);
			}
		}
	}

	fn text(&mut self, x: u32, y: u32, size: u32, color: Color, text: impl AsRef<str>) {
		draw_text_mut(
			self,
			color,
			x as i32,
			y as i32,
			PxScale {
				x: size as f32,
				y: size as f32,
			},
			FONT.deref(),
			text.as_ref()
		)
	}

	fn text_lines(&mut self, size: u32, color: Color, lines: impl IntoIterator<Item=impl AsRef<str>>) {
		let margin = size/2;
		for (line_i, line) in lines.into_iter().enumerate() {
			self.text(
				margin,
				margin + (line_i as u32)*size,
				size,
				color,
				line
			);
		}
	}
}


pub struct Image {
	img: RgbImage
}

impl Image {

	fn of(img: RgbImage) -> Self {
		Self {
			img
		}
	}

	pub fn new(width: u32, height: u32) -> Self {
		Self::of(RgbImage::new(width, height))
	}

	pub fn montage<F>(count: usize, tile_width: u32, tile_height: u32, mut tile_renderer: F) -> Result<Self>
		where
			for<'s,'i> F: FnMut(usize, SubImage<'i>) -> Result<()>
	{

		let len_x = (count as f64).sqrt().ceil() as u32;
		let len_y = ((count as f64)/(len_x as f64)).ceil() as u32;

		let mut montage = Self::new(len_x*tile_width, len_y*tile_height);

		for tile_i in 0 .. count {
			let y = tile_i as u32/len_x;
			let x = tile_i as u32 % len_x;

			let sub_image = montage.sub_image(
				x*tile_width,
				y*tile_height,
				tile_width,
				tile_height
			);

			// render the tile
			tile_renderer(tile_i, sub_image)?;
		}

		Ok(montage)
	}

	pub fn draw(&mut self) -> &mut impl ImageDrawing {
		&mut self.img
	}

	pub fn sub_image(&mut self, x: u32, y: u32, w: u32, h: u32) -> SubImage {
		SubImage {
			sub_img: self.img.sub_image(x, y, w, h)
		}
	}

	pub fn save(&self, path: impl AsRef<Path>) -> Result<()> {
		let path = path.as_ref();
		self.img.save(path)
			.context(format!("Failed to save image to: {}", path.to_string_lossy()))?;
		info!("Saved image: {}", path.to_string_lossy());
		Ok(())
	}
}


pub struct SubImage<'a> {
	sub_img: image::SubImage<&'a mut RgbImage>
}

impl<'a> SubImage<'a> {

	pub fn draw(&mut self) -> &mut (impl ImageDrawing + 'a) {
		self.sub_img.deref_mut()
	}
}

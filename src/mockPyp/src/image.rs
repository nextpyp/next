
use std::ops::{Deref, DerefMut};
use std::path::Path;
use std::sync::LazyLock;

use ab_glyph::{FontRef, PxScale};
use anyhow::{Context, Result};
use image::{GenericImage, Pixel, Rgb, Rgba, RgbImage};
use imageproc::definitions::Clamp;
use imageproc::drawing::{draw_filled_rect_mut, draw_filled_circle_mut, draw_text_mut, Canvas};
use imageproc::rect::Rect;
use tracing::info;

use crate::rand::Gaussian;


static FONT: LazyLock<FontRef> = LazyLock::new(|| {
	FontRef::try_from_slice(include_bytes!("UbuntuMono.ttf"))
		.expect("failed to load embedded font")
});


type Color = Rgb<u8>;
type BlendedColor = Rgba<u8>;


fn clamp_channel<T>(c: T) -> u8
	where
		u8: Clamp<T>
{
	// ugh, rust is just nasty sometimes ...
	<<Color as Pixel>::Subpixel as Clamp<T>>::clamp(c)
}


pub trait ImageDrawing {
	fn fill(&mut self, color: Color);
	fn fill_circle_blended(&mut self, x: u32, y: u32, r: u32, color: BlendedColor);
	fn noise(&mut self, dist: &Gaussian);
	fn text(&mut self, x: u32, y: u32, size: u32, color: Color, text: impl AsRef<str>);
	fn text_lines(&mut self, size: u32, color: Color, lines: impl IntoIterator<Item=impl AsRef<str>>);
	fn border(&mut self, size: u32, color: Color);
	fn tile_border(&mut self, size: u32, tile_i: usize);
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

	fn fill_circle_blended(&mut self, x: u32, y: u32, r: u32, color: BlendedColor) {
		let mut blended_img = BlendRgba(self);
		draw_filled_circle_mut(
			&mut blended_img,
			(x as i32, y as i32),
			r as i32,
			color
		);
	}

	fn noise(&mut self, dist: &Gaussian) {

		// tragically, the library's `gaussian_noise_mut` function can't operate on a GenericImage,
		// so we'll just have to implement our own gaussian noise here =(
		// it's easy enough tho

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

	fn border(&mut self, size: u32, color: Color) {
		draw_filled_rect_mut(self, Rect::at(0, 0).of_size(self.width(), size), color);
		draw_filled_rect_mut(self, Rect::at((self.width() - size) as i32, 0).of_size(size, self.height()), color);
		draw_filled_rect_mut(self, Rect::at(0, (self.height() - size) as i32).of_size(self.width(), size), color);
		draw_filled_rect_mut(self, Rect::at(0, 0).of_size(size, self.height()), color);
	}

	fn tile_border(&mut self, size: u32, tile_i: usize) {

		// Montages are 2D square tilings, so for each tile to have a different border color than its neighbor,
		// we need at least 4 unique colors. However, it's hard to actually compute that coloring. =(
		// It's still non-trivial even if we use more than 4 colors, even on a simple graph like a square grid,
		// so let's just do something really dumb and call it good enough. =P

		const COLORS: [Color; 11] = [
			Rgb([255, 0, 0]),
			Rgb([0, 255, 0]),
			Rgb([0, 0, 255]),
			Rgb([255, 255, 0]),
			Rgb([255, 0, 255]),
			Rgb([0, 255, 255]),
			Rgb([128, 0, 0]),
			Rgb([0, 128, 0]),
			Rgb([0, 0, 128]),
			Rgb([128, 128, 0]),
			Rgb([128, 0, 128]),
			//Rgb([0, 128, 128]) // skip the last color in the pattern, to have a prime number of colors
		];

		self.border(size, COLORS[tile_i % COLORS.len()]);
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

	pub fn montage<F>(count: usize, tile_width: u32, tile_height: u32, mut tile_renderer: F) -> Self
		where
			for<'s,'i> F: FnMut(usize, SubImage<'i>)
	{
		let result = Self::try_montage(count, tile_width, tile_height, |tile_i, sub_image| {
			tile_renderer(tile_i, sub_image);
			Ok(())
		});

		result.unwrap()
		// PANIC SAFETY: tile_renderer is infallible
	}

	pub fn try_montage<F>(count: usize, tile_width: u32, tile_height: u32, mut tile_renderer: F) -> Result<Self>
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


pub struct BlendRgba<T>(T);

impl<'a,T> Canvas for BlendRgba<&'a mut T>
	where
		T: GenericImage<Pixel=Color>
{
	type Pixel = BlendedColor;

	fn dimensions(&self) -> (u32, u32) {
		self.0.dimensions()
	}

	fn get_pixel(&self, x: u32, y: u32) -> Self::Pixel {
		let p = self.0.get_pixel(x, y);
		Rgba([p[0], p[1], p[2], 255])
	}

	fn draw_pixel(&mut self, x: u32, y: u32, color: Self::Pixel) {
		let mut p = self.get_pixel(x, y);
		p.blend(&color);
		self.0.put_pixel(x, y, Rgb([p[0], p[1], p[2]]));
	}
}

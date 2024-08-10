
use std::ops::Deref;
use std::path::Path;
use std::sync::LazyLock;

use ab_glyph::{FontRef, PxScale};
use anyhow::{Context, Result};
use image::{Rgb, RgbImage};
use imageproc::drawing::draw_text_mut;
use imageproc::noise::gaussian_noise_mut;
use tracing::info;


static FONT: LazyLock<FontRef> = LazyLock::new(|| {
	FontRef::try_from_slice(include_bytes!("UbuntuMono.ttf"))
		.expect("failed to load embedded font")
});


pub struct Image {
	img: RgbImage
}

type Color = Rgb<u8>;

impl Image {

	pub fn new(width: u32, height: u32) -> Self {
		Self {
			img: RgbImage::new(width, height)
		}
	}

	pub fn fill(&mut self, color: Color) {
		for p in self.img.pixels_mut() {
			*p = color;
		}
	}

	pub fn noise(&mut self) {
		gaussian_noise_mut(&mut self.img, 0.0, 30.0, 12345);
	}

	pub fn text(&mut self, x: u32, y: u32, size: u32, color: Color, text: impl AsRef<str>) {
		draw_text_mut(
			&mut self.img,
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

	// other drawing commands: https://docs.rs/imageproc/latest/imageproc/drawing/index.html

	pub fn save(&self, path: impl AsRef<Path>) -> Result<()> {
		let path = path.as_ref();
		self.img.save(path)
			.context(format!("Failed to save image to: {}", path.to_string_lossy()))?;
		info!("Saved image: {}", path.to_string_lossy());
		Ok(())
	}
}

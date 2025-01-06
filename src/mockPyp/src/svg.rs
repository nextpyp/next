
use std::ffi::OsStr;
use std::fs::File;
use std::path::Path;

use anyhow::{Context, Result};
use flate2::Compression;
use flate2::write::GzEncoder;
use svg::{Document, Node};
use svg::node::element::{Rectangle, Text};
use svg::node::Value;

use crate::info;
use crate::web::Web;


#[derive(Debug, Clone, Copy)]
pub struct Rgb(pub u32, pub u32, pub u32);

impl Rgb {

	pub fn to_string(&self) -> String {
		format!("rgb({},{},{})", self.0, self.1, self.2)
	}
}

impl Into<Value> for Rgb {

	fn into(self) -> Value {
		self.to_string().into()
	}
}


pub struct SvgImage {
	doc: Document
}

impl SvgImage {

	pub fn new(w: u32, h: u32) -> Self {
		Self {
			doc: Document::new()
				.set("viewBox", (0, 0, w, h))
		}
	}

	pub fn draw(&mut self) -> SvgDrawing {
		SvgDrawing {
			doc: &mut self.doc
		}
	}

	pub fn save(&self, web: &Web, path: impl AsRef<Path>) -> Result<()> {

		let path = path.as_ref();

		if path.extension() == Some(OsStr::new("svgz")) {

			// compress the output
			let mut file = File::create(&path)
				.context(format!("Failed to create file: {}", path.to_string_lossy()))?;
			let mut encoder = GzEncoder::new(&mut file, Compression::default());
			svg::write(&mut encoder, &self.doc)
				.context(format!("Failed to save compressed image to: {}", path.to_string_lossy()))?;

		} else {
			svg::save(path, &self.doc)
				.context(format!("Failed to save image to: {}", path.to_string_lossy()))?;
		}

		info!(web, "Saved image: {}", path.to_string_lossy());
		Ok(())
	}
}


pub struct SvgDrawing<'a> {
	doc: &'a mut Document
}

impl <'a> SvgDrawing<'a> {

	pub fn fill_rect(&mut self, x: u32, y: u32, w: u32, h: u32, color: Rgb) {
		self.doc.append(Rectangle::new()
			.set("fill", color)
			.set("x", x)
			.set("y", y)
			.set("width", w)
			.set("height", h)
		);
	}

	pub fn text(&mut self, x: u32, y: u32, size: u32, color: Rgb, text: impl Into<String>) {
		self.doc.append(Text::new(text)
			.set("fill", color)
			.set("font-size", format!("{}px", size))
			.set("x", x)
			.set("y", y)
		);
	}

	pub fn text_lines(&mut self, size: u32, color: Rgb, lines: impl IntoIterator<Item=impl AsRef<str>>) {
		let margin = size/2;
		for (line_i, line) in lines.into_iter().enumerate() {
			self.text(
				margin,
				margin + (line_i as u32)*size + size,
				size,
				color,
				line.as_ref()
			);
		}
	}
}

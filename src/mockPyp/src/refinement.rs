
use std::ops::RangeInclusive;

use anyhow::{Context, Result};

use crate::rand::Gaussian;


#[derive(Debug, Clone, PartialEq)]
pub struct Reconstruction {
	pub id: String,
	pub class_num: u32,
	pub iteration: u32,
	pub fsc: FSC,
	pub metadata: ReconstructionMetadata,
	pub plots: ReconstructionPlots
}


/// Fourier Shell Correlation
#[derive(Debug, Clone, PartialEq)]
pub struct FSC {
	/// inner: main track + each iteration
	pub samples: Vec<Vec<f64>>
}

impl FSC {

	pub fn sample(num_samples: usize, num_iters: usize, start: f64, target: f64) -> Self {

		// start each iteration at 1
		let mut vals = (0 .. num_iters)
			.map(|_| 1.0)
			.collect::<Vec<_>>();

		let dist = Gaussian::new(0.01, 0.01);

		let mut samples = Vec::with_capacity(num_samples);
		for s in 0 .. num_samples {
			let mut iters = Vec::with_capacity(1 + num_iters);

			// let the main value asymptotically approach the target
			let m = (start - target)/(s as f64 + 1.0);
			iters.push(m);

			// drop each iteration value a bit with each sample
			for i in 0 .. num_iters {
				vals[i] = (vals[i] - dist.sample()).max(0.0);
				iters.push(vals[i]);
			}

			samples.push(iters);
		}

		Self {
			samples
		}
	}
}


#[derive(Debug, Clone, PartialEq)]
pub struct ReconstructionMetadata {
	pub particles_total: f64,
	pub particles_used: f64,
	pub phase_residual: f64,
	pub occ: f64,
	pub logp: f64,
	pub sigma: f64
}

impl ReconstructionMetadata {

	pub fn sample() -> Self {
		Self {
			particles_total: Gaussian::new(100.0, 20.0).sample(),
			particles_used: Gaussian::new(100.0, 20.0).sample(),
			phase_residual: Gaussian::new(5.0, 1.0).sample(),
			occ: Gaussian::new(6.0, 1.0).sample(),
			logp: Gaussian::new(7.0, 1.0).sample(),
			sigma: Gaussian::new(8.0, 1.0).sample()
		}
	}
}


#[derive(Debug, Clone, PartialEq)]
pub struct ReconstructionPlots {
	pub def_rot_histogram: Heatmap,
	pub def_rot_scores: Heatmap,
	pub rot_hist: Histogram,
	pub def_hist: Histogram,
	pub scores_hist: Histogram,
	pub occ_hist: Histogram,
	pub logp_hist: Histogram,
	pub sigma_hist: Histogram,
	pub occ_plot: Option<Vec<f64>>
}

impl ReconstructionPlots {

	pub fn sample(occ: bool) -> Self {
		Self {
			def_rot_histogram: Heatmap::sample_isotropic(10, 10, 2, 2),
			def_rot_scores: Heatmap::sample_isotropic(10, 10, 8, 8),
			rot_hist: Histogram::sample(10, 1.0 ..= 9.0, 2.0, 2.0, 100),
			def_hist: Histogram::sample(10, 1.0 ..= 9.0, 3.0, 2.0, 100),
			scores_hist: Histogram::sample(10, 1.0 ..= 9.0, 4.0, 2.0, 100),
			occ_hist: Histogram::sample(10, 1.0 ..= 9.0, 5.0, 2.0, 100),
			logp_hist: Histogram::sample(10, 1.0 ..= 9.0, 6.0, 2.0, 100),
			sigma_hist: Histogram::sample(10, 1.0 ..= 9.0, 7.0, 2.0, 100),
			occ_plot: if occ {
				let dist = Gaussian::new(5.0, 1.0);
				let samples = (0 .. 20)
					.map(|_| dist.sample())
					.collect();
				Some(samples)
			} else {
				None
			}
		}
	}
}


#[derive(Debug, Clone, PartialEq)]
pub struct Heatmap {
	pub pixels: Vec<Vec<f64>>
}

impl Heatmap {

	pub fn sample_isotropic(nx: usize, ny: usize, cx: usize, cy: usize) -> Self {

		let max_d = dist2_l2(0, 0, nx, ny);

		let mut p = Vec::with_capacity(ny);
		for y in 0 .. ny {
			let mut row = Vec::with_capacity(nx);
			for x in 0 .. nx {
				let v = max_d - dist2_l2(cx, cy, x, y);
				row.push(v);
			}
			p.push(row);
		}

		Self {
			pixels: p
		}
	}
}


fn dist(a: usize, b: usize) -> usize {
	if a > b {
		a - b
	} else {
		b - a
	}
}

fn _dist2_l1(
	x1: usize, y1: usize,
	x2: usize, y2: usize
) -> usize {
	let dx = dist(x1, x2);
	let dy = dist(y1, y2);
	dx + dy
}

fn dist2_l2(
	x1: usize, y1: usize,
	x2: usize, y2: usize
) -> f64 {
	let dx = dist(x1, x2);
	let dy = dist(y1, y2);
	let d2 = (dx*dx + dy*dy) as f64;
	d2.sqrt()
}



#[derive(Debug, Clone, PartialEq)]
pub struct Histogram {
	pub n: Vec<f64>,
	pub bins: Vec<f64>
}

impl Histogram {

	pub fn sample(num_bins: usize, range: RangeInclusive<f64>, mean: f64, stddev: f64, num_samples: usize) -> Self {

		// define bins
		let bins = (0 ..= num_bins).into_iter()
			.map(|i| range.start() + (range.end() - range.start())*(i as f64)/(num_bins as f64))
			.collect::<Vec<_>>();
		let mut n = (0 .. num_bins)
			.map(|_| 0.0)
			.collect::<Vec<_>>();

		// sample the distribution, bucket into a histogram
		let dist = Gaussian::new(mean, stddev);
		for _ in 0 .. num_samples {
			let s = dist.sample();
			let sn = (s - range.start())/(range.end() - range.start())
				.clamp(0.0 , 1.0);
			let i = ((sn*(num_bins as f64)) as usize)
				.clamp(0, num_bins - 1);
			n[i] += 1.0;
		}
		
		// TODO: these histograms don't come out looking bell-shaped,
		//       so there must be a bug here ... somewhere

		Self {
			n,
			bins
		}
	}
}


pub fn filename_fragment(class_num: u32, iter_num: Option<u32>) -> Result<String> {

	// some pyp file paths copy the parent folder name for some reason
	let cwd = std::env::current_dir()
		.context("Failed to get current folder")?;
	let name = cwd.file_name()
		.context("Current folder has no filename")?
		.to_string_lossy();

	let iter = match iter_num {
		Some(iter_num) => format!("_{iter_num:02}"),
		None => "".to_string()
	};

	Ok(format!("{name}_r{class_num:02}{iter}"))
}


pub mod images {

	use image::Rgb;

	use crate::image::{Image, ImageDrawing};
	use crate::rand::Gaussian;


	pub const DEFAULT_NOISE: Gaussian = Gaussian::new(0.0, 30.0);


	pub fn reconstruction(block_id: &str, iter_num: u32, class_num: u32, noise: &Gaussian) -> Image {
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(noise);
		img.draw().text_lines(32, Rgb([255, 255, 255]), [
			format!("Block: {}", block_id),
			"Type: Reconstruction".to_string(),
			format!("Iteration: {}", iter_num),
			format!("Class: {}", class_num)
		]);
		img
	}
}


pub mod maps {

	use std::path::Path;

	use crate::info;
	use crate::mrc::Mrc;
	use crate::web::Web;

	use super::*;


	pub enum MapKind {
		Full,
		Crop,
		Half1,
		Half2
	}


	pub fn save(web: &Web, dir_maps: &Path, iter_num: Option<u32>, class_num: u32, kind: MapKind) -> Result<()> {

		let fragment = filename_fragment(class_num, iter_num)?;

		let mut mrc = Mrc::new(16, 16, 16);
		mrc.cube(iter_num.unwrap_or(8), class_num, 8);

		let suffix = match kind {
			MapKind::Full => "",
			MapKind::Crop => "_crop",
			MapKind::Half1 => "_half1",
			MapKind::Half2 => "_half2"
		};
		let vol_path = dir_maps.join(format!("{fragment}{suffix}.mrc"));
		info!(web, "Saved Volume: {}", vol_path.to_string_lossy());
		mrc.save(vol_path)?;

		Ok(())
	}
}

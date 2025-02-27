
use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};

use crate::info;
use crate::args::{Args, ArgsConfig, ArgValue};
use crate::image::{Image, ImageDrawing};
use crate::mrc::Mrc;
use crate::svg::SvgImage;
use crate::tomography::images::DEFAULT_NOISE;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-drgn-eval";

const GROUP_ANALYZE: &'static str = "tomodrgn_analyze";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	// get args
	let skipumap = args.get_from_group(GROUP_ANALYZE, "skipumap")
		.into_bool()?
		.or(false)
		.value();
	let pc = args.get_from_group(GROUP_ANALYZE, "pc")
		.into_u32()?
		.or(3)
		.value();
	let ksample = args.get_from_group(GROUP_ANALYZE, "ksample")
		.into_u32()?
		.or(5)
		.value();

	// send parameters to the website
	args.set_from_group(GROUP_ANALYZE, "skipumap", ArgValue::Bool(skipumap));
	args.set_from_group(GROUP_ANALYZE, "pc", ArgValue::Int(pc as i64));
	args.set_from_group(GROUP_ANALYZE, "ksample", ArgValue::Int(ksample as i64));
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	let dir_train = PathBuf::from("train");
	fs::create_dir_all(&dir_train)
		.context("Failed to create train dir")?;
	let dir_kmeans = dir_train.join(format!("kmeans{ksample}"));
	fs::create_dir_all(&dir_kmeans)
		.context("Failed to create kmeans dir")?;

	// figure out the mode
	let mode = match skipumap {
		false => Mode::UMAP {
			num_classes: ksample
		},
		true => Mode::PCA {
			num_dimensions: pc,
			num_classes: ksample
		}
	};

	match mode {

		Mode::UMAP { num_classes } => {
			info!(web, "UMAP: num_classes={num_classes}");
			plot_img(web, &dir_kmeans, "Resolution", mode.name(), "z_umap_scatter_subplotkmeanslabel".to_string())?;
			plot_img(web, &dir_kmeans, "Occupancy", mode.name(), "z_umap_scatter_colorkmeanslabel".to_string())?;
			generate_classes(web, &dir_kmeans, mode.name(), None, num_classes)?;
		}

		Mode::PCA { num_dimensions, num_classes } => {
			info!(web, "PCA: num_dimensions={num_dimensions}, num_classes={num_classes}");
			plot_img(web, &dir_kmeans, "Resolution", mode.name(), "z_pca_scatter_subplotkmeanslabel".to_string())?;
			plot_img(web, &dir_kmeans, "Occupancy", mode.name(), "z_pca_scatter_colorkmeanslabel".to_string())?;
			for dim in 1 ..= num_dimensions {

				let dir_dim = dir_train.join(format!("pc{dim}"));
				fs::create_dir_all(&dir_dim)
					.context("Failed to create dimension dir")?;

				generate_classes(web, &dir_dim, mode.name(), Some(dim), num_classes)?;
			}
		}
	}

	Ok(())
}


enum Mode {
	UMAP {
		num_classes: u32
	},
	PCA {
		num_dimensions: u32,
		num_classes: u32
	}
}

impl Mode {

	fn name(&self) -> &'static str {
		match self {
			Self::UMAP { .. } => "UMAP",
			Self::PCA { .. } => "PCA"
		}
	}
}


fn generate_classes(web: &Web, dir: &Path, mode_name: &'static str, dim: Option<u32>, num_classes: u32) -> Result<()> {

	use image::Rgb;

	// generate class files
	for class_num in 1 ..= num_classes {

		let filename = format!("vol_{class_num:03}");

		// generate the volume itself
		let mut mrc = Mrc::new(16, 16, 16);
		mrc.cube(dim.unwrap_or(8), class_num, 8);
		let vol_path = dir.join(format!("{filename}.mrc"));
		info!(web, "Saved Volume: {}", vol_path.to_string_lossy());
		mrc.save(vol_path)?;

		// generate the volume image
		let mut img = Image::new(512, 512);
		img.draw().fill(Rgb([128, 128, 128]));
		img.draw().noise(&DEFAULT_NOISE);
		let mut lines = vec![
			format!("Block: {}", BLOCK_ID),
			format!("Mode: {}", mode_name),
			"Type: Volume".to_string()
		];
		if let Some(dim) = &dim {
			lines.push(format!("Dimension: {}", dim));
		}
		lines.push(format!("Class: {}", class_num));
		img.draw().text_lines(32, Rgb([255, 255, 255]), lines);
		img.save(web, dir.join(format!("{filename}.webp")))?;
	}

	Ok(())
}


fn plot_img(web: &Web, dir: &Path, title: impl AsRef<str>, mode_name: &'static str, filename: impl AsRef<str>) -> Result<()> {

	use crate::svg::Rgb;

	let mut img = SvgImage::new(512, 512);
	img.draw().fill_rect(0, 0, 512, 512, Rgb(128, 128, 128));
	img.draw().text_lines(32, Rgb(255, 255, 255), [
		format!("Block: {}", BLOCK_ID),
		format!("Mode: {}", mode_name),
		format!("Type: {}", title.as_ref())
	]);
	img.save(web, dir.join(format!("{}.svgz", filename.as_ref())))
}

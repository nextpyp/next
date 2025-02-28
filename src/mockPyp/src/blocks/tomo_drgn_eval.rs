
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

	info!(web, "skipumap={skipumap}");
	info!(web, "pc={pc}");
	info!(web, "ksample={ksample}");

	// alias parameters so they make more sense in this context
	let num_dimensions = pc;
	let num_classes = ksample;

	// create subfolders
	let dir_train = PathBuf::from("train");
	fs::create_dir_all(&dir_train)
		.context("Failed to create train dir")?;
	let dir_kmeans = dir_train.join(format!("kmeans{ksample}"));
	fs::create_dir_all(&dir_kmeans)
		.context("Failed to create kmeans dir")?;

	if !skipumap {

		// generate static plots
		plot_img(web, &dir_kmeans, "z_umap_scatter_subplotkmeanslabel".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_umap_scatter_colorkmeanslabel".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_umap_scatter_annotatekmeans".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_umap_hexbin_annotatekmeans".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_pca_scatter_subplotkmeanslabel".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_pca_scatter_colorkmeanslabel".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_pca_scatter_colorkmeanslabel".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_pca_scatter_annotatekmeans".to_string(), None)?;
		plot_img(web, &dir_kmeans, "z_pca_hexbin_annotatekmeans".to_string(), None)?;
		plot_img(web, &dir_kmeans, "tomogram_label_distribution".to_string(), None)?;

		generate_classes(web, &dir_kmeans, None, num_classes)?;
	}

	for dim in 1 ..= num_dimensions {

		let dir_dim = dir_train.join(format!("pc{dim}"));
		fs::create_dir_all(&dir_dim)
			.context("Failed to create dimension dir")?;

		if dim == 1 {
			plot_img(web, &dir_dim, "z_umap_hexbin_annotatepca".to_string(), None)?;
			plot_img(web, &dir_dim, "z_umap_scatter_annotatepca".to_string(), None)?;
			plot_img(web, &dir_dim, "z_pca_hexbin_annotatepca".to_string(), None)?;
			plot_img(web, &dir_dim, "z_pca_scatter_annotatepca".to_string(), None)?;
		}

		plot_img(web, &dir_dim, "z_umap_colorlatentpca".to_string(), Some(dim))?;

		generate_classes(web, &dir_dim, Some(dim), num_classes)?;
	}

	Ok(())
}


fn generate_classes(web: &Web, dir: &Path, dim: Option<u32>, num_classes: u32) -> Result<()> {

	use image::Rgb;

	// generate class files
	for class_num in 1 ..= num_classes {

		let filename = format!("vol_{:03}", class_num - 1);

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
			"Type: Volume".to_string()
		];
		if let Some(dim) = &dim {
			lines.push("Mode: PCA".to_string());
			lines.push(format!("Dimension: {}", dim));
		} else {
			lines.push("Mode: UMAP".to_string());
		}
		lines.push(format!("Class: {}", class_num));
		img.draw().text_lines(32, Rgb([255, 255, 255]), lines);
		img.save(web, dir.join(format!("{filename}.webp")))?;
	}

	Ok(())
}


fn plot_img(web: &Web, dir: &Path, filename: impl AsRef<str>, dim: Option<u32>) -> Result<()> {

	use crate::svg::Rgb;

	let mut img = SvgImage::new(512, 512);
	img.draw().fill_rect(0, 0, 512, 512, Rgb(128, 128, 128));
	let mut lines = vec![
		format!("Block: {}", BLOCK_ID),
	];
	let (x, mut y) = img.draw().text_line_pos(32, 1);
	if let Some(dim) = dim {
		lines.push(format!("Dimension: {dim}"));
		y += 32;
	}
	img.draw().text_lines(32, Rgb(255, 255, 255), lines);
	img.draw().text(x, y, 18, Rgb(255, 255, 255), format!("Filename: {}", filename.as_ref()));
	img.save(web, dir.join(format!("{}.svgz", filename.as_ref())))
}

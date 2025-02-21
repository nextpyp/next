
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


pub const BLOCK_ID: &'static str = "tomo-drgn-train";

const GROUP_CONVERGENCE: &'static str = "tomodrgn_vae_convergence";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	// get args
	let final_maxima = args.get_from_group(GROUP_CONVERGENCE, "final_maxima")
		.into_u32()?
		.or(3)
		.value();
	let epoch_index = args.get_from_group(GROUP_CONVERGENCE, "epoch_index")
		.into_str()?
		.try_map(|v| {
			v.map(|v| {
				v.parse::<u32>()
					.context(format!("not an int: {v}"))
			}).transpose()
		})?
		.or(15)
		.value();
	let epoch_interval = args.get_from_group(GROUP_CONVERGENCE, "epoch_interval")
		.into_u32()?
		.or(5)
		.value();

	// send parameters to the website
	args.set_from_group(GROUP_CONVERGENCE, "final_maxima", ArgValue::Int(final_maxima as i64));
	web.write_parameters(&args, &args_config)?;

	// create subfolders
	let dir_train = PathBuf::from("train");
	fs::create_dir_all(&dir_train)
		.context("Failed to create train dir")?;
	let dir_convergence = dir_train.join("convergence");
	fs::create_dir_all(&dir_convergence)
		.context("Failed to create convergence dir")?;
	let dir_plots = dir_convergence.join("plots");
	fs::create_dir_all(&dir_plots)
		.context("Failed to create plots dir")?;

	// do the iterations based on the epochs
	let mut iter = 0;
	for epoch in 0 ..= epoch_index {
		if epoch % epoch_interval != 0 {
			continue;
		}

		info!(web, "epoch {epoch}, iteration {iter}");

		plot_img(web, &dir_plots, format!("09_pairwise_CC_matrix_epoch-{}", epoch))?;

		let dir_iter = dir_convergence.join(format!("vols.{}", epoch));
		fs::create_dir_all(&dir_iter)
			.context("Failed to create iteration dir")?;

		// generate class files
		for class_num in 1 ..= final_maxima {

			let filename = format!("vol_{class_num:03}");

			// generate the volume itself
			let mut mrc = Mrc::new(16, 16, 16);
			mrc.cube(iter, class_num, 8);
			let vol_path = dir_iter.join(format!("{filename}.mrc"));
			info!(web, "Saved Volume: {}", vol_path.to_string_lossy());
			mrc.save(vol_path)?;

			// generate the volume image
			{
				use image::Rgb;

				let mut img = Image::new(512, 512);
				img.draw().fill(Rgb([128, 128, 128]));
				img.draw().noise(&DEFAULT_NOISE);
				img.draw().text_lines(32, Rgb([255, 255, 255]), [
					format!("Block: {}", BLOCK_ID),
					"Type: Volum".to_string(),
					format!("Iteration: {}", iter),
					format!("Epoch: {}", epoch),
					format!("Class: {}", class_num)
				]);
				img.save(web, dir_iter.join(format!("{filename}.webp")))?;
			}
		}

		web.write_tomo_drgn_convergence(epoch)?;
		iter += 1;
	}

	// create the distribution plot, which copies the parent folder name for some reason
	let cwd = std::env::current_dir()
		.context("Failed to get current folder")?;
	let name = cwd.file_name()
		.context("Current folder has no filename")?
		.to_string_lossy();
	plot_img(web, &dir_train, format!("{name}_particles.star_particle_uid_ntilt_distribution"))?;

	// create the summary plots
	plot_img(web, &dir_plots, "00_total_loss")?;
	plot_img(web, &dir_plots, "01_encoder_pcs")?;
	plot_img(web, &dir_plots, "02_encoder_umaps")?;
	plot_img(web, &dir_plots, "03_encoder_latent_vector_shifts")?;
	plot_img(web, &dir_plots, "04_decoder_UMAP-sketching")?;
	plot_img(web, &dir_plots, "05_decoder_maxima-sketch-consistency")?;
	plot_img(web, &dir_plots, "06_decoder_CC")?;
	plot_img(web, &dir_plots, "07_decoder_FSC")?;
	plot_img(web, &dir_plots, "08_decoder_FSC-nyquist")?;

	Ok(())
}


fn plot_img(web: &Web, dir: &Path, name: impl AsRef<str>) -> Result<()> {

	use crate::svg::Rgb;

	let name = name.as_ref();
	let mut img = SvgImage::new(512, 512);
	img.draw().fill_rect(0, 0, 512, 512, Rgb(128, 128, 128));
	img.draw().text_lines(32, Rgb(255, 255, 255), [
		format!("Block: {}", BLOCK_ID),
		format!("Type: {}", name)
	]);
	img.save(web, dir.join(format!("{}.svgz", name)))
}


use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};

use crate::info;
use crate::args::{Args, ArgsConfig, ArgValue};
use crate::particles::{read_next_tomo_particles};
use crate::refinement::{filename_fragment, FSC, images, maps, Reconstruction, ReconstructionMetadata, ReconstructionPlots};
use crate::refinement::maps::MapKind;
use crate::web::Web;


pub const BLOCK_ID: &'static str = "tomo-coarse-refinement";


pub fn run(web: &Web, args: &mut Args, args_config: &ArgsConfig) -> Result<()> {

	let class_num = args.get_from_group("class", "num")
		.into_u32()?
		.or(3)
		.value();
	let refine_maxiter = args.get_from_group("refine", "maxiter")
		.into_u32()?
		.or(5)
		.value();

	info!(web, "class_num = {class_num}");
	info!(web, "refine_maxiter = {refine_maxiter}");

	let classes = 1 ..= class_num;
	let iterations = 2 ..= refine_maxiter;
	// iterations start numbering at 2 ... for some reason

	// try to read the manual particles, if any
	match read_next_tomo_particles()? {
		Some(tilt_series_particles) => {
			let num_particles = tilt_series_particles.iter()
				.map(|(_, tilt_series)| tilt_series.len())
				.sum::<usize>();
			info!(web, "Read {} manual particles from {} tilt series", num_particles, tilt_series_particles.len());
		}
		_ => info!(web, "No manual particles")
	}

	// send parameters to the website
	args.set_from_group("class", "num", ArgValue::Int(class_num as i64));
	args.set_from_group("refine", "maxiter", ArgValue::Int(refine_maxiter as i64));
	web.write_parameters(&args, &args_config)?;

	let dir_maps = PathBuf::from("frealign/maps");
	fs::create_dir_all(&dir_maps)
		.context("Failed to create maps dir")?;

	for i in iterations.clone() {
		for c in classes.clone() {

			let reconstruction = Reconstruction {
				id: format!("rec_{i}_{c}"),
				class_num: c,
				iteration: i,
				fsc: FSC::sample(100, (i - 1) as usize, 300.0, 2.5),
				metadata: ReconstructionMetadata::sample(),
				plots: ReconstructionPlots::sample(class_num > 1)
			};

			let filename_fragment = filename_fragment(c, Some(i))?;

			// generate images
			images::reconstruction(BLOCK_ID, i, c, &images::DEFAULT_NOISE)
				.save(web, dir_maps.join(format!("{filename_fragment}_map.webp")))?;

			// generate maps
			maps::save(web, &dir_maps, Some(i), c, MapKind::Full)?;
			maps::save(web, &dir_maps, Some(i), c, MapKind::Crop)?;
			if &i == iterations.end() {
				maps::save(web, &dir_maps, Some(i), c, MapKind::Half1)?;
				maps::save(web, &dir_maps, Some(i), c, MapKind::Half2)?;
			}

			// tell the website
			web.write_reconstruction(&reconstruction)?;
		}
	}

	Ok(())
}

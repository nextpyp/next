
use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};

use crate::metadata::Particle3D;
use crate::scale::{ValueBinnedF, ValueBinnedU};


pub fn read_tomo_particles() -> Result<Vec<(String,Vec<Particle3D>)>> {

	let images_path = PathBuf::from("train/particles_images.txt");
	let images_content = fs::read_to_string(&images_path)
		.context(format!("Failed to read {}", images_path.to_string_lossy()))?;

	// skip the first line of the images file: it's just a header
	let mut images_iter = images_content.lines();
	images_iter.next();

	let mut tilt_series_particles = Vec::<(String,Vec<Particle3D>)>::new();
	for image in images_iter {
		let Some(tilt_series_id) = image.splitn(2, '\t')
			.next()
		else { continue; };

		let coords_path = PathBuf::from(format!("next/{}.next", tilt_series_id));
		let coords_content = fs::read_to_string(&coords_path)
			.context(format!("Failed to read {}", coords_path.to_string_lossy()))?;
		let mut particles = Vec::<Particle3D>::new();
		for (linei, line) in coords_content.lines().enumerate() {
			let mut parts = line.splitn(3, '\t');
			let x = parts.next()
				.context(format!("Missing x coord, line {} of {}", linei, coords_path.to_string_lossy()))?
				.parse::<u32>()
				.context(format!("Failed to read x coord, line {} of {}", linei, coords_path.to_string_lossy()))?;
			let y = parts.next()
				.context(format!("Missing y coord, line {} of {}", linei, coords_path.to_string_lossy()))?
				.parse::<u32>()
				.context(format!("Failed to read y coord, line {} of {}", linei, coords_path.to_string_lossy()))?;
			let z = parts.next()
				.context(format!("Missing z coord, line {} of {}", linei, coords_path.to_string_lossy()))?
				.parse::<u32>()
				.context(format!("Failed to read z coord, line {} of {}", linei, coords_path.to_string_lossy()))?;
			particles.push(Particle3D {
				x: ValueBinnedU(x),
				y: ValueBinnedU(y),
				z: ValueBinnedU(z),
				r: ValueBinnedF(0.0),
				threshold: None,
			});
		}

		tilt_series_particles.push((tilt_series_id.to_string(), particles));
	}

	Ok(tilt_series_particles)
}

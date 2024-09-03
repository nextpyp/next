
use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};

use crate::metadata::{Particle3D, Virion3D};
use crate::scale::{TomogramDimsBinned, ToValueU, ValueBinnedF, ValueBinnedU};


pub fn sample_particle_3d(dims: TomogramDimsBinned, radius: ValueBinnedF) -> Particle3D {
	Particle3D {
		x: fastrand::u32(0 ..= dims.width.0).to_binned(),
		y: fastrand::u32(0 ..= dims.height.0).to_binned(),
		z: fastrand::u32(0 ..= dims.depth.0).to_binned(),
		r: radius,
		threshold: None
	}
}


pub fn sample_virion(dims: TomogramDimsBinned, radius: ValueBinnedF) -> Virion3D {
	Virion3D {
		particle: sample_particle_3d(dims, radius),
		threshold: 5
		// TODO: do something with the thresholds?
	}
}


pub fn sample_tomo_particles(
	virion_radius: ValueBinnedF,
	num_tilt_series: u32,
	num_particles: u32,
	dims: TomogramDimsBinned
) -> Vec<(String,Vec<Particle3D>)> {
	(0 .. num_tilt_series)
		.map(|tilt_series_i| {
			let tilt_series_id = format!("tilt_series_{}", tilt_series_i);
			let particles = (0 .. num_particles)
				.map(|_| sample_particle_3d(dims, virion_radius))
				.collect();
			(tilt_series_id, particles)
		})
		.collect()
}


pub fn read_manual_tomo_particles(radius: ValueBinnedF) -> Result<Option<Vec<(String,Vec<Particle3D>)>>> {

	let images_path = PathBuf::from("train/particles_images.txt");
	if !images_path.exists() {
		return Ok(None);
	}
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
				r: radius,
				threshold: None,
			});
		}

		tilt_series_particles.push((tilt_series_id.to_string(), particles));
	}

	Ok(Some(tilt_series_particles))
}

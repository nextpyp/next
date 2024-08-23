
use std::f64::consts::PI;

use crate::metadata::{AvgRot, AvgRotSample, Ctf, DriftCtf, DriftPos, Particle3D, Virion3D, Xf, XfSample};
use crate::scale::{ToValueU, ValueA, ValueBinnedF, ValueBinnedU, ValueUnbinnedF};

pub struct Gaussian {
	mean: f64,
	stddev: f64
}

impl Gaussian {

	pub fn new(mean: f64, stddev: f64) -> Self {
		Self {
			mean,
			stddev
		}
	}

	pub fn sample(&self) -> f64 {

		// sample the gaussian using the Box-Muller transform

		// start with uniform samples
		let u1 = fastrand::f64();
		let u2 = fastrand::f64();

		// convert to polar coordinates
		let theta = u2*PI*2.0;
		let r = (u1.ln()*-2.0).sqrt();

		// get the cartesian x coordinate
		let x = r*theta.cos();

		// apply stddev and mean
		x*self.stddev + self.mean
	}
}


pub fn sample_ctf(x: ValueUnbinnedF, y: ValueUnbinnedF, z: ValueUnbinnedF, pixel_size: ValueA, binning_factor: u32) -> Ctf {
	Ctf {
		mean_defocus: Gaussian::new(1.0, 1.0).sample(),
		cc: Gaussian::new(2.0, 1.0).sample(),
		defocus1: Gaussian::new(3.0, 1.0).sample(),
		defocus2: Gaussian::new(4.0, 1.0).sample(),
		angast: Gaussian::new(5.0, 1.0).sample(),
		ccc: Gaussian::new(6.0, 1.0).sample(),
		x,
		y,
		z,
		pixel_size,
		voltage: 300.0,
		binning_factor,
		cccc: Gaussian::new(7.0, 1.0).sample(),
		counts: Gaussian::new(8.0, 1.0).sample(),
	}
}


pub fn sample_xf(num_samples: usize) -> Xf {
	Xf {
		samples: (0 .. num_samples)
			.map(|_| {
				let theta = fastrand::f64()*PI*2.0;
				XfSample {
					mat00: theta.cos(),
					mat01: -theta.sin(),
					mat10: theta.sin(),
					mat11: theta.cos(),
					x: Gaussian::new(0.0, 1.0).sample(),
					y: Gaussian::new(0.0, 1.0).sample(),
				}
			})
			.collect()
	}
}


pub fn sample_avgrot(num_samples: usize) -> AvgRot {
	AvgRot {
		samples: (0 .. num_samples)
			.map(|_| AvgRotSample {
				spatial_freq: Gaussian::new(1.0, 1.0).sample(),
				avg_rot_no_astig: Gaussian::new(2.0, 1.0).sample(),
				avg_rot: Gaussian::new(3.0, 1.0).sample(),
				ctf_fit: Gaussian::new(4.0, 1.0).sample(),
				cross_correlation: Gaussian::new(5.0, 1.0).sample(),
				two_sigma: Gaussian::new(6.0, 1.0).sample(),
			})
			.collect()
	}
}


pub fn sample_drift_ctf(index: u32) -> DriftCtf {
	DriftCtf {
		index,
		defocus1: Gaussian::new(1.0, 1.0).sample(),
		defocus2: Gaussian::new(2.0, 1.0).sample(),
		astigmatism: Gaussian::new(3.0, 1.0).sample(),
		cc: Gaussian::new(4.0, 1.0).sample(),
		resolution: Gaussian::new(5.0, 1.0).sample()
	}
}


pub fn sample_drifts(num_samples: usize) -> Vec<DriftPos> {
	(0 .. num_samples)
		.map(|_| DriftPos {
			x: Gaussian::new(1.0, 1.0).sample(),
			y: Gaussian::new(2.0, 1.0).sample()
		})
		.collect::<Vec<_>>()
}


/// interpolates the tilt angle evenly in [-magnitude,magnitude]
pub fn interpolate_tilt_angle(magnitude: u32, tilt_i: u32, num_tilts: u32) -> i32 {
	if num_tilts == 0 {
		return 0;
	}
	(tilt_i*magnitude*2/(num_tilts - 1)) as i32 - magnitude as i32
}


pub fn sample_particle_3d(width: ValueBinnedU, height: ValueBinnedU, depth: ValueBinnedU, radius: ValueBinnedF) -> Particle3D {
	Particle3D {
		x: fastrand::u32(0 ..= width.0).to_binned(),
		y: fastrand::u32(0 ..= height.0).to_binned(),
		z: fastrand::u32(0 ..= depth.0).to_binned(),
		r: radius,
		threshold: None
	}
}

pub fn sample_virion(width: ValueBinnedU, height: ValueBinnedU, depth: ValueBinnedU, radius: ValueBinnedF) -> Virion3D {
	Virion3D {
		particle: sample_particle_3d(width, height, depth, radius),
		threshold: 5
		// TODO: do something with the thresholds?
	}
}

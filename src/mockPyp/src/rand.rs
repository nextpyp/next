
use std::f64::consts::PI;

use crate::metadata::{AvgRot, AvgRotSample, Ctf, DriftCtf, DriftPos, Xf, XfSample};


pub struct Gaussian {
	mean: f64,
	stddev: f64
}

impl Gaussian {

	pub const fn new(mean: f64, stddev: f64) -> Self {
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


pub fn sample_ctf(ctf: Ctf) -> Ctf {
	Ctf {
		mean_defocus: Gaussian::new(1.0, 1.0).sample(),
		cc: Gaussian::new(2.0, 1.0).sample(),
		defocus1: Gaussian::new(3.0, 1.0).sample(),
		defocus2: Gaussian::new(4.0, 1.0).sample(),
		angast: Gaussian::new(5.0, 1.0).sample(),
		ccc: Gaussian::new(6.0, 1.0).sample(),
		voltage: 300.0,
		cccc: Gaussian::new(7.0, 1.0).sample(),
		counts: Gaussian::new(8.0, 1.0).sample(),
		// copy everything else from the input
		.. ctf
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

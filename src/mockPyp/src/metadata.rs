
use crate::scale::{ValueA, ValueBinnedF, ValueBinnedU, ValueUnbinnedF};


#[derive(Debug, Clone, PartialEq)]
pub struct TiltSeries {
	pub tilt_series_id: String,
	pub ctf: Option<Ctf>,
	pub xf: Option<Xf>,
	pub avgrot: Option<AvgRot>,
	pub drift: Option<TiltSeriesDrifts>,
	pub virions: Option<Vec<Virion3D>>,
	pub spikes: Option<Vec<Particle3D>>,
}


#[derive(Debug, Clone, PartialEq)]
pub struct Ctf {
	pub mean_defocus: f64,
	pub cc: f64,
	pub defocus1: f64,
	pub defocus2: f64,
	pub angast: f64,
	pub ccc: f64,
	pub x: ValueUnbinnedF,
	pub y: ValueUnbinnedF,
	pub z: ValueUnbinnedF,
	pub pixel_size: ValueA,
	pub voltage: f64,
	pub binning_factor: u32,
	pub cccc: f64,
	pub counts: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct Xf {
	pub samples: Vec<XfSample>
}

#[derive(Debug, Clone, PartialEq)]
pub struct XfSample {
	pub mat00: f64,
	pub mat01: f64,
	pub mat10: f64,
	pub mat11: f64,
	pub x: f64,
	pub y: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct AvgRot {
	pub samples: Vec<AvgRotSample>
}

#[derive(Debug, Clone, PartialEq)]
pub struct AvgRotSample {
	pub spatial_freq: f64,
	pub avg_rot_no_astig: f64,
	pub avg_rot: f64,
	pub ctf_fit: f64,
	pub cross_correlation: f64,
	pub two_sigma: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct TiltSeriesDrifts {
	pub tilts: Vec<f64>,
	pub drifts: Vec<Vec<DriftPos>>,
	pub ctf_values: Vec<DriftCtf>,
	pub ctf_profiles: Vec<AvgRot>,
	pub tilt_axis_angle: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct DriftPos {
	pub x: f64,
	pub y: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct DriftCtf {
	pub index: u32,
	pub defocus1: f64,
	pub defocus2: f64,
	pub astigmatism: f64,
	pub cc: f64,
	pub resolution: f64
}


#[derive(Debug, Clone, PartialEq)]
pub struct Virion3D {
	pub particle: Particle3D,
	pub threshold: u32
}


#[derive(Debug, Clone, PartialEq)]
pub struct Particle3D {
	pub x: ValueBinnedU,
	pub y: ValueBinnedU,
	pub z: ValueBinnedU,
	pub r: ValueBinnedF,
	pub threshold: Option<u32>
}


#[derive(Debug, Clone, PartialEq)]
pub struct Reconstruction {
	pub reconstruction_id: String,
	pub class_num: u32,
	pub iteration: u32,
	pub fsc: Vec<Vec<f64>>,
	pub metadata: ReconstructionMetadata,
	pub plots: ReconstructionPlots
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


#[derive(Debug, Clone, PartialEq)]
pub struct ReconstructionPlots {
	// TODO
}

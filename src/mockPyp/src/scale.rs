
#![allow(unused)]


#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ValueA(pub f64);

impl ValueA {

	pub fn to_unbinned(self, pixel_size: ValueA) -> ValueUnbinnedF {
		ValueUnbinnedF(self.0/pixel_size.0)
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ValueUnbinnedU(pub u32);

impl ValueUnbinnedU {

	pub fn to_f(self) -> ValueUnbinnedF {
		ValueUnbinnedF(self.0 as f64)
	}

	pub fn to_binned(self, binning_factor: u32) -> ValueBinnedU {
		ValueBinnedU(self.0/binning_factor)
	}
}


#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ValueUnbinnedF(pub f64);

impl ValueUnbinnedF {

	pub fn to_u(self) -> ValueUnbinnedU {
		ValueUnbinnedU(self.0 as u32)
	}

	pub fn to_binned(self, binning_factor: u32) -> ValueBinnedF {
		ValueBinnedF(self.0/(binning_factor as f64))
	}

	pub fn to_a(self, pixel_size: ValueA) -> ValueA {
		ValueA(self.0*pixel_size.0)
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ValueBinnedU(pub u32);

impl ValueBinnedU {

	pub fn to_f(self) -> ValueBinnedF {
		ValueBinnedF(self.0 as f64)
	}

	pub fn to_unbinned(self, binning_factor: u32) -> ValueUnbinnedU {
		ValueUnbinnedU(self.0*binning_factor)
	}

	pub fn with_additional_binning(self, additional_binning: u32) -> Self {
		Self(self.0/additional_binning)
	}

	pub fn without_additional_binning(self, additional_binning: u32) -> Self {
		Self(self.0*additional_binning)
	}
}


#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ValueBinnedF(pub f64);

impl ValueBinnedF {

	pub fn to_u(self) -> ValueBinnedU {
		ValueBinnedU(self.0 as u32)
	}

	pub fn to_unbinned(self, binning_factor: u32) -> ValueUnbinnedF {
		ValueUnbinnedF(self.0*(binning_factor as f64))
	}

	pub fn with_additional_binning(self, additional_binning: u32) -> Self {
		Self(self.0/(additional_binning as f64))
	}

	pub fn without_additional_binning(self, additional_binning: u32) -> Self {
		Self(self.0*(additional_binning as f64))
	}
}


pub trait ToValueF {
	fn to_a(self) -> ValueA;
	fn to_unbinned(self) -> ValueUnbinnedF;
	fn to_binned(self) -> ValueBinnedF;
}

impl ToValueF for f64 {

	fn to_a(self) -> ValueA {
		ValueA(self)
	}

	fn to_unbinned(self) -> ValueUnbinnedF {
		ValueUnbinnedF(self)
	}

	fn to_binned(self) -> ValueBinnedF {
		ValueBinnedF(self)
	}
}


pub trait ToValueU {
	fn to_unbinned(self) -> ValueUnbinnedU;
	fn to_binned(self) -> ValueBinnedU;
}

impl ToValueU for u32 {

	fn to_unbinned(self) -> ValueUnbinnedU {
		ValueUnbinnedU(self)
	}

	fn to_binned(self) -> ValueBinnedU {
		ValueBinnedU(self)
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TomogramDimsUnbinned {
	pub width: ValueUnbinnedU,
	pub height: ValueUnbinnedU,
	pub depth: ValueUnbinnedU
}

impl TomogramDimsUnbinned {

	pub fn new(width: ValueUnbinnedU, height: ValueUnbinnedU, depth: ValueUnbinnedU) -> Self {
		Self {
			width,
			height,
			depth
		}
	}

	pub fn to_binned(self, binning_factor: u32) -> TomogramDimsBinned {
		TomogramDimsBinned {
			width: self.width.to_binned(binning_factor),
			height: self.height.to_binned(binning_factor),
			depth: self.depth.to_binned(binning_factor)
		}
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TomogramDimsBinned {
	pub width: ValueBinnedU,
	pub height: ValueBinnedU,
	pub depth: ValueBinnedU
}

impl TomogramDimsBinned {

	pub fn new(width: ValueBinnedU, height: ValueBinnedU, depth: ValueBinnedU) -> Self {
		Self {
			width,
			height,
			depth
		}
	}

	pub fn to_unbinned(self, binning_factor: u32) -> TomogramDimsUnbinned {
		TomogramDimsUnbinned {
			width: self.width.to_unbinned(binning_factor),
			height: self.height.to_unbinned(binning_factor),
			depth: self.depth.to_unbinned(binning_factor)
		}
	}

	pub fn with_additional_binning(self, additional_binning: u32) -> Self {
		Self {
			width: self.width.with_additional_binning(additional_binning),
			height: self.height.with_additional_binning(additional_binning),
			depth: self.depth.with_additional_binning(additional_binning)
		}
	}
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MicrographDimsUnbinned {
	pub width: ValueUnbinnedU,
	pub height: ValueUnbinnedU,
}

impl MicrographDimsUnbinned {

	pub fn new(width: ValueUnbinnedU, height: ValueUnbinnedU) -> Self {
		Self {
			width,
			height
		}
	}

	pub fn to_binned(self, binning_factor: u32) -> MicrographDimsBinned {
		MicrographDimsBinned {
			width: self.width.to_binned(binning_factor),
			height: self.height.to_binned(binning_factor)
		}
	}
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MicrographDimsBinned {
	pub width: ValueBinnedU,
	pub height: ValueBinnedU,
}


impl MicrographDimsBinned {

	pub fn new(width: ValueBinnedU, height: ValueBinnedU) -> Self {
		Self {
			width,
			height
		}
	}

	pub fn to_unbinned(self, binning_factor: u32) -> MicrographDimsUnbinned {
		MicrographDimsUnbinned {
			width: self.width.to_unbinned(binning_factor),
			height: self.height.to_unbinned(binning_factor)
		}
	}
}

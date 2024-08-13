
#![allow(unused)]


#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ValueA(pub f64);

impl ValueA {

	pub fn to_unbinned(self, pixel_a: f64) -> ValueUnbinnedF {
		ValueUnbinnedF(self.0*pixel_a)
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

	pub fn to_a(self, pixel_a: f64) -> ValueA {
		ValueA(self.0/pixel_a)
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

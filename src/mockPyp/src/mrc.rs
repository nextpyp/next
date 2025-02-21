
// MRC file (from the Medical Research Council, in the UK)
// https://en.wikipedia.org/wiki/MRC_(file_format)

// format specification:
// https://www.ccpem.ac.uk/mrc_format/mrc2014.php

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;

use anyhow::{Context, Result};
use byteorder::{WriteBytesExt, LE};


pub struct Mrc {
	nx: u32,
	ny: u32,
	nz: u32,
	voxels: Vec<i8>
}

impl Mrc {

	pub fn new(nx: u32, ny: u32, nz: u32) -> Self {
		Self {
			nx,
			ny,
			nz,
			voxels: vec![0i8; (nx as usize)*(ny as usize)*(nz as usize)]
		}
	}

	fn index(&self, x: u32, y: u32, z: u32) -> usize {
		let x = x as usize;
		let y = y as usize;
		let z = z as usize;
		let nx = self.nx as usize;
		let ny = self.ny as usize;
		return z*nx*ny + y*nx + x;
	}

	pub fn get(&self, x: u32, y: u32, z: u32) -> i8 {
		self.voxels[self.index(x, y, z)]
	}

	pub fn set(&mut self, x: u32, y: u32, z: u32, val: i8) {
		let i = self.index(x, y, z);
		self.voxels[i] = val;
	}

	pub fn save(&self, path: impl AsRef<Path>) -> Result<()> {

		let path = path.as_ref();

		let mut file = File::create(&path)
			.context(format!("Failed to open file for writing: {}", path.to_string_lossy()))?;
		let mut writer = BufWriter::new(&mut file);

		// first, write the header: it's 256 (4-byte) words, or 1024 bytes total

		// NOTE: the MRC reader on the JavaScript side only reads these header fields:
		//       nx, ny, nz, mode, machst, nsymbt

		// NOTE: While the MRC format supports Big-Endian byte orders,
		//       the MRC parser in our JS code doesn't seem to implement byte-order
		//       normalization correctly, so we should only use Little-Endian byte orders.

		// write the dimensions (words 1-3)
		writer.write_u32::<LE>(self.nx)?;
		writer.write_u32::<LE>(self.ny)?;
		writer.write_u32::<LE>(self.nz)?;

		// use mode 0: 8 bit signed int
		writer.write_u32::<LE>(0)?;

		// we're at word 5 now: skip to word 24
		writer.write(&[0u8; 4*(24 - 5)])?;

		// we're not using any extra header space, so zero out nsymbt
		writer.write_u32::<LE>(0)?;

		// we're at word 25 now: skip to word 54
		writer.write(&[0u8; 4*(54 - 25)])?;

		// write the machine stamp: signal little-endianess (note 11)
		writer.write(&[0x44, 0x44, 0x00, 0x00])?;

		// we're at word 55 now: skip to the end of the header (word 257)
		writer.write(&[0u8; 4*(257 - 55)])?;

		// write the voxels: z(y(x)) order
		for z in 0 .. self.nz {
			for y in 0 .. self.ny {
				for x in 0 .. self.nx {
					writer.write_i8(self.get(x, y, z))?;
				}
			}
		}

		// write buffers should be flushed before dropping
		writer.flush()?;

		Ok(())
	}

	#[allow(unused)]
	pub fn isotropic(&mut self, cx: u32, cy: u32, cz: u32) {
		let max_d = dist3_l2(0, 0, 0, self.nx, self.ny, self.nz);
		for z in 0 .. self.nz {
			for y in 0 .. self.ny {
				for x in 0 .. self.nx {
					let d = dist3_l2(cx, cy, cz, x, y, z);
					let d = ((max_d - d)*(i8::MAX as f32)/max_d) as i8;
					self.set(x, y, z, d);
				}
			}
		}
	}

	pub fn cube(&mut self, cx: u32, cy: u32, cz: u32) {
		let max_d = dist3_l1(0, 0, 0, self.nx, self.ny, self.nz);
		for z in 0 .. self.nz {
			for y in 0 .. self.ny {
				for x in 0 .. self.nx {
					let d = dist3_l1(cx, cy, cz, x, y, z);
					let d = ((max_d - d)*(i8::MAX as u32)/max_d) as i8;
					self.set(x, y, z, d);
				}
			}
		}
	}
}


fn dist(a: u32, b: u32) -> u32 {
	if a > b {
		a - b
	} else {
		b - a
	}
}


fn dist3_l1(
	x1: u32, y1: u32, z1: u32,
	x2: u32, y2: u32, z2: u32
) -> u32 {
	let dx = dist(x1, x2);
	let dy = dist(y1, y2);
	let dz = dist(z1, z2);
	return dx + dy + dz
}

fn dist3_l2(
	x1: u32, y1: u32, z1: u32,
	x2: u32, y2: u32, z2: u32
) -> f32 {
	let dx = dist(x1, x2);
	let dy = dist(y1, y2);
	let dz = dist(z1, z2);
	let d2 = (dx*dx + dy*dy + dz*dz) as f32;
	d2.sqrt()
}

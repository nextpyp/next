
// MRC file (from the Medical Research Council, in the UK)
// https://en.wikipedia.org/wiki/MRC_(file_format)

// format specification:
// https://www.ccpem.ac.uk/mrc_format/mrc2014.php

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;

use anyhow::{Context, Result};
use byteorder::{WriteBytesExt, BE};


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
		self.voxels[self.index(x, y, z)] = val;
	}

	pub fn save(&self, path: impl AsRef<Path>) -> Result<()> {

		let path = path.as_ref();

		let mut file = File::create(&path)
			.context(format!("Failed to open file for writing: {}", path.to_string_lossy()))?;
		let mut writer = BufWriter::new(&mut file);

		// NOTE: the MRC reader on the JavaScript side only reads these header fields:
		//       nx, ny, nz, mode, machst, nsymbt

		// write the dimensions (words 1-3)
		writer.write_u32::<BE>(self.nx)?;
		writer.write_u32::<BE>(self.ny)?;
		writer.write_u32::<BE>(self.nz)?;

		// use mode 0: 8 bit signed int
		writer.write_u32::<BE>(0)?;

		// we're at word 5 now: skip to word 24
		writer.write(&[0u8; 8*(24 - 5)])?;

		// we're not using any extra header space, so zero out nsymbt
		writer.write_u32::<BE>(0)?;

		// we're at word 25 now: skip to word 54
		writer.write(&[0u8; 8*(54 - 25)])?;

		// write the machine stamp: signal big-endianess (note 11)
		writer.write(&[0x11, 0x11, 0x00, 0x00])?;

		// we're at word 55 now: skip to the end of the header (word 1025)
		writer.write(&[0u8; 8*(1025 - 55)])?;

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
}

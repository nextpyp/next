
use std::fs;
use std::os::unix::fs::FileTypeExt;

use anyhow::Result;
use gumdrop::Options;


#[derive(Options)]
pub struct Args {

	/// The directory to list
	#[options(free)]
	dir: String
}


pub fn run(_quiet: bool, args: Args) -> Result<()> {

	// read the dir using the Rust stdlib, which is just a thin wrapper around libc
	// should be pretty fast for most cases, right?
	// TODO: do we need to go to raw kernel interfaces for more speed?? might not be very portable?
	for result in fs::read_dir(args.dir)? {
		match result {
			Ok(entry) => println!("{}: {}",
				entry.file_type()
					.map(|t| {
						if t.is_file() {
							"File"
						} else if t.is_dir() {
							"Dir"
						} else if t.is_symlink() {
							"Symlink"
						} else if t.is_fifo() {
							"Fifo"
						} else if t.is_socket() {
							"Socket"
						} else if t.is_block_device() {
							"BlockDev"
						} else if t.is_char_device() {
							"CharDev"
						} else {
							"(Unknown)"
						}.to_string()
					})
					.unwrap_or_else(|e| format!("(Type Error: {})", e)),
				entry.file_name().to_string_lossy()
			),
			Err(e) => println!("Error: {}", e)
		}
	}

	Ok(())
}

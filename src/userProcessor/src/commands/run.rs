
use std::process::Command;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::{io, thread};
use std::time::Duration;

use anyhow::{bail, Context, Result};
use gumdrop::Options;
use tracing::info;


#[derive(Options)]
pub struct Args {

	/// The directory in which to run the command
	#[options(free)]
	cwd: String,

	/// Path to the exectuable
	#[options(free)]
	exe: String,

	/// Arguments to the command
	#[options(free)]
	args: Vec<String>
}


pub fn run(quiet: bool, args: Args) -> Result<()> {

	if !quiet {
		let pid = std::process::id();
		let pgid = match unsafe { libc::getpgid(0) } {
			-1 => Err(io::Error::last_os_error())
				.context("Failed to call getpgid()")?,
			pgid => pgid
		};
		info!("Running command:\n\tin: {}\n\t{} {:?}\n\tPID={}, PGID={}", args.cwd, args.exe, args.args, pid, pgid);
	}

	// install SIGINT hooks
	let sigint = Arc::new(AtomicBool::new(false));
	signal_hook::flag::register(signal_hook::consts::SIGINT, sigint.clone())
		.context("Failed to install SIGINT handler")?;

	// run the command
	let mut process = Command::new(args.exe)
		.current_dir(args.cwd)
		.args(args.args)
		.spawn()
		.context("Failed to run command")?;

	// wait for it to finish
	let mut killed = false;
	loop {

		let exit = process.try_wait()
			.context("Failed to check status of command process, abandoning it")?;
		if let Some(exit) = exit {
			match exit.code() {
				Some(code) => {
					if exit.success() {
						return Ok(());
					} else {
						bail!("command process exited with code: {}", code);
					}
				}
				None => {
					if killed {
						info!("command process killed succesfully");
						return Ok(());
					} else {
						bail!("command process was killed");
					}
				}
			}
		}

		// command is still running

		// look for an incoming SIGINT
		if sigint.load(Ordering::Relaxed) {
			sigint.store(false, Ordering::Relaxed);
			info!("received SIGINT: waiting for command process to exit");
			info!("  (assuming SIGINT was sent to the whole process group)");
			// NOTE: we don't need to forward SIGINT since it's usually sent to a whole process group
			killed = true;
		}

		// wait a bit before checking again
		thread::sleep(Duration::from_millis(500));
	}
}

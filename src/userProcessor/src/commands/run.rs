
use std::process::Command;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
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
		info!("Running command:\n\tin: {}\n\t{} {:?}", args.cwd, args.exe, args.args);
	}

	// install SIGTERM hook
	let sigterm = Arc::new(AtomicBool::new(false));
	signal_hook::flag::register(signal_hook::consts::SIGTERM, sigterm.clone())
		.context("Failed to install SIGTERM handler")?;

	// run the command
	let mut process = Command::new(args.exe)
		.current_dir(args.cwd)
		.args(args.args)
		.spawn()
		.context("Failed to run command")?;

	// wait for it to finish
	loop {

		let exit = process.try_wait()
			.context("Failed to check status of command, abandoning it")?;
		if let Some(exit) = exit {
			if exit.success() {
				return Ok(());
			} else if let Some(code) = exit.code() {
				bail!("command exited with code: {}", code);
			} else {
				bail!("command was killed");
			}
		}

		// command is still running: wait a bit and then check again
		// unless sigterm was requested, then forward it to the command and keep waiting
		if sigterm.load(Ordering::Relaxed) {
			sigterm.store(false, Ordering::Relaxed);
			info!("SIGTERM: forwarding to command");
			Command::new("kill")
				.arg(process.id().to_string())
				.spawn()
				.context("Failed to forward SIGTERM, abandoning the command")?;
		} else {
			thread::sleep(Duration::from_millis(500));
		}
	}
}

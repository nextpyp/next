
use std::{env, thread};
use std::ffi::{OsStr, OsString};
use std::path::PathBuf;
use std::process::{Command, ExitCode};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use gumdrop::{Options, ParsingStyle};


#[derive(Options)]
struct Args {

	/// Prints this help message
	#[options(help_flag)]
	help: bool,

	/// Omits unnecessary stdout messages
	#[options(default_expr = "false")]
	quiet: bool
}


fn main() -> ExitCode {

	// parse arguments
	let (args, cmd): (Args, Vec<OsString>) = {

		// split into args for here, and args for the command
		let mut args = env::args_os().into_iter()
			.skip(1) // skip the 0 argument, it's just the runas path
			.collect::<Vec<_>>();
		let delimiter = OsStr::new("--");
		let pos = args.iter()
			.position(|arg| arg.as_os_str() == delimiter);
		let cmd = match pos {
			Some(pos) => {
				let out = args.split_off(pos + 1);
				args.pop(); // remove the trailing --
				out
			}
			None => vec![]
		};

		// parse the here args
		let result = args.into_iter()
			.map(|a| a.into_string())
			.collect::<Result<Vec<_>,_>>();
		let utf8_args = match result {
			Ok(a) => a,
			Err(str) => {
				println!("Argument was not valid UTF-8: {:?}", str);
				return ExitCode::FAILURE;
			}
		};
		let result = Args::parse_args(&utf8_args, ParsingStyle::AllOptions);
		let args = match result {
			Ok(a) => a,
			Err(e) => {
				println!("Failed to parse arguments: {}", e);
				return ExitCode::FAILURE;
			}
		};

		(args, cmd)
	};

	// handle special flags
	if args.help {
		println!("nextPYP runas:");
		println!("   Run commands as the setuid user");
		println!("USAGE: runas [options] -- command arg1 arg2 ...");
		println!("{}", Args::usage());
		return ExitCode::SUCCESS;
	}

	if !args.quiet {

		// get the name of this runas command (it often gets renamed since we need multiple copies)
		let runas_name = env::args_os()
			.next()
			.map(|s| {
				PathBuf::from(s)
					.file_name()
					.map(|n| n.to_string_lossy().to_string())
			})
			.flatten()
			.unwrap_or("runas (probably?)".to_string());

		// show the current user
		let uid_current = users::get_current_uid();
		let uid_effective = users::get_effective_uid();
		let username_current = users::get_user_by_uid(uid_current)
			.map(|user| user.name().to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		let username_effective = users::get_user_by_uid(uid_effective)
			.map(|user| user.name().to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		if uid_current == uid_effective {
			println!("nextPYP {} running as {}:{}", runas_name, uid_current, username_current);
		} else {
			println!("nextPYP {} started as {}:{}, but acting as {}:{}", runas_name, uid_current, username_current, uid_effective, username_effective);
		}
		// NOTE: compiling with a statically-linked libc can cause the above libc calls to segfault
		// if the compiled libc is incompatible with the kernel in the deployed environment
	}

	// parse the command
	let Some(cmd_program) = cmd.get(0)
		else {
			println!("No command given");
			return ExitCode::FAILURE;
		};
	let cmd_args = cmd.get(1..)
		.unwrap_or(&[]);
	if !args.quiet {
		println!("running command: {:?} {:?}", cmd_program, cmd_args);
	}

	// install SIGTERM hook
	let sigterm = Arc::new(AtomicBool::new(false));
	let result = signal_hook::flag::register(signal_hook::consts::SIGTERM, sigterm.clone());
	if let Err(e) = result {
		println!("Failed to install SIGTERM handler: {}", e);
		return ExitCode::FAILURE
	}

	// run the command
	let result = Command::new(cmd_program)
		.args(cmd_args)
		.spawn();
	let mut process = match result {
		Ok(p) => p,
		Err(e) => {
			println!("Failed to launch command: {}", e);
			return ExitCode::FAILURE;
		}
	};

	// wait for it to finish
	loop {
		match process.try_wait() {

			Ok(Some(exit)) => {
				return match exit.code() {
					Some(code) => ExitCode::from(code as u8),
					None => {
						println!("Command finished without exit code (maybe it ended by signal?)");
						ExitCode::FAILURE
					}
				}
			}

			Ok(None) => {
				// command is still running: wait a bit and then check again
				// unless sigterm was requested, then forward it to the command and keep waiting
				if sigterm.load(Ordering::Relaxed) {
					sigterm.store(false, Ordering::Relaxed);
					println!("SIGTERM: forwarding to command");
					let result = Command::new("kill")
						.arg(process.id().to_string())
						.spawn();
					if let Err(e) = result {
						println!("Failed to forward SIGTERM, abandoning the command: {}", e);
						return ExitCode::FAILURE;
					}
				} else {
					thread::sleep(Duration::from_millis(500));
				}
			}

			Err(e) => {
				println!("Failed to check status of command, abandoning it: {}", e);
				return ExitCode::FAILURE;
			}
		}
	}
}

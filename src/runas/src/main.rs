
use std::env;
use std::ffi::{OsStr, OsString};
use std::process::{Command, ExitCode};

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
				println!("Failed to parse arguments: {}", e.to_string());
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
		// show the current user
		let user_current = users::get_current_username()
			.map(|s| s.to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		let user_effective = users::get_effective_username()
			.map(|s| s.to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		if user_current == user_effective {
			println!("nextPYP runas running as {}", user_current);
		} else {
			println!("nextPYP runas started as {}, but acting as {}", user_current, user_effective);
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

	// run the command
	let result = Command::new(cmd_program)
		.args(cmd_args)
		.spawn();
	let mut process = match result {
		Ok(p) => p,
		Err(e) => {
			println!("Failed to launch command: {}", e.to_string());
			return ExitCode::FAILURE;
		}
	};

	// wait for it to finish
	match process.wait() {
		Ok(exit) => match exit.code() {
			Some(code) => ExitCode::from(code as u8),
			None => {
				println!("Command finished without exit code (maybe it ended by signal?)");
				ExitCode::FAILURE
			}
		}
		Err(e) => {
			println!("Failed to launch command: {}", e.to_string());
			ExitCode::FAILURE
		}
	}
}

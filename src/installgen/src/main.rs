
mod config;
mod installed;
mod install;
mod script;

use std::ops::Deref;
use std::process::ExitCode;

use display_error_chain::ErrorChainExt;
use gumdrop::Options;


#[derive(Debug, Options)]
struct Args {

	/// print help message
	#[options()]
	help: bool,

	/// print version
	#[options()]
	version: bool,

	#[options(command)]
	cmd: Option<Command>
}


#[derive(Debug, Options)]
enum Command {
	Install(ArgsInstall)
}


#[derive(Debug, Options)]
struct ArgsInstall {
	// no args needed ... yet?
}


mod gen {
	include!(concat!(env!("OUT_DIR"), "/gen.rs"));
}


fn main() -> ExitCode {

	let args = Args::parse_args_default_or_exit();

	if args.version {
		println!("nextPYP installgen version {}", gen::VERSION);
		return ExitCode::SUCCESS;
	}

	// handle the commands
	let result = match args.cmd {
		Some(Command::Install(..)) => install::run(),
		None => {
			println!("No command given");
			return ExitCode::FAILURE;
		}
	};

	match result {
		Ok(()) => ExitCode::SUCCESS,
		Err(e) => {
			println!("ERROR: {}", e.deref().chain());
			ExitCode::FAILURE
		}
	}
}

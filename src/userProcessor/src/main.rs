
use std::{env, io};
use std::path::PathBuf;
use std::process::ExitCode;

use anyhow::{bail, Context, Result};
use gumdrop::{Options, ParsingStyle};
use tracing::{info, warn};

use user_processor::commands;
use user_processor::logging::{self, ResultExt};


#[derive(Options)]
struct Args {

	#[options(help_flag)]
	help: bool,

	/// settings for log output
	#[options(default = "user_processor=info")]
	log: String,

	/// Omits unnecessary stdout messages
	#[options(default_expr = "false")]
	quiet: bool,

	#[options(command)]
	cmd: Option<Command>
}

#[derive(Options)]
enum Command {

	/// Run the user processor daemon
	Daemon(commands::daemon::Args),

	/// Run a command as the user
	Run(commands::run::Args),

	/// Fast directory listing, even on slower networked filesystems
	Dirlist(commands::dirlist::Args)
}


fn main() -> ExitCode {

	// parse arguments
	let args = Args::parse_args_or_exit(ParsingStyle::StopAtFirstFree);

	// init logging
	let Ok(_) = logging::init(&args.log)
		.log_err()
		else { return ExitCode::FAILURE; };

	let Ok(_) = run(args)
		.log_err()
		else { return ExitCode::FAILURE; };

	// we finished! =)
	ExitCode::SUCCESS
}


#[tracing::instrument(skip_all, level = 5, name = "UserProcessor", fields(u))]
fn run(args: Args) -> Result<()> {

	// get the effective username, for the log
	let uid_current = users::get_current_uid();
	let uid_effective = users::get_effective_uid();
	let username_effective = users::get_user_by_uid(uid_effective)
		.map(|user| user.name().to_string_lossy().to_string())
		.unwrap_or("(unknown)".to_string());
	tracing::Span::current().record("u", &username_effective);

	if !args.quiet {

		// get the name of this command (it often gets renamed since we need multiple copies)
		let user_processor_name = env::args_os()
			.next()
			.map(|s| {
				PathBuf::from(s)
					.file_name()
					.map(|n| n.to_string_lossy().to_string())
			})
			.flatten()
			.context("Failed to query executable name")?;

		// show the current user
		let username_current = users::get_user_by_uid(uid_current)
			.map(|user| user.name().to_string_lossy().to_string())
			.unwrap_or("(unknown)".to_string());
		if uid_current == uid_effective {
			info!("{} running as {}:{}", user_processor_name, uid_current, username_current);
		} else {
			info!("{} started as {}:{}, but acting as {}:{}", user_processor_name, uid_current, username_current, uid_effective, username_effective);
		}
	}

	// set the UID to match the EUID, to keep from confusing other programs
	// but also save the original UID so we don't lose all trace of the service account
	// as an unpriviledged process, we're only allowed to set UID to itself or EUID, but nothing else
	// NOTE: man setreuid says we can send -1 to leave an id unchanged,
	//       but since the rust wrapper function uses u32 for uid_t,
	//       we'll just have to encode the -1 ourselves as a 4-byte bitstring of all 1s, ie the max unsigned value
	match unsafe { libc::setresuid(uid_effective, libc::uid_t::MAX, uid_current) } {
		0 => (), // ok
		_ => Err(io::Error::last_os_error())
			.context("Failed to call setresuid()")?
	}

	match args.cmd {
		Some(Command::Daemon(daemon_args)) => commands::daemon::run(args.quiet, daemon_args),
		Some(Command::Run(run_args)) => commands::run::run(args.quiet, run_args),
		Some(Command::Dirlist(dirlist_args)) => commands::dirlist::run(args.quiet, dirlist_args),
		_ => bail!("No command, try one of:\n{}", Args::command_list().unwrap())
	}
}

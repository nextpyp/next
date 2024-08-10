
use std::collections::VecDeque;
use std::env;
use std::ops::Deref;
use std::process::ExitCode;

use anyhow::{anyhow, Context, Result};
use display_error_chain::ErrorChainExt;
use tracing::{error, info, warn};

use mock_pyp::{blocks, logging};
use mock_pyp::args::Args;
use mock_pyp::logging::ResultExt;


fn main() -> ExitCode {

	// init logging
	let Ok(_) = logging::init("mock_pyp=trace")
		.log_err()
		else { return ExitCode::FAILURE; };

	if let Err(e) = run() {
		error!("{}", e.deref().chain());
		return ExitCode::FAILURE
	}

	// we finished! =)
	ExitCode::SUCCESS
}


#[tracing::instrument(skip_all, level = 5, name = "MockPyp", fields(u))]
fn run() -> Result<()> {

	// find the pyp command to run
	let mut args = env::args().into_iter().collect::<VecDeque<_>>();
	args.pop_front(); // ignore the executable path, no info there
	let cmd = args.pop_front()
		.context("missing pyp command as first argument")?;
	info!("command: {}", cmd);

	// parse the arguments
	let args = Args::from(args);

	// get the block
	let block_id = args.get("micromon_block")
		.require()?
		.value();

	// run the command with the rest of the args
	match block_id {
		"tomo-rawdata" => blocks::tomo_rawdata::run(args),
		_ => Err(anyhow!("unrecognized pyp command: {}", cmd))
	}?;

	Ok(())
}

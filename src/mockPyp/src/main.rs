
use std::collections::VecDeque;
use std::{env, fs};
use std::ops::Deref;
use std::path::Path;
use std::process::ExitCode;

use anyhow::{Context, Result};
use display_error_chain::ErrorChainExt;
use tracing::{error, info, warn};

use mock_pyp::{blocks, logging};
use mock_pyp::args::{Args, ArgsConfig};
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

	// parse the new arguments
	let new_args = Args::from(args);

	// and combine with any old arguments
	let args_path = Path::new("pyp_args.dat");
	let mut args =
		if args_path.exists() {
			let mut old_args = Args::read(args_path)?;
			old_args.set_all(&new_args);
			old_args
		} else {
			new_args
		};

	// load the arguments config
	let args_config_path = "/opt/pyp/config/pyp_config.toml";
	let args_config = fs::read_to_string(&args_config_path)
		.context(format!("Failed to read args config file: {}", &args_config_path))?;
	let args_config = ArgsConfig::from(args_config)
		.context(format!("Failed to parse config file: {}", &args_config_path))?;

	// get the block
	let block_id = args.get("micromon_block")
		.require()?
		.into_str()?
		.value()
		.to_string();

	info!("block id: {}", block_id);

	// run the command with the rest of the args
	blocks::run(block_id.as_str(), &mut args, &args_config)?;

	// save the args for next time
	args.write(args_path)?;

	Ok(())
}

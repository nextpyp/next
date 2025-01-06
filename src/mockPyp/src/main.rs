
use std::collections::VecDeque;
use std::{env, fs};
use std::ops::Deref;
use std::process::ExitCode;

use anyhow::{bail, Context, Result};
use display_error_chain::ErrorChainExt;
use tracing::{error, info, warn};

use mock_pyp::{blocks, logging, sessions, info as web_info, error as web_error};
use mock_pyp::args::{Args, ArgsConfig};
use mock_pyp::logging::ResultExt;
use mock_pyp::web::Web;


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

	// look for an array element
	let array_element = match env::var("SLURM_ARRAY_TASK_ID") {
		Ok(s) => {
			let i = s.parse::<u32>()
				.context(format!("Failed to parse SLURM array id: {}", s))?;
			Some(i)
		}
		Err(_) => None
	};

	// run the command
	match cmd.as_str() {

		"webrpc" => run_webrpc(args, array_element),

		"streampyp" => {
			let web = Web::new()?;
			let result = run_session(&web, args, array_element);
			if let Err(e) = &result {
				// make an attempt to stream the error to the log
				web_error!(web, "{}", e.deref().chain());
			}
			result
		}

		_ => {
			let web = Web::new()?;
			web_info!(&web, "command: {}", cmd);
			let result = run_project(&web, args, array_element);
			if let Err(e) = &result {
				// make an attempt to stream the error to the log
				web_error!(web, "{}", e.deref().chain());
			}
			result
		}
	}
}


fn run_webrpc(args: VecDeque<String>, array_element: Option<u32>) -> Result<()> {

	let cmd = args.get(0).map(|s| s.as_str());
	match cmd {

		Some("slurm_started") => {

			info!("webrpc: slurm_started");

			let web = Web::new()?;
			web.slurm_started(array_element)?;
		}

		Some("slurm_ended") => {

			// look for an optional exit code argument
			let exit_code =  match args.get(1) {

				Some(a) => {
					const PREFIX: &str = "--exit=";
					if a.starts_with(PREFIX) {
						let code_str = &a[PREFIX.len()..];
						let code = code_str
							.parse::<u32>()
							.context(format!("unrecognized exit code: {}", code_str))?;
						Some(code)
					} else {
						None
					}
				}

				None => None
			};

			info!("webrpc: slurm_ended: exit={:?}", exit_code);

			let web = Web::new()?;
			web.slurm_ended(array_element, exit_code)?;
		}

		_ => bail!("Unrecognized webrpc command: {:?}", cmd)
	}

	Ok(())
}


fn parse_args(args: VecDeque<String>) -> Result<(Args,ArgsConfig)> {

	// load the arguments config
	let args_config_path = "/opt/pyp/config/pyp_config.toml";
	let args_config = fs::read_to_string(&args_config_path)
		.context(format!("Failed to read args config file: {}", &args_config_path))?;
	let args_config = ArgsConfig::from(args_config)
		.context(format!("Failed to parse config file: {}", &args_config_path))?;

	// we should only get one argument: -params_file=<path>
	const PREFIX: &'static str = "-params_file=";
	if args.len() != 1 || !args[0].starts_with(PREFIX) {
		bail!("Expected a single argument: -params_file=<path>, instead got: {:?}", args);
	}
	let params_path = &args[0][PREFIX.len()..];

	// read the params file
	let args = Args::read(&params_path, &args_config)?;

	Ok((args, args_config))
}


fn run_project(web: &Web, args: VecDeque<String>, array_element: Option<u32>) -> Result<()> {

	let (mut args, args_config) = parse_args(args)?;

	// get the block
	let block_id = args.get("micromon_block")
		.require()?
		.into_str()?
		.value()
		.to_string();

	web_info!(web, "block id: {}", block_id);

	// run the block command
	blocks::run(web, block_id.as_str(), &mut args, &args_config, array_element)?;

	Ok(())
}


fn run_session(web: &Web, args: VecDeque<String>, _array_element: Option<u32>) -> Result<()> {

	let (mut args, args_config) = parse_args(args)?;

	// get the session type
	let data_mode = args.get("data_mode")
		.require()?
		.into_str()?
		.value();

	web_info!(web, "session: {}", data_mode);

	match data_mode {
		"tomo" => sessions::tomo::run(web, &mut args, &args_config)?,
		"spr" => sessions::spr::run(web, &mut args, &args_config)?,
		_ => bail!("unrecognized session mode: {}", data_mode)
	}

	Ok(())
}

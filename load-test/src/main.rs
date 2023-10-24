
use async_trait::async_trait;
use clap::{Parser, ValueEnum};
use goose::config::GooseConfiguration;
use goose::goose::{GooseResponse, Scenario as GooseScenario};
use goose::prelude::*;
use goose_eggs::{Validate, validate_page};
use http::header::{CONTENT_TYPE, COOKIE};
use http::HeaderValue;


/// Load-tester for Micromon
#[derive(Parser, Debug)]
#[command()]
struct Args {

	/// the target server, eg http://theserver.app
	#[arg()]
	host: String,

	#[arg(value_enum)]
	scenario: Scenario,

	#[arg(value_enum)]
	load: Load
}


#[derive(ValueEnum, Debug, Clone)]
enum Scenario {

	/// targets the index page
	Index,

	/// loads the projects json
	ProjectsList,

	/// loads the demo homepage and all associated assets,services
	DemoHomepage
}

impl Scenario {

	fn id(&self) -> String {
		self.to_possible_value()
			.unwrap() // this should never fail, right?
			.get_name()
			.to_string()
	}
}


#[derive(ValueEnum, Debug, Clone)]
enum Load {

	/// 1 request/sec, for 30 seconds
	Warmup,

	/// 1 thread, at max speed, for 30 seconds
	Hammer1,

	/// 4 threads, at max speed, for 30 seconds
	Hammer4,

	/// 16 threads, at max speed, for 60 seconds
	Hammer16,

	/// 64 threads, at max speed, for 60 seconds
	Hammer64,

	/// 128 threads, at max speed, for 60 seconds
	Hammer128,

	/// 256 threads, at max speed, for 60 seconds
	Hammer256
}

impl Load {

	fn id(&self) -> String {
		self.to_possible_value()
			.unwrap() // this should never fail, right?
			.get_name()
			.to_string()
	}
}


#[tokio::main]
async fn main() -> Result<(),GooseError> {

	let args = Args::parse();

	// configure goose, see:
	// https://book.goose.rs/getting-started/runtime-options.html
	let mut config = GooseConfiguration::default();

	// apply common options
	config.host = args.host;
	config.no_reset_metrics = true;
	config.report_file = format!("report-{}-{}.html", args.scenario.id(), args.load.id());
	config.no_telnet = true;
	config.no_websocket = true;

	// configure the scenario
	let scenario = GooseScenario::new(&args.scenario.id())
		.register_transaction(transaction!(session_start).set_on_start())
		.register_transaction(match &args.scenario {
			Scenario::Index => transaction!(scenario_index),
			Scenario::ProjectsList => transaction!(scenario_projects_list),
			Scenario::DemoHomepage => transaction!(scenario_demo_homepage)
		});

	// configure the load
	match &args.load {

		Load::Warmup => {
			config.users = Some(1);
			config.throttle_requests = 1;
			config.run_time = "30s".to_string();
		}

		Load::Hammer1 => {
			config.users = Some(1);
			config.run_time = "30s".to_string();
		}

		Load::Hammer4 => {
			config.users = Some(4);
			config.run_time = "30s".to_string();
		}

		Load::Hammer16 => {
			config.users = Some(16);
			config.hatch_rate = Some("2".to_string());
			config.run_time = "60s".to_string();
		}

		Load::Hammer64 => {
			config.users = Some(64);
			config.hatch_rate = Some("8".to_string());
			config.run_time = "60s".to_string();
		}

		Load::Hammer128 => {
			config.users = Some(128);
			config.hatch_rate = Some("12".to_string());
			config.run_time = "60s".to_string();
		}

		Load::Hammer256 => {
			config.users = Some(256);
			config.hatch_rate = Some("16".to_string());
			config.run_time = "60s".to_string();
		}
	}

	GooseAttack::initialize_with_config(config)?
		.register_scenario(scenario)
		.execute()
		.await?;

	Ok(())
}


#[async_trait]
trait CheckResponse {
	async fn check_ok(self, user: &mut GooseUser) -> TransactionResult;
}

#[async_trait]
impl CheckResponse for GooseResponse {

	async fn check_ok(self, user: &mut GooseUser) -> TransactionResult {

		let validate = Validate::builder()
			.status(200)
			.build();

		validate_page(user, self, &validate)
			.await?;

		Ok(())
	}
}


/// because the post_json() function tries to be too fancy and doesn't quite do what we want
#[async_trait]
trait PostRawJson {
	async fn post_raw_json(&mut self, path: &str, raw_json: &'static str) -> Result<GooseResponse,Box<TransactionError>>;
}

#[async_trait]
impl PostRawJson for GooseUser {

	async fn post_raw_json(&mut self, path: &str, raw_json: &'static str) -> Result<GooseResponse,Box<TransactionError>> {

		let mut builder = self.get_request_builder(&GooseMethod::Post, path)?
			.body(raw_json)
			.header(CONTENT_TYPE, HeaderValue::from_static("application/json"));

		// add the session cookie if we can
		if let Some(val) = self.get_session_data::<String>() {
			let header_val = HeaderValue::from_str(val)
				.expect("bad session cookie value");
			builder = builder.header(COOKIE, header_val);
		} else {
			panic!("no session cookie to send");
		}

		// send the request
		let request = GooseRequest::builder()
			.set_request_builder(builder)
			.build();
		self.request(request)
			.await
	}
}


async fn session_start(user: &mut GooseUser) -> TransactionResult {

	// load the index page
	let response = user.get("")
		.await?
		.response
		.map_err(|e| TransactionError::from(e))?;

	// pull out the session cookie from the response and save it in the session
	let cookie = response.cookies()
		.find(|cookie| cookie.name() == "nextPYP");
	if let Some(cookie) = cookie {
		user.set_session_data(cookie.value().to_string());
	} else {
		panic!("no session cookie in index response");
	}

	Ok(())
}

async fn scenario_index(user: &mut GooseUser) -> TransactionResult {

	user.get("")
		.await?
		.check_ok(user)
		.await?;

	Ok(())
}


async fn scenario_projects_list(user: &mut GooseUser) -> TransactionResult {

	let json = r#"{"id":1,"method":"/kv/projects/list","params":["\"demo\""]}"#;
	user.post_raw_json("/kv/projects/list", json)
		.await?
		.check_ok(user)
		.await?;

	Ok(())
}


struct Script<'a> {
	user: &'a mut GooseUser
}

impl<'a> Script<'a> {

	fn new(user: &'a mut GooseUser) -> Self {
		Self {
			user
		}
	}

	async fn get(&mut self, path: &str) -> TransactionResult {
		self.user.get(path)
			.await?
			.check_ok(self.user)
			.await?;
		Ok(())
	}

	async fn post_json(&mut self, path: &str, json: &'static str) -> TransactionResult {
		self.user.post_raw_json(path, json)
			.await?
			.check_ok(self.user)
			.await?;
		Ok(())
	}
}


async fn scenario_demo_homepage(user: &mut GooseUser) -> TransactionResult {

	let mut script = Script::new(user);

	// load the homepage
	script.get("")
		.await?;

	// then load all the assets on the homepage
	script.get("main.bundle.js")
		.await?;
	script.get("nextPYP_logo.svg")
		.await?;
	script.get("ada6e6df937f7e5e8b79.woff2")
		.await?;
	script.get("favicon.png")
		.await?;

	// then hit the service endpoints
	script.post_json("kv/projects/list", r#"{"id":1,"method":"/kv/projects/list","params":["\"demo\""]}"#)
		.await?;
	script.post_json("kv/projects/listRunning", r#"{"id":2,"method":"/kv/projects/listRunning","params":["\"demo\""]}"#)
		.await?;

	// then load the project images
	script.get("/kv/jobs/efCiM9YiTFwKmwiQ/gainCorrectedImage/small")
		.await?;
	script.get("/kv/reconstructions/efZWGMgi0z5qxdEl/1/2/images/map/small")
		.await?;
	script.get("/kv/jobs/efCijCB2u15nZLdV/gainCorrectedImage/small")
		.await?;
	script.get("/kv/reconstructions/effQfsNXtNUxiJI5/1/2/images/map/small")
		.await?;

	Ok(())
}

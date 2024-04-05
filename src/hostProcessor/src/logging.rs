
use std::ops::Deref;

use anyhow::{Context, Result};
use display_error_chain::ErrorChainExt;
use time::format_description::FormatItem;
use time::macros::format_description;
use time::UtcOffset;
use tracing::{error, warn};
use tracing::dispatcher::DefaultGuard;
use tracing_subscriber::{EnvFilter, FmtSubscriber};
use tracing_subscriber::fmt::format::{Format, Full};
use tracing_subscriber::fmt::time::OffsetTime;


pub fn init(log: impl AsRef<str>) -> Result<()> {

	let log_subscriber = FmtSubscriber::builder()
		.with_env_filter(log_filter(log)?)
		.event_format(log_format())
		.finish();

	tracing::subscriber::set_global_default(log_subscriber)
		.context("Failed to set logging subscriber")?;

	Ok(())
}


pub fn init_test() -> DefaultGuard {

	let subscriber = FmtSubscriber::builder()
		.with_env_filter(log_filter("trace").unwrap())
		.event_format(log_format())
		.with_test_writer()
		.finish();

	tracing::subscriber::set_default(subscriber)
}


fn log_filter(log: impl AsRef<str>) -> Result<EnvFilter> {
	let log = log.as_ref();
	EnvFilter::builder()
		.parse(log)
		.context(format!("Failed to parse log value: {}", log))
}


fn log_format() -> Format<Full,OffsetTime<&'static [FormatItem<'static>]>> {

	let time_format = format_description!(
		version = 2,
		"[year]-[month]-[day] [hour]:[minute]:[second].[subsecond digits:4] [offset_hour sign:mandatory]:[offset_minute]"
	);

	let time_offset = UtcOffset::current_local_offset()
		.unwrap_or(UtcOffset::UTC);

	Format::default()
		.with_timer(OffsetTime::new(time_offset, time_format))
		.with_target(false)
}


pub trait ResultExt<T> {
	fn log_err(self) -> Result<T,()>;
	fn warn_err(self) -> Result<T,()>;
}

impl<T> ResultExt<T> for Result<T,anyhow::Error> {

	fn log_err(self) -> Result<T,()> {
		self.map_err(|e| {
			error!("{}", e.deref().chain());
			()
		})
	}

	fn warn_err(self) -> Result<T,()> {
		self.map_err(|e| {
			warn!("{}", e.deref().chain());
			()
		})
	}
}

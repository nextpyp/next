
use anyhow::{bail, Context};
use semver::Version;

use crate::config::Config;
use crate::script::Script;


const CANONICAL_URL: &'static str = "https://nextpyp.app/files/pyp";


pub fn choose_download_fn(config: &Config) -> Result<String,anyhow::Error> {

	// TODO: allow url override via config
	let url = CANONICAL_URL;

	// find the URL scheme, if any
	let scheme = url.splitn(2, ':')
		.next()
		.with_context(|| format!("Invalid URL: {}", url))?;
	match scheme {

		"file" => bail!("TODO: support local file copies"),

		"http" | "https" => {
			// TODO: support curl?
			// TODO: detect wget progress bar capability?
			download_wget(url, &config.install.version)
		}

		other => bail!("Unrecognized URL scheme: {}", other)
	}
}


fn download_wget(
	url: &str,
	version: &Version
) -> Result<String,anyhow::Error> {

	let mut script = Script::new();

	// download from URL using wget
	let versioned_url = format!("{}/{}", url, version.to_string());
	script.print_template(
		Script::DOWNLOAD_WGET,
		vec![
			("_url", versioned_url)
		],
		vec![]
	);

	Ok(script.to_string())
}

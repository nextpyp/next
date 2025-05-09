
use std::fs;
use std::path::Path;

use toml::Table;


fn main() {

	// read the local.properties file
	let root_dir = Path::new("../../");
	let props_path = root_dir.join("local.properties");
	let props_str = fs::read_to_string(&props_path)
		.expect(&format!("failed to read local.properties from: {}", props_path.to_string_lossy()));

	// look for the `pypDir` property
	let mut pyp_dir = None::<&Path>;
	for line in props_str.lines() {

		if line.starts_with("#") {
			continue;
		}

		let mut parts = line.splitn(2, '=');
		let key = parts.next()
			.expect(&format!("Unrecognizable property: {}", line));
		if key == "pypDir" {
			let value = parts.next()
				.expect(&format!("Unrecognizable property: {}", line));
			pyp_dir = Some(Path::new(value));
		}
	}
	let pyp_dir = pyp_dir
		.expect("local.properties has no pypDir");

	// get the version number from pyp
	let nextpyp_path = root_dir.join(pyp_dir).join("nextpyp.toml");
	let nextpyp_str = fs::read_to_string(&nextpyp_path)
		.expect(&format!("Failed to read nextpyp.toml from: {}", nextpyp_path.to_string_lossy()));
	let nextpyp_toml = nextpyp_str.parse::<Table>()
		.expect("Failed to parse nextpyp.toml");
	let version = nextpyp_toml.get("version")
		.expect("nextpyp.toml missing version")
		.as_str()
		.expect("nextpyp.toml version not string");

	// generate the rust code
	let code = format!("
pub const VERSION: &'static str = \"{version}\";
");

	// write the source file
	let out_dir = std::env::var_os("OUT_DIR")
		.expect("no OUT_DIR");
	let mod_path = Path::new(&out_dir).join("gen.rs");
	fs::write(&mod_path, code)
		.expect(&format!("Failed to write {}", mod_path.to_string_lossy()));
}


use std::path::Path;


pub fn bin_path() -> &'static Path {
	let bin_path = Path::new(env!("CARGO_BIN_EXE_user-processor"));
	if !bin_path.exists() {
		panic!("Target binary not found at: {:?}", bin_path);
	}
	bin_path
}

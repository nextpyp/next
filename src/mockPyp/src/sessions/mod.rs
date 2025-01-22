
pub mod tomo;
pub mod spr;


use std::path::{Path, PathBuf};


fn session_dir(session_group: Option<&str>, session_name: Option<&str>) -> PathBuf {
	match (session_group, session_name) {

		// old-style sessions used the nested group/name folders
		(Some(session_group), Some(session_name)) => Path::new(&session_group).join(&session_name),

		// new-style sessions use the current folder
		_ => PathBuf::from(".")
	}
}


// different integration tests only use parts of the lib,
// but have separate compilation units,
// so unused code warnings are pretty useless here,
// and extremely annoying
#![allow(unused)]

pub mod install_dir;
pub mod cmd;

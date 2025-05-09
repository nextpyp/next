
use std::fs;
use std::path::Path;


pub struct Script {
	lines: Vec<String>
}

impl Script {

	pub fn new() -> Self {
		Self {
			lines: vec!["#!/bin/sh".to_string()]
		}
	}

	pub fn println(&mut self, line: impl Into<String>) {
		self.lines.push(line.into());
	}

	pub fn write(&self, path: impl AsRef<Path>) -> Result<(),std::io::Error> {
		fs::write(path, self.lines.join("\n"))
	}
}

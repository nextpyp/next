
use std::collections::HashMap;

use tokio::process::{Child, ChildStdin};


pub struct Processes {
	procs: HashMap<u32,Proc>
}

impl Processes {

	pub fn new() -> Self {
		Self {
			procs: HashMap::new(),
		}
	}

	pub fn contains(&self, pid: u32) -> bool {
		self.procs.contains_key(&pid)
	}

	pub fn add(&mut self, pid: u32, proc: &mut Child) {
		let proc = Proc {
			pid,
			stdin: proc.stdin.take()
		};
		self.procs.insert(proc.pid, proc);
	}

	pub fn get_mut(&mut self, pid: u32) -> Option<&mut Proc> {
		self.procs.get_mut(&pid)
	}

	pub fn remove(&mut self, pid: u32) -> Option<()> {
		self.procs.remove(&pid)
			.map(|_| ())
	}
}


pub struct Proc {
	pid: u32,
	pub stdin: Option<ChildStdin>
}

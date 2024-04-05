
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

	pub fn add(&mut self, pid: u32, proc: &mut Child) {
		let proc = Proc {
			pid,
			stdin: proc.stdin.take()
		};
		self.procs.insert(proc.pid, proc);
	}

	pub fn remove(&mut self, pid: u32) -> Option<()> {
		self.procs.remove(&pid)
			.map(|_| ())
	}
}


struct Proc {
	pid: u32,
	stdin: Option<ChildStdin>
}

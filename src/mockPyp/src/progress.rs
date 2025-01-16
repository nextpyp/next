
use crate::progress;
use crate::web::Web;


/// Emulates progress messages used by the `tqdm` library
pub struct ProgressBar {
	progress: u32,
	total: u32
}

impl ProgressBar {

	pub fn new(total: u32) -> Self {
		Self {
			progress: 0,
			total
		}
	}

	pub fn _progress(&self) -> u32 {
		self.progress
	}

	pub fn total(&self) -> u32 {
		self.total
	}

	pub fn update(&mut self, work: u32) {
		self.progress += work;
	}

	pub fn report(&self, web: &Web) {

		// messages look like, eg:
		// (unknown file):0 |  0%|          | 0/100 [00:00<?, ?it/s]
		// (unknown file):0 | 10%|#         | 10/100 [00:00<00:04, 19.96it/s]
		// (unknown file):0 | 20%|##        | 20/100 [00:01<00:04, 18.37it/s]
		// (unknown file):0 | 30%|###       | 30/100 [00:01<00:03, 18.78it/s]
		// (unknown file):0 | 40%|####      | 40/100 [00:02<00:03, 18.83it/s]
		// (unknown file):0 | 50%|#####     | 50/100 [00:02<00:02, 18.81it/s]
		// (unknown file):0 | 60%|######    | 60/100 [00:03<00:02, 18.90it/s]
		// (unknown file):0 | 70%|#######   | 70/100 [00:03<00:01, 19.06it/s]
		// (unknown file):0 | 80%|########  | 80/100 [00:04<00:01, 19.22it/s]
		// (unknown file):0 | 90%|######### | 90/100 [00:04<00:00, 19.21it/s]
		// (unknown file):0 |100%|##########| 100/100 [00:05<00:00, 19.16it/s]
		// (unknown file):0 |100%|##########| 100/100 [00:05<00:00, 18.98it/s]
		// or even, eg:
		//   0%|          | 0/1 [00:00<?, ?it/s]

		let percent = self.progress*100/self.total;
		let bar = bar(percent);
		progress!(web, "{:>3}%|{}| {}/{} [00:00<00:05, 5.6it/s]", percent, bar, self.progress, self.total);
	}
}


fn bar(percent: u32) -> String {
	(0 .. 10)
		.map(|i|  {
			let min = i*10;
			let max = min + 10;
			if percent <= min {
				' '
			} else if percent >= max {
				'#'
			} else {
				match percent - min {
					1 => '1',
					2 => '2',
					3 => '3',
					4 => '4',
					5 => '5',
					6 => '6',
					7 => '7',
					8 => '8',
					9 => '9',
					_ => '?' // shouldn't happen, since (percent - min) \in [1,9]
				}
			}
		})
		.collect::<String>()
}


#[cfg(test)]
mod test {

	use super::*;


	#[test]
	fn bar_values() {
		assert_eq!(bar(  0).as_str(), "          ");
		assert_eq!(bar(  1).as_str(), "1         ");
		assert_eq!(bar(  2).as_str(), "2         ");
		assert_eq!(bar(  8).as_str(), "8         ");
		assert_eq!(bar(  9).as_str(), "9         ");
		assert_eq!(bar( 10).as_str(), "#         ");
		assert_eq!(bar( 11).as_str(), "#1        ");
		assert_eq!(bar( 12).as_str(), "#2        ");
		assert_eq!(bar( 18).as_str(), "#8        ");
		assert_eq!(bar( 19).as_str(), "#9        ");
		assert_eq!(bar( 20).as_str(), "##        ");
		assert_eq!(bar( 21).as_str(), "##1       ");
		assert_eq!(bar( 90).as_str(), "######### ");
		assert_eq!(bar( 91).as_str(), "#########1");
		assert_eq!(bar( 92).as_str(), "#########2");
		assert_eq!(bar( 98).as_str(), "#########8");
		assert_eq!(bar( 99).as_str(), "#########9");
		assert_eq!(bar(100).as_str(), "##########");
	}
}

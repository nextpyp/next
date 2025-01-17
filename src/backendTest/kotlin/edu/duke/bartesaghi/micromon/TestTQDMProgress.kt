package edu.duke.bartesaghi.micromon

import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe


@EnabledIf(RuntimeEnvironment.Host::class)
class TestTQDMProgress : DescribeSpec({

	describe("TQDM progress") {

		val lines = listOf(
			/*  0 */ "(unknown file):0 |  0%|          | 0/100 [00:00<?, ?it/s]",
			/*  1 */ "(unknown file):0 | 10%|#         | 10/100 [00:00<00:04, 19.96it/s]",
			/*  2 */ "(unknown file):0 | 20%|##        | 20/100 [00:01<00:04, 18.37it/s]",
			/*  3 */ "(unknown file):0 | 30%|###       | 30/100 [00:01<00:03, 18.78it/s]",
			/*  4 */ "(unknown file):0 | 40%|####      | 40/100 [00:02<00:03, 18.83it/s]",
			/*  5 */ "(unknown file):0 | 50%|#####     | 50/100 [00:02<00:02, 18.81it/s]",
			/*  6 */ "(unknown file):0 | 60%|######    | 60/100 [00:03<00:02, 18.90it/s]",
			/*  7 */ "(unknown file):0 | 70%|#######   | 70/100 [00:03<00:01, 19.06it/s]",
			/*  8 */ "(unknown file):0 | 80%|########  | 80/100 [00:04<00:01, 19.22it/s]",
			/*  9 */ "(unknown file):0 | 90%|######### | 90/100 [00:04<00:00, 19.21it/s]",
			/* 10 */ "(unknown file):0 |100%|##########| 100/100 [00:05<00:00, 19.16it/s]",
			/* 11 */ "(unknown file):0 |100%|##########| 100/100 [00:05<00:00, 18.98it/s]",
			/* 12 */ "  0%|          | 0/1 [00:00<?, ?it/s]",
			/* 13 */ "Progress:  10%|#         | 10/100 [00:00<00:04, 19.96it/s]",
			/* 14 */ "Progress:  98%|#####8    | 88/100 [00:00<00:04, 19.96it/s]",
			/* 15 */ "  0%|          | 37/42 [?<?, 5beers/florp]",
			/* 16 */ "\r  5%|5         | 5/7 [?<?, 5a/b]",
			/* 17 */ "2025-01-16 15:05:26.9869 -05:00  INFO MockPyp: 83%|########3 | 83/100 [00:00<00:05, 5.6it/s]",
			// sometimes, we get whitespace at the end of the lines too
			/* 18 */ "  5%|5         | 5/7 [?<?, 5a/b]\r",
			/* 19 */ "  5%|5         | 5/7 [?<?, 5a/b] ",
		)


		it("is regex") {
			for (line in lines) {
				withClue("line: `$line`") {
					TQDMProgressInfo.isProgress(line) shouldBe true
				}
			}
		}


		it("from") {

			TQDMProgressInfo.from(lines[0]).shouldNotBeNull().apply {
				value shouldBe 0
				max shouldBe 100
				timeElapsed shouldBe "00:00"
				timeRemaining shouldBe null
				rate shouldBe null
				unit shouldBe "it/s"
			}

			TQDMProgressInfo.from(lines[1]).shouldNotBeNull().apply {
				value shouldBe 10
				max shouldBe 100
				timeElapsed shouldBe "00:00"
				timeRemaining shouldBe "00:04"
				rate shouldBe 19.96.plusOrMinus(0.001)
				unit shouldBe "it/s"
			}

			TQDMProgressInfo.from(lines[8]).shouldNotBeNull().apply {
				value shouldBe 80
				max shouldBe 100
				timeElapsed shouldBe "00:04"
				timeRemaining shouldBe "00:01"
				rate shouldBe 19.22.plusOrMinus(0.001)
				unit shouldBe "it/s"
			}

			TQDMProgressInfo.from(lines[12]).shouldNotBeNull().apply {
				value shouldBe 0
				max shouldBe 1
				timeElapsed shouldBe "00:00"
				timeRemaining shouldBe null
				rate shouldBe null
				unit shouldBe "it/s"
			}

			TQDMProgressInfo.from(lines[14]).shouldNotBeNull().apply {
				value shouldBe 88
				max shouldBe 100
				timeElapsed shouldBe "00:00"
				timeRemaining shouldBe "00:04"
				rate shouldBe 19.96.plusOrMinus(0.001)
				unit shouldBe "it/s"
			}

			TQDMProgressInfo.from(lines[15]).shouldNotBeNull().apply {
				value shouldBe 37
				max shouldBe 42
				timeElapsed shouldBe null
				timeRemaining shouldBe null
				rate shouldBe 5.0
				unit shouldBe "beers/florp"
			}

			TQDMProgressInfo.from(lines[16]).shouldNotBeNull().apply {
				value shouldBe 5
				max shouldBe 7
				timeElapsed shouldBe null
				timeRemaining shouldBe null
				rate shouldBe 5.0
				unit shouldBe "a/b"
			}

			TQDMProgressInfo.from(lines[17]).shouldNotBeNull().apply {
				value shouldBe 83
				max shouldBe 100
				timeElapsed shouldBe "00:00"
				timeRemaining shouldBe "00:05"
				rate shouldBe 5.6
				unit shouldBe "it/s"
			}
		}


		it("collapse") {

			// no progress info
			"""
				|hello world
				|this is only a test
				|of the emergency broadcast system
			""".trimMargin().collapseProgress() shouldBe """
				|hello world
				|this is only a test
				|of the emergency broadcast system
			""".trimMargin()

			// progress bar only
			"""
				|  0%|          | 0/3 [00:00<?, ?it/s]
				| 33%|##3       | 1/3 [00:00<?, ?it/s]
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|100%|##########| 3/3 [00:00<?, ?it/s]
			""".trimMargin().collapseProgress() shouldBe """
				|100%|##########| 3/3 [00:00<?, ?it/s]
			""".trimMargin()

			// progress bar at the start
			"""
				|  0%|          | 0/3 [00:00<?, ?it/s]
				| 33%|##3       | 1/3 [00:00<?, ?it/s]
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress
			""".trimMargin().collapseProgress() shouldBe """
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress
			""".trimMargin()

			// progress bar in the middle
			"""
				|not progress: before edition
				|  0%|          | 0/3 [00:00<?, ?it/s]
				| 33%|##3       | 1/3 [00:00<?, ?it/s]
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: after edition
			""".trimMargin().collapseProgress() shouldBe """
				|not progress: before edition
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: after edition
			""".trimMargin()

			// progress bar at the end
			"""
				|not progress
				|  0%|          | 0/3 [00:00<?, ?it/s]
				| 33%|##3       | 1/3 [00:00<?, ?it/s]
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|100%|##########| 3/3 [00:00<?, ?it/s]
			""".trimMargin().collapseProgress() shouldBe """
				|not progress
				|100%|##########| 3/3 [00:00<?, ?it/s]
			""".trimMargin()

			// progress bar with extra newlines
			// observed in pyp stdout
			// possibly emitted by a logging library trying to use console control characters or non-standard newlines
			// but they're getting re-encoded or translated somehow before reaching the website,
			// so they just show up as extra newlines
			"""
				|not progress: the before times
				|
				|  0%|          | 0/3 [00:00<?, ?it/s]
				|
				|  5%|5         | 1/3 [00:00<?, ?it/s]
				|
				| 17%|#7        | 1/3 [00:00<?, ?it/s]
				|
				| 27%|##7       | 1/3 [00:00<?, ?it/s]
				|
				| 31%|###1      | 1/3 [00:00<?, ?it/s]
				|
				| 33%|###3      | 1/3 [00:00<?, ?it/s]
				|
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin().collapseProgress() shouldBe """
				|not progress: the before times
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin()

			// progress bar with extra whitespace at the end
			// observed in pyp stdout (WHY?!?!?!)
			"""
				|not progress: the before times
				|
				|  0%|          | 0/3 [00:00<?, ?it/s]
				|
				| 33%|###3      | 1/3 [00:00<?, ?it/s] 
				|
				| 66%|######6   | 2/3 [00:00<?, ?it/s]
				|
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin().collapseProgress() shouldBe """
				|not progress: the before times
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin()

			// progress bar with extra carraige returns
			// observed in pyp streaming log messages
			// NOTE: build a raw line sequence here so we can deliberately make bad newline encodings
			sequenceOf(
				"not progress: the before times",
				"\r  0%|          | 0/3 [00:00<?, ?it/s]",
				"\r 33%|##3       | 1/3 [00:00<?, ?it/s]",
				"\r 66%|######6   | 2/3 [00:00<?, ?it/s]",
				"\r100%|##########| 3/3 [00:00<?, ?it/s]",
				"not progress: aftermath"
			).collapseProgress(
				liner = { it },
				transformer = carriageReturnTrimmer(
					liner = { it },
					factory = { _, line -> line }
				)
			).joinToString("\n") shouldBe """
				|not progress: the before times
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin()

			// just in case the carriage returns show up at the end of the line too
			sequenceOf(
				"not progress: the before times",
				"  0%|          | 0/3 [00:00<?, ?it/s]\r",
				"",
				" 11%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 22%|          | 1/3 [00:00<?, ?it/s]",
				"",
				" 33%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 44%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 55%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 66%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 77%|          | 1/3 [00:00<?, ?it/s]",
				"",
				" 88%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				" 99%|          | 1/3 [00:00<?, ?it/s]\r",
				"",
				"100%|##########| 3/3 [00:00<?, ?it/s]\r",
				"not progress: aftermath"
			).collapseProgress(
				liner = { it },
				transformer = carriageReturnTrimmer(
					liner = { it },
					factory = { _, line -> line }
				)
			).joinToString("\n") shouldBe """
				|not progress: the before times
				|100%|##########| 3/3 [00:00<?, ?it/s]
				|not progress: aftermath
			""".trimMargin()
		}
	}
})

package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.EphemeralMongo
import edu.duke.bartesaghi.micromon.RuntimeEnvironment
import edu.duke.bartesaghi.micromon.mongo.Database
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec


@EnabledIf(RuntimeEnvironment.Website::class)
class TestPypArgs : DescribeSpec({

	describe("pyp args") {

		it("sends nothing for default values") {

			// make a database
			EphemeralMongo.start().install {
				println("Database installed")

				// TEMP
				println("Users: ${Database.instance.users.getAllUsers().size}")
			}
			println("database exited!")

			// TODO: make a user, make a project, make a block
		}

		/* TODO
		it("sends something for non-default values") {
		}
		*/

		/* TODO
		it("resends nothing for unchanged values") {
		}
		*/

		/* TODO
		it("resends something for changed values") {
		}
		*/

		/* TODO
		it("resends something for re-defaulted values") {
		}
		*/
	}
})

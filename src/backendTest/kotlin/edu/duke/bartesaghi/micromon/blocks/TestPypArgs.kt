package edu.duke.bartesaghi.micromon.blocks

import edu.duke.bartesaghi.micromon.*
import edu.duke.bartesaghi.micromon.services.IProjectsService
import edu.duke.bartesaghi.micromon.services.ProjectData
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import kotlin.io.path.div


@EnabledIf(RuntimeEnvironment.Website::class)
class TestPypArgs : DescribeSpec({

	describe("pyp args") {

		it("sends nothing for default values") {

			EphemeralMongo.start().useInstalled {
				EphemeralWebsite().useInstalled { website ->

					// TEMP
					println("shared dir? ${Config.instance.web.sharedDir}")
					println("users: ${Config.instance.web.sharedDir / "users"}")

					// list projects
					val projects: List<ProjectData> = website.services.rpc(IProjectsService::list, User.NoAuthId)
					println("projects: $projects")

					// TODO: NEXTTIME: make a project, make a block
				}
			}
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

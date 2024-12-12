package edu.duke.bartesaghi.micromon.services

import edu.duke.bartesaghi.micromon.linux.userprocessor.Response
import edu.duke.bartesaghi.micromon.linux.userprocessor.deleteAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.statAs
import edu.duke.bartesaghi.micromon.linux.userprocessor.writerAs
import edu.duke.bartesaghi.micromon.respondExceptions
import edu.duke.bartesaghi.micromon.use
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path


class FileUpload(
	val path: Path,
	val osUsername: String?
) {

	suspend fun data(): FileUploadData? =
		when (val stat = path.statAs(osUsername)) {
			is Response.Stat.File -> FileUploadData(stat.size.toLong())
			else -> null
		}


	companion object {

		fun routeHandler(getUploader: suspend PipelineContext<Unit,ApplicationCall>.(ProjectPermission) -> FileUpload): Route.() -> Unit {

			return {

				post(FileUploadOperation.Set.id) {
					call.respondExceptions r@{

						val uploader = getUploader(ProjectPermission.Write)

						// get the file stream from the HTTP request
						val input = when (val part = call.receiveMultipart().readPart()) {
							is PartData.BinaryItem -> part.provider()
							is PartData.FileItem -> part.provider()
							else -> return@r
						}

						// stream the upload into the file
						uploader.path.writerAs(uploader.osUsername).use { writer ->
							while (true) {

								var buf = ByteArray(32*1024)
								val bytesRead = input.readAvailable(buf)
								if (bytesRead <= 0) {
									break
								} else if (bytesRead < buf.size) {
									// truncate the buffer
									buf = buf.copyOf(bytesRead)
								}
								writer.write(buf)

								/* WARNING:
									The ByteBuffer verison of this copy loop doesn't work!!
									There's a bug in ktor somewhere ... maybe in Input.readAvailable() ?
									The bug causes the buffer contents after readAvailable() to be incorrect!
									We're using ktor v1.6.0 now, but the bug still exists in v1.6.8.
									Ktor versions after that are 2.x.x, so we probably don't want to upgrade any time soon.

								val buf = ByteBuffer.allocate(32*1024)
								buf.clear()
								val bytesRead = input.readAvailable(buf)
								if (bytesRead <= 0) {
									break
								}
								buf.flip()
								writer.write(buf)
								*/
							}
						}

						call.respondOk()
					}
				}

				get(FileUploadOperation.Get.id) {
					call.respondExceptions {
						val uploader = getUploader(ProjectPermission.Read)
						call.respondStreamingWriter(uploader.path, uploader.osUsername)
					}
				}

				get(FileUploadOperation.Delete.id) {
					call.respondExceptions {
						val uploader = getUploader(ProjectPermission.Read)
						uploader.path.deleteAs(uploader.osUsername)
						call.respondOk()
					}
				}

				get(FileUploadOperation.Data.id) {
					call.respondExceptions {
						val uploader = getUploader(ProjectPermission.Read)
						val json = Json.encodeToString(uploader.data().toOption())
						call.respondText(json, ContentType.Application.Json)
					}
				}
			}
		}
	}
}

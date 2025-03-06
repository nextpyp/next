package edu.duke.bartesaghi.micromon.linux

import edu.duke.bartesaghi.micromon.RuntimeEnvironment
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Paths


@EnabledIf(RuntimeEnvironment.Host::class)
class TestDF : DescribeSpec({

	describe("df") {

		it("filesystems") {

			val out = DF.parse("""
				|Filesystem                                                   Type           1B-blocks            Used      Available Use% Mounted on
				|udev                                                         devtmpfs     97055465472               0    97055465472   0% /dev
				|tmpfs                                                        tmpfs        19416129536        13156352    19402973184   1% /run
				|/dev/mapper/ubuntuos-root                                    xfs          40852606976     15195656192    25656950784  38% /
				|xxxxxxxxxxxxxxxxxxxxxxxxx:xxx/xxxxxxxxxxxxx/xxxxxxxxxxxxxxxx nfs      111111111111111 222222222222222 33333333333333  92% /nfs/foo
			""".trimMargin().lines())

			out.errors shouldBe emptyList()

			out.filesystems[0] shouldBe DF.Filesystem(
				name = "udev",
				type = "devtmpfs",
				bytes = 97055465472L,
				bytesUsed = 0L,
				mountedOn = Paths.get("/dev")
			)

			out.filesystems[1] shouldBe DF.Filesystem(
				name = "tmpfs",
				type = "tmpfs",
				bytes = 19416129536L,
				bytesUsed = 13156352L,
				mountedOn = Paths.get("/run")
			)

			out.filesystems[2] shouldBe DF.Filesystem(
				name = "/dev/mapper/ubuntuos-root",
				type = "xfs",
				bytes = 40852606976L,
				bytesUsed = 15195656192L,
				mountedOn = Paths.get("/")
			)

			out.filesystems[3] shouldBe DF.Filesystem(
				name = "xxxxxxxxxxxxxxxxxxxxxxxxx:xxx/xxxxxxxxxxxxx/xxxxxxxxxxxxxxxx",
				type = "nfs",
				bytes = 111111111111111L,
				bytesUsed = 222222222222222L,
				mountedOn = Paths.get("/nfs/foo")
			)

			out.filesystems.size shouldBe 4
		}

		it("errors") {

			val out = DF.parse("""
				|df: /cifs/Krios-EPUData: Resource temporarily unavailable
				|df: /cifs/Krios-OffloadData: Host is down
				|df: /cifs/Krios-gatan: Host is down
				|Filesystem                                                                                                        Type           1B-blocks            Used      Available Use% Mounted on
				|overlay                                                                                                           overlay         16777216          126976       16650240   1% /
				|udev                                                                                                              devtmpfs     20852178944               0    20852178944   0% /dev
				|tmpfs                                                                                                             tmpfs        20909326336           16384    20909309952   1% /dev/shm
				|/dev/mapper/ubuntuos-root--lv                                                                                     xfs          26830438400     12585590784    14244847616  47% /tmp
				|tmpfs                                                                                                             tmpfs           16777216          126976       16650240   1% /.singularity.d/libs
				|oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab/micromon/research-bartesaghilab-06/config.toml       nfs      109951162777600  92627908689920 17323254087680  85% /var/micromon/config.toml
				|/dev/sdb1                                                                                                         xfs         238252199936     16000958464   222251241472   7% /scratch/local
				|oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab/micromon/research-bartesaghilab-06/shared            nfs      109951162777600  92627908689920 17323254087680  85% /nfs/bartesaghilab/micromon/research-bartesaghilab-06/shared
				|oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab                                                      nfs      109951162777600  92627908689920 17323254087680  85% /nfs/bartesaghilab
				|oit-nas-fe13.oit.duke.edu:/ifs/oit-nas-fe13/bartesaghi-lab2                                                       nfs      329853488332800 324884540424192  4968947908608  99% /nfs/bartesaghilab2
				|rcgroups-nas-fe13.dscr.duke.local:/cryoem                                                                         nfs4     467292441804800 387675336998912 79617104805888  83% /hpc/group/cryoem
				|/dev/mapper/ubuntuos-home--lv                                                                                     xfs           5358223360      1876484096     3481739264  36% /home/nextpyp/.ssh/id_rsa
				|oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab/micromon/research-bartesaghilab-06/workflows         nfs      109951162777600  92627908689920 17323254087680  85% /nfs/bartesaghilab/micromon/research-bartesaghilab-06/workflows
				|oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab/micromon/research-bartesaghilab-06/cluster-templates nfs      109951162777600  92627908689920 17323254087680  85% /nfs/bartesaghilab/micromon/research-bartesaghilab-06/cluster-templates
			""".trimMargin().lines())

			out.errors[0] shouldBe "df: /cifs/Krios-EPUData: Resource temporarily unavailable"
			out.errors[1] shouldBe "df: /cifs/Krios-OffloadData: Host is down"
			out.errors[2] shouldBe "df: /cifs/Krios-gatan: Host is down"
			out.errors.size shouldBe 3

			// spot-check a few of the filesystems
			out.filesystems[0] shouldBe DF.Filesystem(
				name = "overlay",
				type = "overlay",
				bytes = 16777216L,
				bytesUsed = 126976L,
				mountedOn = Paths.get("/")
			)

			out.filesystems[9] shouldBe DF.Filesystem(
				name = "oit-nas-fe13.oit.duke.edu:/ifs/oit-nas-fe13/bartesaghi-lab2",
				type = "nfs",
				bytes = 329853488332800L,
				bytesUsed = 324884540424192L,
				mountedOn = Paths.get("/nfs/bartesaghilab2")
			)

			out.filesystems[10] shouldBe DF.Filesystem(
				name = "rcgroups-nas-fe13.dscr.duke.local:/cryoem",
				type = "nfs4",
				bytes = 467292441804800L,
				bytesUsed = 387675336998912L,
				mountedOn = Paths.get("/hpc/group/cryoem")
			)

			out.filesystems[13] shouldBe DF.Filesystem(
				name = "oit-nas-fe13f.oit.duke.edu:/ifs/oit-nas-fe13f/bartesaghi-lab/micromon/research-bartesaghilab-06/cluster-templates",
				type = "nfs",
				bytes = 109951162777600L,
				bytesUsed = 92627908689920L,
				mountedOn = Paths.get("/nfs/bartesaghilab/micromon/research-bartesaghilab-06/cluster-templates")
			)

			out.filesystems.size shouldBe 14
		}
	}
})


# Contributing to MicroMon

## Prerequisites

 * **Java:**\
   [Download the latest LTS release for the JDK](https://adoptium.net/), currently v17 at the time of this writing.
 * **VirtualBox:**\
   [Any recent version should be fine.](https://www.virtualbox.org/).
   At the time of this writing, the most recent version is 6.1.
   Note that VirtualBox is not supported on ARM CPUs (e.g. Apple M1 and the like),
   So we strongly recommend using an `x86_64`/`amd64` CPU for development.


### IDE

MicroMon is written mostly in the [Kotlin][kotlin] programming language, with a little bit of JavaScript here and there.
You're welcome to use any IDE you like (or not, text editors will work too), but [IntelliJ IDEA][intellij] currently
offers the best development experience for Kotlin development, and has excellent integration with Gradle (the build
system) too, so we strongly recommend using Intellij IDEA for development here. The Professional version (not free) has
lots of fun bells and whistles, but isn't necessary for this project. The Community version (free) is more than
enough to work on MicroMon.

[kotlin]: https://kotlinlang.org/
[intellij]: https://www.jetbrains.com/idea/


## Clone the git repository

```shell
git clone https://github.com/nextpyp/next.git
```

## Building

MicroMon uses the [Gradle Build Tool](https://gradle.org/) to manage compilation and library dependencies.

Gradle can be run directly from the command line using the `gradlew` wrapper script. But it's much easier
to use the integration with IntelliJ. Just import the project into IntelliJ and look for the Gradle tab
on the right side of the window.

Inside the Gradle tab, the refresh-looking icon on the very left will synchronize Gradle with IntelliJ.
Synchronization also downloads all the development libraries needed to compile and run the project.
The project import process should have already synchronized the project, but it never hurts to sync it again.
When synchronizing Gradle, look for the `Build` tab (at the bottom of the main window) for the console output.
Any errors that happen (hopefully none) will be reported in the `Build` tab.

To run Gradle tasks, find the task name in the Gradle tab in IntelliJ (usually in a folder) and double-click on it.
Then look for the output in the `Run` tab on the bottom of the main window.

Or you can run the task on the command line if you want. Just run:
```shell
./gradlew <task-name>
```

Before we can actually call various Gradle tasks to build the project, we first need to set up the
virtualized development environment.


### Virtualized Development Environment

To simplify the development environment for MicroMon, we've virtualized the entire backend using VirtualBox.
Before you can run the development version of MicroMon, you'll need to create and configure your virtual machine.
Most of this process has been automated using Gradle tasks, but there are still some manual steps.

1. First, run the `vmCreate` task in Gradle (in the `dev` folder).
   This will create the VM and start the OS installer. We're using [Debian Linux][debian] as our development environment,
   since it's a better fit for standalone VMs than RHEL and its derivatives.
   1. If the script fails with an error like "pyp folder not found", edit the `local.properties` file
      and set the correct path to pyp in the `pypDir` variable. This assumes you've cloned the pyp
      git repository to a local path somewhere.

2. Once Debian is up and running in side the VM, run these steps manually to configure the operating system:
   1. Choose `Graphical Install` at the GRUB menu.
   2. Then choose your language, location, and keyboard.
   3. After a few progress bars, choose your primary network interface: `enp0s3`.
   4. Chooose a hostname: `nextpyp`.
   5. On the next screen, leave domain name blank.
   6. On the next screen, leave the root password blank.
      This will disable the root account entirely, which is what we want.
   7. On the next screen, enter your name if you want. Or leave it blank. It doesn't matter.
   8. On the next screen, enter your username. *Choose the same username as your host OS!*
   9. After entering your username, enter the password `dummypassword`.
      Debian won't let you leave this one blank and the value has to be long enough to make the installer happy.
      *It's important to use a dummy password here and not a real password
      because the build scripts have to store the password in plaintext!*
   10. Next, choose your time zone.
   11. On the `Partition Disks` screen, choose `Guided - use entire disk`.
   12. Then choose the one disk option presented.
   13. Then choose `All files in one partition`.
   14. Then choose `Finish partitioning and write changes to disk`.
   15. Then choose `Yes` to write the changes to the VM's disk.
   16. Installing files will take a few moments ...
   17. After installation is complete, you can skip the optional step to `Scan extra installation media`.
   18. Next, choose your country to configure the pacakge manager.
   19. Next, choose your debian mirror. The default `deb.debian.org` is a fine choice.
   20. HTTP proxy info can be left blank.
   21. Next, the installer will install OS packages, which will also take a few moments ...
   22. When it's done, you can also skip the optinal participation in the package popularity contest.
   23. Next, since this will be a headless VM, we can un-select the desktop environments to skip installing them. Uncheck:
       * Debian Desktop Environment
       * ... GNOME
       Leave `standard system utilities` checked.
   24. After pacakge installation finished, choose `yes` to install GRUB to the primary drive.
       Choose the option that starts with e.g., `/dev/sda`
   25. Next, choose `continue` to reboot the VM.
   
3. Configure the Debian VM:
   1. When the VM reboots, log in with your username and password `dummypassword`.
   2. Once logged in, install the keyring for the package repository we're about to add:
       ```shell
       sudo apt install fasttrack-archive-keyring
       ```
   3. Once logged in, create the file `/etc/apt/sources.list.d/virualbox-guest.list` with the following contents:
       ```
       deb http://deb.debian.org/debian bookworm-backports main contrib
       deb http://fasttrack.debian.net/debian-fasttrack/ bookworm-fasttrack main contrib
       deb http://fasttrack.debian.net/debian-fasttrack/ bookworm-backports-staging main contrib
       ```
       You may want to install vim? `sudo apt install vim`.
       Tragically, I don't know how to copy text from the host OS and paste into the guest OS, so you'll have to
       type the file path and contents by hand. =(
   4. Once complete, run `sudo apt update`.
   5. Then, install the VirtualBox guest additions:
       ```shell
       sudo apt install -y virtualbox virtualbox-ext-pack virtualbox-guest-utils
       ```
   6. Agree to the Oracle licence for VirtualBox
   7. Installation will take a few moments ...
   8. When installation is finished, you can shutdown the VM:
      ```shell
      sudo shutdown now
      ```

4. Now that we have guest additions in the VM now, the rest of the VM setup can be completely automated.
   Just run the `vmSetup` task in Gradle.

5. Finally, bootstrap your MicroMon environment configuration by running the `vmGenerateConfig` task in Gradle (in the `run` folder).
   This will create the `run/config.toml` file that describes your development environment to MicroMon.
   Although you may need to edit this file in the future, the defaults should be good enough for now to get started.

Now that you (hopefully) have a working VM, here are some general Gradle tasks to operate it:

 * **vmStart:** Starts the VM in headless mode (no window showing the console).
   If you want to SSH into the VM, instructions for doing that should be printed to the task output
   (in the `Run` tab of the main IntelliJ window).
 * **vmStop:** Stops the running VM.

You can also still manage and interact with the VM using the usual VirtualBox tools, but the Gradle
integration here just makes it easier to automate some common development tasks.

[debian]: https://debian.org/


### Building and running MicroMon

Now that we have a working VM, building and running MicroMon is handled by just a few Gradle tasks
(in the `run` folder):

 * **vmContainerRun:** Builds and runs the MicroMon application server. Once running, the console output for the task
   will show the URL you can use to access the website. The application server runs inside of an
   [Apptainer/Singularity][apptainer] container, which is itself inside of the Linux VM.
   
 * **vmContainerStop:** Stops the application server, and its container
 * **vmContainerRerun:** Runs `vmContainerStop` and then `vmContainerRun` as a shortcut.

[apptainer]: http://apptainer.org/


### Typical Workflow

The typical workflow for development is to start by running these tasks:
 1. `vmStart`
 2. `vmContainerRerun`

Then do your development work. Run `vmContainerRerun` as needed to deploy your changes to the development VM.

When you're all done, run:
 1. `vmContainerStop`
 2. `vmStop`


## Building releases

To build the release singularity container, run the `vmBuildNextPyp`
Gradle task. When finished, the container will appear at `run/nextPYP.sif`.

To build the reverse proxy container, run the `vmBuildReverseProxy`
Gradle task. When finished, the container will appear at `run/reverse-proxy.sif`.


## Tips and Tricks

If for some reason, webpack runs out of memory (or any node process),
you can set the node heap size by adding an environment variable to your
Run/Debug configuration in IntelliJ IDEA:
```
NODE_OPTIONS=--max-old-space-size=2048
```

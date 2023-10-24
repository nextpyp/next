
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

TODO: these instructions will need to be written after the repo becomes publicly available


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
   This will create the VM and start the OS installer. We're using [Rocky Linux][rocky] as our development environment,
   since it's the spiritual successor to [CentOS][centos], the once widely-available downstream version of [RHEL][rhel].
   1. If the script fails with an error like "pyp folder not found", edit the `local.properties` file
      and set the correct path to pyp in the `pypDir` variable. This assumes you've cloned the pyp
      git repository to a local path somewhere. Make sure you're using the `micromon` branch of pyp.

2. Once Rocky is up and running inside the VM, run these steps manually to configure the operating system:
   1. Choose your language from the UI (usually en_us, right?).
   2. For the `Installation Destination` section, just accept the defaults.
   3. For the `Network & Host Name` section, choose a host name for your VM.
      You'll use this hostname to SSH into the VM over a local network.
      The choice here is arbitrary and up to you, but if you're looking for a suggestion, try `micromon`.
   4. For the `Root Password` section, leave this alone. Don't pick a root password.
   5. For the `User Creation` section (you might have to scroll down to see it), create your user.
      Choose the same username as your host computer!
      Check the administrator option.
      Uncheck the password option. Password-protecting the development VM causes lots of inconvience,
      and doesn't really provide any useful security here.
   6. All done! Begin the installation.
   7. When the installer is done, don't reboot the VM, like it asks. Instead, shutdown the VM.
      In the VirtualBox menu bar for the VM, find the `machine` option, then pick `ACPI Shutdown` from the dropdown.

3. Then, install updates for Rocky Linux. Run the `vmUpdate` task in Gradle.
   Once the VM loads to a terminal (you should already be logged in), run the update command:
   ```shell
   sudo sed -i 's/ONBOOT=no/ONBOOT=yes/g' /etc/sysconfig/network-scripts/*
   sudo ifup enp0s3
   sudo dnf update -y
   ```
   The first command tells Rocky Linux to turn on the network interfaces at boot time.
   The second command tells the running Linux VM to turn on the outgoing network interface now, so
   you can download things from the internet.
   The third command actually downloads the operating system updates and installs them.
   After the updates are finished, shut down the VM:
   ```shell
   shutdown now
   ```

4. Next, install the guest additions for the VM. Run the `vmGuestAdditions` task in Gradle.
   Once the VM loads into a terminal, run these commands:
   ```shell
   sudo dnf install -y kernel-devel kernel-headers gcc make bzip2 perl elfutils-libelf-devel
   sudo mount /dev/sr0 /mnt
   sudo /mnt/VBoxLinuxAdditions.run
   ```
   You can try to see if VirtualBox will let you paste the commands (using a menu option maybe?), but since we
   haven't installed the guest additions yet, those options might not be available. It's worth looking to see
   if your VirtualBox UI on your OS here has any options though.
   When the guest additions are done installing (and compiling, ugh), shut down the VM:
   ```shell
   shutdown now
   ```

5. Now that we have guest additions in the VM now, the rest of the VM setup can be completely automated.
   Just run the `vmSetup` task in Gradle.

6. Finally, bootstrap your MicroMon environment configuration by running the `vmGenerateConfig` task in Gradle (in the `run` folder).
   This will create the `run/config.toml` file that describes your development environment to MicroMon.
   Although you may need to edit this file in the future, the defaults should be good enough for now to get started.

Now that you (hopefully) have a working VM, here are some general Gradle tasks to operate it:

 * **vmStart:** Starts the VM in headless mode (no window showing the console).
   If you want to SSH into the VM, instructions for doing that should be printed to the task output
   (in the `Run` tab of the main IntelliJ window).
 * **vmStop:** Stops the running VM.

You can also still manage and interact with the VM using the usual VirtualBox tools, but the Gradle
integration here just makes it easier to automate some common development tasks.

[rocky]: https://rockylinux.org/
[centos]: https://www.centos.org/
[rhel]: https://www.redhat.com/en/technologies/linux-platforms/enterprise-linux


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


## Git protocols

This Git repository depends on the `micromon` branch of the `pyp` repository.

We don't use fancy git tools like `subrepo` or `subtree` anymore. Just push and pull
your pyp changes directly to the `micromon` branch in the `pyp` repository.

Easy peasy.


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

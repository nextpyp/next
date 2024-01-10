#! /bin/sh

java @bin/classpath.txt -Dlogback.configurationFile=logback-cli.xml edu.duke.bartesaghi.micromon.CliKt $@

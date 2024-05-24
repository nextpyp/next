#! /bin/sh

# build the JVM args and show the environmental config
echo Starting MicroMon with:
jvmargs=

# set the JVM max heap size
echo "  JVM heap size: $NEXTPYP_HEAPMIB MiB"
jvmargs="$jvmargs -Xmx${NEXTPYP_HEAPMIB}M"

# explicitly pick the production logging configuration
# since sometimes logback likes to default to the testing one for some reason
jvmargs="$jvmargs -Dlogback.configurationFile=logback.xml"

if [ -n "$NEXTPYP_JMX" ]; then
  echo "  JMX remote monitoring ON"
  jvmargs="$jvmargs -Dcom.sun.management.jmxremote.ssl=false"
  jvmargs="$jvmargs -Dcom.sun.management.jmxremote.authenticate=false"
  jvmargs="$jvmargs -Dcom.sun.management.jmxremote.port=9010"
  jvmargs="$jvmargs -Dcom.sun.management.jmxremote.rmi.port=9011"
  jvmargs="$jvmargs -Djava.rmi.server.hostname=localhost"
  jvmargs="$jvmargs -Dcom.sun.management.jmxremote.local.only=false"
else
  echo "  JMX remote monitoring off"
fi

if [ -n "$NEXTPYP_OOMDUMP" ]; then
  echo "  Will perform heap dump on out-of-memory errors"
  jvmargs="$jvmargs -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
else
  echo "  Will not perform heap dump on out-of-memory errors"
fi

if [ -n "$NEXTPYP_LOGS" ]; then
  jvmargs="$jvmargs -Dlogback.configurationFile=logback-test.xml"
fi

# turn on headless mode so the JVM doesn't try to look for a display/GPU
jvmargs="$jvmargs -Djava.awt.headless=true"

java $jvmargs @bin/classpath.txt edu.duke.bartesaghi.micromon.MainKt

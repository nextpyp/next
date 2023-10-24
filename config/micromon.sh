#! /bin/sh

# show the environmental config
echo Starting MicroMon with:
echo "  JVM heap size: $NEXTPYP_HEAPMIB MiB"

if [ -n "$NEXTPYP_JMX" ]; then
  echo "  JMX remote monitoring ON"
  jmx="-Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.port=9010 \
    -Dcom.sun.management.jmxremote.rmi.port=9011 \
    -Djava.rmi.server.hostname=localhost \
    -Dcom.sun.management.jmxremote.local.only=false"
else
  echo "  JMX remote monitoring off"
fi

if [ -n "$NEXTPYP_OOMDUMP" ]; then
  echo "  Will perform heap dump on out-of-memory errors"
  dump="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
else
  echo "  Will not perform heap dump on out-of-memory errors"
fi

java -Xmx${NEXTPYP_HEAPMIB}M $jmx $dump -Djava.awt.headless=true @bin/classpath.txt edu.duke.bartesaghi.micromon.MainKt

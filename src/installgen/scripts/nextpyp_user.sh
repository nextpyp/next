#!/bin/sh


set -e


# configure the script
local_dir=
hpexec=
heap_mib=
database_memgb=
oom_dump=
apptainer_args=


here=$(pwd)
sock_dir="$local_dir/sock"


# process the command
case "$1" in

  start)

    # start the host processor, if needed
    if [ -n "$hpexec" ]; then
      echo "Starting host processor: $hpexec"
      hp_pid=$( cd "$sock_dir" ; "$hpexec" > "$local_dir/logs/hostprocessor" 2>&1 & echo $! )
      echo "Host Processor started (pid=$hp_pid)"
    fi

    echo "Configuring environment ..."

    # configure the environment
    NEXTPYP_LOCAL=$local_dir
    export NEXTPYP_LOCAL
    NEXTPYP_HEAPMIB=$heap_mib
    export NEXTPYP_HEAPMIB
    NEXTPYP_DATABASE_MEMGB=$database_memgb
    export NEXTPYP_DATABASE_MEMGB
    NEXTPYP_OOMDUMP=$oom_dump
    export NEXTPYP_OOMDUMP
    NEXTPYP_HOSTPROCESSOR_PID=$hp_pid
    export NEXTPYP_HOSTPROCESSOR_PID
    NEXTPYP_APPTAINER_ARGS=$apptainer_args
    export NEXTPYP_APPTAINER_ARGS
    PYP_CONFIG_HOST="$here/config.toml"
    export PYP_CONFIG_HOST

    # finally, start the website container
    echo "Starting apptainer container ..."
    start_instance() {
      eval "set -- $apptainer_args \"\$@\""
      apptainer instance start "$@"
    }
    start_instance nextPYP

    ;;


  stop)

    # ok to fail here, keep going so we can cleanup
    set +e

    # stop the website container
    apptainer instance stop nextPYP

    # stop any running host processors
    for f in "$sock_dir"/host-processor-* ; do
      # NOTE: if the glob matches nothing, the filename `host-processor-*` will be iterated
      #       sigh ... shells are so annoying to program
      if [ -S "$f" ] ; then
        pid=$(basename "$f" | cut -d '-' -f 2)
        echo "Found host processor $pid, sending SIGTERM"
        kill -s TERM "$pid"
      fi
    done

    ;;


  # TODO: uninstall

esac

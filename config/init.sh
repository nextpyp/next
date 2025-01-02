#!/bin/sh

# exit if any command fails
set -e


# tragically, apptainer doesn't seem to send the output from this script to anywhere visible
# so explcitly set stdout/stderr to a log file
# hopefully the NEXTPYP_LOCAL env var has been set by the launcher so we know where to write the log
exec > "$NEXTPYP_LOCAL/logs/init.log" 2>&1

echo "nextPYP initializing container ..."
echo "local directory is $NEXTPYP_LOCAL"

# transform the mongodb config, since it doesn't have a simple way to expand environment variables
# first, excape all the forward slashes in the path so we can use it safely with sed
# NOTE: we have to double-escape the sed pattern for the sub-shell
escapedpath=$(echo "$NEXTPYP_LOCAL" | sed "s/\\//\\\\\\//g")
sed "s/\$NEXTPYP_LOCAL/$escapedpath/g" /etc/mongod.conf.template \
  | sed "s/\$NEXTPYP_DATABASE_MEMGB/$NEXTPYP_DATABASE_MEMGB/g" \
  > "$NEXTPYP_LOCAL/db/mongod.conf"

# NOTE: if mongodb gets aborted without properly shutting down, it will leave lock files behind and refuse to restart:
# local/db/mongod.lock
# local/db/WiredTiger.lock
# do we want to auto-delete these files here, at the risk of clobbering the database files?
# probably not... so what's the right way to deal with this?
# maybe alert the user about an improper shutdown,
# and if they're 100% positive the db isn't running, ask them to delete the files?

echo Init complete, starting supervisord

# turn off output redirection, since supervisord has its own log
exec > /dev/null 2>&1

# finally, start supervisord
/usr/local/bin/supervisord -c /etc/supervisor/supervisord.conf

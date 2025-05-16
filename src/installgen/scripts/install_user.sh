#!/bin/sh


set -e


# configure script
old_version=
new_version=
local_dir=
shared_dir=
scratch_dir=


# make sure the script got initialized
initialized=
if [ -z "$initialized" ]; then
  echo "Script not initialized"
  exit 1
fi


# show version info
if [ -n "$old_version" ]; then
  echo "Upgrading nextPYP from version $old_version to $new_version"
else
  echo "Installing nextPYP version $new_version"
fi


__DOWNLOAD_FN__

# make sure the download function got defined
if ! command -v "download" > /dev/null; then
  echo "ERROR: function not defined: download"
  exit 1
fi


mkdir_user() {
  _path=$1

  if [ -d "$_path" ]; then
    echo "Using existing folder: $_path"
  else
    mkdir "$_path"
    echo "Created folder: $_path"
  fi
}


here=$(pwd)


# download files
bin_dir="$here/bin"
mkdir_user "$bin_dir"
download "nextPYP.sif", "$bin_dir"
download "host-processor" "$bin_dir" u+x
download "user-processor" "$bin_dir" u+x

containers_dir="$here/containers"
mkdir_user "$containers_dir"
# TODO: externalize list of containers somehow?
download "pyp.sif" "$containers_dir"

workflows_dir="$here/workflows"
mkdir_user "$workflows_dir"
# TODO: externalize list of workflows somehow?
download "workflows/spr_tutorial.toml" "$workflows_dir/"
download "workflows/tomo_tutorial.toml" "$workflows_dir/"
download "workflows/class_tutorial.toml" "$workflows_dir/"


# create data folders, if needed
mkdir_user "$local_dir"
mkdir_user "$local_dir/containers"
mkdir_user "$local_dir/logs"
mkdir_user "$local_dir/db"
mkdir_user "$local_dir/sock"
mkdir_user "$shared_dir"
mkdir_user "$shared_dir/batch"
mkdir_user "$shared_dir/log"
mkdir_user "$shared_dir/users"
mkdir_user "$shared_dir/os-users"
mkdir_user "$shared_dir/sessions"
mkdir_user "$shared_dir/groups"
mkdir_user "$scratch_dir"


# save the installation info
tee "$here/installed.toml" > /dev/null << EOF
__INSTALLED_FILE__
EOF


# success! =D
echo ""
echo "Finished installing nextPYP $new_version !"
echo "  Next, generate scripts with \`installgen script\`"

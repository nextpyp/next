
download() {
  _url=

  _remote_subpath=$1
  _local_dir=$2
  _perms=$3

  _filename=$(basename "$_remote_subpath")
  _local_path="$_local_dir/$_filename"

  echo "Downloading $_remote_subpath ..."
  wget --no-verbose -O "$_local_path" "$_url/$_remote_subpath"

  # set file permissions, if needed
  if [ -n "$_perms" ]; then
    chmod "$_perms" "$_local_path"
  fi
}

# TODO progress bar? --progress=bar

#! /bin/sh


# if any command exits with an error, abort the script
set -e


echo Starting setup.sh ...


# enable extra repositories and tools
dnf install -y \
  epel-release \
  wget \
  dnf-plugins-core
dnf config-manager --set-enabled powertools


# install singularity
dnf install -y apptainer


# install slurm
dnf install -y \
  slurm \
  slurm-slurmd \
  slurm-slurmctld
systemctl enable munge slurmd slurmctld
cp /media/micromon/dev/slurm.conf /etc/slurm/

# make a new munge key if needed
if [ ! -f "/etc/munge/munge.key" ]; then
  create-munge-key
fi

# apply fix for bug in slurm, see:
# https://aur.archlinux.org/packages/slurm-llnl#comment-820480
sed -i 's/network\.target/network-online\.target/g' /usr/lib/systemd/system/slurmd.service
sed -i 's/network\.target/network-online\.target/g' /usr/lib/systemd/system/slurmctld.service
systemctl daemon-reload

# sometimes the node starts drained for some reason? so activate it again
# the scontrol command only works if the node is actually drained though
#systemctl start munge slurmd slurmctld
#scontrol update nodename=localhost state=resume


# create an SSH key the container can use to get onto the SLURM login node (also the VM)
# NOTE: this script runs as root, so `~` resolves to `/root`
# NOTE: we don't really need this key to login to sshd in the VM, but micromon expects a key file to be here
user=$SUDO_USER
sshdir="/home/$user/.ssh"
keyfile="$sshdir/id_rsa"
mkdir -p "$sshdir"
if [ -f "$keyfile" ]; then
  rm "$keyfile"
fi
ssh-keygen -t rsa -N "" -f "$keyfile"
cp "$keyfile.pub" "$sshdir/authorized_keys"
chown -R "$user:$user" "$sshdir"


# disable the firewall
# the VM is inaccessible from outside of the host anyway due to the networking setup
systemctl stop firewalld
systemctl disable firewalld


# configure sshd
sshdconf=/etc/ssh/sshd_config
# the root account is disabled anyway
sed -i 's/PermitRootLogin yes/PermitRootLogin no/g' "$sshdconf"
# the user account doesn't even have a password
sed -i 's/#PermitEmptyPasswords no/PermitEmptyPasswords yes/g' "$sshdconf"


# install MPI
dnf install -y openmpi

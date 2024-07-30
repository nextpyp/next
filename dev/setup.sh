#! /bin/sh

# NOTE: this script is run as root
user=$SUDO_USER


# if any command exits with an error, abort the script
set -e


echo Starting setup.sh ...


# enable the enp0s8 networking interface
cat << EOF >> "/etc/network/interfaces"

# The secondary LAN interface
allow-hotplug enp0s8
iface enp0s8 inet dhcp

EOF

systemctl restart networking


# install sshd
apt install -y openssh-server
sshdconf=/etc/ssh/sshd_config
# the root account is disabled anyway
sed -i 's/PermitRootLogin yes/PermitRootLogin no/g' "$sshdconf"
sed -i 's/#PermitEmptyPasswords no/PermitEmptyPasswords yes/g' "$sshdconf"
systemctl restart sshd


# install apptainer
debpath=/tmp/apptainer.deb
wget https://github.com/apptainer/apptainer/releases/download/v1.3.3/apptainer_1.3.3_amd64.deb -O "$debpath"
apt -f install -y "$debpath"
rm "$debpath"
wget https://github.com/apptainer/apptainer/releases/download/v1.3.3/apptainer-suid_1.3.3_amd64.deb -O "$debpath"
apt -f install -y "$debpath"
rm "$debpath"


# install slurm
apt install -y slurm slurmd slurmctld
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
sshdir="/home/$user/.ssh"
keyfile="$sshdir/id_rsa"
mkdir -p "$sshdir"
if [ -f "$keyfile" ]; then
  rm "$keyfile"
fi
ssh-keygen -t rsa -N "" -f "$keyfile"
cp "$keyfile.pub" "$sshdir/authorized_keys"
chown -R "$user:$user" "$sshdir"


# install MPI
apt install -y openmpi-common


# setup nextPYP (create folders the way the installer would)

usdo="sudo -u $user"

home="/home/$user"
$usdo chmod go+x "$home"

# local folders
appDir="/home/$user/nextPYP"
$usdo mkdir "$appDir"
$usdo chmod go+x "$appDir"

localDir="$appDir/local"
$usdo mkdir "$localDir"
$usdo mkdir "$localDir/logs"
$usdo mkdir "$localDir/db"
$usdo mkdir "$localDir/sock"
$usdo chmod ugo=rwx,+t "$localDir/sock"

# shared folders
sharedDir="$appDir/shared"
$usdo mkdir "$sharedDir"
$usdo chmod go+x "$sharedDir"
$usdo mkdir "$sharedDir/batch"
$usdo chmod ugo=rwx,+t "$sharedDir/batch"
$usdo mkdir "$sharedDir/log"
$usdo chmod ugo=rwx,+t "$sharedDir/log"
$usdo mkdir "$sharedDir/users"
$usdo mkdir "$sharedDir/os-users"
$usdo chmod ugo=rwx,+t "$sharedDir/os-users"
$usdo mkdir "$sharedDir/user-processors"
$usdo chmod ugo=rwx,+t "$sharedDir/user-processors"
$usdo mkdir "$sharedDir/sessions"
$usdo mkdir "$sharedDir/groups"

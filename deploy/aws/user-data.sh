#!/bin/bash
# First boot of the EC2 instance (Amazon Linux 2023): Docker, and 1 GiB of swap as a safety margin for two
# JVMs (the API and Kafka) in 2 GiB of memory.
set -euxo pipefail
dnf install -y docker
systemctl enable --now docker
usermod -aG docker ec2-user
fallocate -l 1G /swapfile
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
touch /var/tmp/dashcart-ready

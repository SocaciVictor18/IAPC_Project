#!/usr/bin/env bash
# ADR-008, layer 1: storage-level AES-256 encryption for the Postgres data directory
# (covers ALL data, including embeddings and the full-text column, which cannot be encrypted
# at application level without breaking search).
#
# Linux host only (requires root + cryptsetup). On Windows use BitLocker, on macOS FileVault,
# in AWS use RDS with KMS storage encryption (AES-256).
#
#   sudo infra/scripts/create-encrypted-volume.sh /srv/edupulse-pgdata.img 10G
#   then in docker-compose.yml replace the "pgdata" volume with:  /mnt/edupulse-pgdata:/var/lib/postgresql/data
set -euo pipefail

IMG="${1:-/srv/edupulse-pgdata.img}"
SIZE="${2:-10G}"
NAME="edupulse-pgdata"
MOUNT="/mnt/${NAME}"

command -v cryptsetup >/dev/null || { echo "cryptsetup is required (apt install cryptsetup)"; exit 1; }

if [ ! -f "$IMG" ]; then
  echo "Creating ${SIZE} container file ${IMG}"
  fallocate -l "$SIZE" "$IMG"
  # AES-256 in XTS mode needs a 512-bit key (2 x 256).
  cryptsetup luksFormat --type luks2 --cipher aes-xts-plain64 --key-size 512 --hash sha256 "$IMG"
  cryptsetup open "$IMG" "$NAME"
  mkfs.ext4 -q "/dev/mapper/${NAME}"
else
  cryptsetup open "$IMG" "$NAME"
fi

mkdir -p "$MOUNT"
mount "/dev/mapper/${NAME}" "$MOUNT"
chown 999:999 "$MOUNT"   # uid/gid of the postgres user inside the official image

echo "Encrypted volume mounted at ${MOUNT}. Evidence for the report:"
cryptsetup status "$NAME"

#!/bin/bash
# Aurora-side setup for the gc-backup user that receives red-dragon's
# Ground Control DB backups (GC-P021 off-box durability clause).
#
# Run this ONCE on aurora as root (or with sudo):
#
#   sudo bash deploy/scripts/aurora-setup-gc-backup.sh '<pubkey>'
#
# where <pubkey> is the single-line ed25519 pubkey printed by
# deploy/scripts/install-gc-backup.sh on red-dragon. The script is
# idempotent: re-running it does not duplicate the authorized_keys
# entry, and the backup dir is created only if it doesn't already
# exist.
#
# What it does:
#
#   1. Creates the `gc-backup` system user (nologin, home /var/lib/gc-backup).
#   2. Creates /var/backups/groundcontrol/ owned by gc-backup, mode 750.
#   3. Writes an authorized_keys entry locked to a single forced
#      command: `rrsync /var/backups/groundcontrol/`. With `restrict`
#      and that ForceCommand, the key can do nothing on aurora except
#      land rsync payloads into that one directory.
#
# Requires: rrsync (ships with modern rsync at /usr/bin/rrsync on
# Ubuntu/Debian; on systems where it is gzipped under
# /usr/share/doc/rsync/scripts/rrsync, the script unpacks it).
set -euo pipefail

PUBKEY="${1:?usage: $0 '<pubkey-text>'}"
BACKUP_DIR=/var/backups/groundcontrol

require_root() {
  if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: this script must run as root (use sudo)." >&2
    exit 1
  fi
}

ensure_rrsync() {
  if command -v rrsync >/dev/null 2>&1; then
    return
  fi
  GZ=/usr/share/doc/rsync/scripts/rrsync.gz
  PLAIN=/usr/share/doc/rsync/scripts/rrsync
  if [ -f "${GZ}" ]; then
    gunzip -k "${GZ}"
    install -o root -g root -m 755 "${PLAIN}" /usr/local/bin/rrsync
  elif [ -f "${PLAIN}" ]; then
    install -o root -g root -m 755 "${PLAIN}" /usr/local/bin/rrsync
  else
    echo "ERROR: rrsync not found. Install rsync (apt install rsync) or place rrsync at /usr/local/bin/rrsync." >&2
    exit 1
  fi
}

ensure_user() {
  if id -u gc-backup >/dev/null 2>&1; then
    return
  fi
  useradd --system --shell /usr/sbin/nologin \
    --home /var/lib/gc-backup --create-home gc-backup
}

ensure_backup_dir() {
  install -d -o gc-backup -g gc-backup -m 750 "${BACKUP_DIR}"
}

ensure_authorized_key() {
  install -d -o gc-backup -g gc-backup -m 700 /var/lib/gc-backup/.ssh
  KEYS=/var/lib/gc-backup/.ssh/authorized_keys
  touch "${KEYS}"
  chmod 600 "${KEYS}"
  chown gc-backup:gc-backup "${KEYS}"
  RRSYNC_BIN=$(command -v rrsync)
  ENTRY="command=\"${RRSYNC_BIN} ${BACKUP_DIR}/\",restrict ${PUBKEY}"
  if grep -qF "${PUBKEY}" "${KEYS}"; then
    echo "OK: pubkey already present in ${KEYS}; no change"
  else
    printf '%s\n' "${ENTRY}" >> "${KEYS}"
    echo "OK: added gc-backup forced-command entry to ${KEYS}"
  fi
}

require_root
ensure_rrsync
ensure_user
ensure_backup_dir
ensure_authorized_key

echo "OK: gc-backup ready on $(hostname -s); backups land in ${BACKUP_DIR}"

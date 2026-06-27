#!/bin/bash
# Install the Ground Control backup mechanism on red-dragon.
#
# Idempotent: re-running this script does not duplicate the user, the
# key, or any installed file. Run it with sudo from the repo root:
#
#   sudo bash deploy/scripts/install-gc-backup.sh
#
# What it does:
#
#   1. Creates the `gc-backup` system user (nologin, in the docker
#      group, home /var/lib/gc-backup) if missing.
#   2. Hands /data/backups/ to gc-backup (mode 750, group operator-
#      readable via the supplied OPERATOR_GROUP if set).
#   3. Generates a fresh ed25519 SSH key under
#      /var/lib/gc-backup/.ssh/id_ed25519 if missing, and prints the
#      pubkey for aurora-side install via deploy/scripts/aurora-setup-gc-backup.sh.
#   4. Installs /opt/gc/backup.sh + /opt/gc/test-restore.sh + the systemd
#      units from deploy/systemd/.
#   5. systemctl daemon-reload + enable --now gc-backup.timer and
#      gc-restore-test.timer (GC-P021: backups ≥ 3×/day, restore verified
#      ≥ 1×/day).
#
# After this runs, hand the printed pubkey to aurora-side setup. Until
# aurora-side is wired up the local dump still works; rsync logs WARN.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.."
REPO_ROOT="$(cd "${REPO_ROOT}" && pwd)"

OPERATOR_GROUP=${OPERATOR_GROUP:-atomik}

require_root() {
  if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: this script must run as root (use sudo)." >&2
    exit 1
  fi
}

require_repo_layout() {
  for f in \
    "${REPO_ROOT}/deploy/scripts/backup.sh" \
    "${REPO_ROOT}/deploy/scripts/test-restore.sh" \
    "${REPO_ROOT}/deploy/systemd/gc-backup.service" \
    "${REPO_ROOT}/deploy/systemd/gc-backup.timer" \
    "${REPO_ROOT}/deploy/systemd/gc-restore-test.service" \
    "${REPO_ROOT}/deploy/systemd/gc-restore-test.timer"; do
    [ -r "$f" ] || { echo "ERROR: missing ${f}" >&2; exit 1; }
  done
}

ensure_user() {
  if id -u gc-backup >/dev/null 2>&1; then
    return
  fi
  useradd --system --shell /usr/sbin/nologin \
    --home /var/lib/gc-backup --create-home gc-backup
  usermod -aG docker gc-backup
}

ensure_backup_dir() {
  install -d -o gc-backup -g gc-backup -m 750 /data/backups
  # Let the operator group (default atomik) read the dumps without sudo.
  # Skipped silently if the group does not exist on this host.
  if getent group "${OPERATOR_GROUP}" >/dev/null 2>&1; then
    chgrp "${OPERATOR_GROUP}" /data/backups
    chmod 2750 /data/backups   # setgid so new dumps inherit the group
  fi
}

ensure_ssh_key() {
  install -d -o gc-backup -g gc-backup -m 700 /var/lib/gc-backup/.ssh
  if [ ! -f /var/lib/gc-backup/.ssh/id_ed25519 ]; then
    sudo -u gc-backup ssh-keygen -t ed25519 \
      -f /var/lib/gc-backup/.ssh/id_ed25519 \
      -C "gc-backup@red-dragon (Ground Control backup, GC-P021)" \
      -N ''
  fi
  chmod 600 /var/lib/gc-backup/.ssh/id_ed25519
  chmod 644 /var/lib/gc-backup/.ssh/id_ed25519.pub
  chown gc-backup:gc-backup /var/lib/gc-backup/.ssh/id_ed25519{,.pub}
}

install_files() {
  install -o root -g root -m 755 \
    "${REPO_ROOT}/deploy/scripts/backup.sh" /opt/gc/backup.sh
  install -o root -g root -m 755 \
    "${REPO_ROOT}/deploy/scripts/test-restore.sh" /opt/gc/test-restore.sh
  install -o root -g root -m 644 \
    "${REPO_ROOT}/deploy/systemd/gc-backup.service" /etc/systemd/system/gc-backup.service
  install -o root -g root -m 644 \
    "${REPO_ROOT}/deploy/systemd/gc-backup.timer" /etc/systemd/system/gc-backup.timer
  install -o root -g root -m 644 \
    "${REPO_ROOT}/deploy/systemd/gc-restore-test.service" /etc/systemd/system/gc-restore-test.service
  install -o root -g root -m 644 \
    "${REPO_ROOT}/deploy/systemd/gc-restore-test.timer" /etc/systemd/system/gc-restore-test.timer
}

enable_timer() {
  systemctl daemon-reload
  systemctl enable --now gc-backup.timer
  systemctl enable --now gc-restore-test.timer
}

print_pubkey() {
  echo
  echo "=================================================================="
  echo "gc-backup install complete. Pubkey to install on aurora:"
  echo "------------------------------------------------------------------"
  cat /var/lib/gc-backup/.ssh/id_ed25519.pub
  echo "------------------------------------------------------------------"
  echo "On aurora, run as root:"
  echo "  sudo bash deploy/scripts/aurora-setup-gc-backup.sh '$(cat /var/lib/gc-backup/.ssh/id_ed25519.pub)'"
  echo "Until aurora is wired up the local dump still works; off-box rsync"
  echo "logs WARN."
  echo "=================================================================="
}

require_root
require_repo_layout
ensure_user
ensure_backup_dir
ensure_ssh_key
install_files
enable_timer
print_pubkey

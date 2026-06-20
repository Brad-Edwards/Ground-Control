#!/usr/bin/env bash
#
# install-skills.sh
#
# Installs the canonical Ground Control skills (under skills/) into the
# Claude Code, Codex, and Cursor CLI skill directories on this host.
#
# Defaults to symlinks so the agent always reads the latest source-of-truth
# from this repo (Claude Code and Codex). Cursor CLI always gets hard copies:
# it resolves symlink targets and rejects SKILL.md files whose real path falls
# outside the skills root (see scripts/test-cursor-skill-symlink.sh). Use
# --copy to hard-copy for every host target. Idempotent: re-run after pulling
# to refresh.
#
# Host targets are never clobbered blindly. A target this script owns — a
# symlink, or a copy byte-identical to the repo source — is refreshed in
# place. Anything else (a directory/file that differs and isn't a symlink)
# is left untouched and the run fails; re-run with --force to overwrite it.
#
# Usage:
#   bin/install-skills.sh [--copy] [--dry-run] [--force] [--no-codex] [--no-cursor]
#                         [--claude-dir <path>] [--codex-dir <path>] [--codex-prompts-dir <path>]
#                         [--cursor-dir <path>]
#
# Options:
#   --copy            Hard-copy each skill instead of symlinking.
#   --dry-run         Print actions without writing anything.
#   --force           Overwrite host targets that differ from the repo copy.
#   --no-codex        Skip the Codex install targets (use when Codex isn't on this host or its convention isn't settled).
#   --no-cursor       Skip the Cursor CLI install target (use when Cursor isn't on this host).
#   --claude-dir P    Override the Claude Code install root (default: ~/.claude/skills).
#   --codex-dir P     Override the Codex skill install root (default: ~/.codex/skills).
#   --codex-prompts-dir P
#                     Override the legacy Codex prompt install root (default: ~/.codex/prompts).
#   --cursor-dir P    Override the Cursor CLI skill install root (default: ~/.cursor/skills).
#
# Per ADR-027: the canonical workflow lives at skills/<name>/SKILL.md in this
# repo. Host-local files are install targets, not the source of truth.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
skills_root="${repo_root}/skills"

mode="symlink"
dry_run=0
force=0
install_codex=1
install_cursor=1
claude_dir="${HOME}/.claude/skills"
codex_dir="${HOME}/.codex/skills"
codex_prompts_dir="${HOME}/.codex/prompts"
cursor_dir="${HOME}/.cursor/skills"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --copy)
      mode="copy"; shift ;;
    --dry-run)
      dry_run=1; shift ;;
    --force)
      force=1; shift ;;
    --no-codex)
      install_codex=0; shift ;;
    --no-cursor)
      install_cursor=0; shift ;;
    --claude-dir)
      claude_dir="$2"; shift 2 ;;
    --codex-dir)
      codex_dir="$2"; shift 2 ;;
    --codex-prompts-dir)
      codex_prompts_dir="$2"; shift 2 ;;
    --cursor-dir)
      cursor_dir="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2 ;;
  esac
done

if [[ ! -d "${skills_root}" ]]; then
  echo "ERROR: ${skills_root} does not exist. Are you running this from a Ground Control checkout?" >&2
  exit 1
fi

run() {
  if [[ "${dry_run}" -eq 1 ]]; then
    printf 'DRY-RUN: %s\n' "$*"
  else
    "$@"
  fi
}

# A target is "managed by this script" — and therefore safe to refresh without
# --force — when it is a symlink (stale ones from a previous layout are ours to
# repoint) or a file/directory byte-identical to the repo source.
is_managed_target() {
  local src="$1" dst="$2"
  [[ -L "${dst}" ]] && return 0
  if [[ -f "${src}" && -f "${dst}" && ! -L "${dst}" ]]; then
    diff -q -- "${src}" "${dst}" >/dev/null 2>&1 && return 0
  fi
  if [[ -d "${src}" && -d "${dst}" && ! -L "${dst}" ]]; then
    diff -qr -- "${src}" "${dst}" >/dev/null 2>&1 && return 0
  fi
  return 1
}

# Install (symlink or copy) repo source ${src} at host path ${dst}, refusing to
# clobber unmanaged host content unless --force is set. Optional 4th argument
# overrides the global mode for this target (used by the Cursor install path).
install_target() {
  local src="$1" dst="$2" label="$3"
  local effective_mode="${4:-${mode}}"

  if [[ -e "${dst}" || -L "${dst}" ]]; then
    if is_managed_target "${src}" "${dst}"; then
      run rm -rf -- "${dst}"
    elif [[ "${force}" -eq 1 ]]; then
      echo "FORCE: overwriting ${label} ${dst} (differs from repo copy)" >&2
      run rm -rf -- "${dst}"
    else
      echo "ERROR: refusing to overwrite ${label} ${dst} — it differs from the repo copy and is not a managed symlink. Re-run with --force to overwrite." >&2
      exit 3
    fi
  fi

  if [[ "${effective_mode}" == "symlink" ]]; then
    run ln -sfn "${src}" "${dst}"
  elif [[ -d "${src}" ]]; then
    run cp -R -- "${src}" "${dst}"
  else
    run cp -- "${src}" "${dst}"
  fi
  printf '%-7s %-8s %s -> %s\n' "${effective_mode}" "${label}" "${dst}" "${src}"
}

# Install Claude Code skills: each subdir of skills/ becomes ~/.claude/skills/<name>
run mkdir -p "${claude_dir}"
for skill_dir in "${skills_root}"/*/; do
  [[ -d "${skill_dir}" ]] || continue
  name="$(basename "${skill_dir}")"
  install_target "${skill_dir%/}" "${claude_dir}/${name}" "claude"
done

# Install Codex skills: each subdir of skills/ becomes ~/.codex/skills/<name>.
# Also install legacy prompt aliases: each skills/<name>/SKILL.md becomes
# ~/.codex/prompts/<name>.md. The prompt aliases keep older Codex builds and
# existing muscle memory working while newer Codex builds discover SKILL.md
# files from ~/.codex/skills.
if [[ "${install_codex}" -eq 1 ]]; then
  run mkdir -p "${codex_dir}" "${codex_prompts_dir}"
  for skill_dir in "${skills_root}"/*/; do
    [[ -d "${skill_dir}" ]] || continue
    name="$(basename "${skill_dir}")"
    src="${skill_dir%/}/SKILL.md"
    [[ -f "${src}" ]] || continue
    install_target "${skill_dir%/}" "${codex_dir}/${name}" "codex"
    install_target "${src}" "${codex_prompts_dir}/${name}.md" "codex"
  done
else
  echo "Skipping Codex install (--no-codex set)."
fi

# Install Cursor CLI skills: each subdir of skills/ becomes ~/.cursor/skills/<name>.
# Always hard-copy: Cursor resolves symlink targets and skips SKILL.md when the
# real path escapes the skills root (scripts/test-cursor-skill-symlink.sh).
if [[ "${install_cursor}" -eq 1 ]]; then
  run mkdir -p "${cursor_dir}"
  for skill_dir in "${skills_root}"/*/; do
    [[ -d "${skill_dir}" ]] || continue
    name="$(basename "${skill_dir}")"
    install_target "${skill_dir%/}" "${cursor_dir}/${name}" "cursor" "copy"
  done
else
  echo "Skipping Cursor install (--no-cursor set)."
fi

echo "Done."

#!/bin/bash
# Ground Control operator rollback wrapper (ADR-030, ADR-063, GC-P023).
#
# Resolves a target image ref, patches /opt/gc/.env to pin GC_IMAGE to that
# ref (and GC_ALLOW_IMAGE_PIN when the target is a digest), then delegates
# the actual rollout to the canonical deploy path. Production rollback is
# "deploy, but first pin GC_IMAGE to <target>"; this wrapper does the pin
# step so every rollback goes through the same validated path as a normal
# deploy (drift guard, env validation, staleness guard, health gate, auto-
# rollback, deploy-state publish, GitHub Deployment).
#
# Usage: scripts/rollback.sh [--dry-run] <version-or-ref>
#
#   <version-or-ref>:
#     bare semver   1.0.1                 derives repo from current GC_IMAGE
#     full tag ref  ghcr.io/autarchy-ai/ground-control:1.0.1  (contains '/')
#     digest ref    ghcr.io/autarchy-ai/ground-control@sha256:<hex>
#
#   Only an immutable three-component release tag (X.Y.Z) or a digest is
#   accepted: two-component aliases (1.0), floating tags (:main, :latest, :dev),
#   and untagged refs are rejected with a non-zero exit so an operator
#   fat-finger never silently pins a mutable tag in production (ADR-063).
#   A full/digest ref may only target the SAME registry/repository as the
#   current production pin — a rollback re-pins a version, it never repoints
#   production at a different image source (supply-chain integrity).
#
#   --dry-run  Resolve and validate the ref; print two machine-parseable lines
#              to stdout and exit 0 without mutating anything:
#                GC_IMAGE=<resolved>
#                GC_ALLOW_IMAGE_PIN=<1|unset>
#
# Access model (mirrors scripts/deploy.sh):
#   on-box:   plain sudo to read/patch $GC_DIR/.env
#   off-box:  ssh $DEPLOY_HOST as the operator/sudoer identity
#
# Test seam env knobs (mirror deploy.sh's GC_DIR / GC_HEALTH_RETRIES):
#   GC_DIR              path to the deploy directory (default /opt/gc)
#   GC_ROLLBACK_LOCAL=1 operate directly on $GC_DIR/.env (no sudo/ssh);
#                       run bash "$GC_DIR/deploy.sh" instead of delegating
#                       to scripts/deploy.sh — safe for integration tests
#                       that set up a throwaway GCDIR
#   GC_DEPLOY_HOST      override deploy host name (default red-dragon)
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
GC_DIR="${GC_DIR:-/opt/gc}"
DEPLOY_HOST="${GC_DEPLOY_HOST:-red-dragon}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Version tag regex — an immutable release tag emitted by docker/metadata-action
# from a vX.Y.Z git tag (ADR-063). Requires the full three-component X.Y.Z (with
# optional -prerelease / +build): a two-component major.minor tag like 1.0 is
# commonly a MUTABLE alias that retargets on the next patch release, which would
# defeat the immutable-release-pin contract a rollback depends on. Floating
# branch tags (:main, :latest) never match. (Codex core review #1223, cycle 2.)
VERSION_TAG_RE='^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'

# Refuse any image ref carrying a character outside the image-reference charset.
# This is the security gate that lets a resolved ref be interpolated as DATA
# into the .env sed / ssh sinks below: the allowed set [A-Za-z0-9._:/@-] holds
# NO shell-significant character (no whitespace, quote, $, backtick, ;, |, &,
# parens, <>, *, ?, backslash, or newline), so a crafted repository portion
# cannot break out of a quoted sed/printf argument or an ssh remote program and
# execute commands on the deploy host. Applied to both the raw operator input
# and the fully resolved ref. (Codex security review #1223: "rollback target
# can inject commands into the remote env patch.")
assert_safe_ref() {
  local ref="$1" label="$2"
  case "${ref}" in
    '')
      echo "ERROR: empty ${label}." >&2
      exit 1
      ;;
    *[!A-Za-z0-9._:/@-]*)
      echo "ERROR: ${label} contains characters outside the image-reference charset" >&2
      echo "       [A-Za-z0-9._:/@-]; refusing as a possible injection vector." >&2
      exit 1
      ;;
  esac
}

# Extract the registry[:port]/repository portion of an image ref, dropping any
# @digest suffix and any :tag (slash-aware, so a registry port is never mistaken
# for a tag). Used to enforce that a rollback only ever re-pins a version/digest
# of the SAME image — never repoints production at a different registry or repo.
canonical_repo_of() {
  local ref="$1" base name_tag
  base="${ref%@*}"
  name_tag="${base##*/}"
  case "${name_tag}" in
    *:*) printf '%s' "${base%:*}" ;;
    *)   printf '%s' "${base}" ;;
  esac
}

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
  echo "usage: $0 [--dry-run] <version-or-ref>" >&2
  echo "" >&2
  echo "  version-or-ref (must target the SAME image as the current pin):" >&2
  echo "    bare semver X.Y.Z  1.0.1   (repo derived from current GC_IMAGE)" >&2
  echo "    full versioned ref ghcr.io/autarchy-ai/ground-control:1.0.1" >&2
  echo "    digest ref         ghcr.io/autarchy-ai/ground-control@sha256:<hex>" >&2
  echo "" >&2
  echo "  Two-component (1.0), floating (:main, :latest, :dev), and untagged refs" >&2
  echo "  are rejected; immutable X.Y.Z release tags or digests only (ADR-063)." >&2
  echo "  --dry-run  resolve + validate; print GC_IMAGE and GC_ALLOW_IMAGE_PIN; exit 0." >&2
  exit 2
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
  shift
fi

if [ "${1:-}" = "" ]; then
  usage
fi
ARG="$1"
shift
if [ "$#" -ne 0 ]; then
  echo "ERROR: unexpected arguments: $*" >&2
  usage
fi

# Gate the raw operator input before it is used anywhere (resolution, .env
# mutation, remote ssh). A crafted ref with a valid-looking tag but shell
# metacharacters in the repository portion is rejected here.
assert_safe_ref "${ARG}" "rollback target"

# ---------------------------------------------------------------------------
# Host topology (mirrors scripts/deploy.sh)
# ---------------------------------------------------------------------------
on_box() {
  [ "$(hostname -s 2>/dev/null || hostname)" = "${DEPLOY_HOST}" ]
}

# ---------------------------------------------------------------------------
# .env read / write helpers
#
# These never source /opt/gc/.env (a crafted .env could execute code) and
# never print secret values — they only read/write by key name.  Mirrors
# the no-source contract of deploy/docker/validate-env.sh (GC-TM-003).
# ---------------------------------------------------------------------------

# Read KEY=value from $GC_DIR/.env; output only the value.
read_env_key() {
  local key="$1"
  local result
  if [ "${GC_ROLLBACK_LOCAL:-0}" = "1" ]; then
    result="$(grep "^${key}=" "${GC_DIR}/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
  elif on_box; then
    result="$(sudo grep "^${key}=" "${GC_DIR}/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
  else
    result="$(ssh "${DEPLOY_HOST}" "sudo grep '^${key}=' '${GC_DIR}/.env' 2>/dev/null | tail -1 | cut -d= -f2-" || true)"
  fi
  printf '%s' "${result}"
}

# Set KEY=VALUE in $GC_DIR/.env (update existing line or append).
set_env_key() {
  local key="$1" value="$2"
  local env_file="${GC_DIR}/.env"
  if [ "${GC_ROLLBACK_LOCAL:-0}" = "1" ]; then
    if grep -q "^${key}=" "${env_file}" 2>/dev/null; then
      sed -i "s|^${key}=.*|${key}=${value}|" "${env_file}"
    else
      printf '%s=%s\n' "${key}" "${value}" >> "${env_file}"
    fi
  elif on_box; then
    if sudo grep -q "^${key}=" "${env_file}" 2>/dev/null; then
      sudo sed -i "s|^${key}=.*|${key}=${value}|" "${env_file}"
    else
      printf '%s=%s\n' "${key}" "${value}" | sudo tee -a "${env_file}" >/dev/null
    fi
  else
    ssh "${DEPLOY_HOST}" "
      if sudo grep -q '^${key}=' '${env_file}' 2>/dev/null; then
        sudo sed -i 's|^${key}=.*|${key}=${value}|' '${env_file}'
      else
        printf '%s=%s\n' '${key}' '${value}' | sudo tee -a '${env_file}' >/dev/null
      fi
    "
  fi
}

# Remove KEY= line(s) from $GC_DIR/.env (no-op when key is absent).
remove_env_key() {
  local key="$1"
  local env_file="${GC_DIR}/.env"
  if [ "${GC_ROLLBACK_LOCAL:-0}" = "1" ]; then
    sed -i "/^${key}=/d" "${env_file}"
  elif on_box; then
    sudo sed -i "/^${key}=/d" "${env_file}"
  else
    ssh "${DEPLOY_HOST}" "sudo sed -i '/^${key}=/d' '${env_file}'"
  fi
}

# ---------------------------------------------------------------------------
# Ref resolution
#
# Three input shapes:
#   1. digest  — contains @sha256: or @sha512:; use as-is; enable pin override
#   2. full    — contains '/' (registry/image:tag); extract + validate tag
#   3. bare    — semver string; validate; derive repo from current GC_IMAGE
# ---------------------------------------------------------------------------
RESOLVED=""
REF_TYPE=""   # "version" or "digest"

# Read the current production pin once. The bare-version path derives the repo
# from it, and EVERY rollback target is checked against it for provenance below.
current_image="$(read_env_key GC_IMAGE)"
if [ -z "${current_image}" ]; then
  echo "ERROR: GC_IMAGE is not set in ${GC_DIR}/.env; cannot anchor the rollback" >&2
  echo "       to the canonical production image. Refusing." >&2
  exit 1
fi
canonical_repo="$(canonical_repo_of "${current_image}")"

case "${ARG}" in
  *@sha256:*|*@sha512:*)
    # Digest ref: use as-is (repository is provenance-checked below).
    RESOLVED="${ARG}"
    REF_TYPE="digest"
    ;;
  */*)
    # Full ref: extract tag and validate it is an immutable versioned release.
    name_tag="${ARG##*/}"
    case "${name_tag}" in
      *:*) tag="${name_tag##*:}" ;;
      *)   tag="" ;;
    esac
    if [ -z "${tag}" ]; then
      echo "ERROR: '${ARG}' has no image tag (would resolve to the mutable :latest)." >&2
      echo "       Specify an immutable versioned release tag, e.g. ...:1.0.1 (ADR-063)." >&2
      exit 1
    fi
    if [[ "${tag}" =~ ${VERSION_TAG_RE} ]]; then
      RESOLVED="${ARG}"
      REF_TYPE="version"
    else
      echo "ERROR: ':${tag}' is not an immutable versioned release tag (X.Y.Z)." >&2
      echo "       Production rollback requires an immutable release tag" >&2
      echo "       (e.g. ...:1.0.1) or a digest ref (ADR-063)." >&2
      exit 1
    fi
    ;;
  *)
    # Bare string: must be a three-component semver; derive repo from the
    # current pin (canonical_repo_of is digest- and registry-port-aware, so a
    # currently digest-pinned production state still yields repo:<version>).
    if ! [[ "${ARG}" =~ ${VERSION_TAG_RE} ]]; then
      echo "ERROR: '${ARG}' is not an immutable versioned release tag (X.Y.Z) or" >&2
      echo "       a full image ref. Two-component (1.0), floating (:main, :latest)," >&2
      echo "       and branch-name tags are rejected (ADR-063)." >&2
      exit 1
    fi
    RESOLVED="${canonical_repo}:${ARG}"
    REF_TYPE="version"
    ;;
esac

# Supply-chain integrity: a rollback may only re-pin a different version/digest
# of the SAME canonical image. Reject any target whose registry/repository
# differs from the current production pin — otherwise a caller who can influence
# the rollback target could repoint production at an attacker-owned image that
# is then pulled and run with production env + credentials. The bare-version
# path derives the repo from the current pin so it always matches; full and
# digest refs are the real check here. (Codex security review #1223, cycle 2.)
target_repo="$(canonical_repo_of "${RESOLVED}")"
if [ "${target_repo}" != "${canonical_repo}" ]; then
  echo "ERROR: rollback target repository '${target_repo}' does not match the" >&2
  echo "       current production image repository '${canonical_repo}'." >&2
  echo "       A rollback may only re-pin a version or digest of the SAME image;" >&2
  echo "       refusing to repoint production at a different registry/repository." >&2
  exit 1
fi

# Defense in depth: re-gate the fully resolved ref (the repo prefix derived
# from the current pin is host-controlled, not operator-controlled, but it
# flows into the same sinks) before it is written to .env or any remote shell.
assert_safe_ref "${RESOLVED}" "resolved image ref"

# ---------------------------------------------------------------------------
# Dry-run: print resolved ref + pin flag and exit without mutating anything.
# This output is machine-parseable (KEY=value) for operator preview and tests.
# ---------------------------------------------------------------------------
if [ "${DRY_RUN}" = "1" ]; then
  echo "GC_IMAGE=${RESOLVED}"
  if [ "${REF_TYPE}" = "digest" ]; then
    echo "GC_ALLOW_IMAGE_PIN=1"
  else
    echo "GC_ALLOW_IMAGE_PIN=unset"
  fi
  exit 0
fi

# ---------------------------------------------------------------------------
# Patch $GC_DIR/.env: pin GC_IMAGE and manage GC_ALLOW_IMAGE_PIN.
#
# For a version tag:  patch GC_IMAGE; REMOVE any stale GC_ALLOW_IMAGE_PIN so
#   a digest-era override cannot silently pass a later digest check.
# For a digest ref:   patch GC_IMAGE; ENSURE GC_ALLOW_IMAGE_PIN=1 (deploy-time
#   validation rejects a digest pin without this explicit loud override, per
#   GC-P023 / ADR-063).
# ---------------------------------------------------------------------------
echo "Rollback: pinning GC_IMAGE=${RESOLVED} in ${GC_DIR}/.env ..."
set_env_key "GC_IMAGE" "${RESOLVED}"

if [ "${REF_TYPE}" = "digest" ]; then
  echo "Rollback: digest pin — ensuring GC_ALLOW_IMAGE_PIN=1 (GC-P023) ..."
  set_env_key "GC_ALLOW_IMAGE_PIN" "1"
else
  echo "Rollback: version tag pin — removing any stale GC_ALLOW_IMAGE_PIN ..."
  remove_env_key "GC_ALLOW_IMAGE_PIN"
fi

# ---------------------------------------------------------------------------
# Delegate to the canonical deploy path.
#
# In test mode (GC_ROLLBACK_LOCAL=1) run the on-host canonical deploy.sh
# from GC_DIR directly — same as the integration tests do — so no network,
# SSH, or sudo is needed and the entire orchestration is exercised end-to-end
# in a throwaway directory.
#
# In production mode exec the operator wrapper (scripts/deploy.sh) which
# first syncs canonical artifacts into /opt/gc/ and then runs the on-host
# deploy.sh via sudo -u gc-deploy or ssh gc-deploy@host.
# ---------------------------------------------------------------------------
if [ "${GC_ROLLBACK_LOCAL:-0}" = "1" ]; then
  exec bash "${GC_DIR}/deploy.sh"
else
  exec "${SCRIPT_DIR}/deploy.sh"
fi

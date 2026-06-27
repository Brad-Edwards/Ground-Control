#!/bin/bash
# Validate a deploy-host env file against the canonical env.schema (GC-P023).
#
# Usage: validate-env.sh [ENV_FILE] [SCHEMA_FILE]
#   ENV_FILE    defaults to /opt/gc/.env
#   SCHEMA_FILE defaults to env.schema beside this script
#
# deploy.sh calls this BEFORE `docker compose up -d`, so a malformed or
# incomplete /opt/gc/.env fails the rollout loudly instead of producing a
# backend that 401s every caller (#828) or crashes at startup. Exit 0 = valid,
# exit 1 = one or more violations (each printed as a line), exit 2 = usage error.
#
# SECURITY: this script reports only variable NAMES and counts. It never prints
# a variable's value, so it is safe to run with stdout going to CI logs, SSH
# output, or a deploy transcript (GC-TM-003 deploy-host secret exposure). It
# parses the env file line by line and never `source`s it, so a crafted .env
# cannot execute code in this validator.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${1:-/opt/gc/.env}"
SCHEMA_FILE="${2:-${SCRIPT_DIR}/env.schema}"

if [ ! -f "${SCHEMA_FILE}" ]; then
  echo "ERROR: env schema not found at ${SCHEMA_FILE}" >&2
  exit 2
fi
if [ ! -f "${ENV_FILE}" ]; then
  echo "ERROR: env file not found at ${ENV_FILE}" >&2
  exit 2
fi

# Read the env file into an associative array NAME -> value. Only lines of the
# form KEY=VALUE (optionally `export KEY=VALUE`) are honored; comments and blank
# lines are ignored. Values are stored only to test non-blankness; they are
# never printed.
declare -A ENV_VALUES=()
while IFS= read -r line || [ -n "${line}" ]; do
  case "${line}" in
    ''|'#'*) continue ;;
  esac
  line="${line#export }"
  case "${line}" in
    *=*) ;;
    *) continue ;;
  esac
  key="${line%%=*}"
  val="${line#*=}"
  case "${key}" in
    [A-Za-z_]*) ;;
    *) continue ;;
  esac
  # Trim surrounding whitespace on the key only.
  key="${key%"${key##*[![:space:]]}"}"
  ENV_VALUES["${key}"]="${val}"
done < "${ENV_FILE}"

is_set_nonblank() {
  # True when the key exists and its value is not empty / not whitespace-only.
  local k="$1"
  [ "${ENV_VALUES["${k}"]+x}" = "x" ] || return 1
  local v="${ENV_VALUES["${k}"]}"
  [ -n "${v//[[:space:]]/}" ]
}

errors=()
checked=0

while IFS= read -r sline || [ -n "${sline}" ]; do
  case "${sline}" in
    ''|'#'*) continue ;;
  esac
  directive="${sline%% *}"
  var="${sline#* }"
  var="${var%"${var##*[![:space:]]}"}"
  [ -n "${var}" ] || continue
  checked=$((checked + 1))
  case "${directive}" in
    REQUIRED)
      if ! is_set_nonblank "${var}"; then
        errors+=("missing or blank REQUIRED variable: ${var}")
      fi
      ;;
    OPTIONAL)
      if [ "${ENV_VALUES["${var}"]+x}" = "x" ] && ! is_set_nonblank "${var}"; then
        errors+=("OPTIONAL variable present but blank: ${var}")
      fi
      ;;
    RELEASE_PIN)
      if is_set_nonblank "${var}"; then
        v="${ENV_VALUES["${var}"]}"
        case "${v}" in
          *@sha256:*|*@sha512:*)
            # A digest pin is the deliberate rollback/cutover form (ADR-063 §5):
            # it promotes a specific prior release by its immutable digest. It is
            # allowed only with an explicit, loud override in the env file so a
            # digest is never pinned silently. Mirrors GC_ALLOW_SAME_REVISION in
            # deploy.sh.
            if [ "${ENV_VALUES[GC_ALLOW_IMAGE_PIN]:-}" = "1" ]; then
              echo "WARNING: ${var} is digest-pinned and GC_ALLOW_IMAGE_PIN=1 is set;" \
                   "proceeding with a deliberate rollback/cutover pin." >&2
            else
              errors+=("${var} is digest-pinned (contains @sha256:); a digest pin is only for a deliberate rollback/cutover. Pin a versioned release tag (e.g. ...:1.4.0), or set GC_ALLOW_IMAGE_PIN=1 to confirm the digest is intentional")
            fi
            ;;
          *)
            # Production must run an immutable versioned release (ADR-063): the
            # semver image tag docker/metadata-action emits from a vX.Y.Z git tag
            # (X.Y.Z or X.Y, leading v stripped). A floating branch tag (:main,
            # :latest, :dev), any non-version tag, or no tag (implicit :latest)
            # re-conflates release and deploy and silently re-promotes on the
            # next pull (#1222). Take the tag after the last '/' then last ':' so
            # a registry port (host:5000/img:1.2.3) is not mistaken for the tag.
            name_tag="${v##*/}"
            case "${name_tag}" in
              *:*) tag="${name_tag##*:}" ;;
              *) tag="" ;;
            esac
            if [ -z "${tag}" ]; then
              errors+=("${var} has no image tag (resolves to the mutable :latest); pin an immutable versioned release tag, e.g. ...:1.4.0 (ADR-063)")
            elif [[ "${tag}" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?([-+][0-9A-Za-z.-]+)?$ ]]; then
              : # immutable versioned release pin — accepted
            else
              errors+=("${var} is pinned to a floating/non-version tag ':${tag}'; production must run an immutable versioned release (e.g. ...:1.4.0), not a moving branch tag like :main (ADR-063 / #1222)")
            fi
            ;;
        esac
      fi
      ;;
    CREDENTIAL_SLOT)
      filled=0
      for field in PRINCIPAL_NAME TOKEN ROLE; do
        if is_set_nonblank "${var}_${field}"; then
          filled=$((filled + 1))
        fi
      done
      if [ "${filled}" -ne 0 ] && [ "${filled}" -ne 3 ]; then
        errors+=("credential slot ${var} is partially populated (${filled}/3 of PRINCIPAL_NAME/TOKEN/ROLE); ADR-026 requires all-or-nothing or startup validation fails (#828)")
      fi
      ;;
    ALLOWLIST_SLOT)
      if [ "${ENV_VALUES["${var}"]+x}" = "x" ] && ! is_set_nonblank "${var}"; then
        errors+=("allowlist slot present but blank: ${var}")
      fi
      ;;
    *)
      errors+=("unknown schema directive '${directive}' for ${var}")
      ;;
  esac
done < "${SCHEMA_FILE}"

# Cross-cutting: security enabled (default true) requires at least one fully
# populated credential slot, or every authenticated route 401s (#828).
security_enabled="${ENV_VALUES[GC_SECURITY_ENABLED]:-true}"
case "${security_enabled}" in
  true|TRUE|True|1|yes)
    any_slot=0
    for i in 0 1 2 3 4; do
      p="GROUNDCONTROL_SECURITY_CREDENTIALS_${i}"
      if is_set_nonblank "${p}_PRINCIPAL_NAME" \
        && is_set_nonblank "${p}_TOKEN" \
        && is_set_nonblank "${p}_ROLE"; then
        any_slot=1
        break
      fi
    done
    if [ "${any_slot}" -eq 0 ]; then
      errors+=("GC_SECURITY_ENABLED is true but no credential slot is fully populated; every authenticated route would 401 (#828)")
    fi
    ;;
esac

if [ "${#errors[@]}" -gt 0 ]; then
  echo "ERROR: ${ENV_FILE} failed validation against $(basename "${SCHEMA_FILE}") (${#errors[@]} issue(s)):" >&2
  for e in "${errors[@]}"; do
    echo "  - ${e}" >&2
  done
  exit 1
fi

echo "env validation passed (${checked} schema directives checked; no secret values inspected for content)"
exit 0

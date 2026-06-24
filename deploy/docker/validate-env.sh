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
    FLOATING_TAG)
      if is_set_nonblank "${var}"; then
        v="${ENV_VALUES["${var}"]}"
        case "${v}" in
          *@sha256:*|*@sha512:*)
            # A deliberate digest pin (controlled cutover / rollback) is allowed
            # only with an explicit, loud override in the env file — never a
            # silent steady-state freeze (#953/GC-P022). Mirrors the
            # GC_ALLOW_SAME_REVISION override in deploy.sh.
            if [ "${ENV_VALUES[GC_ALLOW_IMAGE_PIN]:-}" = "1" ]; then
              echo "WARNING: ${var} is digest-pinned and GC_ALLOW_IMAGE_PIN=1 is set;" \
                   "proceeding with a deliberate pin. Restore a floating tag (e.g. :main)" \
                   "after the rollback/cutover or the deploy stays frozen." >&2
            else
              errors+=("${var} is digest-pinned (contains @sha256:); a long-lived digest pin freezes the deploy (#953/GC-P022). Use a floating tag such as :main, or set GC_ALLOW_IMAGE_PIN=1 for a deliberate temporary pin")
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

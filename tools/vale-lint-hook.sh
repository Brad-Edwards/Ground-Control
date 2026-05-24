#!/bin/bash
# pre-commit wrapper around Vale (ADR-054).
#
# Lints the staged .md / .markdown files passed by pre-commit. Skips
# gracefully (exit 0) when Vale is not installed on the contributor's
# machine, so a fresh clone does not start failing every commit before
# the developer has had a chance to run `make vale-install`. CI is the
# enforcement layer; the pre-commit hook is the early-warning layer.
set -euo pipefail

if [ "$#" -eq 0 ]; then
  exit 0
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VALE_BIN="${REPO_ROOT}/.tools/vale/current/vale"
VALE_INI="${REPO_ROOT}/.vale.ini"

if [ ! -x "${VALE_BIN}" ]; then
  echo "vale-lint-hook: Vale not installed at ${VALE_BIN}; run 'make vale-install' to enable doc style lint" >&2
  exit 0
fi

if [ ! -f "${VALE_INI}" ]; then
  echo "vale-lint-hook: missing ${VALE_INI}" >&2
  exit 1
fi

exec "${VALE_BIN}" --config="${VALE_INI}" "$@"

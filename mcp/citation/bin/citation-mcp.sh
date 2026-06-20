#!/usr/bin/env bash
# citation-mcp launcher.
#
# Locates the venv relative to this script's directory so the `.mcp.json`
# entry can use a repo-relative `command` instead of a hard-coded absolute
# path. Auto-bootstraps the venv on first run (idempotent); subsequent
# launches are a pure exec.
#
# .mcp.json:
#   "citation": {
#     "type": "stdio",
#     "command": "mcp/citation/bin/citation-mcp.sh",
#     "args": [],
#     "env": { ... }
#   }
#
# Standalone:
#   mcp/citation/bin/citation-mcp.sh                 # starts the MCP server
#   mcp/citation/bin/citation-mcp.sh --self-test     # offline self-test

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV="$PKG_DIR/.venv"

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "[citation-mcp] bootstrapping venv at $VENV" >&2
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --upgrade pip >&2
  "$VENV/bin/pip" install --quiet -e "$PKG_DIR" >&2
fi

exec "$VENV/bin/python" -m citation_mcp.server "$@"

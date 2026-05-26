#!/usr/bin/env bash
# # phase-4 Argdown validation tooling.
#
# Two checks over an argument map:
#   1. SYNTAX    — the Argdown CLI parses the file.
#   2. STRUCTURE — check-argument-structure.mjs flags, from the parsed model:
#        - ungrounded premises      (no {evidence:} tag, not derived elsewhere)
#        - unreconstructed support  (a support argument given no PCS)
#        - unanswered objections    (an attack on a load-bearing claim, no reply)
#        - circular support         (a statement that transitively supports itself)
#
# What this tooling does NOT check: material validity — whether the premises
# actually entail the conclusion — and the truth of each premise. Those stay the
# agent's job (see lit-review-argument/SKILL.md, workflow step 5).
#
# Requires: Node.js >= 20 (no other globals). The Argdown CLI is pinned in
# package.json + package-lock.json next to this script; on first run, the
# script installs the lockfile-resolved version into a local node_modules and
# every subsequent invocation reuses it offline. Pinning + offline reuse closes
# the supply-chain RCE path that ad-hoc `npx --yes` would open (see codex
# security review on PR #1032).
#
# Exit codes: 0 OK · 1 syntax OR structural failure in the MAP · 2 bad input ·
#             3 environment/tooling problem (NOT a problem with the map).
#
# Usage: validate-argument-map.sh [path-to-.argdown]   (default: argument-map.argdown)

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAP="${1:-argument-map.argdown}"

if [ ! -f "$MAP" ]; then
  echo "FAIL [input]: argument map not found: $MAP" >&2
  exit 2
fi

if ! command -v node >/dev/null 2>&1; then
  echo "FAIL [environment]: node not found — install Node.js (>= 20) to run the Argdown CLI." >&2
  exit 3
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
if [ "${NODE_MAJOR:-0}" -lt 20 ]; then
  echo "FAIL [environment]: Node $(node --version 2>/dev/null) detected; the Argdown CLI needs Node >= 20." >&2
  echo "This is a tooling/environment problem, not a problem with $MAP." >&2
  exit 3
fi

ARGDOWN_BIN="$SCRIPT_DIR/node_modules/.bin/argdown"
if [ ! -x "$ARGDOWN_BIN" ]; then
  if ! command -v npm >/dev/null 2>&1; then
    echo "FAIL [environment]: npm not found and Argdown CLI not installed at $ARGDOWN_BIN — install Node.js (>= 20) so npm can install the pinned CLI." >&2
    exit 3
  fi
  echo "[validate-argument-map] installing pinned Argdown CLI (one-time, offline reuse after)..." >&2
  ( cd "$SCRIPT_DIR" && npm ci --silent --no-audit --no-fund ) >&2
  if [ ! -x "$ARGDOWN_BIN" ]; then
    echo "FAIL [environment]: npm ci did not produce $ARGDOWN_BIN — see the install log above." >&2
    exit 3
  fi
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# --- 1. syntax: compile forces a full parse and model build ---
LOG="$OUT/argdown.log"
"$ARGDOWN_BIN" compile "$MAP" "$OUT" >"$LOG" 2>&1
STATUS=$?
cat "$LOG"

if grep -qE 'node:internal|Cannot find module|^[[:space:]]+at .+:[0-9]+:[0-9]+' "$LOG"; then
  echo "FAIL [environment]: the Argdown CLI failed to execute — see the trace above." >&2
  echo "This is a tooling/environment problem, not a problem with $MAP." >&2
  exit 3
fi
if [ "$STATUS" -ne 0 ]; then
  echo "FAIL [syntax]: Argdown reported problems parsing $MAP — fix the errors above." >&2
  exit 1
fi
echo "syntax OK — $MAP parses."

# --- 2. structure: export the JSON model, run the structural checker ---
"$ARGDOWN_BIN" json "$MAP" "$OUT" >"$OUT/json.log" 2>&1
JSON="$(ls "$OUT"/*.json 2>/dev/null | head -1)"
if [ -z "$JSON" ]; then
  echo "FAIL [environment]: Argdown JSON export produced no model file." >&2
  cat "$OUT/json.log" >&2
  exit 3
fi

echo "--- structural checks ---"
node "$SCRIPT_DIR/check-argument-structure.mjs" "$JSON"
CHECK=$?
if [ "$CHECK" -eq 2 ]; then
  echo "FAIL [environment]: the structural checker could not read the model." >&2
  exit 3
fi
if [ "$CHECK" -ne 0 ]; then
  echo "FAIL [structure]: fix the structural issues above." >&2
  exit 1
fi

echo
echo "OK: $MAP passes syntax and structural checks."
echo "Reminder: material validity (do the premises entail the conclusion) and the"
echo "truth of each premise are checked by the agent against the evidence base."

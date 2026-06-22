#!/usr/bin/env bash
# # phase-4 argument-map validator (migrated to argdown-feedback in issue #1045).
#
# Wrapper preserves the exact contract callers rely on:
#   - same entrypoint, same default map path, same exit codes
#   - 0 OK · 1 map failure · 2 bad input · 3 environment/tooling
#
# Behind the wrapper, the implementation is now Python:
#   - pyargdown (bundled by argdown-feedback) parses the .argdown file.
#   - skills/lit-review-argument/handlers/*.py runs the four project-specific
#     structural checks (A grounding, B unreconstructed support,
#     C unanswered objection, D circular support).
#   - argdown_feedback.verifiers.core.logreco_handler is wired in as an
#     opt-in formal-validity check (--logreco; requires PCS members to
#     carry {formalization:} metadata). Without --logreco we skip it,
#     matching today's structural-only behaviour.
#
# What this wrapper does NOT check: material validity in the absence of
# formalization metadata. That stays the agent's pass — see SKILL.md
# workflow step 5.
#
# Dependency pinning + offline reuse: skills/lit-review-argument/requirements.txt
# pins argdown-feedback to a commit SHA. On first invocation the wrapper
# creates a script-local .venv, installs the pinned tree, and reuses it
# offline thereafter — mirroring the citation MCP bootstrap (ADR-055).
#
# Usage: validate-argument-map.sh [--logreco] [path-to-.argdown]
#   default map path: argument-map.argdown

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"
LOCK_FILE="$SCRIPT_DIR/requirements-lock.txt"
LOCK_SENTINEL="$VENV_DIR/.lock-sha256"
PYTHON_BIN=""

LOGRECO_ARG=""
MAP=""
for arg in "$@"; do
  case "$arg" in
    --logreco)
      LOGRECO_ARG="--logreco"
      ;;
    --help|-h)
      cat <<'EOF'
Usage: validate-argument-map.sh [--logreco] [path-to-.argdown]

Validates a phase-4 argument map.

Options:
  --logreco   Also run the upstream LogReco family (Z3-backed FOL validity).
              Requires PCS members to carry {formalization: ...} metadata.

Exit codes:
  0  OK
  1  map fails one or more checks
  2  bad input (file missing, etc.)
  3  environment / tooling problem
EOF
      exit 0
      ;;
    *)
      if [[ -z "$MAP" ]]; then
        MAP="$arg"
      else
        echo "FAIL [input]: unexpected extra argument: $arg" >&2
        exit 2
      fi
      ;;
  esac
done
MAP="${MAP:-argument-map.argdown}"

if [[ ! -f "$MAP" ]]; then
  echo "FAIL [input]: argument map not found: $MAP" >&2
  exit 2
fi

# The wrapper has to `cd "$SCRIPT_DIR"` further down so `run_verifier.py` can
# do `from handlers import ...` against its own package; absolutize the user's
# path before that cd so a relative path supplied from the caller's CWD still
# resolves after the cd.
MAP="$(cd "$(dirname -- "$MAP")" && pwd)/$(basename -- "$MAP")"

if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL [environment]: python3 not found — install Python >= 3.11 to run the verifier." >&2
  exit 3
fi

PYTHON_MAJOR_MINOR="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null || echo "0.0")"
case "$PYTHON_MAJOR_MINOR" in
  3.1[1-9]|3.[2-9][0-9]|[4-9].*)
    ;;
  *)
    echo "FAIL [environment]: Python $PYTHON_MAJOR_MINOR detected; argdown-feedback needs Python >= 3.11." >&2
    exit 3
    ;;
esac

if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  echo "[validate-argument-map] creating script-local venv (one-time)..." >&2
  if ! python3 -m venv "$VENV_DIR" >/dev/null 2>&1; then
    echo "FAIL [environment]: python3 -m venv $VENV_DIR failed." >&2
    exit 3
  fi
fi

PYTHON_BIN="$VENV_DIR/bin/python"

if [[ ! -f "$LOCK_FILE" ]]; then
  echo "FAIL [environment]: pinned dependency lockfile not found: $LOCK_FILE" >&2
  exit 3
fi
LOCK_SHA="$(sha256sum "$LOCK_FILE" | awk '{print $1}')"
INSTALLED_SHA="$(cat "$LOCK_SENTINEL" 2>/dev/null || echo "")"

if [[ "$LOCK_SHA" != "$INSTALLED_SHA" ]] || ! "$PYTHON_BIN" -c "import argdown_feedback, pyargdown" >/dev/null 2>&1; then
  echo "[validate-argument-map] installing pinned dependencies from lockfile (one-time per lock change, offline reuse after)..." >&2
  # pip refuses to install VCS URLs under --require-hashes (no way to hash
  # a git ref). It also refuses mixed requirements files where some
  # entries have --hash= and others don't. Split the lockfile into two
  # temporary streams: VCS entries (install by commit SHA — content-
  # addressed) and hashable entries (install with --require-hashes).
  TMPDIR_LOCK="$(mktemp -d)"
  VCS_FILE="$TMPDIR_LOCK/vcs.txt"
  HASHED_FILE="$TMPDIR_LOCK/hashed.txt"
  if ! "$PYTHON_BIN" - "$LOCK_FILE" "$VCS_FILE" "$HASHED_FILE" <<'PYSPLIT'
import re, sys
lock, vcs_out, hashed_out = sys.argv[1], sys.argv[2], sys.argv[3]
with open(lock, encoding="utf-8") as fh:
    lines = fh.readlines()
vcs_blocks, hashed_blocks = [], []
i = 0
while i < len(lines):
    line = lines[i]
    if re.match(r"^[A-Za-z0-9_.\-]+ *@ *git\+", line):
        vcs_blocks.append(line)
        i += 1
        continue
    if line.lstrip().startswith("#") or not line.strip():
        # comments and blank lines: keep with hashed side so the file stays readable
        hashed_blocks.append(line)
        i += 1
        continue
    # collect a continuation block until a line doesn't end with backslash
    block = [line]
    while block[-1].rstrip().endswith("\\") and i + 1 < len(lines):
        i += 1
        block.append(lines[i])
    hashed_blocks.extend(block)
    i += 1
with open(vcs_out, "w", encoding="utf-8") as fh:
    fh.writelines(vcs_blocks)
with open(hashed_out, "w", encoding="utf-8") as fh:
    fh.writelines(hashed_blocks)
PYSPLIT
  then
    rm -rf "$TMPDIR_LOCK"
    echo "FAIL [environment]: lockfile split failed." >&2
    exit 3
  fi
  if ! "$PYTHON_BIN" -m pip install --quiet --no-deps -r "$VCS_FILE" >&2; then
    rm -rf "$TMPDIR_LOCK"
    echo "FAIL [environment]: pip install of VCS-pinned deps failed — see log above." >&2
    exit 3
  fi
  if ! "$PYTHON_BIN" -m pip install --quiet --no-deps --require-hashes -r "$HASHED_FILE" >&2; then
    rm -rf "$TMPDIR_LOCK"
    echo "FAIL [environment]: pip install of hash-locked deps failed — see log above." >&2
    exit 3
  fi
  rm -rf "$TMPDIR_LOCK"
  printf '%s' "$LOCK_SHA" >"$LOCK_SENTINEL"
fi

if ! "$PYTHON_BIN" -c "import nltk; nltk.data.find('tokenizers/punkt')" >/dev/null 2>&1; then
  echo "[validate-argument-map] downloading NLTK punkt corpus (one-time)..." >&2
  # Try the env-var trick first; if that fails, retry without it — different
  # nltk versions behave differently. Only fail if both attempts fail.
  if ! "$PYTHON_BIN" -c "import nltk, os; nltk.download('punkt', download_dir=os.path.join(os.environ['VIRTUAL_ENV'], 'nltk_data'), quiet=True)" \
      VIRTUAL_ENV="$VENV_DIR" >/dev/null 2>&1 \
      && ! "$PYTHON_BIN" -c "import nltk; nltk.download('punkt', quiet=True)" >/dev/null 2>&1; then
    echo "FAIL [environment]: NLTK punkt corpus download failed; argdown-feedback needs it." >&2
    exit 3
  fi
fi

cd "$SCRIPT_DIR"
"$PYTHON_BIN" "$SCRIPT_DIR/run_verifier.py" $LOGRECO_ARG "$MAP"
exit $?

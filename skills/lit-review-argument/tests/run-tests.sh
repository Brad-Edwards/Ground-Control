#!/usr/bin/env bash
# # phase-4 argument-tooling unit tests (post-migration to argdown-feedback).
#
# Exercises validate-argument-map.sh against four fixtures:
#   clean-map.argdown        — structurally clean; must pass without --logreco.
#   dirty-map.argdown        — syntactically valid but trips rules A, B, C, D.
#                              No formalizations, so --logreco is not tested here;
#                              the failures are structural, not formal.
#   logreco-valid.argdown    — structurally clean, every PCS member formalized,
#                              inference deductively valid; passes with --logreco.
#   logreco-invalid.argdown  — structurally clean, every PCS member formalized,
#                              inference is affirming-the-consequent; fails with
#                              --logreco (the logreco family rejects it).
#
# Run: tests/run-tests.sh   (exit 0 = all tests pass, 1 = a test failed)

set -u
TDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATE="$TDIR/../validate-argument-map.sh"
PASS=0
FAIL=0
check() {
  if [ "$1" = ok ]; then
    PASS=$((PASS + 1))
    echo "  ok   — $2"
  else
    FAIL=$((FAIL + 1))
    echo "  FAIL — $2"
  fi
}

echo "clean-map.argdown — expect: passes, exit 0"
OUT="$("$VALIDATE" "$TDIR/clean-map.argdown" 2>&1)"
RC=$?
[ "$RC" -eq 0 ] && check ok "clean map exits 0" || check no "clean map exit $RC (expected 0)"
grep -q "STRUCTURE OK" <<<"$OUT" && check ok "clean map reports STRUCTURE OK" || check no "clean map missing STRUCTURE OK"

echo "dirty-map.argdown — expect: fails rules A, B, C, D, exit 1"
OUT="$("$VALIDATE" "$TDIR/dirty-map.argdown" 2>&1)"
RC=$?
[ "$RC" -eq 1 ] && check ok "dirty map exits 1" || check no "dirty map exit $RC (expected 1)"
for c in A B C D; do
  grep -q "FAIL: $c " <<<"$OUT" && check ok "dirty map triggers check $c" || check no "dirty map did not trigger check $c"
done

echo "logreco-valid.argdown --logreco — expect: passes, exit 0"
OUT="$("$VALIDATE" --logreco "$TDIR/logreco-valid.argdown" 2>&1)"
RC=$?
[ "$RC" -eq 0 ] && check ok "logreco-valid exits 0" || check no "logreco-valid exit $RC (expected 0)"
grep -q "STRUCTURE OK" <<<"$OUT" && check ok "logreco-valid reports STRUCTURE OK" || check no "logreco-valid missing STRUCTURE OK"

echo "logreco-invalid.argdown --logreco — expect: fails on logreco, exit 1"
OUT="$("$VALIDATE" --logreco "$TDIR/logreco-invalid.argdown" 2>&1)"
RC=$?
[ "$RC" -eq 1 ] && check ok "logreco-invalid exits 1" || check no "logreco-invalid exit $RC (expected 1)"
# Match the actual failure line ("  FAIL: logreco <handler>: ..."), not the
# section header "--- logreco (opt-in formal validity) ---" which is printed
# unconditionally whenever --logreco is passed. A bare `grep -q "logreco"`
# would pass even with _run_logreco silently returning zero failures.
grep -qE "FAIL:[[:space:]]*logreco " <<<"$OUT" && check ok "logreco-invalid surfaces a logreco failure" || check no "logreco-invalid did not surface a logreco failure"

echo
echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

#!/usr/bin/env bash
# # phase-4 argument-tooling unit tests.
#
# Exercises validate-argument-map.sh (Argdown CLI parse + structural checker)
# against two fixtures:
#   clean-map.argdown — well-formed; must pass.
#   dirty-map.argdown — deliberately broken; must fail structural checks A, B,
#                       C, and D. It is syntactically valid, so the failures are
#                       structural, not parse errors — which is the point: it
#                       exercises check-argument-structure.mjs, not the parser.
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

echo "dirty-map.argdown — expect: fails structural checks A, B, C, D, exit 1"
OUT="$("$VALIDATE" "$TDIR/dirty-map.argdown" 2>&1)"
RC=$?
[ "$RC" -eq 1 ] && check ok "dirty map exits 1" || check no "dirty map exit $RC (expected 1)"
for c in A B C D; do
  grep -q "FAIL: $c " <<<"$OUT" && check ok "dirty map triggers check $c" || check no "dirty map did not trigger check $c"
done

echo
echo "result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

#!/usr/bin/env python3
"""Phase-4 argument-map verifier entry point.

Invoked by validate-argument-map.sh inside the skill's local venv. Parses the
argdown file with pyargdown, runs the four project-specific structural checks
(A grounding, B unreconstructed support, C unanswered objection, D circular
support), and optionally runs argdown_feedback's LogReco family for
Z3-backed material validity when --logreco is passed.

Exit codes:
    0  structural (and, if requested, formal) checks all pass
    1  the map fails one or more checks
    2  bad input (missing file, etc.)
    3  environment / tooling problem (parser blew up, etc.)
"""

from __future__ import annotations

import argparse
import sys
import traceback
from pathlib import Path

from handlers import (
    check_circular_support,
    check_evidence_grounding,
    check_unanswered_objection,
    check_unreconstructed_support,
)

EXIT_OK = 0
EXIT_MAP_FAIL = 1
EXIT_BAD_INPUT = 2
EXIT_ENVIRONMENT = 3


def _emit(label: str, lines: list[str]) -> None:
    if not lines:
        return
    for line in lines:
        print(f"  {label}: {line}")


def _run_logreco(parsed) -> tuple[list[str], list[str]]:
    """Run the upstream LogReco family. Returns ``(failures, infos)``.

    Skips entirely when no PCS member in the map carries a ``formalization``
    key — logreco is an opt-in capability and demanding formalizations
    everywhere would defeat the agent's normal phase-4 workflow.
    """

    from argdown_feedback.verifiers.core.logreco_handler import (
        LogRecoCompositeHandler,
    )
    from argdown_feedback.verifiers.verification_request import (
        PrimaryVerificationData,
        VerificationDType,
        VerificationRequest,
    )

    has_formalization = False
    for argument in parsed.arguments:
        for member in argument.pcs:
            prop = parsed.get_proposition(member.proposition_label)
            if prop and isinstance(prop.data, dict) and prop.data.get("formalization"):
                has_formalization = True
                break
        if has_formalization:
            break
    if not has_formalization:
        return [], [
            "logreco skipped — no {formalization: ...} metadata on any PCS member."
        ]

    vdata = PrimaryVerificationData(
        id="argdown-0",
        dtype=VerificationDType.argdown,
        data=parsed,
        code_snippet=None,
        metadata={},
    )
    request = VerificationRequest(inputs="", verification_data=[vdata])

    handler = LogRecoCompositeHandler()
    handler.process(request)

    failures: list[str] = []
    for result in request.results:
        if result.is_valid:
            continue
        verifier = result.verifier_id.split(".")[-1]
        message = result.message or "(no detail)"
        failures.append(f"logreco {verifier}: {message}")
    return failures, []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="run_verifier",
        description="Verify a phase-4 argument map.",
    )
    parser.add_argument(
        "map_path",
        nargs="?",
        default="argument-map.argdown",
        help="path to the .argdown file (default: argument-map.argdown)",
    )
    parser.add_argument(
        "--logreco",
        action="store_true",
        help="also run upstream LogReco family (Z3-backed FOL validity check)",
    )
    args = parser.parse_args(argv)

    map_path = Path(args.map_path)
    if not map_path.is_file():
        print(f"FAIL [input]: argument map not found: {map_path}", file=sys.stderr)
        return EXIT_BAD_INPUT

    try:
        source = map_path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL [input]: cannot read {map_path}: {exc}", file=sys.stderr)
        return EXIT_BAD_INPUT

    try:
        from pyargdown import parse_argdown
    except ImportError as exc:
        print(
            "FAIL [environment]: pyargdown not installed in the verifier venv: "
            f"{exc}",
            file=sys.stderr,
        )
        return EXIT_ENVIRONMENT

    try:
        parsed = parse_argdown(source)
    except Exception as exc:
        print(
            f"FAIL [syntax]: Argdown failed to parse {map_path}: {exc}",
            file=sys.stderr,
        )
        return EXIT_MAP_FAIL

    print(f"syntax OK — {map_path} parses.")
    print("--- structural checks ---")

    all_failures: list[str] = []
    all_infos: list[str] = []
    for check in (
        check_evidence_grounding,
        check_unreconstructed_support,
        check_unanswered_objection,
        check_circular_support,
    ):
        try:
            failures, infos = check(parsed)
        except Exception as exc:
            print(
                f"FAIL [environment]: structural check {check.__name__} raised: {exc}",
                file=sys.stderr,
            )
            traceback.print_exc(file=sys.stderr)
            return EXIT_ENVIRONMENT
        all_failures.extend(failures)
        all_infos.extend(infos)

    if args.logreco:
        print("--- logreco (opt-in formal validity) ---")
        try:
            failures, infos = _run_logreco(parsed)
        except Exception as exc:
            print(
                f"FAIL [environment]: logreco run raised: {exc}",
                file=sys.stderr,
            )
            traceback.print_exc(file=sys.stderr)
            return EXIT_ENVIRONMENT
        all_failures.extend(failures)
        all_infos.extend(infos)

    _emit("info", all_infos)
    n_arguments = len(parsed.arguments)
    n_premises = sum(
        sum(1 for _ in argument.pcs) for argument in parsed.arguments
    )
    n_attacks = sum(
        1
        for edge in parsed.dialectical_relations
        if edge.valence.name in {"ATTACK", "CONTRADICT", "UNDERCUT"}
    )
    print(
        f"  checked: {n_arguments} arguments, {n_premises} pcs members, "
        f"{n_attacks} attack relations."
    )

    if all_failures:
        for failure in all_failures:
            print(f"  FAIL: {failure}")
        print(f"  STRUCTURE FAIL — {len(all_failures)} issue(s) above.")
        return EXIT_MAP_FAIL

    print(
        "  STRUCTURE OK — grounding, support-reconstruction, "
        "objection-closure, non-circularity all pass."
    )
    print(
        "  NOTE: premise truth against the evidence base remains the agent's "
        "judgement; mechanical checks do not establish it."
    )
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())

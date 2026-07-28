"""Policy checks: contract surface.

Extracted from tools/policy/checks.py (issue #1355), which had reached 5,679 lines against
the repo's 500-LOC limit. checks.py remains the entry point and re-exports this module, so
every existing import path and the CLI keep working.
"""

from __future__ import annotations
import argparse
import fnmatch
import hashlib
import json
import os
import posixpath
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from .core import (
    REPO_ROOT,
    Violation,
)
from .ontology_crosswalk import (
    IMPLEMENT_SKILL_PATH,
    IMPLEMENT_STEP_TEST_QUALITY_PATH,
    extract_step_section,
)


def run_test_quality_decision_record_contract(
    *,
    text: str | None = None,
    root: Path = REPO_ROOT,
) -> list[Violation]:
    """Assert the test-quality review step mandates the decision-record contract.

    The contract is: every test-quality cycle ends with a
    `gc_post_decision_record` post carrying `reviewer: "test-quality"` and
    the findings list, and a clean cycle (`findings: []`) is the structured
    advance signal (no user acknowledgment turn).

    Section heading the check looks for is `### Step 6.6: ...` (the #906
    placement; the step was at `### Step 13: ...` from #884 v2 until #906
    moved it pre-push).

    Pass `text=` directly to validate a fixture string. With no `text`, the
    check reads `skills/implement/SKILL.md` from `root`.

    Emits:
      ``test-quality-section-missing``       — `### Step 6.6: ...` heading absent.
      ``test-quality-decision-record-contract`` — section present, contract not.
    """
    if text is None:
        # The Step 6.6 section moved from inline in SKILL.md to its own
        # per-step file under skills/implement/steps/ when issue #934
        # split the monolithic SKILL into a thin orchestrator + per-step
        # prose. Prefer reading from the orchestrator's per-step file
        # directly; fall back to the orchestrator itself (covering both
        # the new layout and the older inline layout).
        step_path = root / IMPLEMENT_STEP_TEST_QUALITY_PATH
        skill_path = root / IMPLEMENT_SKILL_PATH
        if step_path.exists():
            text = step_path.read_text(encoding="utf-8")
        elif skill_path.exists():
            text = skill_path.read_text(encoding="utf-8")
        else:
            return [
                Violation(
                    code="test-quality-skill-missing",
                    message=(
                        f"Neither {IMPLEMENT_STEP_TEST_QUALITY_PATH} nor "
                        f"{IMPLEMENT_SKILL_PATH} exists — test-quality "
                        "contract cannot be verified."
                    ),
                    details=[
                        f"expected at {IMPLEMENT_STEP_TEST_QUALITY_PATH}",
                        f"or at {IMPLEMENT_SKILL_PATH}",
                    ],
                )
            ]

    section = extract_step_section(text, "Step 6.6")
    if section is None:
        return [
            Violation(
                code="test-quality-section-missing",
                message="`Step 6.6: ...` section is missing from the test-quality contract source.",
                details=[
                    "Step 6.6 is the pre-push test-quality review phase "
                    "(moved from former Step 13 by issue #906); the section "
                    "must exist and must mandate the gc_post_decision_record "
                    "contract per issue #884. After issue #934 the section "
                    f"lives at {IMPLEMENT_STEP_TEST_QUALITY_PATH}.",
                ],
            )
        ]

    violations: list[Violation] = []

    # Anti-contract patterns: prose that would re-introduce the #884
    # regression in a different shape. Substring/regex presence of any of
    # these in Step 13 fails the check regardless of which positive tokens
    # are also present. Each pattern is intentionally specific — the goal
    # is "this exact failure mode," not a broad ban on the words.
    #
    # Negation guard. Some anti-patterns look like "skip ... decision
    # record" or "do not advance to Step 14". These same word sequences
    # appear in *correct* guardrail prose ("do not skip the decision
    # record", "on `ok: false`, do not advance to Step 14"). Without a
    # guard, the check rejects the prose it is supposed to require.
    # _ANTI_NEGATION_PRECEDES_RE matches a negator within ~60 chars
    # immediately before the matched anti-pattern; when present the match
    # is skipped. Negators recognized:
    #   "do(es) not ..." / "don't" / "must not"
    #   "never" (when used as a directive: "must never", "agents never")
    #   "on `ok: false`,"  (the success-precondition context)
    #   "until `ok: true`,"
    #   "unless ..." (defensive — usually scopes a permitted exception)
    # The window is small enough that legitimate anti-contract prose
    # ("Skip the decision record on clean cycles") which lacks an
    # immediately-preceding negator still matches.
    # Anti-pattern windows allow newlines (anti-contract prose often wraps
    # across multiple lines in a numbered list); they stop at a sentence
    # boundary (`.`) and are length-capped so they cannot span paragraphs.
    anti_patterns: tuple[tuple[str, str], ...] = (
        (
            "do-not-call-post-record",
            # "do not call/post/invoke gc_post_decision_record"
            r"(?is)\bdo(?:es)?\s+not\s+(?:call|post|invoke)\b"
            r"[^.]{0,120}?\bgc_post_decision_record\b",
        ),
        (
            "skip-decision-record",
            # "skip the decision record / skip posting decision record on clean"
            r"(?is)\bskip(?:ping)?\b[^.]{0,60}?\bdecision[-\s]?record\b",
        ),
        (
            "do-not-proceed-step14",
            # "do not proceed to Step 14" / "do not advance to Step 14"
            r"(?is)\bdo(?:es)?\s+not\s+(?:proceed|advance|continue)\b"
            r"[^.]{0,120}?\bstep\s*14\b",
        ),
        (
            "findings-empty-not-enough",
            # "`findings: []` is not enough / does not suffice / is insufficient"
            r"(?is)`?findings:\s*\[\]`?[^.]{0,60}?"
            r"(?:is\s+not\s+enough|does\s+not\s+suffice|is\s+insufficient|"
            r"is\s+not\s+sufficient)",
        ),
        (
            "require-user-acknowledgment-turn",
            # "wait for the user to acknowledge" + Step 14 / clean cycle context
            r"(?is)\bwait\s+for\s+(?:the\s+)?user\b"
            r"[^.]{0,120}?(?:acknowledg|sign[-\s]?off|approval|confirm)"
            r"[^.]{0,120}?(?:clean|step\s*14)",
        ),
        (
            "findings-routed-to-user",
            # The exact failure mode the user reported after #884 v1
            # shipped: parent treats returned findings as a status report
            # to the user instead of work to fix in the same turn.
            # Patterns: "echo / report / return / hand findings to the
            # user" (literal) and "echo them to the user" (pronoun).
            # The verb is paired with a "to/back to/for ... user/human"
            # phrase to distinguish it from the legitimate
            # "return control to the parent" prose. The pronouns "them"
            # / "it" are accepted because the noun "findings" often sits
            # in the preceding clause ("when findings are returned, echo
            # them to the user"). The whole point of the review is to
            # fix the tests; surfacing findings to the user defeats that
            # contract.
            r"(?is)\b(?:echo|report|return|hand|surface|present|forward)\b"
            r"[^.]{0,120}?\b(?:findings|them|it)\b"
            r"[^.]{0,80}?\b(?:to|back\s+to|for)\s+(?:the\s+)?(?:user|human)\b",
        ),
    )
    # Negation guard: scan the ~60 chars immediately preceding each match
    # for any of the negator phrases listed above. Cap at 60 because real
    # negation usually sits in the same clause as the verb it negates;
    # widening the window invites false negatives where unrelated negators
    # appear earlier in the paragraph.
    negation_window_re = re.compile(
        r"(?is)\b(?:do(?:es)?\s+not|don['’]t|must\s+not|"
        r"(?:must|may|should|shall|agents?)\s+never|never\s+skip|"
        r"on\s+`?ok\s*:\s*false`?|until\s+`?ok\s*:\s*true`?|unless)\b"
    )
    matched_anti: list[str] = []
    for code, pattern in anti_patterns:
        for match in re.finditer(pattern, section):
            window_start = max(0, match.start() - 60)
            preceding = section[window_start:match.start()]
            if negation_window_re.search(preceding):
                continue
            matched_anti.append(code)
            break
    if matched_anti:
        violations.append(
            Violation(
                code="test-quality-anti-contract-prose",
                message=(
                    "Step 6.6 (test-quality review) contains anti-contract prose "
                    "that re-introduces the #884 silent-advance failure mode."
                ),
                details=[f"matched anti-contract pattern: {code}" for code in matched_anti],
            )
        )

    missing: list[str] = []
    # Per #884 v2: Step 13 must invoke the MCP tool (`gc_test_quality_review`),
    # not the legacy `Skill("review-tests")` boundary. The Skill-tool
    # boundary's autoregressive bias was the root cause of the #884 v1
    # regression (parent agent echoed prose findings back to the user
    # instead of fixing them in-turn); the MCP tool returns a structured
    # `next_action` envelope that overrides the bias. Pin that requirement
    # structurally so a future SKILL edit cannot silently regress to the
    # old boundary while keeping the decision-record prose intact.
    if "gc_test_quality_review" not in section:
        missing.append(
            "MCP-tool invocation `gc_test_quality_review` (per #884 v2 the "
            "test-quality reviewer is an MCP tool returning a structured "
            "`next_action` envelope, not a `Skill(\"review-tests\")` call; "
            "the Skill-tool boundary's prose-return shape was the root cause "
            "of the v1 regression)"
        )
    # `next_action` is the dispatch field the parent reads as a directive
    # rather than as text to summarize. The SKILL must surface it
    # explicitly so the dispatch branches are visible to readers.
    if "next_action" not in section:
        missing.append(
            "`next_action` dispatch field (the parent reads it as a directive; "
            "must be explicit in Step 13 prose, not implied)"
        )
    if "gc_post_decision_record" not in section:
        missing.append(
            "tool name `gc_post_decision_record` (the canonical durable-record "
            "post; do not invent a new marker family)"
        )
    if not re.search(r'reviewer\s*[:=]\s*"test-quality"', section):
        missing.append(
            "reviewer enum literal `reviewer: \"test-quality\"` (must use "
            "the existing enum value, not a synthetic alias)"
        )
    if "findings: []" not in section:
        missing.append(
            "clean-cycle case `findings: []` (a clean cycle must still post "
            "a decision record; empty findings render as `0 (clean run)`)"
        )
    # Continuation signal: the prose must make explicit that a clean cycle
    # advances into Step 14 in the same turn. Accept any of "advance",
    # "proceed", or "continue" within a window that mentions Step 14, OR
    # the explicit "no acknowledgment turn" / "no user turn" phrasing.
    # The advance target was "Step 14" under the pre-#906 placement; under
    # #906 the test-quality review moved pre-push to Step 6.6 and the
    # advance target is "Phase C" (stage / commit / push). Accept either
    # token so the gate doesn't pin to a historical step number that the
    # workflow may renumber again.
    has_continuation = bool(
        re.search(
            r"(?is)(?:advance|proceed|continue)[^\n]{0,200}?(?:step\s*14|phase\s*c)"
            r"|(?:step\s*14|phase\s*c)[^\n]{0,200}?(?:advance|proceed|continue)"
            r"|no\s+(?:user\s+)?(?:acknowledg(?:e)?ment|user)\s+turn",
            section,
        )
    )
    if not has_continuation:
        missing.append(
            "explicit advance signal (a clean decision record IS "
            "the continuation signal — no acknowledgment turn between the "
            "test-quality review and the next phase; cite Phase C or Step 14 "
            "explicitly)"
        )
    # Success precondition: advancement must be gated on the
    # `gc_post_decision_record` call returning `ok: true`. Without this,
    # `ok: false` envelopes (sensitive-content rejection, body-size cap,
    # `gh` posting failure, network) would let Step 13 advance without the
    # durable issue-thread marker, re-introducing #884 in a different
    # shape. Accept the literal `ok: true` (matching the MCP envelope key)
    # or the prose "success precondition" wording.
    has_success_precondition = bool(
        re.search(
            r"(?is)`?ok\s*:\s*true`?"
            r"|\bsuccess\s+precondition\b",
            section,
        )
    )
    if not has_success_precondition:
        missing.append(
            "success-precondition clause `ok: true` (advance to Step 14 only "
            "after `gc_post_decision_record` returns `ok: true`; on `ok: false` "
            "fix the underlying tooling issue and retry — do not enter Step 14 "
            "with the durable marker missing)"
        )
    # Findings-fix-in-same-turn directive. The whole point of the
    # test-quality review is to fix the tests, not to file a status
    # report on them. After #884 v1 shipped, the regression reappeared
    # in a different shape: when `review-tests` returns findings, the
    # parent agent echoes them back to the user and stops, instead of
    # fixing them in the same agent turn. This required clause pairs an
    # action verb ("fix" / "address" / "resolve") against `findings`
    # with a "same turn" / "do not stop" continuity marker so the
    # contract is unambiguous: findings are work, not a status report.
    has_fix_same_turn_directive = bool(
        re.search(
            r"(?is)\b(?:fix|address|resolve)\b"
            r"[^.]{0,200}?\bfinding[s]?\b"
            r"[^.]{0,200}?\b(?:in\s+the\s+same\s+(?:turn|agent\s+turn)"
            r"|same\s+(?:agent\s+)?turn"
            r"|do\s+not\s+stop"
            r"|without\s+stopping)\b",
            section,
        )
    ) or bool(
        re.search(
            r"(?is)\b(?:in\s+the\s+same\s+(?:turn|agent\s+turn)"
            r"|same\s+(?:agent\s+)?turn"
            r"|do\s+not\s+stop"
            r"|without\s+stopping)\b"
            r"[^.]{0,200}?\b(?:fix|address|resolve)\b"
            r"[^.]{0,200}?\bfinding[s]?\b",
            section,
        )
    )
    if not has_fix_same_turn_directive:
        missing.append(
            "findings-fix-in-same-turn directive (when `review-tests` returns "
            "findings, the parent MUST fix them in the same agent turn — do "
            "not stop, do not echo the findings to the user as a status "
            "report; the whole point of the review is to fix the tests, "
            "issue #884 follow-up)"
        )

    if missing:
        violations.append(
            Violation(
                code="test-quality-decision-record-contract",
                message=(
                    "Step 6.6 (test-quality review) must mandate the "
                    "gc_post_decision_record contract for test-quality cycles "
                    "(issue #884; step moved pre-push by #906)."
                ),
                details=missing,
            )
        )
    return violations


IMPLEMENT_STEP_17_PATH = "skills/implement/steps/step-17-completion.md"


IMPLEMENT_STEP_20_PATH = "skills/implement/steps/step-20-close-issue-on-merge.md"

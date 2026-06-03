"""Rule A: every PCS premise must be grounded.

A premise is grounded if it carries an `{evidence: "<non-empty>"}` data tag
or its proposition is itself the conclusion of another argument in the map
(grounded by derivation). A premise tagged `{evidence: "paper-contribution: ..."}`
is the paper's own analytic frame — listed in the info channel, not failed.
"""

from __future__ import annotations

from typing import Iterable

from pyargdown import ArgdownMultiDiGraph, Conclusion


def _evidence_tag(proposition_data: dict | None) -> str | None:
    if not proposition_data:
        return None
    raw = proposition_data.get("evidence")
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None


def _snippet(proposition) -> str:
    text = (proposition.texts[0] if proposition.texts else proposition.label or "?")
    text = " ".join(text.split())
    return text[:72]


def check_evidence_grounding(argdown: ArgdownMultiDiGraph) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule A.

    Failures are ungrounded-premise messages. Infos list analytic-frame
    premises (defended in prose, not cited) — surfaced separately so the
    agent can audit them without treating them as errors.
    """

    conclusion_labels: set[str] = set()
    for argument in argdown.arguments:
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                conclusion_labels.add(member.proposition_label)

    failures: list[str] = []
    infos: list[str] = []

    for argument in argdown.arguments:
        arg_label = argument.label or "<unlabeled argument>"
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                continue
            proposition = argdown.get_proposition(member.proposition_label)
            tag = _evidence_tag(proposition.data) if proposition else None
            if tag is not None and tag.lower().startswith("paper-contribution"):
                infos.append(
                    f"analytic-frame premise (defend in prose, not cited) — <{arg_label}>: "
                    f'"{_snippet(proposition)}…"'
                )
                continue
            if tag:
                continue
            if member.proposition_label in conclusion_labels:
                continue
            snippet = _snippet(proposition) if proposition else member.proposition_label
            failures.append(
                f'A ungrounded premise — <{arg_label}>: "{snippet}…" '
                "has no {evidence:} tag and is not derived in the map."
            )

    return failures, infos


__all__ = ["check_evidence_grounding"]

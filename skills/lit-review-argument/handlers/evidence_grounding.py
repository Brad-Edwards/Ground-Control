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
    """Return the trimmed ``evidence`` data tag, or ``None`` if absent/empty."""
    if not proposition_data:
        return None
    raw = proposition_data.get("evidence")
    if raw is None:
        return None
    text = str(raw).strip()
    return text or None


def _snippet(proposition: object) -> str:
    """Return a whitespace-collapsed, 72-char snippet of a proposition's text."""
    text = (proposition.texts[0] if proposition.texts else proposition.label or "?")
    text = " ".join(text.split())
    return text[:72]


def _collect_conclusion_labels(argdown: ArgdownMultiDiGraph) -> set[str]:
    """Return the set of proposition labels that are conclusions in the map."""
    conclusion_labels: set[str] = set()
    for argument in argdown.arguments:
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                conclusion_labels.add(member.proposition_label)
    return conclusion_labels


def _classify_premise(
    argdown: ArgdownMultiDiGraph,
    member,
    arg_label: str,
    conclusion_labels: set[str],
) -> tuple[str, str] | None:
    """Classify a single non-conclusion premise.

    Returns ``("info", message)`` for analytic-frame premises,
    ``("failure", message)`` for ungrounded premises, or ``None`` when the
    premise is grounded (cited or derived).
    """
    proposition = argdown.get_proposition(member.proposition_label)
    tag = _evidence_tag(proposition.data) if proposition else None
    if tag is not None and tag.lower().startswith("paper-contribution"):
        return (
            "info",
            f"analytic-frame premise (defend in prose, not cited) — <{arg_label}>: "
            f'"{_snippet(proposition)}…"',
        )
    if tag:
        return None
    if member.proposition_label in conclusion_labels:
        return None
    snippet = _snippet(proposition) if proposition else member.proposition_label
    return (
        "failure",
        f'A ungrounded premise — <{arg_label}>: "{snippet}…" '
        "has no {evidence:} tag and is not derived in the map.",
    )


def check_evidence_grounding(argdown: ArgdownMultiDiGraph) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule A.

    Failures are ungrounded-premise messages. Infos list analytic-frame
    premises (defended in prose, not cited) — surfaced separately so the
    agent can audit them without treating them as errors.
    """

    conclusion_labels = _collect_conclusion_labels(argdown)

    failures: list[str] = []
    infos: list[str] = []

    for argument in argdown.arguments:
        arg_label = argument.label or "<unlabeled argument>"
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                continue
            classified = _classify_premise(argdown, member, arg_label, conclusion_labels)
            if classified is None:
                continue
            kind, message = classified
            if kind == "info":
                infos.append(message)
            else:
                failures.append(message)

    return failures, infos


__all__ = ["check_evidence_grounding"]

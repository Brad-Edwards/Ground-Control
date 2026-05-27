"""Rule B: an argument wired in as support for a claim must be reconstructed.

A no-PCS argument that only attacks something is a bare-stated objection
(acceptable, surfaced as info). A no-PCS argument that sits at the source
of a SUPPORT edge has been claimed to ground something without actually
spelling out its premises — that is the failure mode argdown exists to
prevent.
"""

from __future__ import annotations

from pyargdown import ArgdownMultiDiGraph, Valence


def check_unreconstructed_support(
    argdown: ArgdownMultiDiGraph,
) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule B."""

    no_pcs_arguments = {
        argument.label
        for argument in argdown.arguments
        if argument.label and not argument.pcs
    }

    support_sources: set[str] = set()
    for edge in argdown.dialectical_relations:
        if edge.valence is Valence.SUPPORT:
            support_sources.add(edge.source)

    failures: list[str] = []
    infos: list[str] = []
    for label in sorted(no_pcs_arguments):
        if label in support_sources:
            failures.append(
                f"B unreconstructed support — <{label}> is wired in as "
                "support for a claim but has no premise-conclusion reconstruction."
            )
        else:
            infos.append(f"argument stated without a reconstruction (no PCS) — <{label}>")

    return failures, infos


__all__ = ["check_unreconstructed_support"]

"""Rule C: every modelled objection must have a reply.

A modelled objection is a node that attacks a load-bearing claim — a
statement that participates in some argument's PCS. An objection with no
incoming attack of its own is unanswered, and the discussion has a gap.

A statement that supports its target rather than attacks it is not an
objection; pyargdown's ``Valence.UNDERCUT`` is treated as an attack here
because it functions the same way for the closure check.
"""

from __future__ import annotations

from pyargdown import ArgdownMultiDiGraph, Valence


_ATTACK_VALENCES = {Valence.ATTACK, Valence.CONTRADICT, Valence.UNDERCUT}


def check_unanswered_objection(
    argdown: ArgdownMultiDiGraph,
) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule C."""

    load_bearing_titles: set[str] = set()
    for argument in argdown.arguments:
        for member in argument.pcs:
            load_bearing_titles.add(member.proposition_label)

    attacked_nodes: set[str] = set()
    attacking_nodes_against_load_bearing: list[str] = []
    for edge in argdown.dialectical_relations:
        if edge.valence in _ATTACK_VALENCES:
            attacked_nodes.add(edge.target)
            if edge.target in load_bearing_titles:
                attacking_nodes_against_load_bearing.append(edge.source)

    failures: list[str] = []
    seen: set[str] = set()
    for objection in attacking_nodes_against_load_bearing:
        if objection in seen:
            continue
        seen.add(objection)
        if objection not in attacked_nodes:
            failures.append(
                f'C unanswered objection — "{objection}" attacks a load-bearing claim '
                "and has no response (no incoming attack)."
            )

    return failures, []


__all__ = ["check_unanswered_objection"]

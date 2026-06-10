"""Rule D: no statement may transitively support itself.

Build the directed graph where a PCS premise points at its argument and an
argument points at its main / preliminary conclusion. A back-edge in DFS
is a cycle: a statement whose chain of support eventually points back at
the statement itself.
"""

from __future__ import annotations

from pyargdown import ArgdownMultiDiGraph, Conclusion

WHITE, GREY, BLACK = 0, 1, 2


def _build_adjacency(argdown: ArgdownMultiDiGraph) -> dict[str, list[str]]:
    """Build the support graph as an adjacency list keyed by node label."""
    adjacency: dict[str, list[str]] = {}

    def add_edge(src: str, dst: str) -> None:
        """Record a directed edge ``src -> dst`` in the adjacency list."""
        adjacency.setdefault(src, []).append(dst)

    for argument in argdown.arguments:
        if not argument.label or not argument.pcs:
            continue
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                add_edge(argument.label, member.proposition_label)
            else:
                add_edge(member.proposition_label, argument.label)

    return adjacency


def _find_cycle_from(
    node: str,
    adjacency: dict[str, list[str]],
    color: dict[str, int],
    path: list[str],
) -> list[str] | None:
    """Return the first back-edge cycle reachable from ``node``, else ``None``."""
    color[node] = GREY
    for nxt in adjacency.get(node, []):
        state = color.get(nxt, WHITE)
        if state == GREY:
            back_idx = path.index(nxt) if nxt in path else 0
            return path[back_idx:] + [node, nxt]
        if state == WHITE:
            cycle = _find_cycle_from(nxt, adjacency, color, path + [node])
            if cycle is not None:
                return cycle
    color[node] = BLACK
    return None


def check_circular_support(
    argdown: ArgdownMultiDiGraph,
) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule D."""

    adjacency = _build_adjacency(argdown)

    color: dict[str, int] = {}
    for node in adjacency.keys():
        if color.get(node, WHITE) == WHITE:
            cycle = _find_cycle_from(node, adjacency, color, [])
            if cycle is not None:
                return [f"D circular support — {' -> '.join(cycle)}"], []

    return [], []


__all__ = ["check_circular_support"]

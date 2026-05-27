"""Rule D: no statement may transitively support itself.

Build the directed graph where a PCS premise points at its argument and an
argument points at its main / preliminary conclusion. A back-edge in DFS
is a cycle: a statement whose chain of support eventually points back at
the statement itself.
"""

from __future__ import annotations

from pyargdown import ArgdownMultiDiGraph, Conclusion


def check_circular_support(
    argdown: ArgdownMultiDiGraph,
) -> tuple[list[str], list[str]]:
    """Return ``(failures, infos)`` for rule D."""

    adjacency: dict[str, list[str]] = {}

    def add_edge(src: str, dst: str) -> None:
        adjacency.setdefault(src, []).append(dst)

    for argument in argdown.arguments:
        if not argument.label or not argument.pcs:
            continue
        for member in argument.pcs:
            if isinstance(member, Conclusion):
                add_edge(argument.label, member.proposition_label)
            else:
                add_edge(member.proposition_label, argument.label)

    WHITE, GREY, BLACK = 0, 1, 2
    color: dict[str, int] = {}
    cycle: list[str] | None = None

    def dfs(node: str, path: list[str]) -> bool:
        nonlocal cycle
        color[node] = GREY
        for nxt in adjacency.get(node, []):
            state = color.get(nxt, WHITE)
            if state == GREY:
                back_idx = path.index(nxt) if nxt in path else 0
                cycle = path[back_idx:] + [node, nxt]
                return True
            if state == WHITE and dfs(nxt, path + [node]):
                return True
        color[node] = BLACK
        return False

    for node in list(adjacency.keys()):
        if color.get(node, WHITE) == WHITE and dfs(node, []):
            break

    if cycle is not None:
        return [f"D circular support — {' -> '.join(cycle)}"], []
    return [], []


__all__ = ["check_circular_support"]

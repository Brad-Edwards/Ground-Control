"""The CPU admission policy.

Deliberately pure: it takes the host capacity, a snapshot of the shared ledger,
and the ticket asking to run, and returns the grant or ``None``. Keeping the
scheduling decision free of filesystem, lock, and process concerns is what lets a
future weighted-fair policy replace this module without touching persistence or
command execution (ADR-096).
"""

from __future__ import annotations


def _effective(value: int, capacity: int) -> int:
    """Clamp a demand to the host's total capacity.

    A workload that asks for more CPU than the machine has is a configuration
    mismatch, not a deadlock: clamping lets it run alone on an otherwise idle
    host instead of queueing until the wait bound expires.
    """
    return max(1, min(int(value), capacity))


def plan_admission(capacity: int, entries: list[dict], ticket: str) -> int | None:
    """Return the CPU grant for ``ticket``, or ``None`` while it must keep waiting.

    Admission is strict FIFO by sequence number. Each queued entry ahead of the
    caller consumes what it would be granted, so cheaper work behind a satisfied
    request is backfilled from the remainder. The walk stops at the first entry
    that does not fit: letting later work jump a blocked head is what starves a
    large suite indefinitely on a busy host.
    """
    capacity = max(1, int(capacity))
    used = sum(int(e.get("granted") or 0) for e in entries if e.get("state") == "running")
    remaining = max(0, capacity - used)

    waiting = sorted(
        (e for e in entries if e.get("state") == "queued"),
        key=lambda e: (int(e["seq"]), str(e["ticket"])),
    )
    for entry in waiting:
        minimum = _effective(entry["minimum"], capacity)
        if remaining < minimum:
            return None
        grant = min(_effective(entry["requested"], capacity), remaining)
        if entry["ticket"] == ticket:
            return grant
        remaining -= grant
    return None

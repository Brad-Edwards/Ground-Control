"""The per-user capacity ledger shared by every dispatcher process on a host.

Coordination has to survive across repositories, checkouts, agent drivers, and MCP
server processes, so the shared state is a small file protected by an OS advisory
lock rather than anything held inside one process. The lock is held only while the
ledger is read, swept, scheduled against, and replaced; never while a command runs.

Liveness is not a PID check. Every entry owns a lease file the owner holds under
`flock` for its whole life, and the descriptor is inherited by the command it
launched. The kernel drops that lock only once every process sharing the open file
description is gone, which makes reclamation exact, immune to PID reuse, and
correct when a supervisor dies while its child is still consuming CPU.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import secrets
import time
from contextlib import contextmanager
from pathlib import Path

from .admission import plan_admission

LEDGER_VERSION = 1
LEDGER_NAME = "ledger.json"
LOCK_NAME = "ledger.lock"
LEASE_DIR_NAME = "leases"

ENTRY_FIELDS = (
    "ticket", "seq", "profile", "requested", "minimum", "granted",
    "state", "enqueued_at", "started_at", "supervisor", "orphaned_at",
)
ENTRY_STATES = ("queued", "running")
MAX_DEMAND = 1024
MAX_PROFILE_LENGTH = 64

_TICKET_RE = re.compile(r"^[0-9a-f]{16}$")
_PROFILE_RE = re.compile(r"^[^\x00-\x1f\x7f]{1,%d}$" % MAX_PROFILE_LENGTH)
# `/proc/<pid>/stat` field 22 is the process start time. Combined with the PID it
# is a reuse-safe identity: a recycled PID belonging to a new process reports a
# different start time, so a dead supervisor can never look alive.
_HAS_PROC = Path("/proc/self/stat").exists()


class LedgerError(RuntimeError):
    """Unusable host state. Callers fail closed rather than reset live leases."""


class Ticket:
    """A claim on the ledger plus the open lease that proves the claimant is alive."""

    def __init__(self, ticket_id: str, seq: int, lease_path: Path, fd: int):
        self.id = ticket_id
        self.seq = seq
        self.lease_path = lease_path
        self._fd = fd

    @property
    def lease_fd(self) -> int | None:
        return self._fd

    def close_lease(self) -> None:
        if self._fd is None:
            return
        fd, self._fd = self._fd, None
        try:
            os.close(fd)
        except OSError:
            pass


def process_token(pid: int) -> str | None:
    """Return the reuse-safe start-time token for `pid`, or None when it is gone."""
    try:
        # eslint-style suppressions do not apply here; the path is built from an int.
        with open(f"/proc/{pid}/stat", "rb") as handle:
            data = handle.read()
    except OSError:
        return None
    # The comm field can contain spaces and parentheses, so split after its close.
    tail = data.rsplit(b")", 1)[-1].split()
    if len(tail) < 20:
        return None
    return tail[19].decode("ascii", "replace")


def _supervisor_identity() -> dict:
    pid = os.getpid()
    return {"pid": pid, "token": process_token(pid) if _HAS_PROC else None}


def _supervisor_alive(supervisor) -> bool:
    """True while the process that took the grant is still running.

    Without `/proc` the start-time token is unavailable, so this falls back to a
    signal probe. That is weaker against PID reuse, but it errs toward reporting a
    supervisor alive, which holds capacity rather than issuing it twice.
    """
    if not supervisor:
        return False
    pid = supervisor.get("pid")
    if not isinstance(pid, int) or pid <= 0:
        return False
    if _HAS_PROC:
        return process_token(pid) == supervisor.get("token")
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def prepare_state_dir(path) -> Path:
    """Create or validate the private per-user runtime directory."""
    path = Path(path)
    if path.is_symlink():
        raise LedgerError(f"dispatcher state path must not be a symlink: {path}")
    if path.exists() and not path.is_dir():
        raise LedgerError(f"dispatcher state path is not a directory: {path}")
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise LedgerError(f"cannot create dispatcher state directory {path}: {exc}") from exc
    stat = path.stat()
    if stat.st_uid != os.getuid():
        raise LedgerError(f"dispatcher state directory is not owned by this user: {path}")
    if stat.st_mode & 0o077:
        path.chmod(0o700)
    leases = path / LEASE_DIR_NAME
    leases.mkdir(exist_ok=True)
    if leases.stat().st_mode & 0o077:
        leases.chmod(0o700)
    return path


def _lease_is_unheld(path: Path) -> bool:
    """True when no live process holds the lease, so its capacity is reclaimable."""
    try:
        fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    except FileNotFoundError:
        return True
    except OSError:
        return False
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        return False
    else:
        fcntl.flock(fd, fcntl.LOCK_UN)
        return True
    finally:
        os.close(fd)


def _require(condition, detail: str) -> None:
    if not condition:
        raise LedgerError(
            f"host ledger is invalid and will not be reset or scheduled against: {detail}")


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _validate_supervisor(entry: dict) -> None:
    supervisor = entry["supervisor"]
    if supervisor is None:
        return
    _require(isinstance(supervisor, dict), "an entry has a malformed supervisor record")
    _require(set(supervisor) == {"pid", "token"}, "a supervisor record has unexpected fields")
    _require(_is_int(supervisor["pid"]) and supervisor["pid"] > 0, "a supervisor PID is not positive")
    _require(supervisor["token"] is None or isinstance(supervisor["token"], str),
             "a supervisor token is not a string")


def _validate_entry(entry, seen: set) -> None:
    """Reject any record the scheduler could misread as free or unlimited capacity."""
    _require(isinstance(entry, dict), "an entry is not an object")
    _require(set(entry) == set(ENTRY_FIELDS),
             f"entry fields {sorted(entry)} do not match the ledger schema")
    ticket = entry["ticket"]
    _require(isinstance(ticket, str) and _TICKET_RE.match(ticket), "an entry has a malformed ticket")
    _require(ticket not in seen, f"ticket {ticket} appears more than once")
    seen.add(ticket)
    _require(_is_int(entry["seq"]) and entry["seq"] >= 1, f"ticket {ticket} has a non-positive seq")
    _require(isinstance(entry["profile"], str) and _PROFILE_RE.match(entry["profile"]),
             f"ticket {ticket} has a malformed profile")
    for field in ("requested", "minimum"):
        value = entry[field]
        _require(_is_int(value) and 1 <= value <= MAX_DEMAND,
                 f"ticket {ticket} has an out-of-range {field}")
    _require(entry["minimum"] <= entry["requested"], f"ticket {ticket} has minimum above requested")
    _require(entry["state"] in ENTRY_STATES, f"ticket {ticket} has an unknown state")
    _require(_is_number(entry["enqueued_at"]), f"ticket {ticket} has a malformed enqueue time")
    if entry["state"] == "running":
        # A running entry with a zero or missing grant reads as free capacity, so
        # a corrupt record here would let the host be granted out twice over.
        _require(_is_int(entry["granted"]) and 1 <= entry["granted"] <= MAX_DEMAND,
                 f"ticket {ticket} is running without a valid grant")
        _require(_is_number(entry["started_at"]), f"ticket {ticket} is running without a start time")
    else:
        _require(entry["granted"] is None, f"ticket {ticket} is queued but carries a grant")
        _require(entry["started_at"] is None, f"ticket {ticket} is queued but carries a start time")
    _require(entry["orphaned_at"] is None or _is_number(entry["orphaned_at"]),
             f"ticket {ticket} has a malformed orphan time")
    _validate_supervisor(entry)


def validate_ledger(doc) -> dict:
    _require(isinstance(doc, dict), "the ledger is not an object")
    _require(set(doc) == {"version", "next_seq", "entries"}, "the ledger has unexpected top-level fields")
    _require(doc["version"] == LEDGER_VERSION,
             f"ledger version {doc['version']!r} is not supported by this dispatcher")
    _require(_is_int(doc["next_seq"]) and doc["next_seq"] >= 1, "next_seq is not a positive integer")
    _require(isinstance(doc["entries"], list), "entries is not a list")
    seen: set = set()
    for entry in doc["entries"]:
        _validate_entry(entry, seen)
    return doc


class Ledger:
    def __init__(self, state_dir, stale_after_seconds: float):
        self.state_dir = prepare_state_dir(state_dir)
        self.lease_dir = self.state_dir / LEASE_DIR_NAME
        self.stale_after_seconds = float(stale_after_seconds)
        self._ledger_path = self.state_dir / LEDGER_NAME
        self._lock_path = self.state_dir / LOCK_NAME

    # -- persistence -----------------------------------------------------

    @contextmanager
    def _locked(self):
        fd = os.open(self._lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    def _read(self) -> dict:
        try:
            raw = self._ledger_path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return {"version": LEDGER_VERSION, "next_seq": 1, "entries": []}
        except OSError as exc:
            raise LedgerError(f"cannot read {self._ledger_path}: {exc}") from exc
        try:
            doc = json.loads(raw)
        except ValueError as exc:
            raise LedgerError(
                f"{self._ledger_path} is not valid JSON; refusing to reset live host state ({exc})"
            ) from exc
        return validate_ledger(doc)

    def _write(self, doc: dict) -> None:
        tmp = self._ledger_path.with_suffix(f".tmp.{os.getpid()}")
        fd = os.open(tmp, os.O_CREAT | os.O_WRONLY | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(doc, handle)
            os.replace(tmp, self._ledger_path)
        except OSError as exc:
            tmp.unlink(missing_ok=True)
            raise LedgerError(f"cannot write {self._ledger_path}: {exc}") from exc

    # -- sweeping --------------------------------------------------------

    def _lease_path(self, ticket_id: str) -> Path:
        return self.lease_dir / f"{ticket_id}.lease"

    def _is_reclaimable(self, entry: dict, now: float) -> bool:
        """Decide whether an entry's capacity can be issued to somebody else.

        The order is the whole safety argument. An unheld lease proves nothing is
        running behind the entry. A live supervisor proves the opposite, and holds
        its grant however long the work takes. Only when neither is true (a
        descendant inherited the lease and no supervisor remains) does the age
        bound apply, so a long but healthy suite is never evicted mid-run.
        """
        if _lease_is_unheld(self._lease_path(entry["ticket"])):
            return True
        if _supervisor_alive(entry["supervisor"]):
            return False
        base = entry["orphaned_at"] or entry["started_at"] or entry["enqueued_at"]
        return (now - float(base)) >= self.stale_after_seconds

    def _sweep(self, doc: dict, protect: str | None = None) -> bool:
        now = time.time()
        kept, dropped = [], []
        for entry in doc["entries"]:
            if entry["ticket"] == protect or not self._is_reclaimable(entry, now):
                kept.append(entry)
            else:
                dropped.append(entry)
        for entry in dropped:
            self._lease_path(entry["ticket"]).unlink(missing_ok=True)
        doc["entries"] = kept
        return bool(dropped)

    # -- public operations ----------------------------------------------

    def enqueue(self, profile: str, requested: int, minimum: int) -> Ticket:
        """Claim a lease, then register the queued entry it backs.

        The lease is acquired first on purpose: a ledger entry must never exist
        without a live holder behind it, or a crash between the two steps would
        strand capacity that no sweep can attribute.
        """
        ticket_id = secrets.token_hex(8)
        lease_path = self._lease_path(ticket_id)
        fd = os.open(lease_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # The command this dispatcher launches must inherit the lease, so the
            # two processes share one open file description and the lock outlives a
            # supervisor killed mid-run. Python marks new descriptors
            # non-inheritable by default (PEP 446), which would silently defeat that.
            os.set_inheritable(fd, True)
            entry = {
                "ticket": ticket_id,
                "seq": 0,
                "profile": profile,
                "requested": int(requested),
                "minimum": int(minimum),
                "granted": None,
                "state": "queued",
                "enqueued_at": time.time(),
                "started_at": None,
                "supervisor": _supervisor_identity(),
                "orphaned_at": None,
            }
            with self._locked():
                doc = self._read()
                self._sweep(doc)
                entry["seq"] = int(doc["next_seq"])
                doc["next_seq"] = entry["seq"] + 1
                doc["entries"].append(entry)
                self._write(doc)
        except BaseException:
            os.close(fd)
            lease_path.unlink(missing_ok=True)
            raise
        return Ticket(ticket_id, entry["seq"], lease_path, fd)

    def try_admit(self, ticket: Ticket, capacity: int) -> int | None:
        """Return this ticket's grant, or None while it must keep waiting."""
        with self._locked():
            doc = self._read()
            changed = self._sweep(doc, protect=ticket.id)
            mine = next((e for e in doc["entries"] if e["ticket"] == ticket.id), None)
            if mine is None:
                raise LedgerError(f"ticket {ticket.id} is no longer registered in the host ledger")
            granted = plan_admission(capacity, doc["entries"], ticket.id)
            if granted is not None:
                mine["state"] = "running"
                mine["granted"] = int(granted)
                mine["started_at"] = time.time()
                changed = True
            if changed:
                self._write(doc)
        return granted

    def release(self, ticket: Ticket) -> None:
        """Give the grant back, but only once nothing is still spending it.

        Waiting on the direct child is not proof that the work is over: a
        descendant can inherit the lease and keep running. Dropping our own
        descriptor first makes the lease answer that question. If somebody still
        holds it, the entry stays and keeps its capacity accounted, with the
        supervisor identity cleared so the stale bound can eventually reclaim it.

        Idempotent, and safe to call from `finally`: a failure here can only ever
        leave capacity looking busy, so it must not mask the command's exit status.
        """
        ticket.close_lease()
        try:
            with self._locked():
                doc = self._read()
                entry = next((e for e in doc["entries"] if e["ticket"] == ticket.id), None)
                if entry is None:
                    return
                if _lease_is_unheld(ticket.lease_path):
                    doc["entries"] = [e for e in doc["entries"] if e["ticket"] != ticket.id]
                    ticket.lease_path.unlink(missing_ok=True)
                else:
                    entry["supervisor"] = None
                    entry["orphaned_at"] = time.time()
                self._write(doc)
        except (LedgerError, OSError):
            pass

    def snapshot(self) -> list[dict]:
        with self._locked():
            doc = self._read()
            if self._sweep(doc):
                self._write(doc)
            return [dict(e) for e in doc["entries"]]

"""The `gc-test-dispatch` command line.

One parser, one normalized workload shape. A repository declares what its command
costs; the host decides when that cost fits. Nothing here inspects, rewrites, or
reasons about the command itself.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from .hostconfig import HostConfigError, load_host_config
from .ledger import Ledger, LedgerError
from .supervisor import ChildResult, exit_like, run_command

EXIT_USAGE = 64
EXIT_INTERNAL = 70
EXIT_QUEUE_TIMEOUT = 75

PROFILE_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
DEMAND_MIN = 1
DEMAND_MAX = 1024
XDIST_WORKER_ENV = "PYTEST_XDIST_AUTO_NUM_WORKERS"
METRICS_NAME = "metrics.jsonl"
METRICS_MAX_BYTES = 1_048_576
POLL_MIN_SECONDS = 0.02
POLL_MAX_SECONDS = 0.5

USAGE = (
    "usage: gc-test-dispatch --profile <name> [--cpu N] [--min-cpu N] [--xdist] "
    "-- <command> [args...]"
)
VALUE_OPTIONS = ("--profile", "--cpu", "--min-cpu")


class UsageError(ValueError):
    """A malformed invocation, refused before any work is registered."""


@dataclass(frozen=True)
class Request:
    profile: str
    requested: int
    minimum: int
    xdist: bool
    argv: list[str]


def _demand(option: str, raw: str) -> int:
    try:
        value = int(raw, 10)
    except ValueError:
        raise UsageError(f"{option} must be a whole number, got {raw!r}") from None
    if not DEMAND_MIN <= value <= DEMAND_MAX:
        raise UsageError(f"{option} must be between {DEMAND_MIN} and {DEMAND_MAX}, got {value}")
    return value


def _split_options(args: list[str]) -> tuple[list[str], list[str]]:
    if "--" not in args:
        raise UsageError("the command to run must follow '--'")
    separator = args.index("--")
    command = args[separator + 1:]
    if not command:
        raise UsageError("no command was given after '--'")
    return args[:separator], command


def parse_args(args: list[str]) -> Request:
    options, command = _split_options(list(args))
    values: dict[str, str] = {}
    xdist = False
    index = 0
    while index < len(options):
        option = options[index]
        name, _, inline = option.partition("=")
        if name == "--xdist":
            if inline:
                raise UsageError("--xdist takes no value")
            xdist = True
            index += 1
        elif name in VALUE_OPTIONS:
            if inline:
                values[name] = inline
                index += 1
            else:
                if index + 1 >= len(options):
                    raise UsageError(f"{name} needs a value")
                values[name] = options[index + 1]
                index += 2
        else:
            raise UsageError(f"unknown option {option!r}")

    profile = values.get("--profile")
    if profile is None:
        raise UsageError("--profile is required")
    if not PROFILE_RE.match(profile):
        raise UsageError(
            f"--profile must match {PROFILE_RE.pattern} (lowercase, no spaces), got {profile!r}")

    requested = _demand("--cpu", values["--cpu"]) if "--cpu" in values else 1
    minimum = _demand("--min-cpu", values["--min-cpu"]) if "--min-cpu" in values else requested
    if minimum > requested:
        raise UsageError(f"--min-cpu ({minimum}) cannot exceed --cpu ({requested})")
    return Request(profile=profile, requested=requested, minimum=minimum, xdist=xdist, argv=command)


def _milliseconds(seconds: float) -> int:
    return max(0, int(round(seconds * 1000)))


def _report(state_dir: Path, record: dict) -> None:
    """Emit the bounded operational record for one dispatch.

    Local diagnostics only: never an issue-thread record, never step telemetry,
    and never a cache anything may read back as a result.
    """
    summary = " ".join(f"{key}={value}" for key, value in record.items() if value is not None)
    print(f"gc-test-dispatch: {summary}", file=sys.stderr)
    path = state_dir / METRICS_NAME
    try:
        if path.exists() and path.stat().st_size > METRICS_MAX_BYTES:
            path.unlink()
        fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record) + "\n")
    except OSError:
        # Measurement must never be the reason a verification command fails.
        pass


def _wait_for_admission(ledger: Ledger, ticket, capacity: int, wait_seconds: float) -> int | None:
    deadline = time.monotonic() + wait_seconds
    delay = POLL_MIN_SECONDS
    while True:
        granted = ledger.try_admit(ticket, capacity)
        if granted is not None:
            return granted
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        time.sleep(min(delay, remaining))
        delay = min(delay * 2, POLL_MAX_SECONDS)


def _dispatch(request: Request, config, ledger: Ledger) -> ChildResult | int:
    queued_at = time.monotonic()
    ticket = ledger.enqueue(request.profile, request.requested, request.minimum)
    record = {
        "profile": request.profile,
        "requested": request.requested,
        "minimum": request.minimum,
        "granted": None,
        "capacity": config.cpu_capacity,
        "queue_ms": 0,
        "exec_ms": 0,
        "outcome": "queue_timeout",
        "exit_code": None,
        "term_signal": None,
    }
    result: ChildResult | None = None
    try:
        granted = _wait_for_admission(
            ledger, ticket, config.cpu_capacity, config.max_queue_wait_seconds)
        record["queue_ms"] = _milliseconds(time.monotonic() - queued_at)
        if granted is None:
            return EXIT_QUEUE_TIMEOUT
        record["granted"] = granted
        env = dict(os.environ)
        if request.xdist:
            env[XDIST_WORKER_ENV] = str(granted)
        started_at = time.monotonic()
        result = run_command(request.argv, env, ticket.lease_fd)
        record["exec_ms"] = _milliseconds(time.monotonic() - started_at)
        record["outcome"] = "ran"
        record["exit_code"] = result.exit_code
        record["term_signal"] = result.term_signal
    except LedgerError as exc:
        record["outcome"] = "state_error"
        print(f"gc-test-dispatch: {exc}", file=sys.stderr)
        return EXIT_INTERNAL
    finally:
        ledger.release(ticket)
        _report(config.state_dir, record)
    return result


def main(argv: list[str]) -> int:
    try:
        request = parse_args(argv)
    except UsageError as exc:
        print(f"gc-test-dispatch: {exc}\n{USAGE}", file=sys.stderr)
        return EXIT_USAGE
    try:
        config = load_host_config()
        ledger = Ledger(config.state_dir, config.stale_lease_seconds)
    except (HostConfigError, LedgerError) as exc:
        print(f"gc-test-dispatch: {exc}", file=sys.stderr)
        return EXIT_INTERNAL
    outcome = _dispatch(request, config, ledger)
    if isinstance(outcome, ChildResult):
        exit_like(outcome)
    return outcome

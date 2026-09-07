"""Runs the requested command and reports exactly what happened to it.

The dispatcher is a wrapper, not a test runner: it must add admission and nothing
else. The command keeps its own stdin, stdout, and stderr; its exit status is
returned unchanged; and when it dies from a signal the dispatcher dies the same
way, so a caller's shell and CI see the real cause rather than a translated code.
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
from dataclasses import dataclass

# Every signal a supervisor can meaningfully relay. SIGKILL is absent because it
# cannot be caught; the ledger's lease sweep is what recovers from it.
FORWARDED_SIGNALS = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP, signal.SIGQUIT)


@dataclass(frozen=True)
class ChildResult:
    exit_code: int | None
    term_signal: int | None

    @property
    def succeeded(self) -> bool:
        return self.exit_code == 0 and self.term_signal is None


def _claim_terminal(pgid: int) -> int | None:
    """Give the command the terminal, returning the group to restore afterwards.

    Only relevant for an interactive invocation. A background process group that
    reads the terminal is stopped with SIGTTIN, so a command that prompts would
    hang without this handover.
    """
    if not sys.stdin.isatty():
        return None
    try:
        previous = os.tcgetpgrp(sys.stdin.fileno())
    except OSError:
        return None
    _set_terminal_group(pgid)
    return previous


def _set_terminal_group(pgid: int) -> None:
    # tcsetpgrp from a background group raises SIGTTOU at the caller, which would
    # stop the dispatcher mid-handover, so it is masked across the call.
    handler = signal.signal(signal.SIGTTOU, signal.SIG_IGN)
    try:
        os.tcsetpgrp(sys.stdin.fileno(), pgid)
    except OSError:
        pass
    finally:
        signal.signal(signal.SIGTTOU, handler)


def run_command(argv: list[str], env: dict, lease_fd: int | None) -> ChildResult:
    """Execute ``argv`` directly and wait for it.

    The lease descriptor is handed to the child so both processes share one open
    file description: the grant then stays held for as long as real work is
    running, even if this supervisor is killed first.
    """
    pass_fds = (lease_fd,) if lease_fd is not None else ()
    child = subprocess.Popen(  # noqa: S603 - argv execution is the contract; never a shell string
        argv, env=env, pass_fds=pass_fds, start_new_session=False,
        preexec_fn=os.setpgrp,  # noqa: PLW1509 - single-threaded; contains the command's descendants
    )
    previous_group = _claim_terminal(child.pid)
    installed = _forward_signals_to(child.pid)
    try:
        status = child.wait()
    finally:
        for sig, handler in installed.items():
            signal.signal(sig, handler)
        if previous_group is not None:
            _set_terminal_group(previous_group)
    if status < 0:
        return ChildResult(exit_code=None, term_signal=-status)
    return ChildResult(exit_code=status, term_signal=None)


def _forward_signals_to(pgid: int) -> dict:
    def forward(signum, _frame):
        try:
            os.killpg(pgid, signum)
        except OSError:
            pass

    installed = {}
    for sig in FORWARDED_SIGNALS:
        try:
            installed[sig] = signal.signal(sig, forward)
        except (OSError, ValueError):
            continue
    return installed


def exit_like(result: ChildResult) -> None:
    """Terminate this process the way the command terminated, and never return."""
    if result.term_signal is not None:
        signal.signal(result.term_signal, signal.SIG_DFL)
        os.kill(os.getpid(), result.term_signal)
        signal.pause()
    sys.exit(result.exit_code if result.exit_code is not None else 1)

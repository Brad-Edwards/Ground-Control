#!/usr/bin/env python3
"""Measure CI wall-clock characteristics from GitHub Actions run history.

Reports the two numbers issue #1461 is judged on, median and p95 for whole-run
wall clock and for time to first failing check, plus per-job duration and start
offset. Start offset is the diagnostic that exposes a serialized graph: a job
whose median start is far from zero is waiting on something.

Usage:
    make ci-timings
    python3 tools/ci/measure_ci_timings.py --limit 40 --workflow ci.yml

`summarize` and `render_markdown` are pure functions over the jobs-API payload,
so the statistics are unit-tested without network access. `gh` is invoked only
by `collect_runs`.
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
from datetime import datetime

DEFAULT_REPO = "autarchy-ai/Ground-Control"
DEFAULT_WORKFLOW = "ci.yml"
DEFAULT_LIMIT = 40
TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"


def parse_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.strptime(value, TIMESTAMP_FORMAT)


def percentile(values: list[float], fraction: float) -> float | None:
    """Linear-interpolated percentile. Returns None for an empty sample."""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _sample(values: list[float]) -> dict:
    return {
        "n": len(values),
        "median": statistics.median(values) if values else None,
        "p95": percentile(values, 0.95),
        "min": min(values) if values else None,
        "max": max(values) if values else None,
    }


def _usable_jobs(run: dict) -> list[dict]:
    """Jobs that actually ran and reported a positive time window.

    A skipped job carries timestamps but did no work. A job cancelled before it
    started reports `completed_at` fractionally *before* `started_at`, which
    yields a negative duration. Either one distorts the run window and adds a
    phantom row to the per-job table, so both are dropped.
    """
    usable = []
    for item in run.get("jobs", []):
        started = parse_timestamp(item.get("started_at"))
        completed = parse_timestamp(item.get("completed_at"))
        if started is None or completed is None:
            continue
        if item.get("conclusion") == "skipped":
            continue
        if (completed - started).total_seconds() <= 0:
            continue
        usable.append(item)
    return usable


def summarize(runs: list[dict]) -> dict:
    """Aggregate run and job timings. Pure function over the jobs-API payload."""
    totals: list[float] = []
    first_failures: list[float] = []
    durations: dict[str, list[float]] = {}
    offsets: dict[str, list[float]] = {}

    for run in runs:
        jobs = _usable_jobs(run)
        if not jobs:
            continue
        started = [parse_timestamp(item["started_at"]) for item in jobs]
        completed = [parse_timestamp(item["completed_at"]) for item in jobs]
        window_start = min(started)
        total = (max(completed) - window_start).total_seconds()
        if total <= 0:
            continue
        totals.append(total)

        failed = [
            parse_timestamp(item["completed_at"])
            for item in jobs
            if item.get("conclusion") == "failure"
        ]
        if failed:
            first_failures.append((min(failed) - window_start).total_seconds())

        for item in jobs:
            name = item["name"]
            job_start = parse_timestamp(item["started_at"])
            job_end = parse_timestamp(item["completed_at"])
            durations.setdefault(name, []).append((job_end - job_start).total_seconds())
            offsets.setdefault(name, []).append((job_start - window_start).total_seconds())

    jobs_summary = {
        name: {
            "n": len(values),
            "median_seconds": statistics.median(values),
            "p95_seconds": percentile(values, 0.95),
            "median_start_offset_seconds": statistics.median(offsets[name]),
        }
        for name, values in sorted(durations.items())
    }

    return {
        "runs_sampled": len(totals),
        "total_seconds": _sample(totals),
        "first_failure_seconds": _sample(first_failures),
        "jobs": jobs_summary,
    }


def _minutes(seconds: float | None) -> str:
    return "n/a" if seconds is None else f"{seconds / 60:.1f}m"


def render_markdown(summary: dict) -> str:
    total = summary["total_seconds"]
    failure = summary["first_failure_seconds"]

    lines = [
        f"CI timings over {summary['runs_sampled']} runs",
        "",
        "| Metric | n | Median | p95 |",
        "|---|---|---|---|",
        f"| Whole-run wall clock | {total['n']} | {_minutes(total['median'])} | {_minutes(total['p95'])} |",
        f"| Time to first failing check | {failure['n']} | {_minutes(failure['median'])} | {_minutes(failure['p95'])} |",
        "",
        "| Job | n | Median | p95 | Starts at |",
        "|---|---|---|---|---|",
    ]
    for name, stats in summary["jobs"].items():
        lines.append(
            f"| `{name}` | {stats['n']} | {_minutes(stats['median_seconds'])} "
            f"| {_minutes(stats['p95_seconds'])} "
            f"| +{_minutes(stats['median_start_offset_seconds'])} |"
        )
    return "\n".join(lines)


def _gh(args: list[str]) -> str:
    result = subprocess.run(
        ["gh", *args], capture_output=True, text=True, check=True
    )
    return result.stdout


def build_run_list_args(
    repo: str, workflow: str, limit: int, event: str, branch: str | None
) -> list[str]:
    """Assemble the `gh run list` argv.

    `branch` narrows the sample to one branch, which is what an
    after-measurement needs: without it the sample mixes the branch under test
    with historical runs of the topology it replaced.
    """
    args = [
        "run",
        "list",
        "--repo",
        repo,
        "--workflow",
        workflow,
        "--event",
        event,
        "--limit",
        str(limit),
        "--json",
        "databaseId,conclusion,headBranch",
    ]
    if branch:
        args += ["--branch", branch]
    return args


def collect_runs(
    repo: str, workflow: str, limit: int, event: str, branch: str | None = None
) -> list[dict]:
    """Fetch recent runs and attach each run's jobs payload."""
    listing = json.loads(_gh(build_run_list_args(repo, workflow, limit, event, branch)))
    runs = []
    for entry in listing:
        run_id = entry["databaseId"]
        try:
            payload = _gh(
                ["api", f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"]
            )
        except subprocess.CalledProcessError as exc:
            print(f"skipping run {run_id}: {exc}", file=sys.stderr)
            continue
        runs.append({**entry, "jobs": json.loads(payload)["jobs"]})
    return runs


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--workflow", default=DEFAULT_WORKFLOW)
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    parser.add_argument("--event", default="pull_request")
    parser.add_argument(
        "--branch",
        default=None,
        help="restrict the sample to one head branch (use for after-measurements)",
    )
    parser.add_argument(
        "--json", action="store_true", help="emit the raw summary instead of Markdown"
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    summary = summarize(
        collect_runs(args.repo, args.workflow, args.limit, args.event, args.branch)
    )
    print(json.dumps(summary, indent=2) if args.json else render_markdown(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

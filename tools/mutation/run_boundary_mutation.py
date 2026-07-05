#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_REGISTRY = Path("architecture/registry/mutation-boundaries.json")
REGISTRY_RELATIVE = DEFAULT_REGISTRY.as_posix()
VALID_LOCK_LEVELS = {"locked", "guarded", "fluid"}
VALID_TOOLS = {"pitest", "stryker"}


class ConfigError(Exception):
    pass


@dataclass(frozen=True)
class Baseline:
    score: float
    killed: int
    survived: int
    total: int
    measured_at: str
    tool_version: str


@dataclass(frozen=True)
class MutationConfig:
    enabled: bool
    tool: str
    threshold: int
    time_budget_minutes: int
    baseline: Baseline
    pitest: dict[str, Any]
    stryker: dict[str, Any]


@dataclass(frozen=True)
class Boundary:
    id: str
    name: str
    lock_level: str
    paths: tuple[str, ...]
    mutation: MutationConfig


@dataclass(frozen=True)
class CommandPlan:
    boundary: Boundary
    cwd: Path
    argv: tuple[str, ...]
    env: dict[str, str]
    timeout_seconds: int


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    repo_root = args.repo_root.resolve()
    registry_path = resolve_repo_path(repo_root, args.registry)

    try:
        boundaries = load_registry(registry_path, repo_root)
        changed_files = tuple(args.changed_file) if args.changed_file else tuple(read_changed_files(repo_root, args.base))
        selected = select_boundaries(boundaries, changed_files, registry_path, repo_root, args.all)
        if not selected:
            base_note = f" vs {args.base}" if args.base else ""
            print(f"mutation-gate: no changed mutation-contract boundaries{base_note}; success no-op")
            return 0

        plans = [build_command_plan(repo_root, boundary) for boundary in selected]
        for plan in plans:
            if args.dry_run:
                print(render_dry_run(plan, repo_root))
            else:
                run_plan(plan, repo_root)
        print(f"mutation-gate: checked {len(plans)} mutation-contract boundary/boundaries")
        return 0
    except ConfigError as exc:
        print(f"mutation-gate: configuration error: {exc}", file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as exc:
        details = (exc.stderr or "").strip() or (exc.stdout or "").strip() or str(exc)
        print(f"mutation-gate: command failed: {details}", file=sys.stderr)
        return exc.returncode or 1
    except subprocess.TimeoutExpired as exc:
        print(f"mutation-gate: command timed out after {exc.timeout} seconds: {shlex.join(exc.cmd)}", file=sys.stderr)
        return 124


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run CLD mutation tests for changed registry boundaries.")
    parser.add_argument("--repo-root", type=Path, default=Path.cwd(), help="repository root, defaults to cwd")
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY, help="mutation boundary registry path")
    parser.add_argument("--base", default=os.environ.get("BASE_REF", "origin/dev"), help="base ref for git diff")
    parser.add_argument(
        "--changed-file",
        action="append",
        default=[],
        help="repo-relative changed file; repeatable and bypasses git diff, mainly for tests",
    )
    parser.add_argument("--all", action="store_true", help="run every enabled mutation boundary")
    parser.add_argument("--dry-run", action="store_true", help="print fixed command plan without executing tools")
    return parser.parse_args(argv)


def resolve_repo_path(repo_root: Path, path: Path) -> Path:
    candidate = path if path.is_absolute() else repo_root / path
    try:
        resolved = candidate.resolve()
        resolved.relative_to(repo_root)
    except ValueError as exc:
        raise ConfigError(f"path escapes repository root: {path}") from exc
    return resolved


def load_registry(path: Path, repo_root: Path) -> tuple[Boundary, ...]:
    if not path.exists():
        raise ConfigError(f"mutation registry is missing: {path.relative_to(repo_root)}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ConfigError(f"mutation registry is invalid JSON: {exc}") from exc

    if not isinstance(raw, dict):
        raise ConfigError("mutation registry must be a JSON object")
    if raw.get("schema_version") != 1:
        raise ConfigError("mutation registry schema_version must be 1")
    entries = raw.get("boundaries")
    if not isinstance(entries, list) or not entries:
        raise ConfigError("mutation registry boundaries must be a non-empty list")

    seen: set[str] = set()
    boundaries: list[Boundary] = []
    for index, entry in enumerate(entries):
        prefix = f"boundaries[{index}]"
        if not isinstance(entry, dict):
            raise ConfigError(f"{prefix} must be an object")
        boundary_id = require_string(entry, "id", prefix)
        if boundary_id in seen:
            raise ConfigError(f"{prefix}.id duplicates '{boundary_id}'")
        seen.add(boundary_id)

        paths = require_string_list(entry, "paths", prefix)
        for selector in paths:
            validate_selector(repo_root, selector, f"{prefix}.paths")

        lock_level = require_string(entry, "lock_level", prefix)
        if lock_level not in VALID_LOCK_LEVELS:
            raise ConfigError(f"{prefix}.lock_level must be one of {sorted(VALID_LOCK_LEVELS)}")

        boundaries.append(
            Boundary(
                id=boundary_id,
                name=require_string(entry, "name", prefix),
                lock_level=lock_level,
                paths=tuple(paths),
                mutation=parse_mutation_config(entry.get("mutation"), prefix),
            )
        )
    return tuple(boundaries)


def parse_mutation_config(raw: Any, prefix: str) -> MutationConfig:
    if not isinstance(raw, dict):
        raise ConfigError(f"{prefix}.mutation must be an object")
    tool = require_string(raw, "tool", f"{prefix}.mutation")
    if tool not in VALID_TOOLS:
        raise ConfigError(f"{prefix}.mutation.tool must be one of {sorted(VALID_TOOLS)}")
    threshold = require_int(raw, "threshold", f"{prefix}.mutation", minimum=0, maximum=100)
    return MutationConfig(
        enabled=bool(raw.get("enabled", True)),
        tool=tool,
        threshold=threshold,
        time_budget_minutes=require_int(raw, "time_budget_minutes", f"{prefix}.mutation", minimum=1, maximum=120),
        baseline=parse_baseline(raw.get("baseline"), f"{prefix}.mutation"),
        pitest=parse_tool_block(raw.get("pitest"), f"{prefix}.mutation.pitest", required=(tool == "pitest")),
        stryker=parse_tool_block(raw.get("stryker"), f"{prefix}.mutation.stryker", required=(tool == "stryker")),
    )


def parse_baseline(raw: Any, prefix: str) -> Baseline:
    if not isinstance(raw, dict):
        raise ConfigError(f"{prefix}.baseline is required")
    total = require_int(raw, "total", f"{prefix}.baseline", minimum=1, maximum=1_000_000)
    killed = require_int(raw, "killed", f"{prefix}.baseline", minimum=0, maximum=total)
    survived = require_int(raw, "survived", f"{prefix}.baseline", minimum=0, maximum=total)
    score = raw.get("score")
    if not isinstance(score, (int, float)) or score < 0 or score > 100:
        raise ConfigError(f"{prefix}.baseline.score must be a number in [0, 100]")
    return Baseline(
        score=float(score),
        killed=killed,
        survived=survived,
        total=total,
        measured_at=require_string(raw, "measured_at", f"{prefix}.baseline"),
        tool_version=require_string(raw, "tool_version", f"{prefix}.baseline"),
    )


def parse_tool_block(raw: Any, prefix: str, *, required: bool) -> dict[str, Any]:
    if raw is None:
        if required:
            raise ConfigError(f"{prefix} is required")
        return {}
    if not isinstance(raw, dict):
        raise ConfigError(f"{prefix} must be an object")
    return raw


def require_string(raw: dict[str, Any], key: str, prefix: str) -> str:
    value = raw.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"{prefix}.{key} must be a non-empty string")
    return value.strip()


def require_string_list(raw: dict[str, Any], key: str, prefix: str) -> list[str]:
    value = raw.get(key)
    if not isinstance(value, list) or not value:
        raise ConfigError(f"{prefix}.{key} must be a non-empty list")
    result: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item.strip():
            raise ConfigError(f"{prefix}.{key}[{index}] must be a non-empty string")
        result.append(item.strip())
    return result


def require_int(raw: dict[str, Any], key: str, prefix: str, *, minimum: int, maximum: int) -> int:
    value = raw.get(key)
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum or value > maximum:
        raise ConfigError(f"{prefix}.{key} must be an integer in [{minimum}, {maximum}]")
    return value


def validate_selector(repo_root: Path, selector: str, field: str) -> None:
    exact = selector[:-3] if selector.endswith("/**") else selector
    if selector.startswith("/") or ".." in Path(exact).parts:
        raise ConfigError(f"{field} selector must stay inside the repo: {selector}")
    if "*" in exact or ("*" in selector and not selector.endswith("/**")):
        raise ConfigError(f"{field} selector only supports exact paths or trailing /**: {selector}")
    resolve_repo_path(repo_root, Path(exact))


def read_changed_files(repo_root: Path, base: str) -> list[str]:
    completed = subprocess.run(
        ["git", "diff", "--name-status", "--find-renames", "--diff-filter=ACDMRT", f"{base}...HEAD"],
        cwd=repo_root,
        text=True,
        capture_output=True,
        check=True,
    )
    return parse_changed_paths(completed.stdout)


def parse_changed_paths(name_status_output: str) -> list[str]:
    changed: list[str] = []
    for line in name_status_output.splitlines():
        parts = [part.strip() for part in line.split("\t") if part.strip()]
        if len(parts) < 2:
            continue
        status = parts[0]
        if status.startswith("R") and len(parts) >= 3:
            changed.extend([parts[1], parts[2]])
        else:
            changed.append(parts[-1])
    return changed


def select_boundaries(
    boundaries: tuple[Boundary, ...],
    changed_files: tuple[str, ...],
    registry_path: Path,
    repo_root: Path,
    run_all: bool,
) -> tuple[Boundary, ...]:
    enabled = tuple(boundary for boundary in boundaries if boundary.mutation.enabled)
    registry_rel = registry_path.relative_to(repo_root).as_posix()
    if run_all or registry_rel in changed_files:
        return enabled
    selected: list[Boundary] = []
    for boundary in enabled:
        if any(matches_any(changed, boundary.paths) for changed in changed_files):
            selected.append(boundary)
    return tuple(selected)


def matches_any(path: str, selectors: tuple[str, ...]) -> bool:
    normalized = path.strip().lstrip("./")
    return any(matches_selector(normalized, selector) for selector in selectors)


def matches_selector(path: str, selector: str) -> bool:
    if selector.endswith("/**"):
        prefix = selector[:-3]
        return path == prefix or path.startswith(prefix + "/")
    return path == selector


def build_command_plan(repo_root: Path, boundary: Boundary) -> CommandPlan:
    if boundary.mutation.tool == "pitest":
        return build_pitest_plan(repo_root, boundary)
    if boundary.mutation.tool == "stryker":
        return build_stryker_plan(repo_root, boundary)
    raise ConfigError(f"{boundary.id}: unsupported mutation tool {boundary.mutation.tool}")


def build_pitest_plan(repo_root: Path, boundary: Boundary) -> CommandPlan:
    config = boundary.mutation.pitest
    target_classes = require_tool_strings(config, "target_classes", boundary.id)
    target_tests = require_tool_strings(config, "target_tests", boundary.id)
    report_dir = f"build/reports/pitest/{boundary.id}"
    argv = (
        "./gradlew",
        "pitest",
        "--no-daemon",
        f"-PmutationTargetClasses={','.join(target_classes)}",
        f"-PmutationTargetTests={','.join(target_tests)}",
        f"-PmutationThreshold={boundary.mutation.threshold}",
        f"-PmutationReportDir={report_dir}",
        "-PmutationFailWhenNoMutations=true",
    )
    return CommandPlan(
        boundary=boundary,
        cwd=repo_root / "backend",
        argv=argv,
        env={},
        timeout_seconds=boundary.mutation.time_budget_minutes * 60,
    )


def build_stryker_plan(repo_root: Path, boundary: Boundary) -> CommandPlan:
    config = boundary.mutation.stryker
    mutate = require_tool_strings(config, "mutate", boundary.id)
    test_files = require_tool_strings(config, "test_files", boundary.id)
    report_dir = f"build/reports/stryker/{boundary.id}"
    env = {
        "STRYKER_MUTATE": ",".join(mutate),
        "STRYKER_TEST_FILES": ",".join(test_files),
        "STRYKER_THRESHOLD": str(boundary.mutation.threshold),
        "STRYKER_JSON_REPORT": f"{report_dir}/mutation.json",
        "STRYKER_HTML_REPORT": f"{report_dir}/html/index.html",
        "STRYKER_CONCURRENCY": str(config.get("concurrency", 2)),
    }
    return CommandPlan(
        boundary=boundary,
        cwd=repo_root / "frontend",
        argv=("npm", "run", "mutation"),
        env=env,
        timeout_seconds=boundary.mutation.time_budget_minutes * 60,
    )


def require_tool_strings(config: dict[str, Any], key: str, boundary_id: str) -> list[str]:
    if key not in config:
        raise ConfigError(f"{boundary_id}: mutation tool config missing {key}")
    return require_string_list(config, key, boundary_id)


def render_dry_run(plan: CommandPlan, repo_root: Path) -> str:
    cwd = plan.cwd.relative_to(repo_root).as_posix()
    env = " ".join(f"{key}={value}" for key, value in sorted(plan.env.items()))
    env_part = f" {env}" if env else ""
    return f"mutation-gate: dry-run boundary {plan.boundary.id}: cd {cwd} &&{env_part} {shlex.join(plan.argv)}"


def run_plan(plan: CommandPlan, repo_root: Path) -> None:
    print(f"mutation-gate: running {plan.boundary.id} ({plan.boundary.mutation.tool})", flush=True)
    env = os.environ.copy()
    env.update(plan.env)
    subprocess.run(
        list(plan.argv),
        cwd=plan.cwd,
        env=env,
        text=True,
        check=True,
        timeout=plan.timeout_seconds,
    )


if __name__ == "__main__":
    sys.exit(main())

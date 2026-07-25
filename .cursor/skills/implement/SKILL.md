---
name: implement
description: End-to-end issue implementation - from plan through merged PR. Agent-neutral (Claude Code, Codex, Cursor CLI). Parameterized by .ground-control.yaml. Thin orchestrator that delegates per-step work to subagents per ADR-036 + issue #934.
disable-model-invocation: true
---

# Implement (Cursor wrapper): $ARGUMENTS

This file exists for **Cursor skill discovery only**. The canonical source of truth is [`skills/implement/SKILL.md`](../../../skills/implement/SKILL.md) (ADR-027). Cursor does not register skill folders that are symlinks to elsewhere in the repo.

**Execute the canonical workflow verbatim:**

1. Before route resolution, issue lookup, branch handling, or delegation, read
   [`skills/implement/_development-principles.md`](../../../skills/implement/_development-principles.md)
   in full and preserve its immutable execution-contract fields.
2. Read and follow [`skills/implement/SKILL.md`](../../../skills/implement/SKILL.md) in full.
3. Resolve every step file under `skills/implement/steps/` (not under `.cursor/skills/implement/`).
4. Pass the user's slash-command argument through as `$ARGUMENTS` (GitHub issue number or requirement UID).

Do not treat this wrapper as an independent workflow definition. If the canonical SKILL and this wrapper disagree, the canonical SKILL wins.

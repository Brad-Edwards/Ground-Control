---
id: GC-X100
title: "Codex review fix-the-class instruction in /implement workflow"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 6
created_at: 2026-05-09T07:34:50.685699Z
updated_at: 2026-05-09T07:34:50.685699Z
---

# GC-X100 — Codex review fix-the-class instruction in /implement workflow

## Statement

When the /implement workflow surfaces findings from a codex review (pre-push at Step 6.5, or post-push if applicable), the per-cycle fix instruction shall direct the active agent to: (1) fix each specific finding, (2) infer the broader class of issue each finding represents (for example "shell argument quoting edge cases", "OCSF spec correctness", "per-tool flag semantics"), (3) pre-emptively find and fix other instances of that class within the same diff before re-staging, and (4) add regression tests covering both the specific finding AND representative instances of the class. The instruction shall be appended automatically by the Ground Control MCP layer when it returns codex findings to the agent — that is the workflow guidance ships with the findings, not as a separate paragraph the agent has to remember to apply.

The decision record posted to the issue thread for each cycle shall additionally record the class identified for each finding so the audit trail captures the agent's reasoning, not just the specific fix.

## Rationale

In a long-running /implement run on issue #162 (APTL red-team structured logging), eleven codex review cycles surfaced 70+ distinct findings. The post-cycle pattern was nearly always: codex finds N edge cases of class C; agent fixes those N; next cycle finds N+1 more edge cases of the SAME class C that the agent could have anticipated. Examples observed: (a) shell-argument quoting — codex found `-p "secret phrase"` left part unredacted, then `-p=value` and `-p<value>` attached forms, then escape-aware `-p correct\\ horse`; (b) per-tool flag semantics — codex found `nc -p` is source port not destination, then `nc -w` is timeout not wordlist, then `ssh -o KEY=VAL` is option not file, then `ldapsearch -l` is time-limit not user, then `ldapsearch -w` is bind password not wordlist; (c) OCSF correctness — codex found time-in-seconds, then missing category_uid, then wrong host_discovery class_uid, then wrong web activity_id semantics. Each was a real bug; each was the same SHAPE of bug as the cycle before. An agent prompted with "fix this finding AND find other instances of the same class" converges in two or three cycles instead of nine.

This is a workflow-level improvement (it benefits every /implement run on every project) rather than a per-repo configuration knob. The MCP server is the right injection point because findings flow through it on the way back to the agent — it can append the instruction unconditionally rather than relying on every agent prompt to remember to apply it.</rationale>
<parameter name="requirement_type">FUNCTIONAL

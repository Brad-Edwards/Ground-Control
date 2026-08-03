---
id: GC-W010
title: "Agent-Driven Decision Workflow via MCP"
status: DRAFT
type: INTERFACE
priority: MUST
wave: 6
created_at: 2026-04-11T19:01:12.638547Z
updated_at: 2026-04-11T19:01:12.638547Z
---

# GC-W010 — Agent-Driven Decision Workflow via MCP

## Statement

The system shall expose all decision analysis capabilities (probabilistic estimation, Monte Carlo simulation, CoD/WSJF, CBAM, calibration tracking, VOI, MCDA, debt quantification, delivery forecasting) through MCP tool interfaces suitable for AI agent orchestration. The interface shall support a conversational workflow where the agent structures analyses, selects appropriate methods, runs computations, and presents results while the human provides domain judgment — confidence intervals, criteria weights, strategic context, and final decision ratification. The agent shall be able to compose multi-step analyses (for example, estimate CoD, run Monte Carlo, compute VOI, recommend investigation) without requiring the human to manage intermediate state.

## Rationale

The cost barrier to quantitative decision-making is labor — gathering data, building models, running simulations, tracking calibration. An AI agent can perform 80-90% of this work, reducing the human role to the irreducible judgment calls that actually require domain expertise. MCP exposure ensures the decision tools are accessible from any MCP-compatible agent (Claude Code, IDE extensions, CI pipelines) without custom integration per client.

## Traceability

- DOCUMENTS → GITHUB_ISSUE `#788` (GC-W010: Agent-Driven Decision Workflow via MCP)

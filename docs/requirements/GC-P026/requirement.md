---
id: GC-P026
title: "Repository Identity Consistency and Checkout-Derived Resolution"
status: ACTIVE
type: NON_FUNCTIONAL
priority: SHOULD
created_at: 2026-07-13T02:23:42.849497Z
updated_at: 2026-07-14T01:42:00.291070Z
---

# GC-P026 — Repository Identity Consistency and Checkout-Derived Resolution

## Statement

Ground Control repo-bound operations shall derive GitHub repository identity from the target checkout's git `origin` remote as the authoritative source, treating any configured or caller-supplied `owner/repo` (`.ground-control.yaml::github_repo`, an MCP `repo` argument, or the `GH_REPO` environment variable) as an explicit assertion that is validated (owner/repo shape plus case-insensitive agreement with the checkout) rather than an independent higher-priority destination; repo-bound mutations shall fail closed when no GitHub remote can be validated and shall never silently fall back to `GH_REPO` or `gh repo view`. A repo-native policy gate (`make policy` / `bin/policy`) shall enforce that active repository-identity surfaces (configuration, workflows, scripts, docs, and other active guidance) name the canonical `owner/repo`, while exempting historical ADR/changelog references and intentional arbitrary-owner test fixtures.

## Rationale

Issue #1383: after the GitHub owner move to autarchy-ai/Ground-Control, several active surfaces still named the stale KeplerOps/Ground-Control owner, and issue-creation/context calls plus the integration manager could silently fall back to a process-global GH_REPO default, routing defaulted operations at an inaccessible repository. Deriving identity from the checkout remote (the source git already trusts) and validating configured identity against it closes the env-hijack class (issue #934 lineage), and a repo-native policy gate keeps the next owner move from silently re-introducing stale identifiers. Sibling to GC-P022 (image-namespace drift); both are repo-native drift gates over deploy/identity surfaces.

## Traceability

- DOCUMENTS → ADR `ADR-054` (ADR-054 amendment: repository-identity drift gate + checkout-derived resolution)
- IMPLEMENTS → CODE_FILE `tools/policy/checks.py` (run_repo_identity_drift policy gate)
- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js` (Checkout-derived identity resolution (getOwnerRepo/createGitHubIssue/getIssueContext))
- TESTS → TEST `tools/tests/test_policy_repo_identity_drift.py` (repo-identity drift gate tests (positive/negative/historical/wrong-name/admin))
- TESTS → TEST `mcp/ground-control/lib.repository-identity-resolution-gc-p026-1383.test.js` (Checkout-derived identity + validated-assertion + fail-closed tests)

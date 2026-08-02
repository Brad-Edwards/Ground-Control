---
id: GC-E012
title: "Sensitive Artifact Exclusion Boundary"
status: DRAFT
type: CONSTRAINT
priority: SHOULD
wave: 3
created_at: 2026-03-14T18:58:48.091499Z
updated_at: 2026-03-14T18:58:48.091499Z
---

# GC-E012 — Sensitive Artifact Exclusion Boundary

## Statement

The system shall support configurable exclusion patterns that prevent sensitive artifacts (secrets, credentials, API keys, private key files, `.env` files, and other configurable patterns) from being linked as traced entities in the traceability graph. Exclusion patterns shall apply to: manual artifact linking (GC-E001), automated UID scanning (GC-E010), test UID scanning (GC-E006), and evidence collection imports. Attempted links to excluded artifacts shall be rejected with a warning identifying the exclusion rule that matched. The default exclusion set shall include common secret file patterns (`.env`, `*credentials*`, `*secret*`, `*.pem`, `*.key`) and shall be overridable per project.

## Rationale

The traceability graph stores artifact identifiers, URLs, and metadata. If a `.env` file or `credentials.json` is linked as a CONFIG artifact, the artifact identifier is visible to all graph consumers, and evidence collection (GC-I003, GC-S001) could pull the file's contents into the evidence store. No existing requirement guards this boundary. This is a security concern that becomes critical in multi-tenant (GC-P006) and agentic (GC-O004) scenarios where agents create links autonomously.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#248` (GC-E012: Sensitive Artifact Exclusion Boundary)

---
id: GC-E009
title: "Source Code Line-Range Traceability"
status: DRAFT
type: FUNCTIONAL
priority: SHOULD
wave: 3
created_at: 2026-03-14T17:22:53.838213Z
updated_at: 2026-03-14T17:22:53.838213Z
---

# GC-E009 — Source Code Line-Range Traceability

## Statement

The system shall support optional line-range metadata on traceability links of artifact type CODE_FILE, specifying a start line and end line within the linked file. When present, artifact change detection (GC-E005) and link health tracking (GC-E004) shall use the line range to scope staleness detection to changes within the specified range rather than any change to the file.

## Rationale

StrictDoc's FILE relation type supports path+line-range tracing. Without line ranges, every change to a large linked file triggers false-positive staleness alerts. Line-range precision enables agents to distinguish between changes that affect traced requirements and changes to unrelated code in the same file, reducing noise and enabling targeted re-verification.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `#227` (GC-E009: Source Code Line-Range Traceability)

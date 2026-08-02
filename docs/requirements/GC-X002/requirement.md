---
id: GC-X002
title: "Knowledge base location declared in repository configuration"
status: ACTIVE
type: FUNCTIONAL
priority: MUST
wave: 6
created_at: 2026-04-12T19:13:25.116632Z
updated_at: 2026-04-13T00:47:16.272606Z
---

# GC-X002 — Knowledge base location declared in repository configuration

## Statement

Each repository shall declare the location of its knowledge base in its Ground Control configuration file, including at minimum the knowledge base directory and optionally the paths to the schema file and the inbox directory.

## Rationale

Repository configuration is the single source of truth for how a repo participates in the Ground Control workflow. Putting the knowledge base location in the same configuration file keeps the wiring co-located with the rest of the workflow config and avoids a separate per-repo registry file. It also lets the configuration be re-read on every sweep so location changes take effect without re-registering.

## Traceability

- IMPLEMENTS → CODE_FILE `mcp/ground-control/lib.js`
- IMPLEMENTS → CONFIG `.ground-control.yaml`
- IMPLEMENTS → GITHUB_ISSUE `522`
- TESTS → TEST `mcp/ground-control/lib.getrepogroundcontrolcontext-paths.test.js` (knowledge_base path resolution from repository configuration)

# Requirements (specs-as-code)

Ground Control's own requirements live here as version-controlled files, one folder per
requirement, so they diff in the same pull request as the code they govern (issue #1500,
[ADR-093](../../architecture/adrs/093-requirements-specs-as-code.md)). Git is the record;
requirements are no longer edited only through the database and a graph projection.

## Layout

```
docs/requirements/<UID>/requirement.md
```

`<UID>` is the project-local requirement identifier (for example `GC-Q015`) and is also the
folder name and the frontmatter `id`.

## Frontmatter contract (v1)

Every `requirement.md` begins with a YAML frontmatter block. This contract is validated
deterministically by `make policy` (`run_requirement_specs_frontmatter_check`), so keep files
in step with it.

```yaml
---
id: GC-Q015            # required; equals the folder name
title: "..."           # required; quoted scalar
status: ACTIVE         # required; one of DRAFT | ACTIVE | DEPRECATED | ARCHIVED
type: FUNCTIONAL       # required; one of FUNCTIONAL | NON_FUNCTIONAL | CONSTRAINT | INTERFACE
priority: MUST         # required; one of MUST | SHOULD | COULD | WONT
wave: 3                # optional; integer when present
created_at: 2026-...   # optional; ISO-8601 instant
updated_at: 2026-...   # optional; ISO-8601 instant
---
```

The body carries `## Statement`, an optional `## Rationale`, and an optional `## Traceability`
list. The contract is versioned: new fields are added as a frontmatter evolution, not a parser
fork.

## Regenerating from the database (one-time migration)

These files are produced by the one-time exporter, which reuses the existing read path
(`AnalysisService.getRequirementsExportData`); it never issues its own SQL and never writes
outside the supplied output root:

```bash
cd backend && ./gradlew bootRun --args='--export-requirements \
  --project=ground-control --output-dir=../docs/requirements \
  --spring.main.web-application-type=none --spring.flyway.enabled=false \
  --spring.jpa.hibernate.ddl-auto=none'
```

Run against a read-only view of the database (Flyway disabled) so the export never mutates
schema. For another project, export to a maintainer-controlled staging directory, then copy the
resulting folders into that repository out of band. The workflow never selects or writes another
repository.

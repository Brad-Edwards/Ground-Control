# Implement Threat/Risk Screening Preflight

Issue: #1099
Requirement: none

This is architecture guardrail guidance for adding threat and risk screening to
`/implement`. It is not an implementation plan.

## Boundary

The new step is a workflow gate over existing GRC records. It must not create a
new threat/risk domain model, a parallel traceability graph, or an agent-owned
security verdict store.

The screening step belongs between codebase assessment and planning. It reads
the planned change surface and the existing threat/risk workspaces, records one
of three verdicts, and posts a deterministic issue-thread record:

- `not_security_relevant`: one-line rationale, no silent skip.
- `security_relevant`: threat-model, risk-scenario, control, and CODE-link UIDs
  created, updated, or confirmed during the run.
- `no_baseline`: explicit declination when the project has no threat-model
  baseline.

The issue-thread record is durable workflow state per ADR-029. Local telemetry,
subagent cache, PR body text, or ad hoc comments are not substitutes.

## Incumbents To Reuse

- `/implement` orchestration: `skills/implement/SKILL.md` one-file-per-step
  structure, `DEFAULT_IMPLEMENT_ROUTING_STAGES`, and `.ground-control.yaml`
  `routing.stages` are the canonical step registry. Add exactly one stable
  stage id for this gate and keep those surfaces in sync.
- Deterministic MCP record surface: follow `gc_post_implementation_plan`,
  `gc_post_decision_record`, and `gc_post_final_report` in
  `mcp/ground-control/lib.js` / `index.js`: Zod tool schema, pure
  validate/render helper, sensitive-content filter, GitHub body-size cap,
  reserved marker rejection, host-side `gh api` posting, and structured
  `{ok,error,message,next_action}` failures.
- GRC workspaces: use `gc_threat_model_workspace` /
  `getThreatModelWorkspace` and `gc_risk_scenario_workspace` /
  `getRiskScenarioWorkspace` for read-side context. Do not scan backend tables,
  AGE, or repository files directly from the MCP server for domain facts.
- Entity writes and links: use existing consolidated tools
  `gc_threat_model`, `gc_risk_scenario`, and `gc_control`; keep shared
  `link-create.js` semantics for `target_type`, `target_entity_id`,
  `target_identifier`, and `link_type`.
- Backend link validation remains authoritative. MCP may require the universal
  `target_type` and `link_type`, but internal-vs-external target semantics stay
  in `GraphTargetResolverService` and service-level validation.
- Project scoping, auth, audit, and errors remain on the existing REST path:
  `ProjectService`, ADR-026/037 security filters, `ActorFilter`/`ActorHolder`,
  domain exceptions, `GlobalExceptionHandler`, and `ErrorResponse`.
- Workflow policy remains repo-native: update docs and policy surfaces together
  where needed and run `make policy` before completion.

## Cross-Cutting Layers

- MCP tool schema: the screening renderer should accept bounded structured
  input only: positive `issue_number`, `repo_path`, verdict enum, short
  rationale, bounded arrays of entity references, and optional record metadata.
  Entity refs should be explicit typed objects, not arbitrary markdown.
- Record marker parsing: use a distinct marker family such as
  `<!-- gc:grc-screening ... -->`. Reject caller-controlled text containing
  `<!-- gc:` so a rationale or title cannot forge phase or record markers.
- Sensitive content: run the existing body scanner before posting. Screening
  records may mention repo-relative code paths and UIDs, but must not publish
  prompts, raw diffs, file contents, secrets, bearer tokens, env dumps, stack
  traces, or command output.
- GitHub side effects: only the MCP server posts the deterministic screening
  record through argv-shaped `gh api`. The skill step must not tell agents to
  use `gh issue comment`, `git`, `curl`, or raw GitHub API calls for this
  record.
- Backend validation: threat/risk/control create, update, transition, and link
  actions continue through existing REST controllers, Bean Validation, service
  semantics, and `GraphTargetResolverService`. Do not duplicate same-project or
  internal/external target validation in the screening step.
- Error envelope: backend failures flow through `RequestError` and standard
  `ErrorResponse`; MCP renderer failures return structured refusal envelopes.
  Do not add a GRC-screening exception hierarchy.
- Config and OS exposure: this gate should require no new secrets, env vars,
  subprocesses, network clients, local state directories, or argv-visible
  tokens beyond existing MCP/GitHub posting behavior.
- Observability: per-step telemetry remains operational JSONL when enabled.
  It can record the screening step's wall time and outcome, but it is not the
  GRC evidence record and does not gate companion assertions.

## Extensibility

The extension seam is a versioned screening record contract plus a server-side
assertion that can parse and verify it later. Keep the record narrow and
machine-readable:

- `schema` or marker version.
- `verdict`.
- `rationale`.
- `entities_created`, `entities_updated`, and `entities_confirmed`, each as
  typed refs with `type`, `uid`, and optional `id`.
- `code_links` as typed refs that include owner type/uid and repo-relative
  `target_identifier` values for `targetType=CODE`.

This shape lets companion issue #1100 verify reconciliation without scraping
free prose and lets future verdicts or entity families be added by extending the
typed-ref enum rather than rewriting every skill step.

## Gotchas And Anti-Patterns

- Do not conflate `no_baseline` with `not_security_relevant`. Missing baseline
  is an explicit declination record, not a clean screening verdict.
- Do not claim `security_relevant` unless the run creates, updates, or confirms
  concrete threat/risk/control UIDs and CODE links before completion.
- Do not create a generic `GrcScreening` JPA aggregate just to remember workflow
  state. The GitHub issue thread is the workflow record; domain records remain
  threat models, risk scenarios, controls, and links.
- Do not embed free-form markdown tables as the only data surface. The renderer
  may render markdown, but its input and marker must stay structured enough for
  server-side assertion.
- Do not add prompt-only instructions in `skills/implement/SKILL.md` for rules
  the MCP tools cannot enforce. Structural gates need tool-layer validation.
- Do not duplicate `link-create.js`, `gc_query`, workspace composition,
  GraphTargetResolverService, error envelopes, auth filters, or telemetry
  writers.
- Do not infer security relevance from filenames alone. Filenames are an input
  to classification, not proof that the threat/risk graph is reconciled.

## Non-Goals

- No implementation of #1099 in this note.
- No new backend aggregate, migration, controller, repository, graph
  materializer, workflow database table, or Temporal worker.
- No replacement of existing threat-model, risk-scenario, control, risk-control
  mapping, traceability, or workspace contracts.
- No new automated risk scoring, treatment-plan generation, threat lifecycle
  transition, requirement status transition, or control-effectiveness
  assessment logic.
- No new secret/config surface, local durable state, or agent-side privileged
  GitHub write path.

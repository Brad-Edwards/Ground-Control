# IaC and Pipeline Derivation Adapter Preflight

Issue: #1116
Requirement: GC-GRC-003

This is architecture guardrail guidance for adding infrastructure-as-code and
CI/CD pipeline derivation. It is not an implementation plan.

## Boundary

GC-GRC-003 is a build-time derivation adapter requirement under ADR-058. The
adapter must plug into the existing GC-GRC-001 derivation port and persist only
`DerivationRun`, `SystemModelFact`, and `DerivationCaptureLimit` records through
`DerivationService`.

Keep these concepts separate:

- `DerivationAdapter` is the infrastructure-side parser port. It returns
  normalized facts and capture limits; it does not persist rows, post workflow
  records, create threat models, create controls, or run deployment tools.
- `SystemModelFact` is the normalized fact carrier. Pipeline-specific meaning
  belongs in bounded payload fields on existing fact kinds unless a repo-wide
  enum/API/MCP/frontend change is justified.
- `DerivationCaptureLimit` is the explicit coverage-gap record for unsupported
  surfaces, unavailable tools, parser limits, and missing base content. It is
  not a successful empty derivation.
- GRC screening classification belongs to the derivation-backed GRC engine and
  later coverage assertions. The adapter must emit enough deterministic facts
  for classification; it must not substitute an agent verdict.
- Runtime provider evidence, DAST, live cloud inspection, and deployment health
  checks remain outside ADR-058 build-time derivation. They belong to verifier
  or evidence adapters when a separate requirement asks for them.

The minimum supported surface tokens should be stable and explicit:

- `github-actions` over YAML workflow files under `.github/workflows/`;
- `dockerfile` over Dockerfile syntax;
- `docker-compose` over Compose YAML files;
- `terraform` over HCL/Terraform files.

The language dimension should describe the file grammar (`yaml`, `dockerfile`,
`hcl`) and the surface dimension should describe the security semantics above.
Do not overload `language=terraform` or `surface=iac` as the only selector if the
facts need to distinguish Terraform from Docker Compose.

## Incumbents To Reuse

- Derivation port and persistence:
  `DerivationAdapter`, `DerivationAdapterDescriptor`,
  `DerivationAdapterRequest`, `DerivationAdapterResult`,
  `DerivedSystemModelFact`, `DerivationFactProvenance`,
  `DerivationService`, `DerivationAdapterRegistry`,
  `DerivationRunRepository`, `SystemModelFactRepository`, and
  `DerivationCaptureLimitRepository`.
- Existing derivation enums: `DerivationScopeMode`, `SystemModelFactKind`, and
  `CaptureLimitReason`. They are L0 value enums; validation stays in services
  and adapters, not on the enum values.
- Existing fact classes: use `COMPONENT`, `TRUST_BOUNDARY`, `DATA_FLOW`,
  `ENTRY_POINT`, `SECRET_USAGE`, `EXTERNAL_INTERACTION`, and
  `DATA_CLASSIFICATION_HINT` before adding any new `SystemModelFactKind`.
- Existing REST/MCP surface: `DerivationController`, `DerivationRunRequest`,
  derivation response records, `docs/API.md`, `gc_derivation`,
  `mcp/ground-control/lib.js`, and `mcp/ground-control/gc-derivation.js`.
- CodeQL adapter precedent: `CodeQlDerivationProperties`,
  `CodeQlDerivationAdapter`, and `CodeQlSarifNormalizer` show the local
  patterns for descriptor metadata, bounded output, sanitized capture limits,
  provenance, deterministic fact keys, and focused normalizer tests.
- Cross-cutting backend concerns: `ApiPathMatrix`, bearer/browser security
  chains, `ActorFilter` / `ActorHolder`, `GlobalExceptionHandler`,
  `ErrorResponse`, `DomainValidationException`, `@ConfigurationProperties`,
  SLF4J structured logging, Flyway/Envers persistence, and ArchUnit
  `api -> domain <- infrastructure` enforcement.
- Repo policy and documentation surfaces: `make policy`, ADR-034 enum mirror
  rules when public enums change, `docs/CODING_STANDARDS.md` enum
  classification, and `docs/API.md` / MCP tool descriptions for contract
  changes.

## Fact Contract

Facts must be sufficient for a deterministic classifier to explain why a
pipeline/IaC change is security-relevant without reading source files or asking
an agent to judge. Store normalized topology and security signals, not raw
artifact bodies.

Use existing fact kinds this way:

- `ENTRY_POINT`: pipeline triggers such as push, pull request, fork-sensitive
  pull request modes, schedule, manual dispatch, reusable workflow calls, and
  deploy entrypoints.
- `COMPONENT`: workflows, jobs, actions, runners, Docker build stages, images,
  Compose services, Terraform modules/resources/providers, registries, and
  deploy environments.
- `TRUST_BOUNDARY`: repository/trusted branch boundary, fork/untrusted PR
  boundary, GitHub-hosted runner boundary, self-hosted runner boundary,
  container daemon boundary, registry boundary, deployment environment, cloud
  account/project/subscription, and Terraform remote state boundary.
- `DATA_FLOW`: artifact build/publish flow, image flow, package flow, Terraform
  plan/apply flow, and secret movement from a scope into a job, step, action,
  container, build arg, module, or deploy target.
- `SECRET_USAGE`: secret references, secret scopes, inherited secrets, env file
  use, build args carrying secret-like inputs, OIDC/id-token use, and exposure
  paths. Store references and scopes only; never store secret values.
- `EXTERNAL_INTERACTION`: registry pushes/pulls, cloud provider calls,
  deployment hosts, package registries, remote modules, third-party actions,
  and webhook or API targets.
- `DATA_CLASSIFICATION_HINT`: sensitive artifact classes and secret-bearing
  channels when they help the later lattice/rule-pack layer.

Payloads should use bounded, typed fields such as `surface`, `artifactKind`,
`sourcePath`, `locations`, `triggerKind`, `triggerTrust`, `runnerKind`,
`runnerTrustLevel`, `permissionSet`, `secretScope`, `secretRef`,
`exposurePath`, `artifactType`, `registryTarget`, `deployTarget`,
`privilegedOperation`, `boundaryCrossing`, `boundaries`, `changeKind`,
`securitySignals`, and `relevanceReasons`. Prefer short enum-like strings and
arrays over free-form prose. Do not include raw workflow YAML, Dockerfile
snippets, Compose files, Terraform bodies, env files, raw diffs, command output,
stderr, or resolved secrets.

For `DIFF` scope, secret-scope widening and other change classifications must
come from parsed base/head facts or a reported capture limit. A fact can cite
`baseCommitSha` in its payload while its provenance `commitSha` remains the
requested head commit. If base content is unavailable or a parser cannot
compare the surface, emit a capture limit rather than falling back to path
heuristics or an agent-provided verdict.

## Cross-Cutting Layers

- Auth surface: derivation endpoints stay under `/api/v1/**` and therefore pass
  the ADR-026/037 security chains. Because GC-GRC-003 facts expose secret
  topology, deploy targets, and runner trust, the implementation must make an
  explicit `ApiPathMatrix` decision for derivation runs and fact reads. The
  safe default is admin-only for the run trigger and for any read that returns
  deployment-secret topology; retaining generic authenticated access needs a
  documented reason and focused authorization tests.
- Secret-handling surface: parse secret references, names, scopes, and exposure
  paths, but never resolve or store values. Do not persist `.env` contents,
  GitHub secret values, Terraform variable values, provider credentials,
  Docker build secrets, SSH keys, tokens, signed URLs, command output, or
  rendered expressions. `DerivationService` blocks obvious raw-content payload
  keys, but the adapter must sanitize by semantics before it builds payloads.
- Environment/config binding: non-secret knobs belong in an
  `@ConfigurationProperties` POJO, for example enabled flag, repository root,
  max file bytes, max files, parser limits, include/exclude globs, and ruleset
  version. Do not add credentials to derivation config. If production env vars
  are introduced, keep compose, `env.schema`, deploy docs, and policy checks in
  sync.
- Parser layer: use structured parsers or conservative grammar-specific
  readers. Do not execute GitHub Actions, Docker builds, Compose, Terraform,
  provider plugins, remote modules, shell scripts, or package installers to
  derive facts. YAML parsing must be safe and bounded; Terraform parsing must
  not run `terraform init` or contact registries.
- Repository file boundary: scan only the target repository source surface.
  Exclude `.git`, `.gc`, `.claude/worktrees`, build outputs, `node_modules`,
  `dist`, `target`, caches, temporary analyzer directories, and generated
  artifacts unless a caller explicitly scopes a file. Symlinks must not escape
  the configured repository root.
- Scope validation: reuse `DerivationService` normalization for commit SHAs,
  paths, languages, and surfaces. Adapter-local file matching must respect
  `FULL_REPO`, `PATH_SET`, and `DIFF`, including path filters. A mismatch is a
  capture limit or empty scoped result, not a widened scan.
- OS-level exposure: prefer in-process parsing. If a subprocess becomes
  unavoidable, follow the CodeQL pattern: `ProcessBuilder` argv list, no shell
  strings, no secrets in argv/env, fixed working directory under repository
  root, timeout, byte cap, sanitized errors, and best-effort temp cleanup.
- Error envelope: controller and service failures must flow through existing
  domain exceptions and `ErrorResponse`. Parser failures inside an adapter
  should become sanitized `TOOL_EXECUTION_FAILED` capture limits unless the
  request itself is invalid.
- Logging/observability: log low-cardinality counts and identifiers only:
  project, adapter id, surface, run id, file count, fact count, capture-limit
  count, duration, and sanitized status. Do not log payloads, source lines,
  env values, secret refs when avoidable, registry credentials, Terraform
  variables, action inputs, or command output.
- Persistence and audit: do not add a new pipeline/IaC table for GC-GRC-003.
  Persist through `DerivationService` so actor provenance, transaction
  boundaries, Envers auditing, project scoping, and repositories stay in the
  existing Service+Aggregate pattern.
- API/MCP mirrors: adding a new public enum value or new MCP action is a
  repo-wide contract change. Update Java enums, API docs, MCP constant arrays,
  Zod schemas, OpenAPI/MCP tests, frontend mirrors if exposed there, and policy
  inventory if the enum is mirrored. Do not hide pipeline semantics in one
  boundary while another boundary still rejects the value.
- Workflow record boundary: the adapter never posts GitHub comments or phase
  markers. ADR-029/057/058 issue-thread records remain MCP-rendered workflow
  records. GC-GRC-003 only supplies the facts those records and assertions can
  later cite.

## Extensibility Seams

The next likely surfaces are Kubernetes manifests, Helm charts, GitLab CI,
Buildkite, CircleCI, reusable action metadata, and additional Terraform module
sources. The seam should be:

- adapter descriptor `languages`, `surfaces`, and `factKinds`;
- configuration for include/exclude globs, parser caps, and enabled surfaces;
- parser/normalizer classes per grammar or surface inside
  `infrastructure/derivation`, behind the existing adapter port;
- versioned `rulesetName` / `rulesetVersion` in provenance for trust and
  security-signal classification rules;
- payload fields that are additive and schema-versioned through
  `SystemModelFact.SCHEMA_VERSION` rather than parallel DTO hierarchies;
- capture limits for partial support instead of silent omissions.

Add a new Java fact kind only when multiple downstream consumers need to query
that class across adapters and the existing fact kinds cannot carry it cleanly.
Until then, use `artifactKind`, `privilegedOperation`, `securitySignals`, and
`relevanceReasons` fields on existing fact kinds.

## Gotchas And Anti-Patterns

- Do not infer security relevance from filenames alone. A workflow-path change
  is an input; the durable result must cite facts such as secret flow, runner
  trust, publish/deploy target, privilege, or boundary crossing.
- Do not treat `not_security_relevant` as an agent assertion. In the ADR-058
  target, it is a derived empty-impact/empty-gap result.
- Do not conflate pipeline facts with evidence artifacts, observations,
  findings, controls, risk scenarios, or threat-model entries. Those are later
  graph/reconciliation products.
- Do not add a second derivation schema, a pipeline-specific repository, a
  duplicate validation layer, a duplicate exception hierarchy, or a workflow
  database table.
- Do not execute untrusted repository content. Static parsing is the contract.
- Do not follow nested worktrees or generated output. This repo currently has
  `.claude/worktrees/` copies of Docker and Compose files; treating those as
  deployment facts would double-count the surface.
- Do not store raw YAML/HCL/Dockerfile content in fact payloads for reviewer
  convenience. Store source paths, bounded locations, and normalized fields.
- Do not rely on the blocked-key filter alone to prevent secret leakage. A key
  named `value`, `env`, `args`, or `with` can still carry sensitive material.
- Do not make Terraform support depend on provider downloads, backend access,
  state reads, or cloud credentials.
- Do not classify Docker/Compose privileged behavior only by image name. Include
  concrete operations such as Docker socket bind mounts, `privileged: true`,
  `cap_add`, host networking, host PID/IPC, root user, and sensitive bind
  mounts when present.
- Do not ignore GitHub Actions inheritance rules: top-level permissions vs job
  permissions, `id-token: write`, `pull_request_target`, fork-trigger context,
  reusable workflow `secrets: inherit`, environments, self-hosted labels, and
  third-party action refs are all security-relevant facts.

## Non-Goals

- No implementation of GC-GRC-003 in this note.
- No new backend aggregate, migration, controller, repository, graph
  materializer, workflow database table, or Temporal worker.
- No replacement of `DerivationService`, `DerivationAdapterRegistry`,
  `SystemModelFact`, `DerivationCaptureLimit`, `gc_derivation`, or the
  existing GRC screening record machinery.
- No execution of GitHub Actions, Docker, Compose, Terraform, Checkov, Trivy,
  cloud CLIs, provider SDK calls, package managers, or deploy scripts.
- No live cloud inventory, runtime DAST, production health checking, evidence
  collection, artifact publishing, threat generation, control selection, risk
  scoring, or issue-thread posting.

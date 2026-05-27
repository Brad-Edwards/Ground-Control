# Argdown Feedback Validator Preflight

Issue #1045 replaces the phase-4 in-house Argdown structural validator with
`debatelab/argdown-feedback` plus one Ground Control-specific evidence
grounding handler. This note records architecture guardrails only. It is not an
implementation plan.

## Architecture Boundary

- Keep `skills/lit-review-argument/validate-argument-map.sh` as the stable
  process boundary and user contract: one map path in, agent-facing report out,
  exit codes `0` OK, `1` map failure, `2` bad input, `3` environment/tooling.
- Treat upstream `argdown-feedback` as the authority for Argdown parsing,
  inference reconstruction, argument-map checks, logical reconstruction, and
  coherence checks. The only Ground Control-specific verifier is evidence
  grounding for PCS premises.
- Keep the evidence rule narrow: a PCS premise is grounded by a non-empty
  `{evidence: "..."}` pointer, by `{evidence: "paper-contribution: ..."}` as
  an info-level analytic-frame premise, or by being the conclusion of another
  reconstructed argument. Do not fold unreconstructed support, unanswered
  objections, circular support, or formal validity back into local code.
- Do not treat a mechanically valid evidence pointer as a truth check. The
  skill's agent pass still verifies that the phase-3 evidence actually says
  what the premise claims, and that method limits are not exceeded.
- This change sits in repository skill tooling, not the Spring
  `api/ -> domain/ <- infrastructure/` boundary. It should not introduce
  controllers, DTOs, repositories, database state, backend exceptions, or MCP
  tools.

## Cross-Cutting Concerns to Reuse

- **Python bootstrap:** mirror the citation MCP launcher pattern:
  script-relative `.venv`, `python3 -m venv`, idempotent first-run install, and
  pure reuse after bootstrap. `.venv` is already ignored by `.gitignore`.
- **Dependency pinning:** install `argdown-feedback` from a full upstream commit
  SHA, not a branch, floating Git URL, or ad hoc `pip install --upgrade`.
  Record the exact SHA in code or a small local requirements file and in
  ADR-055 when the implementation lands.
- **Offline reuse:** after first bootstrap, validation must not require network
  access. Upstream currently requires NLTK `punkt`; the bootstrap must make
  that resource available inside the reusable skill-local environment or fail as
  an environment problem with a clear message.
- **Skill workflow docs:** update only `lit-review-argument` step 5 and the
  research workflow references needed to keep the mechanical surface accurate.
  Do not rewrite the phase-4 workflow spine.
- **Tests:** keep `skills/lit-review-argument/tests/run-tests.sh` as the
  skill-local regression suite. Port the clean and dirty fixtures, keep one
  visible A/B/C/D failure per dirty fixture, and add a logreco fixture that
  proves valid formalized inference passes while invalid formalized inference
  fails.
- **Changelog:** this issue explicitly asks for `changelog.d/1045.changed.md`
  even though skill-only diffs are normally exempt by the changelog README.

## Security Layers In Scope

- **Input shape:** the shell wrapper accepts one local `.argdown` path. Quote all
  paths, reject missing files as exit `2`, and avoid shell interpolation of map
  content or evidence strings.
- **Python argument parsing:** the verifier entrypoint should use `argparse` or
  equivalent structured parsing. Do not parse command text from the map and do
  not use `shell=True` subprocesses.
- **Network and supply chain:** the only network operation should be the
  explicit first bootstrap of the pinned dependency and its required data. Every
  validation run after that is local.
- **Secrets and argv:** no tokens, Zotero credentials, GitHub credentials,
  prompts, source text, or evidence-base excerpts belong in process argv,
  logs, failure messages, or ADR text. The validator should not need secrets.
- **Error surface:** distinguish map failures from tooling failures. Expected
  verifier findings produce stable report lines and exit `1`; import errors,
  missing Python, failed bootstrap, missing NLTK data, and upstream crashes are
  exit `3`. Do not expose raw stack traces as normal agent-facing output.
- **License posture:** do not vendor or copy upstream AGPL code into the repo.
  ADR-055 must record the AGPL-3.0 reasoning carefully, including the fact that
  a local Python entrypoint importing an AGPL package is not the same risk shape
  as executing an unmodified external binary. The subprocess boundary helps, but
  it should not be documented as legal certainty without review.

## Extensibility Guardrail

The seam belongs in one evidence-grounding handler configured by the verifier
entrypoint: evidence-base root or artifact allowlist, the analytic-frame prefix,
the conclusion roles that count as derivation, and the formalization severity
policy. A future evidence artifact name, conclusion role, or stricter logreco
mode should be a parameter change in that canonical verifier surface, not a
second validator or a forked fixture-specific check.

## Gotchas and Anti-Patterns

- Do not recreate B/C/D checks locally after adopting upstream handlers.
- Do not conflate `arganno` source-text anchoring with Ground Control evidence
  grounding. The workflow anchors to phase-3 evidence-base artifacts, not to a
  fixed source-text annotation task.
- Do not make missing formal logic a blanket hard failure unless the workflow
  explicitly changes. Invalid formalization should fail when present; absence
  should follow the issue's chosen severity policy.
- Do not commit `.venv`, NLTK caches, downloaded packages, generated verifier
  output, or replacement dependency trees.
- Do not make the installer silently track upstream `main`; a deliberate SHA
  bump should be the only upgrade path.
- Do not introduce repo-level config, environment variables, or MCP tools just
  to run this skill-local verifier.

## Non-Goals

- No adoption of Argunauts LLMs or `arganno` source-text anchoring.
- No integration with other skills that happen to mention Argdown.
- No backend API, persistence, security, audit, or error-envelope changes.
- No broad rewrite of `SKILL.md` beyond the validation-surface update required
  for phase 4 step 5.
- No claim that mechanical verification proves premise truth against the
  evidence base; that remains a required agent review pass.

# Documentation style

## Rules

Docs describe the system as it ships on the current commit. Write in present
tense. Use active voice. Be concise: remove any sentence that does not add
information the reader needs to understand the feature, architecture, or
contract.

Strip:

- Fluff: restatement of context the reader already has, throat-clearing,
  hedging prose.
- Forward guidance: "future work," "this feature is planned."
- Roadmapping: phase tables, milestone summaries. Roadmaps belong in
  tracking issues.
- Meta-commentary: "this document explains," "the next section covers." If a
  choice needs explaining, the rationale lives in an ADR.

### Em-dash density

Prefer commas, semicolons, periods, or parentheses for clause breaks. Reach
for an em-dash only when the construction genuinely demands the heavier break:
a parenthetical that requires emphasis, or a sharp pivot that a comma or
semicolon cannot carry.

Soft budget: at most one em-dash per paragraph, typically zero. If a paragraph
has two, rewrite one.

Em-dash chains (`X — Y — Z`) should almost always be reordered into separate
clauses.

This pattern was surfaced in shifter #704, where agent-written prose accumulated
56 em-dash occurrences across five documents in a single PR. The
`GoogleProject.EmDashDensity` Vale rule enforces the per-paragraph budget
mechanically at error level; touched docs that exceed the budget fail the
prose-lint gate. See `.vale/styles/GoogleProject/EmDashDensity.yml`.

## Style anchors

- **Voice and tense:** [Google Developer Documentation Style Guide](https://developers.google.com/style).
  Present-tense default, plain English, concision.
- **Structure:** [Diátaxis](https://diataxis.fr/)—every doc is one of
  `tutorial`, `how-to`, `reference`, or `explanation`. Reference and how-to
  docs do not contain roadmaps or meta-commentary by construction.

## Enforcement

Vale with the `errata-ai/Google` package runs on docs touched in the current
diff via `make policy`, the CI `policy` job, and the pre-commit `vale-prose-lint`
hook. The hook installs Vale via `tools/install-vale.sh` on first need; no
manual `make vale-install` step is required.

## Scope: whole file on first touch

When a `.md` / `.markdown` file appears in the current diff (added, copied,
modified, or renamed vs the base ref), Vale lints it in its entirety—not just
the changed lines. A one-line edit to a previously untouched document brings
the whole file into scope; all of its style violations must be fixed in that
PR. Untouched docs are not linted.

The model is "ratchet on touch": each touched file becomes permanently
compliant, and the codebase converges as docs are edited in the normal course
of work. There is no line-range or hunk-aware mode, and there is no carve-out
for "I only changed one paragraph"; if you touch a doc, you own its full
style compliance. See ADR-054 for the rationale behind this trade-off.

## Temporal context

ADRs carry the durable *why*. Release notes and the changelog carry temporal
context. Tracking issues carry roadmaps. Reference docs state the current
contract only.

## Operational lane docs

Operational skill lanes (`/integrate`, `/implement`, `/quickfix`) document
their contracts in `docs/DEVELOPMENT_WORKFLOW.md` and in their `SKILL.md`
files. The style rules above apply to those files the same as to any other
touched `.md` file: present tense, active voice, no forward guidance, at most
one em-dash per paragraph. The `/integrate` lane's `mode=merge` extension is
documented in `docs/DEVELOPMENT_WORKFLOW.md § /integrate § Configuration` and
`skills/integrate/SKILL.md § Invocation`; no separate doc surface is required.

Per-PR documentation outcomes are recorded as a `## Documentation` section in
the PR body and the Step 19 final-report comment. Pass the optional
`documentation_outcome` field to `gc_render_pr_body` or `gc_post_final_report`
when the diff touches a classified surface (per ADR-054). The renderer emits
the section automatically; agents do not hand-author it.

MCP tool registrations in `mcp/ground-control/index.js` are sensitive to
schema shape: `server.tool(name, desc, zodShape, handler)` and
`server.registerTool(name, {description, inputSchema: <Zod schema>}, handler)`
both work, but `server.registerTool({inputSchema: <raw JSON Schema>})` passes
the registration gate and crashes every call with
`v3Schema.safeParseAsync is not a function`. New tools should match the
`server.tool` pattern used by the bulk of the file.

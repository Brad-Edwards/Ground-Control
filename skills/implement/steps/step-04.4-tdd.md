---
stage_id: implementation
step: "Step 4.4"
tier: medium
---

# Step 4.4: Test-Driven Development (mandatory, with one narrow carve-out)

Once Step 4 has posted the plan, implement using **TDD**. This is not optional except under the documentation-only carve-out below.

**Documentation-only carve-out.** Skip the red-green loop only when ALL of the following hold:

- The entire planned diff is documentation: ADR, README, CHANGELOG (or `changelog.d/<fragment>.md`), skill / workflow prose, design notes, or other static text. A single function, helper, schema field, config knob, behavior change, or other executable line in the diff disqualifies the entire carve-out — the full TDD loop applies, and any documentation in the same diff rides along on the back of the executable behavior's tests rather than triggering a separate carve-out path.
- Every clause of every in-scope requirement AND every acceptance criterion in the issue body is already protected by a **structural gate** — a policy check (e.g., `make policy` rule), schema validator, lint rule, verifier script, structural invariant test, or equivalent automated check that fires on real regression. Reviewer judgment alone (codex review, code review) is not a structural gate; it is a process gate. If you cannot name a structural gate for a clause, the carve-out does NOT apply to that clause; revert to the mandatory loop and write a real test, even if the only behavior you are testing is "the structural invariant exists." If the structural gate is genuinely missing and adding one is in scope, add it (it is the "real fix" path) before declaring the carve-out.
- The plan (Step 4) explicitly declared the carve-out and named the structural gate that protects each clause/criterion.
- A second comment on the issue thread re-states the carve-out and the named structural gate, so the durable record is unambiguous (per ADR-029). One issue comment per `/implement` run is fine; bullets per clause are encouraged.
- A substring or snapshot test against the changed prose ("ADR-007 contains 'AIOPS-ACC-003'") does NOT count as a structural gate. If the only test you can write is one that asserts the doc says what it says, that is the carve-out's failure mode — STOP. Either add a real structural gate as part of this PR (the "real fix" path) or remove the unprotected clause/criterion from the issue's scope and surface that to the user. Do NOT ship a requirement claim with no gate behind it; the workflow's contract is "every clause is verified by something durable," not "the carve-out lets us skip verification."
- **Re-validate the carve-out against the actual diff at the end of implementation.** The carve-out is checked against the *planned* diff at Step 4 and the *actual* diff at Step 4.5 (clause-by-clause verification) and again at Step 6 (completion gate). The Step 6 re-validation is a two-check sweep: (a) every changed path must be in the documentation set (`*.md`, ADRs, notes, docs, CHANGELOG, `changelog.d/**`, README, skills prose), and (b) every diff hunk's *content* must be free of executable behavior (no embedded code, no schema/grammar/policy data consumed by a runtime parser, no runnable fixtures). The path check alone is not enough — a doc file can carry executable behavior. If either check fails for any clause, the carve-out is invalidated retroactively for that clause; revert to the mandatory red-green loop for the executable portion AND for any clause whose structural gate was only a "no executable behavior" claim. The plan-time declaration is provisional; the actual diff is what counts.

If the carve-out applies, jump to Step 4.5; the loop below does not apply.

For all other diffs, the loop is mandatory:

1. **Write the failing test first.** For each clause of each in-scope requirement AND each acceptance criterion in the issue body, write a unit test that exercises the new behavior. Run the test and confirm it fails for the right reason (missing code, not a typo / wiring issue). A test you never saw fail is not a test — it's a guess.
2. **Write the minimum production code to make the test pass.** No premature abstraction, no scope creep, no "while I'm here" cleanups. Just enough to flip the failing assertion green.
3. **Refactor with the test green.** Clean up duplication, extract helpers, rename for clarity — but only with the safety net of green tests. Re-run the test after each refactor.
4. **Repeat per clause / acceptance criterion / edge case.** Do not write a batch of production code first and then "fill in tests" afterwards — that is not TDD, it is post-hoc test-shaped coverage and fails to drive the design.
5. **Edge cases and failure modes get tests too.** Validation errors, boundary inputs, conflict states, not-found paths, status transitions. If a behavior matters enough to ship, it matters enough for a red-green cycle.
6. **Integration / framework-specific test layers**: same loop. Write the failing test before the production code that satisfies it. Repository-policy rules from `cfg.rules.plan_rules_content` (e.g., framework-specific test requirements, migration policies) are TDD targets, not afterthoughts.
7. **If you discover during TDD that the plan is wrong**, stop and revise. The tests are telling you something — listen to them. Update the plan and post the revision as a follow-up comment on the issue.

Pre-existing tests around touched code must stay green at every step. If a refactor breaks an unrelated test, fix the root cause; do not silence the test.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "tests_added": [ "<file:lines>" ],
    "production_files_changed": [ "<file>" ],
    "carveout_taken": false,
    "summary": "<one paragraph: what was implemented and how>"
  }
}
```

Do NOT return raw diff content. The orchestrator and downstream steps compute the diff themselves from `git`.

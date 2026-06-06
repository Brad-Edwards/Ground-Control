---
stage_id: contract_definition
step: "Step 3.5"
tier: high
---

# Step 3.5: Interface Contract

Before writing the implementation plan or changing behavior, define the public contract the change will implement. This is language-neutral: interfaces and signatures, DTO shapes, API request/response and error envelopes, state invariants, graph invariants, permission rules, persistence/audit invariants, and compatibility promises.

1. Draft the contract from the issue, in-scope requirements, architecture preflight, and Step 3 codebase assessment. Keep it concrete enough that the Step 4 plan, Step 4.4 tests, Step 6 assurance classifier, and Step 6.6 test-strength lens can use it as an oracle.
2. Include the ADR-059 engineering contract as operational acceptance criteria:
   - Interface-first: what public seam is being changed, and what stays stable.
   - Whole-system fit: which existing helpers, policies, config, API envelopes, and runtime layers the change must fit.
   - Right-sized simplicity: why this is the smallest sufficient design.
   - Realistic defensive coding: which failures are handled and which are outside the contract.
   - Test strength: which obligation each targeted test or property check proves.
   - Secure from the gate: authn/authz, tenant, secret, injection, and error-leakage rules where relevant.
   - Architectural conformance: binding ADRs and package/boundary rules.
   - Extensibility seam: the next likely variation and the seam that absorbs it.
3. Post the contract with `gc_post_interface_contract`:
   - `repo_path`: absolute path from Step 1
   - `issue_number`: the issue number from Step 1
   - `contract_body`: the Markdown contract

   The tool refuses unless the `preflight` phase marker exists for this issue, computes the current diff binding, writes the `contract` phase marker, and returns the canonical engineering-contract rubric. Do not post the contract manually.
4. Cache the returned comment URL and marker state for Step 4. The Step 4 plan tool refuses without this marker.

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "contract_comment_url": "<URL from gc_post_interface_contract>",
    "contract_comment_id": <int>,
    "contract_phase_marker_written": true,
    "contract_summary": "<one paragraph>"
  }
}
```

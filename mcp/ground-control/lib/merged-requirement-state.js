// Verify in-scope requirement state at the immutable merged tree (issue #1541).
//
// runAssertCompletion (phase="post_merge") calls this to confirm that every
// in-scope requirement, AS IT EXISTS at the linked PR's merge revision, matches the
// status the run intends to report. This is the trust-but-verify fix for the defect
// where the final report rendered caller-supplied `requirements[].status` verbatim:
// a merged PR whose requirement files still say DRAFT can no longer produce a final
// report claiming ACTIVE.
//
// Fails closed per UID. Every returned per-UID result carries ONLY bounded,
// non-sensitive observations (uid, expected/observed status, id-match boolean, link
// counts, a stable code) — never requirement statements, rationale, raw frontmatter,
// raw traceability lines, or subprocess output. This matters because the mechanical
// `failure()` helper scrubs its top-level message but does NOT recursively scrub
// nested extras, so the per-UID shape is the security boundary (issue #1541).

import { hasTestableSurfaceTarget } from "./pr-body.js";
import { readRequirementAtRevision } from "./requirement-files.js";

// The closed lifecycle vocabulary the requirement exporter and policy lint share.
export const REQUIREMENT_STATUS_VOCAB = Object.freeze([
  "DRAFT",
  "ACTIVE",
  "DEPRECATED",
  "ARCHIVED",
]);

function linksOfType(requirement, linkType) {
  return (requirement.traceabilityLinks ?? []).filter((l) => l && l.linkType === linkType);
}

// Traceability required for a requirement at a given intended status, mirroring the
// existing reconciliation contract (retired MCP reconciler / Step 16): an ACTIVE
// requirement needs an IMPLEMENTS entry, plus a TESTS entry when any IMPLEMENTS
// target is on a testable surface. DRAFT/forward-looking requirements carry no such
// requirement (they may hold only DOCUMENTS links). Returns a stable shortfall code
// or null.
function traceabilityShortfall(requirement) {
  const implementsLinks = linksOfType(requirement, "IMPLEMENTS");
  if (implementsLinks.length === 0) return "requirement_missing_implements";
  // hasTestableSurfaceTarget reads `artifact_identifier` (snake); the file reader
  // emits `artifactIdentifier` (camel). Adapt without duplicating the prefix list.
  const adapted = implementsLinks.map((l) => ({ artifact_identifier: l.artifactIdentifier }));
  if (hasTestableSurfaceTarget(adapted) && linksOfType(requirement, "TESTS").length === 0) {
    return "requirement_missing_tests";
  }
  return null;
}

async function verifyOne(repoRoot, revision, expectation) {
  const uid = expectation.uid;
  const expectedStatus = expectation.statusIntent ?? null;
  const base = { uid, expected_status: expectedStatus };

  const read = await readRequirementAtRevision(repoRoot, uid, revision);
  if (!read.found) return { ...base, ok: false, code: "requirement_file_absent" };
  if (read.malformed) return { ...base, ok: false, code: "requirement_record_malformed", id_match: false };

  const idMatch = read.frontmatterId === uid;
  if (!idMatch) return { ...base, ok: false, code: "requirement_id_mismatch", id_match: false };

  const observedStatus = read.requirement.status;
  if (!REQUIREMENT_STATUS_VOCAB.includes(observedStatus)) {
    return { ...base, ok: false, code: "requirement_status_unrecognized", id_match: true };
  }
  if (expectedStatus != null && observedStatus !== expectedStatus) {
    return { ...base, ok: false, code: "requirement_status_mismatch", id_match: true, observed_status: observedStatus };
  }

  // Required-traceability is only enforced once the requirement is (intended to be)
  // ACTIVE, matching the reconciliation gate. A DRAFT expectation asserts only path,
  // id, and status.
  if (expectedStatus === "ACTIVE") {
    const shortfall = traceabilityShortfall(read.requirement);
    if (shortfall) {
      return {
        ...base,
        ok: false,
        code: shortfall,
        id_match: true,
        observed_status: observedStatus,
        implements_count: linksOfType(read.requirement, "IMPLEMENTS").length,
        tests_count: linksOfType(read.requirement, "TESTS").length,
      };
    }
  }

  return {
    ok: true,
    uid,
    expected_status: expectedStatus,
    observed_status: observedStatus,
    // Observed title is a short label (not a requirement body); it is carried only on
    // OK results so the report can render merged truth, and never enters a failure
    // envelope.
    observed_title: read.requirement.title,
    id_match: true,
  };
}

// Verify every expectation ({ uid, statusIntent }) against the immutable revision.
// Validates ALL UIDs before returning — collects every failure rather than stopping
// at the first — so the caller withholds side effects until the full aggregate is known.
export async function verifyMergedRequirementState({ repoRoot, revision, expectations }) {
  const results = [];
  for (const expectation of expectations) {
    // Sequential: keeps the (small) git-show fan-out bounded and deterministic.
    results.push(await verifyOne(repoRoot, revision, expectation));
  }
  const failures = results.filter((r) => !r.ok);
  return { ok: failures.length === 0, revision, results, failures };
}

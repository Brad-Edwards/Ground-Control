// Governance status enums and their per-entity validator.
//
// Split out of lib/sonar-watcher.js under issue #1559. The #1355 extraction cut
// lib.js along its dependency layering, which left this block in a module named
// for the SonarCloud watcher; it has nothing to do with that watcher and the
// file was at the 500-line limit (docs/CODING_STANDARDS.md, ADR-092). The
// contents are unchanged.

export const VERIFICATION_STATUSES = ["PROVEN", "REFUTED", "TIMEOUT", "UNKNOWN", "ERROR"];
export const ASSURANCE_LEVELS = ["L0", "L1", "L2", "L3"];
export const GOVERNANCE_STATUS_ENUMS = {
  verification_result: VERIFICATION_STATUSES,
};
export const GOVERNANCE_FIELDS = {
  verification_result: {
    // Mirrors VerificationResultRequest: targetId (optional UUID), requirementId
    // (optional UUID), prover (@NotBlank), property (optional), result (@NotNull
    // VerificationStatus), assuranceLevel (@NotNull), evidence (Map, opaque),
    // verifiedAt (@NotNull Instant), expiresAt (optional Instant).
    // uid/title/description/outcome/status/metadata were not in the DTO (#1106).
    create: [
      "target_id", "requirement_id", "prover", "property",
      "result", "assurance_level", "evidence", "verified_at", "expires_at",
    ],
    // Mirrors UpdateVerificationResultRequest: identical shape to create (all
    // optional on update, same fields — no create-only keys to drop).
    update: [
      "target_id", "requirement_id", "prover", "property",
      "result", "assurance_level", "evidence", "verified_at", "expires_at",
    ],
  },
};
export function validateGovernanceStatus(entity, status) {
  if (status === undefined || status === null || status === "") return;
  const allowed = GOVERNANCE_STATUS_ENUMS[entity];
  if (!allowed) {
    throw new Error(`'status' is not valid for entity='${entity}'`);
  }
  if (!allowed.includes(status)) {
    throw new Error(
      `'status'='${status}' is not valid for entity='${entity}'. ` +
        `Valid values: ${allowed.join(", ")}`,
    );
  }
}

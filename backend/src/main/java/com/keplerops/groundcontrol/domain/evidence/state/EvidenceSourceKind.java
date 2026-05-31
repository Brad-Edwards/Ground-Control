package com.keplerops.groundcontrol.domain.evidence.state;

/**
 * Source-reference seam per GC-M016 / ADR-045 (extended by GC-I003).
 *
 * <p>Internal kinds resolve to first-class entities by project-scoped UUID
 * ({@code sourceEntityId} on {@link
 * com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef}).
 * External kinds carry a canonical identifier ({@code sourceIdentifier}).
 * Exactly one shape is set per source ref; the service validates this.
 *
 * <p>GC-I003 (executable-evidence collection) adds two external kinds —
 * {@code CI_PIPELINE_RESULT} and {@code SECURITY_SCAN_RESULT} — that carry an
 * opaque, length-capped identifier (e.g. CI run URL, scanner report key). The
 * server never dereferences the identifier (SSRF guard); content rendering is
 * the responsibility of the human or tool reading the artifact summary.
 * Existing internal kinds {@code CONTROL_TEST} and {@code VERIFICATION_RESULT}
 * already cover executable test-result and prover-output evidence (ADR-014 /
 * ADR-039) and no new abstraction is required for them.
 */
public enum EvidenceSourceKind {
    OBSERVATION(true),
    CONTROL_TEST(true),
    CONTROL_EFFECTIVENESS_ASSESSMENT(true),
    VERIFICATION_RESULT(true),
    RISK_ASSESSMENT_RESULT(true),
    FINDING(true),
    ATTESTATION(false),
    EXTERNAL(false),
    CI_PIPELINE_RESULT(false),
    SECURITY_SCAN_RESULT(false);

    private final boolean internal;

    EvidenceSourceKind(boolean internal) {
        this.internal = internal;
    }

    public boolean isInternal() {
        return internal;
    }
}

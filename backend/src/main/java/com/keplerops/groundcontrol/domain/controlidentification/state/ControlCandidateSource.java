package com.keplerops.groundcontrol.domain.controlidentification.state;

/**
 * Provenance of a {@code ControlCandidate}'s underlying control (GC-GRC-008). A candidate control is
 * either materialized from an installed control pack (an OSCAL catalog such as NIST SP 800-53/800-218)
 * or one of the project's own existing controls. The distinction is auditable evidence that control
 * selection is framework-backed rather than LLM-invented.
 */
public enum ControlCandidateSource {
    CONTROL_PACK,
    PROJECT_CONTROL
}

package com.keplerops.groundcontrol.domain.riskcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

/**
 * An evidence reference anchored on a {@link RiskControlMapping} (GC-T003 C8).
 *
 * <p>The owning mapping id is implicit via the {@code @ElementCollection} join column; the
 * {@code evidenceRef} field carries the identifier (URI or artifact ID) and an optional
 * descriptive note so retraction, re-scoping, and per-mapping validation are possible.
 */
@Embeddable
public class MappingEvidenceRef {

    @Column(name = "evidence_ref", nullable = false, length = 2000)
    private String evidenceRef;

    @Column(name = "evidence_note", length = 500)
    private String evidenceNote;

    /** Optional reference to an EvidenceArtifact entity in the same project. */
    @Column(name = "evidence_artifact_id")
    private UUID evidenceArtifactId;

    protected MappingEvidenceRef() {
        // JPA
    }

    public MappingEvidenceRef(String evidenceRef, String evidenceNote) {
        this.evidenceRef = evidenceRef;
        this.evidenceNote = evidenceNote;
    }

    public MappingEvidenceRef(String evidenceRef, String evidenceNote, UUID evidenceArtifactId) {
        this.evidenceRef = evidenceRef;
        this.evidenceNote = evidenceNote;
        this.evidenceArtifactId = evidenceArtifactId;
    }

    public String getEvidenceRef() {
        return evidenceRef;
    }

    public String getEvidenceNote() {
        return evidenceNote;
    }

    public UUID getEvidenceArtifactId() {
        return evidenceArtifactId;
    }
}

package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-R004 / GC-RSCH-N002 / GC-RSCH-N004 / ADR-069 §2 — one node in a
 * research run's directed provenance graph. A node identifies a bounded research
 * referent ({@link ProvenanceNodeKind}) keyed by a {@code subjectKey} that is
 * stable within the run/artifact attempt, plus optional stable references
 * (artifact manifest row + attempt, locator, content hash, external identifier
 * such as a DOI or Zotero key) and bounded reproducibility metadata (tool name /
 * version / source action id). It stores references and short summaries only —
 * never raw queries, full text, charting rows, manuscript prose, prompts,
 * provider payloads, or secrets.
 *
 * <p>Provenance is append-only historical state: rework {@code SUPERSEDED}s the
 * prior node (it is never mutated in place) and points the superseded row at its
 * replacement. The recording {@code actor} comes from the authenticated server
 * context, never the caller.
 */
@Entity
@Audited
@Table(name = "research_provenance_node")
public class ResearchProvenanceNode extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProvenanceNodeKind kind;

    @Column(name = "subject_key", nullable = false, length = 200)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ResearchRunStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_type", length = 40)
    private ResearchArtifactType artifactType;

    @Column(name = "artifact_id")
    private UUID artifactId;

    @Column(name = "attempt_no")
    private Integer attemptNo;

    @Column(length = 500)
    private String locator;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "external_identifier", length = 200)
    private String externalIdentifier;

    @Column(length = 2000)
    private String summary;

    @Column(name = "tool_name", length = 200)
    private String toolName;

    @Column(name = "tool_version", length = 100)
    private String toolVersion;

    @Column(name = "source_action_id", length = 200)
    private String sourceActionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvenanceRecordStatus status = ProvenanceRecordStatus.ACTIVE;

    @NotAudited
    @Column(name = "superseded_by_node_id")
    private UUID supersededByNodeId;

    @Column(length = 200)
    private String actor;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    protected ResearchProvenanceNode() {
        // JPA
    }

    public ResearchProvenanceNode(ResearchRun researchRun, ProvenanceNodeKind kind, String subjectKey) {
        if (researchRun == null) {
            throw new DomainValidationException("Research run must not be null", "invalid_provenance_node", Map.of());
        }
        if (kind == null) {
            throw new DomainValidationException("Kind must not be null", "invalid_provenance_node", Map.of());
        }
        if (subjectKey == null || subjectKey.isBlank()) {
            throw new DomainValidationException("Subject key must not be blank", "invalid_provenance_node", Map.of());
        }
        this.researchRun = researchRun;
        this.kind = kind;
        this.subjectKey = subjectKey;
    }

    /** Mark this record {@code SUPERSEDED} ahead of inserting its replacement. */
    public void markSuperseded() {
        this.status = ProvenanceRecordStatus.SUPERSEDED;
    }

    /** Point this superseded node at the {@code ACTIVE} node that replaced it. */
    public void linkSuperseder(UUID replacementNodeId) {
        this.supersededByNodeId = replacementNodeId;
    }

    public void setStage(ResearchRunStage stage) {
        this.stage = stage;
    }

    public void setArtifactType(ResearchArtifactType artifactType) {
        this.artifactType = artifactType;
    }

    public void setArtifactId(UUID artifactId) {
        this.artifactId = artifactId;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public void setLocator(String locator) {
        this.locator = locator;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public void setExternalIdentifier(String externalIdentifier) {
        this.externalIdentifier = externalIdentifier;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public void setSourceActionId(String sourceActionId) {
        this.sourceActionId = sourceActionId;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ProvenanceNodeKind getKind() {
        return kind;
    }

    public String getSubjectKey() {
        return subjectKey;
    }

    public ResearchRunStage getStage() {
        return stage;
    }

    public ResearchArtifactType getArtifactType() {
        return artifactType;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public String getLocator() {
        return locator;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getExternalIdentifier() {
        return externalIdentifier;
    }

    public String getSummary() {
        return summary;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public String getSourceActionId() {
        return sourceActionId;
    }

    public ProvenanceRecordStatus getStatus() {
        return status;
    }

    public UUID getSupersededByNodeId() {
        return supersededByNodeId;
    }

    public String getActor() {
        return actor;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}

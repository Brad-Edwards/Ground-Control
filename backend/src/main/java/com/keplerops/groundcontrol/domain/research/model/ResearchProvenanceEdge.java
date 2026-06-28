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
 * GC-RSCH-R004 / GC-RSCH-N002 / ADR-069 §2 — one directed derivation edge in a
 * research run's provenance graph. The edge runs from an upstream input node
 * ({@code fromNodeId}) to a downstream output node ({@code toNodeId}); a
 * downstream node can therefore be traversed backward through its incoming edges
 * to the user goal and the source evidence that supports it. Both endpoints
 * belong to the same run; self-edges and cycles are rejected by the service.
 *
 * <p>Append-only and rework-aware like {@link ResearchProvenanceNode}: an edge is
 * never mutated in place; rework {@code SUPERSEDED}s it and inserts a
 * replacement. The optional {@code summary} carries a bounded confidence or
 * limitation note, never raw research content.
 */
@Entity
@Audited
@Table(name = "research_provenance_edge")
public class ResearchProvenanceEdge extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Column(name = "from_node_id", nullable = false)
    private UUID fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private UUID toNodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProvenanceEdgeRelation relation;

    @Column(length = 200)
    private String role;

    @Column(length = 2000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProvenanceRecordStatus status = ProvenanceRecordStatus.ACTIVE;

    @NotAudited
    @Column(name = "superseded_by_edge_id")
    private UUID supersededByEdgeId;

    @Column(length = 200)
    private String actor;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    protected ResearchProvenanceEdge() {
        // JPA
    }

    public ResearchProvenanceEdge(
            ResearchRun researchRun, UUID fromNodeId, UUID toNodeId, ProvenanceEdgeRelation relation) {
        if (researchRun == null) {
            throw new DomainValidationException("Research run must not be null", "invalid_provenance_edge", Map.of());
        }
        if (fromNodeId == null || toNodeId == null) {
            throw new DomainValidationException("Edge endpoints must not be null", "invalid_provenance_edge", Map.of());
        }
        if (relation == null) {
            throw new DomainValidationException("Relation must not be null", "invalid_provenance_edge", Map.of());
        }
        this.researchRun = researchRun;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.relation = relation;
    }

    public void markSuperseded() {
        this.status = ProvenanceRecordStatus.SUPERSEDED;
    }

    public void linkSuperseder(UUID replacementEdgeId) {
        this.supersededByEdgeId = replacementEdgeId;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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

    public UUID getFromNodeId() {
        return fromNodeId;
    }

    public UUID getToNodeId() {
        return toNodeId;
    }

    public ProvenanceEdgeRelation getRelation() {
        return relation;
    }

    public String getRole() {
        return role;
    }

    public String getSummary() {
        return summary;
    }

    public ProvenanceRecordStatus getStatus() {
        return status;
    }

    public UUID getSupersededByEdgeId() {
        return supersededByEdgeId;
    }

    public String getActor() {
        return actor;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}

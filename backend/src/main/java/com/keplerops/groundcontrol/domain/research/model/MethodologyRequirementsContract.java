package com.keplerops.groundcontrol.domain.research.model;

import com.keplerops.groundcontrol.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-F007 / ADR-079 — the structured phase-1 methodology requirements
 * contract for one research run. It sits behind, and is tied by run + artifact
 * attempt to, the ACTIVE {@link ResearchArtifactType#METHODOLOGY_REQUIREMENTS}
 * {@link ResearchRunArtifact} manifest (the manifest stays lifecycle metadata;
 * this is its bounded structured content).
 *
 * <p>Exactly one contract exists per artifact attempt — the unique
 * {@code artifact_id} enforces it. Rework records a new artifact attempt and
 * therefore a new contract, so the contract inherits the artifact's supersession
 * and needs no supersede logic of its own. The chosen method is the referenced
 * active {@link ResearchRunMethodologySelection}; extracted entries, their source
 * links, and rejected alternatives are child rows queried by repository (the
 * aggregate is not mapped as JPA collections, matching selection → sources).
 */
@Entity
@Audited
@Table(
        name = "methodology_requirements_contract",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_methodology_requirements_contract_artifact",
                        columnNames = {"artifact_id"}))
public class MethodologyRequirementsContract extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "selection_id", nullable = false)
    private ResearchRunMethodologySelection selection;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion;

    @Column(length = 200)
    private String actor;

    protected MethodologyRequirementsContract() {
        // JPA
    }

    public MethodologyRequirementsContract(
            ResearchRun researchRun,
            ResearchRunMethodologySelection selection,
            UUID artifactId,
            int attemptNo,
            String schemaVersion,
            String actor) {
        this.researchRun = researchRun;
        this.selection = selection;
        this.artifactId = artifactId;
        this.attemptNo = attemptNo;
        this.schemaVersion = schemaVersion;
        this.actor = actor;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ResearchRunMethodologySelection getSelection() {
        return selection;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getActor() {
        return actor;
    }
}

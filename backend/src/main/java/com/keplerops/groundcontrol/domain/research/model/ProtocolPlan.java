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
 * GC-RSCH-F008 / GC-RSCH-F009 / ADR-081 — the structured phase-2 protocol plan
 * for one research run. It sits behind, and is tied by run + artifact attempt
 * to, the ACTIVE {@link ResearchArtifactType#PROTOCOL_PLAN} {@link
 * ResearchRunArtifact} manifest (the manifest stays lifecycle metadata; this is
 * its bounded structured content). The plan is also tied to the one active
 * ADR-080 {@link MethodologyRequirementsContract} attempt it answers.
 *
 * <p>Exactly one plan exists per artifact attempt — the unique {@code
 * artifact_id} enforces it. Rework records a new artifact attempt and
 * therefore a new plan rather than mutating the prior accepted plan. Coverage
 * dispositions and sections are child rows queried by repository (the
 * aggregate is not mapped as JPA collections, matching the ADR-080 contract
 * pattern).
 */
@Entity
@Audited
@Table(
        name = "protocol_plan",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_protocol_plan_artifact",
                        columnNames = {"artifact_id"}))
public class ProtocolPlan extends BaseEntity {

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "methodology_requirements_contract_id", nullable = false)
    private MethodologyRequirementsContract methodologyRequirementsContract;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "protocol_schema_version", nullable = false, length = 40)
    private String protocolSchemaVersion;

    @Column(name = "method_key", nullable = false, length = 200)
    private String methodKey;

    @Column(name = "method_profile_version", length = 100)
    private String methodProfileVersion;

    @Column(length = 200)
    private String actor;

    protected ProtocolPlan() {
        // JPA
    }

    public ProtocolPlan(
            ResearchRun researchRun,
            MethodologyRequirementsContract methodologyRequirementsContract,
            UUID artifactId,
            int attemptNo,
            String protocolSchemaVersion,
            String methodKey,
            String methodProfileVersion,
            String actor) {
        this.researchRun = researchRun;
        this.methodologyRequirementsContract = methodologyRequirementsContract;
        this.artifactId = artifactId;
        this.attemptNo = attemptNo;
        this.protocolSchemaVersion = protocolSchemaVersion;
        this.methodKey = methodKey;
        this.methodProfileVersion = methodProfileVersion;
        this.actor = actor;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public MethodologyRequirementsContract getMethodologyRequirementsContract() {
        return methodologyRequirementsContract;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getProtocolSchemaVersion() {
        return protocolSchemaVersion;
    }

    public String getMethodKey() {
        return methodKey;
    }

    public String getMethodProfileVersion() {
        return methodProfileVersion;
    }

    public String getActor() {
        return actor;
    }
}

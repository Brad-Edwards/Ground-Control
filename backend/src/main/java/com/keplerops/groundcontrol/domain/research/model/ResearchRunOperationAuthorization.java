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
import java.time.Instant;
import java.util.Map;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

/**
 * GC-RSCH-R005 / GC-RSCH-N005 / GC-RSCH-N006 / ADR-085 §3 — durable, run-scoped
 * authorization record for one research high-risk operation (generated-code
 * execution, browser activity, lab/hardware action, external write). It is
 * research execution metadata, not a lifecycle gate or GRC quality gate.
 *
 * <p>The record carries bounded facts only — operation kind, tool/adapter id,
 * sandbox profile reference, requested data class / destination / form, target
 * class, policy basis, proposing/deciding actor, expiry, source-action id, and a
 * short summary. It never stores prompts, generated code, PDFs, page bodies,
 * cookies, credentials, external-write payloads, or absolute paths.
 *
 * <p>Default-deny: a record lands {@code PROPOSED}; only an authenticated
 * admin/operator decision moves it to {@code APPROVED}/{@code DENIED} (an
 * {@code AUTONOMOUS} run may propose, never approve), and approval requires the
 * run's snapshotted egress policy to permit the (dataClass, destination, form)
 * tuple. A one-time-use {@code APPROVED} record is spent to {@code CONSUMED}.
 * State transitions are guarded here so an illegal arc cannot silently corrupt
 * the authorization trail. The deciding/proposing actor comes from the
 * authenticated server context, never the caller.
 */
@Entity
@Audited
@Table(name = "research_run_operation_authorization")
public class ResearchRunOperationAuthorization extends BaseEntity {

    private static final String INVALID = "invalid_operation_authorization";

    @NotAudited
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "research_run_id", nullable = false)
    private ResearchRun researchRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 40)
    private ResearchHighRiskOperationKind operationKind;

    @Column(name = "tool_id", length = 200)
    private String toolId;

    @Column(name = "sandbox_profile", length = 120)
    private String sandboxProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_class", nullable = false, length = 20)
    private ResearchDataClass dataClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_class", nullable = false, length = 30)
    private ResearchDestinationClass destinationClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_form", nullable = false, length = 20)
    private ResearchDataForm requestedForm;

    @Column(name = "target_class", length = 120)
    private String targetClass;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchOperationAuthorizationState state = ResearchOperationAuthorizationState.PROPOSED;

    @Column(name = "policy_basis", length = 500)
    private String policyBasis;

    @Column(name = "proposing_actor", length = 200)
    private String proposingActor;

    @Column(name = "deciding_actor", length = 200)
    private String decidingActor;

    @Column(name = "source_action_id", length = 200)
    private String sourceActionId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(length = 2000)
    private String summary;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    protected ResearchRunOperationAuthorization() {
        // JPA
    }

    public ResearchRunOperationAuthorization(
            ResearchRun researchRun,
            ResearchHighRiskOperationKind operationKind,
            ResearchDataClass dataClass,
            ResearchDestinationClass destinationClass,
            ResearchDataForm requestedForm) {
        if (researchRun == null) {
            throw new DomainValidationException("Research run must not be null", INVALID, Map.of());
        }
        if (operationKind == null) {
            throw new DomainValidationException("Operation kind must not be null", INVALID, Map.of());
        }
        if (dataClass == null) {
            throw new DomainValidationException("Data class must not be null", INVALID, Map.of());
        }
        if (destinationClass == null) {
            throw new DomainValidationException("Destination class must not be null", INVALID, Map.of());
        }
        if (requestedForm == null) {
            throw new DomainValidationException("Requested form must not be null", INVALID, Map.of());
        }
        this.researchRun = researchRun;
        this.operationKind = operationKind;
        this.dataClass = dataClass;
        this.destinationClass = destinationClass;
        this.requestedForm = requestedForm;
    }

    /** Approve a {@code PROPOSED} record; records the deciding actor and policy basis. */
    public void approve(String actor, String policyBasis) {
        requireProposed("approve");
        this.state = ResearchOperationAuthorizationState.APPROVED;
        this.decidingActor = actor;
        this.policyBasis = policyBasis;
    }

    /** Deny a {@code PROPOSED} record; records the deciding actor and basis. */
    public void deny(String actor, String policyBasis) {
        requireProposed("deny");
        this.state = ResearchOperationAuthorizationState.DENIED;
        this.decidingActor = actor;
        this.policyBasis = policyBasis;
    }

    /**
     * Spend a one-time-use {@code APPROVED} record. If the record is past its
     * expiry it moves to {@code EXPIRED} and the consume is rejected, so an
     * executor can never treat an expired approval as authority.
     */
    public void consume(Instant now) {
        if (state == ResearchOperationAuthorizationState.APPROVED && expiresAt != null && !now.isBefore(expiresAt)) {
            this.state = ResearchOperationAuthorizationState.EXPIRED;
        }
        if (state != ResearchOperationAuthorizationState.APPROVED) {
            throw new DomainValidationException(
                    "Only an APPROVED, unexpired authorization can be consumed",
                    "operation_authorization_not_consumable",
                    Map.of("state", state.name()));
        }
        this.state = ResearchOperationAuthorizationState.CONSUMED;
    }

    private void requireProposed(String action) {
        if (state != ResearchOperationAuthorizationState.PROPOSED) {
            throw new DomainValidationException(
                    "Cannot " + action + " an authorization in state " + state,
                    "operation_authorization_invalid_transition",
                    Map.of("state", state.name(), "action", action));
        }
    }

    public void setToolId(String toolId) {
        this.toolId = toolId;
    }

    public void setSandboxProfile(String sandboxProfile) {
        this.sandboxProfile = sandboxProfile;
    }

    public void setTargetClass(String targetClass) {
        this.targetClass = targetClass;
    }

    public void setPolicyBasis(String policyBasis) {
        this.policyBasis = policyBasis;
    }

    public void setProposingActor(String proposingActor) {
        this.proposingActor = proposingActor;
    }

    public void setSourceActionId(String sourceActionId) {
        this.sourceActionId = sourceActionId;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public ResearchRun getResearchRun() {
        return researchRun;
    }

    public ResearchHighRiskOperationKind getOperationKind() {
        return operationKind;
    }

    public String getToolId() {
        return toolId;
    }

    public String getSandboxProfile() {
        return sandboxProfile;
    }

    public ResearchDataClass getDataClass() {
        return dataClass;
    }

    public ResearchDestinationClass getDestinationClass() {
        return destinationClass;
    }

    public ResearchDataForm getRequestedForm() {
        return requestedForm;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public ResearchOperationAuthorizationState getState() {
        return state;
    }

    public String getPolicyBasis() {
        return policyBasis;
    }

    public String getProposingActor() {
        return proposingActor;
    }

    public String getDecidingActor() {
        return decidingActor;
    }

    public String getSourceActionId() {
        return sourceActionId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getSummary() {
        return summary;
    }

    public int getAttemptNo() {
        return attemptNo;
    }
}

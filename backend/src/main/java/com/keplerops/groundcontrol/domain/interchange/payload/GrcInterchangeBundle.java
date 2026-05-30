package com.keplerops.groundcontrol.domain.interchange.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Versioned envelope for graph-native GRC interchange per GC-P012.
 *
 * <p>Carries per-entity payload arrays for the asset, risk-scenario, control,
 * finding, and evidence-artifact surfaces. Each entity payload preserves its
 * external UID, optional source provenance, and timestamps so an importer can
 * reconcile idempotently. The envelope is intentionally JSON-only: ADR-026
 * forbids XML to avoid XXE.
 *
 * <p>The optional {@code projectIdentifier} is informational only; the import
 * controller resolves the destination project from authenticated request
 * scope and rejects mismatches per the cluster security note.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrcInterchangeBundle(
        String formatVersion,
        Instant exportedAt,
        String projectIdentifier,
        List<AssetPayload> assets,
        List<RiskScenarioPayload> riskScenarios,
        List<ControlPayload> controls,
        List<FindingPayload> findings,
        List<EvidenceArtifactPayload> evidenceArtifacts) {

    // Defensive copies so the bundle is genuinely immutable across the
    // controller/service boundary; SpotBugs EI_EXPOSE_REP[2] flags the
    // raw-record accessor + canonical-constructor leaks otherwise.
    public GrcInterchangeBundle {
        assets = assets == null ? null : List.copyOf(assets);
        riskScenarios = riskScenarios == null ? null : List.copyOf(riskScenarios);
        controls = controls == null ? null : List.copyOf(controls);
        findings = findings == null ? null : List.copyOf(findings);
        evidenceArtifacts = evidenceArtifacts == null ? null : List.copyOf(evidenceArtifacts);
    }

    /** Current interchange schema version. */
    public static final String CURRENT_VERSION = "1.0";

    /**
     * Common contract for every entity-kind payload in the interchange bundle.
     * Allows the importer to process provenance-only surfaces generically
     * without a separate overload per kind.
     */
    public interface InterchangePayload {
        String externalUid();

        String sourceSystem();

        Instant createdAt();

        Instant updatedAt();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetPayload(
            String externalUid,
            String title,
            String type,
            String subtype,
            String description,
            String owner,
            String steward,
            String environment,
            String criticality,
            String sourceSystem,
            Instant createdAt,
            Instant updatedAt)
            implements InterchangePayload {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskScenarioPayload(
            String externalUid,
            String title,
            String summary,
            String status,
            String sourceSystem,
            Instant createdAt,
            Instant updatedAt)
            implements InterchangePayload {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ControlPayload(
            String externalUid,
            String title,
            String description,
            String controlType,
            String sourceSystem,
            Instant createdAt,
            Instant updatedAt)
            implements InterchangePayload {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FindingPayload(
            String externalUid,
            String title,
            String severity,
            String status,
            String description,
            String sourceSystem,
            Instant createdAt,
            Instant updatedAt)
            implements InterchangePayload {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceArtifactPayload(
            String externalUid,
            String title,
            String evidenceType,
            String externalUri,
            String sourceSystem,
            Instant createdAt,
            Instant updatedAt)
            implements InterchangePayload {}

    public static GrcInterchangeBundle empty(String projectIdentifier) {
        return new GrcInterchangeBundle(
                CURRENT_VERSION,
                Instant.now(),
                projectIdentifier,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}

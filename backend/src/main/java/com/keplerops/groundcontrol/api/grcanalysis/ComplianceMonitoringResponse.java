package com.keplerops.groundcontrol.api.grcanalysis;

import com.keplerops.groundcontrol.domain.grcanalysis.service.ComplianceMonitoringResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ComplianceMonitoringResponse(
        String analysisKind,
        String project,
        Instant asOf,
        String derivationMethod,
        Inputs inputs,
        List<ImpactItem> impactSet,
        List<GapItem> gapSet,
        List<StaleItem> staleSet,
        DriftCauseCounts driftCauseCounts,
        List<String> limitations) {

    public static ComplianceMonitoringResponse from(ComplianceMonitoringResult result) {
        return new ComplianceMonitoringResponse(
                result.analysisKind(),
                result.project(),
                result.asOf(),
                result.derivationMethod(),
                Inputs.from(result.inputs()),
                result.impactSet().stream().map(ImpactItem::from).toList(),
                result.gapSet().stream().map(GapItem::from).toList(),
                result.staleSet().stream().map(StaleItem::from).toList(),
                DriftCauseCounts.from(result.driftCauseCounts()),
                List.copyOf(result.limitations()));
    }

    public record Inputs(String project, Instant asOf, int freshnessWindowDays) {

        public static Inputs from(ComplianceMonitoringResult.Inputs inputs) {
            return new Inputs(inputs.project(), inputs.asOf(), inputs.freshnessWindowDays());
        }
    }

    public record ImpactItem(
            String driftCause, String entityType, UUID entityId, String entityUid, Instant detectedAt, String summary) {

        public static ImpactItem from(ComplianceMonitoringResult.ImpactItem item) {
            return new ImpactItem(
                    item.driftCause(),
                    item.entityType(),
                    item.entityId(),
                    item.entityUid(),
                    item.detectedAt(),
                    item.summary());
        }
    }

    public record GapItem(String gapKind, String entityType, UUID entityId, String entityUid, String summary) {

        public static GapItem from(ComplianceMonitoringResult.GapItem item) {
            return new GapItem(item.gapKind(), item.entityType(), item.entityId(), item.entityUid(), item.summary());
        }
    }

    public record StaleItem(String sourceKind, UUID entityId, String entityUid, String state, Instant detectedAt) {

        public static StaleItem from(ComplianceMonitoringResult.StaleItem item) {
            return new StaleItem(item.sourceKind(), item.entityId(), item.entityUid(), item.state(), item.detectedAt());
        }
    }

    public record DriftCauseCounts(int controlModification, int artifactGraphChange, int evidenceExpiration) {

        public static DriftCauseCounts from(ComplianceMonitoringResult.DriftCauseCounts counts) {
            return new DriftCauseCounts(
                    counts.controlModification(), counts.artifactGraphChange(), counts.evidenceExpiration());
        }
    }
}

package com.keplerops.groundcontrol.unit.domain.dataclassification;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureFlowDirection;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationElementView;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationResult;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationEvaluationService;
import com.keplerops.groundcontrol.domain.dataclassification.service.DataClassificationLatticeDefinition;
import com.keplerops.groundcontrol.domain.dataclassification.service.DefaultDataClassificationLattice;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationFindingReason;
import com.keplerops.groundcontrol.domain.dataclassification.state.DataClassificationSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exercises the deterministic data-classification lattice evaluator (GC-GRC-006 clause c) against
 * the shipped default lattice — including the headline acceptance criterion that a PII-labeled
 * source flowing to a lower-trust sink is a violation with no LLM involvement.
 */
class DataClassificationEvaluationServiceTest {

    private static final DataClassificationLatticeDefinition DEFAULT = DefaultDataClassificationLattice.definition();
    private static final UUID SNAPSHOT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static DataClassificationElementView store(String stableKey, String label) {
        return new DataClassificationElementView(
                stableKey, ArchitectureModelElementKind.DATA_STORE, label, null, null, null);
    }

    private static DataClassificationElementView flow(
            String stableKey, String source, String target, ArchitectureFlowDirection direction) {
        return new DataClassificationElementView(
                stableKey, ArchitectureModelElementKind.DATA_FLOW, null, source, target, direction);
    }

    private static DataClassificationEvaluationResult evaluate(List<DataClassificationElementView> views) {
        return DataClassificationEvaluationService.evaluate(DEFAULT, "architecture-model/v1", SNAPSHOT, views);
    }

    @Test
    void piiSourceToPublicLogSinkIsAViolation() {
        var result = evaluate(List.of(
                store("db.users", "PII"),
                store("log.app", "PUBLIC"),
                flow("flow.users-to-log", "db.users", "log.app", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.evaluatedFlowCount()).isEqualTo(1);
        assertThat(result.limitations()).isEmpty();
        assertThat(result.source()).isEqualTo(DataClassificationSource.DEFAULT);
        assertThat(result.modelVersion()).isEqualTo("architecture-model/v1");
        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT);
        assertThat(result.violations()).hasSize(1);
        var violation = result.violations().getFirst();
        assertThat(violation.reason()).isEqualTo(DataClassificationFindingReason.LABEL_FLOW_NOT_PERMITTED);
        assertThat(violation.flowStableKey()).isEqualTo("flow.users-to-log");
        assertThat(violation.sourceLabelKey()).isEqualTo("PII");
        assertThat(violation.sinkLabelKey()).isEqualTo("PUBLIC");
    }

    @Test
    void publicSourceToPiiSinkIsPermitted() {
        var result = evaluate(List.of(
                store("api.public", "PUBLIC"),
                store("db.users", "PII"),
                flow("flow.public-to-users", "api.public", "db.users", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void sameLabelFlowIsPermitted() {
        var result = evaluate(List.of(
                store("db.a", "CONFIDENTIAL"),
                store("db.b", "CONFIDENTIAL"),
                flow("flow.a-to-b", "db.a", "db.b", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
    }

    @Test
    void upFlowToMoreSensitiveSinkIsPermitted() {
        var result = evaluate(List.of(
                store("svc.conf", "CONFIDENTIAL"),
                store("db.regulated", "REGULATED"),
                flow("flow.conf-to-reg", "svc.conf", "db.regulated", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
    }

    @Test
    void flowBetweenIncomparableSensitiveLabelsIsAViolation() {
        var result = evaluate(List.of(
                store("vault.pii", "PII"),
                store("vault.secrets", "SECRETS"),
                flow("flow.pii-to-secrets", "vault.pii", "vault.secrets", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.LABEL_FLOW_NOT_PERMITTED);
    }

    @Test
    void bidirectionalFlowReportsOnlyTheViolatingDirection() {
        var result = evaluate(List.of(
                store("db.users", "PII"),
                store("cache.public", "PUBLIC"),
                flow("flow.users-cache", "db.users", "cache.public", ArchitectureFlowDirection.BIDIRECTIONAL)));

        // PII -> PUBLIC violates; PUBLIC -> PII is permitted, so exactly one violation.
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().getFirst().sourceLabelKey()).isEqualTo("PII");
        assertThat(result.violations().getFirst().sinkLabelKey()).isEqualTo("PUBLIC");
    }

    @Test
    void missingSourceLabelIsALimitationNotASilentPass() {
        var result = evaluate(List.of(
                store("svc.unlabeled", null),
                store("log.app", "PUBLIC"),
                flow("flow.unlabeled-to-log", "svc.unlabeled", "log.app", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.MISSING_SOURCE_LABEL);
    }

    @Test
    void missingSinkLabelIsALimitation() {
        var result = evaluate(List.of(
                store("db.users", "PII"),
                store("sink.unlabeled", null),
                flow(
                        "flow.users-to-unlabeled",
                        "db.users",
                        "sink.unlabeled",
                        ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.MISSING_SINK_LABEL);
    }

    @Test
    void unknownLabelIsALimitation() {
        var result = evaluate(List.of(
                store("db.users", "NOT_A_DEFAULT_LABEL"),
                store("log.app", "PUBLIC"),
                flow("flow.unknown-to-log", "db.users", "log.app", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.UNKNOWN_SOURCE_LABEL);
    }

    @Test
    void danglingFlowEndpointIsALimitation() {
        var result = evaluate(List.of(
                store("db.users", "PII"),
                flow("flow.broken", "db.users", "does.not.exist", ArchitectureFlowDirection.UNIDIRECTIONAL)));

        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().getFirst().reason())
                .isEqualTo(DataClassificationFindingReason.DANGLING_FLOW_ENDPOINT);
    }

    @Test
    void nonFlowElementsAreNotEvaluated() {
        var result = evaluate(List.of(store("db.users", "PII"), store("log.app", "PUBLIC")));

        assertThat(result.evaluatedFlowCount()).isZero();
        assertThat(result.violations()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }
}

package com.keplerops.groundcontrol.unit.domain.threatenumeration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidate;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidateElementView;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRule;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatRulePackDefinition;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatEnumerationLimitationReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the pure static {@link ThreatEnumerationService#enumerate} method (GC-GRC-007).
 * All tests are plain-Java — no Spring context needed.
 */
class ThreatEnumerationServiceTest {

    private static final String SNAP = "snap-001";
    private static final String MODEL_VERSION = "architecture-model/v1";

    // ---- helpers ----

    private static ThreatRule alwaysRule(String ruleId, ArchitectureModelElementKind kind, StrideCategory stride) {
        return new ThreatRule(
                ruleId,
                "Test rule: " + ruleId,
                ThreatRuleCategory.STRIDE_BASELINE,
                stride,
                Set.of(kind),
                ThreatRuleMatchPredicate.ALWAYS,
                null,
                "Narrative for {{element}} ({{elementKind}}): {{strideCategory}}",
                "Test rationale");
    }

    private static ThreatRule predicateRule(
            String ruleId,
            ArchitectureModelElementKind kind,
            StrideCategory stride,
            ThreatRuleMatchPredicate predicate,
            ThreatRuleCategory cat) {
        return new ThreatRule(ruleId, "Rule " + ruleId, cat, stride, Set.of(kind), predicate, null, null, null);
    }

    private static ThreatRule metadataRule(String ruleId, ArchitectureModelElementKind kind, String tagKey) {
        return new ThreatRule(
                ruleId,
                "Metadata rule " + ruleId,
                ThreatRuleCategory.SECRET_HANDLING,
                StrideCategory.INFORMATION_DISCLOSURE,
                Set.of(kind),
                ThreatRuleMatchPredicate.HAS_METADATA_TAG,
                tagKey,
                null,
                null);
    }

    private static ThreatRulePackDefinition pack(ThreatRule... rules) {
        return new ThreatRulePackDefinition("test-pack", "1.0.0", "sha256:abc123", List.of(rules));
    }

    private static ThreatCandidateElementView component(String key) {
        return new ThreatCandidateElementView(
                key, ArchitectureModelElementKind.COMPONENT, null, null, null, null, Map.of());
    }

    private static ThreatCandidateElementView process(String key) {
        return new ThreatCandidateElementView(
                key, ArchitectureModelElementKind.PROCESS, null, null, null, null, Map.of());
    }

    private static ThreatCandidateElementView dataStore(String key, String dcKey) {
        return new ThreatCandidateElementView(
                key, ArchitectureModelElementKind.DATA_STORE, null, dcKey, null, null, Map.of());
    }

    private static ThreatCandidateElementView externalEntity(String key) {
        return new ThreatCandidateElementView(
                key, ArchitectureModelElementKind.EXTERNAL_ENTITY, null, null, null, null, Map.of());
    }

    private static ThreatCandidateElementView dataFlow(
            String key, String sourceKey, String targetKey, String trustBoundary) {
        return new ThreatCandidateElementView(
                key, ArchitectureModelElementKind.DATA_FLOW, trustBoundary, null, sourceKey, targetKey, Map.of());
    }

    private static ThreatCandidateElementView withTrustBoundary(
            String key, ArchitectureModelElementKind kind, String tb) {
        return new ThreatCandidateElementView(key, kind, tb, null, null, null, Map.of());
    }

    private static ThreatCandidateElementView withMetadata(
            String key, ArchitectureModelElementKind kind, Map<String, Object> metadata) {
        return new ThreatCandidateElementView(key, kind, null, null, null, null, metadata);
    }

    // ---- tests ----

    @Test
    void alwaysPredicateFiresForEveryMatchingKind() {
        var rule = alwaysRule("rule.1", ArchitectureModelElementKind.COMPONENT, StrideCategory.TAMPERING);
        var views = List.of(component("svc.auth"), component("svc.api"), process("proc.worker"));

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, views);

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).allMatch(c -> c.producingRuleId().equals("rule.1"));
        assertThat(result.candidates())
                .extracting(ThreatCandidate::elementStableKey)
                .containsExactlyInAnyOrder("svc.auth", "svc.api");
    }

    @Test
    void strideBaselineFiresForEachElementKindPerSpec() {
        // One rule per element kind
        var rules = List.of(
                alwaysRule("r.ext", ArchitectureModelElementKind.EXTERNAL_ENTITY, StrideCategory.SPOOFING),
                alwaysRule("r.proc", ArchitectureModelElementKind.PROCESS, StrideCategory.TAMPERING),
                alwaysRule("r.comp", ArchitectureModelElementKind.COMPONENT, StrideCategory.DENIAL_OF_SERVICE),
                alwaysRule("r.store", ArchitectureModelElementKind.DATA_STORE, StrideCategory.INFORMATION_DISCLOSURE),
                alwaysRule("r.flow", ArchitectureModelElementKind.DATA_FLOW, StrideCategory.TAMPERING),
                alwaysRule(
                        "r.boundary",
                        ArchitectureModelElementKind.TRUST_BOUNDARY,
                        StrideCategory.ELEVATION_OF_PRIVILEGE));
        var definition = new ThreatRulePackDefinition("test-pack", "1.0.0", "sha256:abc", rules);

        var views = List.of(
                externalEntity("ext.actor"),
                process("proc.api"),
                component("comp.auth"),
                dataStore("db.users", null),
                dataFlow("flow.api-db", "proc.api", "db.users", null),
                withTrustBoundary("boundary.net", ArchitectureModelElementKind.TRUST_BOUNDARY, "net-edge"));

        var result = ThreatEnumerationService.enumerate(definition, SNAP, MODEL_VERSION, views);

        assertThat(result.candidates()).hasSize(6);
        // Each rule fires exactly once for its target kind
        assertThat(result.candidates())
                .extracting(ThreatCandidate::producingRuleId)
                .containsExactlyInAnyOrder("r.ext", "r.proc", "r.comp", "r.store", "r.flow", "r.boundary");
    }

    @Test
    void crossesTrustBoundaryFiresWhenSourceAndTargetHaveDifferentBoundaries() {
        var rule = predicateRule(
                "boundary.tamper",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.TAMPERING,
                ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY,
                ThreatRuleCategory.UNTRUSTED_INPUT);

        var sourceView = withTrustBoundary("proc.internal", ArchitectureModelElementKind.PROCESS, "internal-zone");
        var targetView = withTrustBoundary("svc.external", ArchitectureModelElementKind.COMPONENT, "dmz-zone");
        var flowView = dataFlow("flow.int-to-ext", "proc.internal", "svc.external", null);

        var result = ThreatEnumerationService.enumerate(
                pack(rule), SNAP, MODEL_VERSION, List.of(sourceView, targetView, flowView));

        assertThat(result.candidates()).hasSize(1);
        var candidate = result.candidates().getFirst();
        assertThat(candidate.producingRuleId()).isEqualTo("boundary.tamper");
        assertThat(candidate.matchedFacts()).containsKey("predicate");
        assertThat(candidate.matchedFacts().get("predicate"))
                .isEqualTo(ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY.name());
        assertThat(candidate.matchedFacts()).containsEntry("sourceTrustBoundaryKey", "internal-zone");
        assertThat(candidate.matchedFacts()).containsEntry("targetTrustBoundaryKey", "dmz-zone");
    }

    @Test
    void crossesTrustBoundaryDoesNotFireWhenSameBoundary() {
        var rule = predicateRule(
                "boundary.tamper",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.TAMPERING,
                ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY,
                ThreatRuleCategory.UNTRUSTED_INPUT);

        var source = withTrustBoundary("svc.a", ArchitectureModelElementKind.COMPONENT, "internal");
        var target = withTrustBoundary("svc.b", ArchitectureModelElementKind.COMPONENT, "internal");
        var flow = dataFlow("flow.a-b", "svc.a", "svc.b", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(source, target, flow));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void sourceIsExternalFiresWhenFlowSourceIsExternalEntity() {
        var rule = predicateRule(
                "authn.source-external",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.SPOOFING,
                ThreatRuleMatchPredicate.SOURCE_IS_EXTERNAL,
                ThreatRuleCategory.AUTHN_AUTHZ);

        var ext = externalEntity("user.browser");
        var api = component("api.gateway");
        var flow = dataFlow("flow.browser-api", "user.browser", "api.gateway", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(ext, api, flow));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().matchedFacts())
                .containsEntry("sourceElementKind", ArchitectureModelElementKind.EXTERNAL_ENTITY.name());
    }

    @Test
    void sourceIsExternalDoesNotFireWhenSourceIsNotExternalEntity() {
        var rule = predicateRule(
                "authn.source-external",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.SPOOFING,
                ThreatRuleMatchPredicate.SOURCE_IS_EXTERNAL,
                ThreatRuleCategory.AUTHN_AUTHZ);

        var internal = component("svc.a");
        var api = component("api.gateway");
        var flow = dataFlow("flow.svc-api", "svc.a", "api.gateway", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(internal, api, flow));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void targetIsExternalFiresWhenFlowTargetIsExternalEntity() {
        var rule = predicateRule(
                "egress.target-external",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.INFORMATION_DISCLOSURE,
                ThreatRuleMatchPredicate.TARGET_IS_EXTERNAL,
                ThreatRuleCategory.DATA_EGRESS);

        var api = component("api.gateway");
        var ext = externalEntity("partner.api");
        var flow = dataFlow("flow.api-partner", "api.gateway", "partner.api", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(api, ext, flow));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().matchedFacts())
                .containsEntry("targetElementKind", ArchitectureModelElementKind.EXTERNAL_ENTITY.name());
    }

    @Test
    void hasMetadataTagFiresWhenKeyPresent() {
        var rule = metadataRule("secret.process", ArchitectureModelElementKind.PROCESS, "secret");
        var tagged = withMetadata("proc.vault", ArchitectureModelElementKind.PROCESS, Map.of("secret", "true"));
        var untagged = process("proc.api");

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(tagged, untagged));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().elementStableKey()).isEqualTo("proc.vault");
        assertThat(result.candidates().getFirst().matchedFacts()).containsEntry("metadataTagKey", "secret");
    }

    @Test
    void hasMetadataTagDoesNotFireWhenKeyAbsent() {
        var rule = metadataRule("secret.process", ArchitectureModelElementKind.PROCESS, "secret");
        var untagged = process("proc.api");

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(untagged));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void hasDataClassificationFiresWhenKeyPresent() {
        var rule = predicateRule(
                "dc.present",
                ArchitectureModelElementKind.DATA_STORE,
                StrideCategory.INFORMATION_DISCLOSURE,
                ThreatRuleMatchPredicate.HAS_DATA_CLASSIFICATION,
                ThreatRuleCategory.SECRET_HANDLING);
        var classified = dataStore("store.pii", "CONFIDENTIAL");

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(classified));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().matchedFacts())
                .containsEntry("dataClassificationKey", "CONFIDENTIAL");
    }

    @Test
    void hasDataClassificationDoesNotFireWhenKeyAbsent() {
        var rule = predicateRule(
                "dc.present",
                ArchitectureModelElementKind.DATA_STORE,
                StrideCategory.INFORMATION_DISCLOSURE,
                ThreatRuleMatchPredicate.HAS_DATA_CLASSIFICATION,
                ThreatRuleCategory.SECRET_HANDLING);
        var unclassified = dataStore("store.scratch", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(unclassified));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void hasTrustBoundaryFiresWhenKeyPresent() {
        var rule = predicateRule(
                "tb.present",
                ArchitectureModelElementKind.COMPONENT,
                StrideCategory.ELEVATION_OF_PRIVILEGE,
                ThreatRuleMatchPredicate.HAS_TRUST_BOUNDARY,
                ThreatRuleCategory.DEPLOYMENT_PIPELINE);
        var boundaryElement = withTrustBoundary("svc.edge", ArchitectureModelElementKind.COMPONENT, "internal-zone");

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(boundaryElement));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().matchedFacts()).containsEntry("trustBoundaryKey", "internal-zone");
    }

    @Test
    void hasTrustBoundaryDoesNotFireWhenKeyAbsent() {
        var rule = predicateRule(
                "tb.present",
                ArchitectureModelElementKind.COMPONENT,
                StrideCategory.ELEVATION_OF_PRIVILEGE,
                ThreatRuleMatchPredicate.HAS_TRUST_BOUNDARY,
                ThreatRuleCategory.DEPLOYMENT_PIPELINE);
        var noBoundary = withTrustBoundary("svc.core", ArchitectureModelElementKind.COMPONENT, null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(noBoundary));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void deterministicOrderSameInputsProduceSameOutput() {
        var rules = List.of(
                alwaysRule("r.b", ArchitectureModelElementKind.COMPONENT, StrideCategory.TAMPERING),
                alwaysRule("r.a", ArchitectureModelElementKind.COMPONENT, StrideCategory.SPOOFING));
        var definition = new ThreatRulePackDefinition("test-pack", "1.0.0", "sha256:xyz", rules);
        var views = List.of(component("svc.b"), component("svc.a"));

        var run1 = ThreatEnumerationService.enumerate(definition, SNAP, MODEL_VERSION, views);
        var run2 = ThreatEnumerationService.enumerate(definition, SNAP, MODEL_VERSION, views);

        assertThat(run1.candidates()).hasSize(4);
        for (int i = 0; i < run1.candidates().size(); i++) {
            var c1 = run1.candidates().get(i);
            var c2 = run2.candidates().get(i);
            assertThat(c1.elementStableKey()).isEqualTo(c2.elementStableKey());
            assertThat(c1.producingRuleId()).isEqualTo(c2.producingRuleId());
            assertThat(c1.strideCategory()).isEqualTo(c2.strideCategory());
        }
    }

    @Test
    void deterministicSortOrderIsElementKeyThenRuleIdThenStride() {
        var rules = List.of(
                alwaysRule("r.z", ArchitectureModelElementKind.COMPONENT, StrideCategory.TAMPERING),
                alwaysRule("r.a", ArchitectureModelElementKind.COMPONENT, StrideCategory.SPOOFING));
        var definition = new ThreatRulePackDefinition("test-pack", "1.0.0", "sha256:xyz", rules);
        var views = List.of(component("svc.b"), component("svc.a"));

        var result = ThreatEnumerationService.enumerate(definition, SNAP, MODEL_VERSION, views);

        // Sorted by elementStableKey first, then ruleId
        assertThat(result.candidates())
                .extracting(ThreatCandidate::elementStableKey)
                .containsExactly("svc.a", "svc.a", "svc.b", "svc.b");
        // Within svc.a: r.a before r.z
        assertThat(result.candidates().get(0).producingRuleId()).isEqualTo("r.a");
        assertThat(result.candidates().get(1).producingRuleId()).isEqualTo("r.z");
    }

    @Test
    void everyCandidateCarriesProducingRuleIdAndNonEmptyMatchedFacts() {
        var rule = alwaysRule("r.always", ArchitectureModelElementKind.COMPONENT, StrideCategory.TAMPERING);
        var views = List.of(component("svc.auth"));

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, views);

        assertThat(result.candidates()).hasSize(1);
        var candidate = result.candidates().getFirst();
        assertThat(candidate.producingRuleId()).isEqualTo("r.always");
        assertThat(candidate.matchedFacts()).isNotEmpty();
        assertThat(candidate.matchedFacts()).containsKey("predicate");
        assertThat(candidate.matchedFacts()).containsKey("elementKind");
    }

    @Test
    void missingStableKeyProducesMissingStableKeyLimitation() {
        var rule = alwaysRule("r.1", ArchitectureModelElementKind.COMPONENT, StrideCategory.TAMPERING);
        var blankKeyView = new ThreatCandidateElementView(
                "", ArchitectureModelElementKind.COMPONENT, null, null, null, null, Map.of());
        var nullKeyView = new ThreatCandidateElementView(
                null, ArchitectureModelElementKind.COMPONENT, null, null, null, null, Map.of());

        var result =
                ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(blankKeyView, nullKeyView));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.limitations()).hasSize(2);
        assertThat(result.limitations())
                .allMatch(l -> l.reason() == ThreatEnumerationLimitationReason.MISSING_STABLE_KEY);
    }

    @Test
    void danglingFlowEndpointProducesDanglingLimitation() {
        var rule = predicateRule(
                "boundary.tamper",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.TAMPERING,
                ThreatRuleMatchPredicate.CROSSES_TRUST_BOUNDARY,
                ThreatRuleCategory.UNTRUSTED_INPUT);

        // Flow references a target that doesn't exist
        var source = withTrustBoundary("svc.a", ArchitectureModelElementKind.COMPONENT, "zone-a");
        var flow = dataFlow("flow.a-missing", "svc.a", "does.not.exist", null);

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(source, flow));

        assertThat(result.limitations())
                .anyMatch(l -> l.reason() == ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT);
    }

    @Test
    void blankKeyElementDoesNotContaminateFlowEndpointResolution() {
        var rule = predicateRule(
                "authn.source-external",
                ArchitectureModelElementKind.DATA_FLOW,
                StrideCategory.SPOOFING,
                ThreatRuleMatchPredicate.SOURCE_IS_EXTERNAL,
                ThreatRuleCategory.AUTHN_AUTHZ);

        // Malformed snapshot: an external entity with a blank stable key, plus a flow whose source
        // references that blank key. The blank-key element is excluded by the MISSING_STABLE_KEY
        // guard, so it must not be addressable as a flow endpoint — the flow must surface a
        // dangling-endpoint limitation rather than a false external-source candidate.
        var blankExternal = new ThreatCandidateElementView(
                "", ArchitectureModelElementKind.EXTERNAL_ENTITY, null, null, null, null, Map.of());
        var api = component("api.gateway");
        var flow = dataFlow("flow.blank-source", "", "api.gateway", null);

        var result =
                ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, List.of(blankExternal, api, flow));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.limitations())
                .anyMatch(l -> l.reason() == ThreatEnumerationLimitationReason.MISSING_STABLE_KEY);
        assertThat(result.limitations())
                .anyMatch(l -> l.reason() == ThreatEnumerationLimitationReason.DANGLING_FLOW_ENDPOINT);
    }

    @Test
    void noRulesProducesEmptyCandidates() {
        var definition = new ThreatRulePackDefinition("empty-pack", "1.0.0", "sha256:000", List.of());
        var views = List.of(component("svc.a"), process("proc.b"));

        var result = ThreatEnumerationService.enumerate(definition, SNAP, MODEL_VERSION, views);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void schemaVersionAndPackMetadataArePropagated() {
        var definition = new ThreatRulePackDefinition("my-pack", "2.1.0", "sha256:checksum", List.of());

        var result = ThreatEnumerationService.enumerate(definition, "snap-abc", "model/v99", List.of());

        assertThat(result.schemaVersion()).isEqualTo(ThreatEnumerationService.SCHEMA_VERSION);
        assertThat(result.packId()).isEqualTo("my-pack");
        assertThat(result.resolvedVersion()).isEqualTo("2.1.0");
        assertThat(result.checksum()).isEqualTo("sha256:checksum");
        assertThat(result.snapshotId()).isEqualTo("snap-abc");
        assertThat(result.modelVersion()).isEqualTo("model/v99");
    }

    @Test
    void narrativeSkeletonIsInterpolated() {
        var rule = new ThreatRule(
                "r.narrative",
                "Narrative test",
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.TAMPERING,
                Set.of(ArchitectureModelElementKind.COMPONENT),
                ThreatRuleMatchPredicate.ALWAYS,
                null,
                "Element {{element}} of kind {{elementKind}} faces {{strideCategory}} threat.",
                null);
        var views = List.of(component("svc.auth"));

        var result = ThreatEnumerationService.enumerate(pack(rule), SNAP, MODEL_VERSION, views);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().narrative())
                .isEqualTo("Element svc.auth of kind COMPONENT faces TAMPERING threat.");
    }
}

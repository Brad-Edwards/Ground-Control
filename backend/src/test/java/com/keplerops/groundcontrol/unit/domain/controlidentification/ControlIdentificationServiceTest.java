package com.keplerops.groundcontrol.unit.domain.controlidentification;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.controlidentification.service.AvailableControl;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationResult;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingRule;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingRuleSet;
import com.keplerops.groundcontrol.domain.controlidentification.service.MappableThreat;
import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import com.keplerops.groundcontrol.domain.controlidentification.state.ControlIdentificationGapReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exercises the pure static {@link ControlIdentificationService#identify} method (GC-GRC-008). All
 * tests are plain-Java — no Spring context needed.
 */
class ControlIdentificationServiceTest {

    private static final UUID C_AC = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID C_IA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID C_PROJ = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

    // ---- helpers ----

    private static ControlMappingRule spoofingRule() {
        return new ControlMappingRule(
                "rule-spoofing",
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.SPOOFING,
                "identity-and-authentication",
                "Identity and authentication",
                Set.of("IA", "AC"),
                "Default IA guidance",
                "Spoofing maps to identity.");
    }

    private static ControlMappingRule cryptoRule() {
        return new ControlMappingRule(
                "rule-crypto",
                ThreatRuleCategory.CRYPTO,
                null,
                "cryptographic-protection",
                "Cryptographic protection",
                Set.of("SC"),
                "Default crypto guidance",
                "Crypto maps to SC.");
    }

    private static ControlMappingRuleSet ruleSet(ControlMappingRule... rules) {
        return new ControlMappingRuleSet("test-ruleset", "1.0.0", List.of(rules));
    }

    private static AvailableControl packControl(
            UUID id, String uid, String guidance, Set<String> frameworkIds, boolean active) {
        return new AvailableControl(
                id,
                uid,
                "Title " + uid,
                "Objective " + uid,
                null,
                "pack:nist:1.0",
                ControlCandidateSource.CONTROL_PACK,
                "nist",
                "1.0",
                "sha256:pack",
                guidance,
                frameworkIds,
                active);
    }

    private static MappableThreat spoofingThreat(String ref) {
        return new MappableThreat(ref, ThreatRuleCategory.STRIDE_BASELINE, StrideCategory.SPOOFING);
    }

    // ---- tests ----

    @Test
    void mapsThreatToCandidateWhenFrameworkFamilyMatches() {
        var control = packControl(C_AC, "AC-3", "Enforce access", Set.of("NIST_800_53:AC-3"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        assertThat(result.candidates()).hasSize(1);
        var candidate = result.candidates().get(0);
        assertThat(candidate.controlId()).isEqualTo(C_AC);
        assertThat(candidate.threatRef()).isEqualTo("t1");
        assertThat(candidate.producingRuleId()).isEqualTo("rule-spoofing");
        assertThat(candidate.objectiveKey()).isEqualTo("identity-and-authentication");
        assertThat(result.gaps()).isEmpty();
    }

    @Test
    void candidateCarriesControlGuidanceAndProvenance() {
        var control = packControl(C_AC, "AC-3", "Control-specific guidance", Set.of("NIST_800_53:AC-3"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        var candidate = result.candidates().get(0);
        // Control-specific guidance is preferred over the rule default.
        assertThat(candidate.implementationGuidance()).isEqualTo("Control-specific guidance");
        assertThat(candidate.source()).isEqualTo(ControlCandidateSource.CONTROL_PACK);
        assertThat(candidate.packId()).isEqualTo("nist");
        assertThat(candidate.packVersion()).isEqualTo("1.0");
        assertThat(candidate.packChecksum()).isEqualTo("sha256:pack");
        assertThat(candidate.ruleSetId()).isEqualTo("test-ruleset");
        assertThat(candidate.ruleSetVersion()).isEqualTo("1.0.0");
        assertThat(candidate.matchedFacts())
                .containsEntry("objective", "identity-and-authentication")
                .containsEntry("candidateSource", "CONTROL_PACK")
                .containsEntry("matchedSelectors", "AC")
                .containsEntry("matchedFrameworkIds", "NIST_800_53:AC-3");
    }

    @Test
    void fallsBackToRuleDefaultGuidanceWhenControlHasNone() {
        var control = packControl(C_AC, "AC-3", null, Set.of("AC-3"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        assertThat(result.candidates().get(0).implementationGuidance()).isEqualTo("Default IA guidance");
    }

    @Test
    void surfacesGapWhenRuleFiresButNoControlMatches() {
        var control = packControl(C_IA, "SC-8", "irrelevant", Set.of("SC-8"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.gaps()).hasSize(1);
        var gap = result.gaps().get(0);
        assertThat(gap.reason()).isEqualTo(ControlIdentificationGapReason.NO_MATCHING_CONTROL);
        assertThat(gap.objectiveKey()).isEqualTo("identity-and-authentication");
        assertThat(gap.threatRef()).isEqualTo("t1");
    }

    @Test
    void surfacesNoControlsAvailableGapWhenNoControlsExist() {
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).reason()).isEqualTo(ControlIdentificationGapReason.NO_CONTROLS_AVAILABLE);
    }

    @Test
    void excludesInactiveControls() {
        var inactive = packControl(C_AC, "AC-3", "guidance", Set.of("AC-3"), false);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(inactive));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).reason()).isEqualTo(ControlIdentificationGapReason.NO_MATCHING_CONTROL);
    }

    @Test
    void strideSpecificRuleDoesNotFireForOtherStride() {
        var control = packControl(C_AC, "AC-3", "guidance", Set.of("AC-3"), true);
        var tamperingThreat = new MappableThreat("t1", ThreatRuleCategory.STRIDE_BASELINE, StrideCategory.TAMPERING);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(tamperingThreat), List.of(control));

        // A SPOOFING rule must not fire for a TAMPERING threat — no candidate and no gap.
        assertThat(result.candidates()).isEmpty();
        assertThat(result.gaps()).isEmpty();
    }

    @Test
    void categoryRuleFiresRegardlessOfStride() {
        var control = packControl(C_AC, "SC-13", "guidance", Set.of("SC-13"), true);
        var cryptoThreat = new MappableThreat("t1", ThreatRuleCategory.CRYPTO, null);
        var result =
                ControlIdentificationService.identify(ruleSet(cryptoRule()), List.of(cryptoThreat), List.of(control));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).objectiveKey()).isEqualTo("cryptographic-protection");
    }

    @Test
    void projectControlIsAnEligibleCandidateSource() {
        var projectControl = new AvailableControl(
                C_PROJ,
                "PROJ-1",
                "Project control",
                null,
                "AC",
                "manual",
                ControlCandidateSource.PROJECT_CONTROL,
                null,
                null,
                null,
                null,
                Set.of("AC"),
                true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(projectControl));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).source()).isEqualTo(ControlCandidateSource.PROJECT_CONTROL);
    }

    @Test
    void deduplicatesControlMatchedByMultipleSelectors() {
        // Control carries both AC and IA identifiers; the spoofing rule selects on both — one candidate.
        var control = packControl(C_AC, "AC-3", "guidance", Set.of("AC-3", "IA-2"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).matchedFacts().get("matchedSelectors"))
                .isEqualTo("AC,IA");
    }

    @Test
    void producesByteStableOrdering() {
        var acControl = packControl(C_AC, "AC-3", "g", Set.of("AC-3"), true);
        var iaControl = packControl(C_IA, "IA-2", "g", Set.of("IA-2"), true);
        var threats = List.of(spoofingThreat("t2"), spoofingThreat("t1"));

        ControlIdentificationResult first =
                ControlIdentificationService.identify(ruleSet(spoofingRule()), threats, List.of(iaControl, acControl));
        ControlIdentificationResult second =
                ControlIdentificationService.identify(ruleSet(spoofingRule()), threats, List.of(acControl, iaControl));

        assertThat(candidateKeys(first)).isEqualTo(candidateKeys(second));
        // Ordered by (threatRef, producingRuleId, controlUid).
        assertThat(candidateKeys(first)).containsExactly("t1|AC-3", "t1|IA-2", "t2|AC-3", "t2|IA-2");
    }

    private static List<String> candidateKeys(ControlIdentificationResult result) {
        return result.candidates().stream()
                .map(c -> c.threatRef() + "|" + c.controlUid())
                .toList();
    }

    @Test
    void resultReportsRuleSetProvenance() {
        var control = packControl(C_AC, "AC-3", "g", Set.of("AC-3"), true);
        var result = ControlIdentificationService.identify(
                ruleSet(spoofingRule()), List.of(spoofingThreat("t1")), List.of(control));

        assertThat(result.schemaVersion()).isEqualTo(ControlIdentificationService.SCHEMA_VERSION);
        assertThat(result.ruleSetId()).isEqualTo("test-ruleset");
        assertThat(result.ruleSetVersion()).isEqualTo("1.0.0");
    }
}

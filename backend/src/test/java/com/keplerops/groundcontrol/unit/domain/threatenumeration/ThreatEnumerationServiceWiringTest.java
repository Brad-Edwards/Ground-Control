package com.keplerops.groundcontrol.unit.domain.threatenumeration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelElementState;
import com.keplerops.groundcontrol.domain.architecturemodel.model.ArchitectureModelSnapshot;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelElementStateRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.repository.ArchitectureModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredThreatRule;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerification;
import com.keplerops.groundcontrol.domain.packregistry.service.PackIntegrityVerifier;
import com.keplerops.groundcontrol.domain.packregistry.service.PackResolver;
import com.keplerops.groundcontrol.domain.packregistry.service.ResolvedPack;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatEnumerationLimitationReason;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleMatchPredicate;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises repository wiring of {@link ThreatEnumerationService}: pack resolution, snapshot
 * selection, element-state projection, and the empty / not-found edges (GC-GRC-007). Pure
 * evaluation logic is covered separately by {@link ThreatEnumerationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ThreatEnumerationServiceWiringTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111100");
    private static final UUID SNAPSHOT_ID = UUID.fromString("33333333-3333-3333-3333-333333333300");
    private static final String PACK_ID = "stride-baseline";
    private static final String VERSION = "1.0.0";
    private static final String CHECKSUM = "sha256:abc";

    @Mock
    private PackResolver packResolver;

    @Mock
    private PackIntegrityVerifier packIntegrityVerifier;

    @Mock
    private ArchitectureModelSnapshotRepository snapshotRepository;

    @Mock
    private ArchitectureModelElementStateRepository stateRepository;

    @InjectMocks
    private ThreatEnumerationService service;

    private RegisteredThreatRule alwaysComponentRule() {
        return new RegisteredThreatRule(
                "stride.component.tampering",
                "Component: Tampering",
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.TAMPERING,
                Set.of(ArchitectureModelElementKind.COMPONENT),
                ThreatRuleMatchPredicate.ALWAYS,
                null,
                "Component {{element}} may be tampered with.",
                "Rationale");
    }

    private PackRegistryEntry entryWithRules(List<RegisteredThreatRule> rules) {
        var entry = mock(PackRegistryEntry.class);
        when(entry.getThreatRuleEntries()).thenReturn(rules);
        return entry;
    }

    private ResolvedPack resolvedPack(PackRegistryEntry entry) {
        return new ResolvedPack(entry, VERSION, null, CHECKSUM, List.of());
    }

    private PackIntegrityVerification verification() {
        return new PackIntegrityVerification(CHECKSUM, true, null, null);
    }

    private void stubPackResolution(ResolvedPack resolved) {
        when(packResolver.resolve(PROJECT_ID, PACK_ID, null)).thenReturn(resolved);
        when(packIntegrityVerifier.verify(resolved)).thenReturn(verification());
    }

    private ArchitectureModelElementState componentState(String stableKey) {
        var state = mock(ArchitectureModelElementState.class);
        when(state.getStableKey()).thenReturn(stableKey);
        when(state.getElementKind()).thenReturn(ArchitectureModelElementKind.COMPONENT);
        when(state.getTrustBoundaryKey()).thenReturn(null);
        when(state.getDataClassificationKey()).thenReturn(null);
        when(state.getFlowSourceStableKey()).thenReturn(null);
        when(state.getFlowTargetStableKey()).thenReturn(null);
        when(state.getMetadata()).thenReturn(Map.of());
        return state;
    }

    @Test
    void enumerateLatestReturnsNoSnapshotLimitationWhenNoSnapshot() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));
        when(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

        var result = service.enumerateLatest(PROJECT_ID, PACK_ID, null);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.limitations()).hasSize(1);
        assertThat(result.limitations().getFirst().reason()).isEqualTo(ThreatEnumerationLimitationReason.NO_SNAPSHOT);
    }

    @Test
    void enumerateLatestEvaluatesMostRecentSnapshot() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));
        var snapshot = mock(ArchitectureModelSnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getModelVersion()).thenReturn("model/v1");
        var states = List.of(componentState("svc.auth"), componentState("svc.api"));
        when(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(snapshot));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_ID)).thenReturn(states);

        var result = service.enumerateLatest(PROJECT_ID, PACK_ID, null);

        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID.toString());
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.limitations()).isEmpty();
    }

    @Test
    void enumerateSnapshotResolvesByIdScopedToProject() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));
        var snapshot = mock(ArchitectureModelSnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getModelVersion()).thenReturn("model/v1");
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_ID, PROJECT_ID)).thenReturn(Optional.of(snapshot));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_ID)).thenReturn(List.of());

        var result = service.enumerateSnapshot(PROJECT_ID, SNAPSHOT_ID, PACK_ID, null);

        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID.toString());
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void enumerateSnapshotThrowsWhenSnapshotNotFound() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));
        when(snapshotRepository.findByIdAndProjectId(SNAPSHOT_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.enumerateSnapshot(PROJECT_ID, SNAPSHOT_ID, PACK_ID, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolvePackDefinitionThrowsNotFoundWhenPackHasNoRules() {
        var entry = entryWithRules(List.of());
        var resolved = resolvedPack(entry);
        when(packResolver.resolve(PROJECT_ID, "empty-pack", null)).thenReturn(resolved);
        when(packIntegrityVerifier.verify(resolved)).thenReturn(verification());

        assertThatThrownBy(() -> service.resolvePackDefinition(PROJECT_ID, "empty-pack", null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void resolvePackDefinitionReturnsDefinitionWithPackMetadata() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));

        var result = service.resolvePackDefinition(PROJECT_ID, PACK_ID, null);

        assertThat(result.packId()).isEqualTo(PACK_ID);
        assertThat(result.resolvedVersion()).isEqualTo(VERSION);
        assertThat(result.checksum()).isEqualTo(CHECKSUM);
        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().getFirst().ruleId()).isEqualTo("stride.component.tampering");
    }

    @Test
    void samePackAndSnapshotProduceIdenticalCandidateOrder() {
        stubPackResolution(resolvedPack(entryWithRules(List.of(alwaysComponentRule()))));
        var snapshot = mock(ArchitectureModelSnapshot.class);
        when(snapshot.getId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getModelVersion()).thenReturn("model/v1");
        var states = List.of(componentState("svc.b"), componentState("svc.a"));
        when(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(snapshot));
        when(stateRepository.findBySnapshotIdOrderByStableKey(SNAPSHOT_ID)).thenReturn(states);

        var run1 = service.enumerateLatest(PROJECT_ID, PACK_ID, null);
        var run2 = service.enumerateLatest(PROJECT_ID, PACK_ID, null);

        assertThat(run1.candidates()).hasSize(run2.candidates().size());
        for (int i = 0; i < run1.candidates().size(); i++) {
            assertThat(run1.candidates().get(i).elementStableKey())
                    .isEqualTo(run2.candidates().get(i).elementStableKey());
            assertThat(run1.candidates().get(i).producingRuleId())
                    .isEqualTo(run2.candidates().get(i).producingRuleId());
        }
    }
}

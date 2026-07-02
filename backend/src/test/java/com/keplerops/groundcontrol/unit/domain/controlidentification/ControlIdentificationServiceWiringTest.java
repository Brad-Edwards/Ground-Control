package com.keplerops.groundcontrol.unit.domain.controlidentification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.architecturemodel.service.ArchitectureModelElementKind;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import com.keplerops.groundcontrol.domain.controlpacks.model.ControlPack;
import com.keplerops.groundcontrol.domain.controlpacks.model.ControlPackEntry;
import com.keplerops.groundcontrol.domain.controlpacks.repository.ControlPackEntryRepository;
import com.keplerops.groundcontrol.domain.controlpacks.state.ControlPackEntryStatus;
import com.keplerops.groundcontrol.domain.controlpacks.state.ControlPackLifecycleState;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatCandidate;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationResult;
import com.keplerops.groundcontrol.domain.threatenumeration.service.ThreatEnumerationService;
import com.keplerops.groundcontrol.domain.threatenumeration.state.ThreatRuleCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Repository wiring of {@link ControlIdentificationService} (GC-GRC-008): available-control assembly
 * from catalog controls + pack entries, and the end-to-end enumerate→map wrapper. Pure mapping logic is
 * covered by {@link ControlIdentificationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ControlIdentificationServiceWiringTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111100");
    private static final UUID PACK_CONTROL_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PROJECT_CONTROL_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Mock
    private ThreatEnumerationService threatEnumerationService;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlPackEntryRepository controlPackEntryRepository;

    @InjectMocks
    private ControlIdentificationService service;

    private static Control packBackedControl() {
        var control = mock(Control.class);
        when(control.getId()).thenReturn(PACK_CONTROL_ID);
        when(control.getUid()).thenReturn("AC-3");
        when(control.getTitle()).thenReturn("Access Enforcement");
        when(control.getObjective()).thenReturn("Enforce access");
        when(control.getCategory()).thenReturn(null);
        when(control.getSource()).thenReturn("pack:nist:1.0");
        return control;
    }

    private static ControlPackEntry packEntry() {
        var pack = mock(ControlPack.class);
        when(pack.getPackId()).thenReturn("nist");
        when(pack.getVersion()).thenReturn("1.0");
        when(pack.getChecksum()).thenReturn("sha256:pack");
        when(pack.getLifecycleState()).thenReturn(ControlPackLifecycleState.INSTALLED);
        var entry = mock(ControlPackEntry.class);
        // getEntryUid orders multi-entry controls; single-entry streams skip the comparator.
        lenient().when(entry.getEntryUid()).thenReturn("AC-3");
        when(entry.getControlPack()).thenReturn(pack);
        when(entry.getEntryStatus()).thenReturn(ControlPackEntryStatus.ACTIVE);
        when(entry.getImplementationGuidance()).thenReturn("Configure RBAC");
        when(entry.getFrameworkMappings())
                .thenReturn(List.of(Map.of("framework", "NIST_800_53", "identifier", "AC-3")));
        return entry;
    }

    @Test
    void loadAvailableControlsAssemblesPackProvenanceAndIdentifiers() {
        var mockControl = packBackedControl();
        var mockEntry = packEntry();
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(mockControl));
        when(controlPackEntryRepository.findByControlId(PACK_CONTROL_ID)).thenReturn(List.of(mockEntry));

        var available = service.loadAvailableControls(PROJECT_ID);

        assertThat(available).hasSize(1);
        var control = available.get(0);
        assertThat(control.sourceKind()).isEqualTo(ControlCandidateSource.CONTROL_PACK);
        assertThat(control.packId()).isEqualTo("nist");
        assertThat(control.packChecksum()).isEqualTo("sha256:pack");
        assertThat(control.implementationGuidance()).isEqualTo("Configure RBAC");
        assertThat(control.frameworkIdentifiers()).contains("AC-3", "NIST_800_53:AC-3");
        assertThat(control.active()).isTrue();
    }

    @Test
    void loadAvailableControlsTreatsControlWithoutPackEntryAsProjectControl() {
        var projectControl = mock(Control.class);
        when(projectControl.getId()).thenReturn(PROJECT_CONTROL_ID);
        when(projectControl.getUid()).thenReturn("PROJ-1");
        when(projectControl.getTitle()).thenReturn("Custom control");
        when(projectControl.getObjective()).thenReturn(null);
        when(projectControl.getCategory()).thenReturn("SC");
        when(projectControl.getSource()).thenReturn("manual");
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(projectControl));
        when(controlPackEntryRepository.findByControlId(PROJECT_CONTROL_ID)).thenReturn(List.of());

        var available = service.loadAvailableControls(PROJECT_ID);

        assertThat(available).hasSize(1);
        assertThat(available.get(0).sourceKind()).isEqualTo(ControlCandidateSource.PROJECT_CONTROL);
        assertThat(available.get(0).frameworkIdentifiers()).containsExactly("SC");
        assertThat(available.get(0).packId()).isNull();
    }

    @Test
    void identifyForLatestSnapshotEnumeratesThenMaps() {
        var candidate = new ThreatCandidate(
                "stride-spoofing",
                ThreatRuleCategory.STRIDE_BASELINE,
                StrideCategory.SPOOFING,
                "svc-auth",
                ArchitectureModelElementKind.PROCESS,
                Map.of(),
                "narrative");
        var enumeration = new ThreatEnumerationResult(
                ThreatEnumerationService.SCHEMA_VERSION,
                "stride-baseline",
                "1.0.0",
                "sha256:threat",
                "snap-1",
                "architecture-model/v1",
                List.of(candidate),
                List.of());
        var mockControl = packBackedControl();
        var mockEntry = packEntry();
        when(threatEnumerationService.enumerateLatest(PROJECT_ID, "stride-baseline", null))
                .thenReturn(enumeration);
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(mockControl));
        when(controlPackEntryRepository.findByControlId(PACK_CONTROL_ID)).thenReturn(List.of(mockEntry));

        var result = service.identifyForLatestSnapshot(PROJECT_ID, "stride-baseline", null);

        assertThat(result.ruleSetId()).isNotBlank();
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates())
                .anySatisfy(c -> assertThat(c.controlUid()).isEqualTo("AC-3"));
        assertThat(result.candidates().get(0).threatRef()).isEqualTo("stride-spoofing@svc-auth");
    }

    @Test
    void loadAvailableControlsDerivesMatchingAndProvenanceFromEligibleEntriesOnly() {
        // The control has an ACTIVE entry (AC-3) and a DEPRECATED entry (SC-8). Only the eligible
        // entry may contribute framework identifiers, guidance, and pack provenance — the deprecated
        // entry must not leak a false selector even though it exists on the control.
        var deprecatedEntry = mock(ControlPackEntry.class);
        when(deprecatedEntry.getEntryStatus()).thenReturn(ControlPackEntryStatus.DEPRECATED);

        var activePack = mock(ControlPack.class);
        when(activePack.getPackId()).thenReturn("nist");
        when(activePack.getVersion()).thenReturn("1.0");
        when(activePack.getChecksum()).thenReturn("sha256:active");
        when(activePack.getLifecycleState()).thenReturn(ControlPackLifecycleState.INSTALLED);
        var activeEntry = mock(ControlPackEntry.class);
        lenient().when(activeEntry.getEntryUid()).thenReturn("AC-3");
        when(activeEntry.getEntryStatus()).thenReturn(ControlPackEntryStatus.ACTIVE);
        when(activeEntry.getControlPack()).thenReturn(activePack);
        when(activeEntry.getImplementationGuidance()).thenReturn("Active guidance");
        when(activeEntry.getFrameworkMappings()).thenReturn(List.of(Map.of("identifier", "AC-3")));

        var control = packBackedControl();
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));
        when(controlPackEntryRepository.findByControlId(PACK_CONTROL_ID))
                .thenReturn(List.of(deprecatedEntry, activeEntry));

        var available = service.loadAvailableControls(PROJECT_ID);

        assertThat(available).hasSize(1);
        var ac = available.get(0);
        assertThat(ac.active()).isTrue();
        assertThat(ac.frameworkIdentifiers()).contains("AC-3").doesNotContain("SC-8");
        assertThat(ac.implementationGuidance()).isEqualTo("Active guidance");
        assertThat(ac.packChecksum()).isEqualTo("sha256:active");
    }

    @Test
    void loadAvailableControlsMarksControlInactiveWhenNoEligibleEntry() {
        var pack = mock(ControlPack.class);
        when(pack.getPackId()).thenReturn("nist");
        when(pack.getVersion()).thenReturn("1.0");
        when(pack.getChecksum()).thenReturn("sha256:old");
        var entry = mock(ControlPackEntry.class);
        lenient().when(entry.getEntryUid()).thenReturn("SC-8");
        when(entry.getEntryStatus()).thenReturn(ControlPackEntryStatus.DEPRECATED);
        when(entry.getControlPack()).thenReturn(pack);
        when(entry.getImplementationGuidance()).thenReturn("Stale guidance");
        when(entry.getFrameworkMappings()).thenReturn(List.of(Map.of("identifier", "SC-8")));

        var control = packBackedControl();
        when(controlRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of(control));
        when(controlPackEntryRepository.findByControlId(PACK_CONTROL_ID)).thenReturn(List.of(entry));

        var available = service.loadAvailableControls(PROJECT_ID);

        assertThat(available).hasSize(1);
        // No eligible entry -> the control is inactive and the engine will never match it.
        assertThat(available.get(0).active()).isFalse();
    }
}

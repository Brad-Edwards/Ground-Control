package com.keplerops.groundcontrol.unit.domain.controlidentification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.controlidentification.service.AvailableControl;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlIdentificationService;
import com.keplerops.groundcontrol.domain.controlidentification.service.ControlMappingConfirmationService;
import com.keplerops.groundcontrol.domain.controlidentification.state.ControlCandidateSource;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.riskcontrol.model.RiskControlMapping;
import com.keplerops.groundcontrol.domain.riskcontrol.repository.RiskControlMappingRepository;
import com.keplerops.groundcontrol.domain.riskcontrol.service.CreateRiskControlMappingCommand;
import com.keplerops.groundcontrol.domain.riskcontrol.service.RiskControlMappingService;
import com.keplerops.groundcontrol.domain.riskcontrol.state.MappingControlRole;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.service.CreateThreatModelLinkCommand;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelLinkService;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wiring of {@link ControlMappingConfirmationService} (GC-GRC-008 clause c): confirmation records both
 * canonical mapping aggregates, is idempotent, and the coverage query unions both edges.
 */
@ExtendWith(MockitoExtension.class)
class ControlMappingConfirmationServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111100");
    private static final UUID THREAT_ID = UUID.fromString("22222222-2222-2222-2222-222222222200");
    private static final UUID CONTROL_ID = UUID.fromString("33333333-3333-3333-3333-333333333300");
    private static final UUID MAPPING_ID = UUID.fromString("44444444-4444-4444-4444-444444444400");
    private static final UUID LINK_ID = UUID.fromString("55555555-5555-5555-5555-555555555500");

    @Mock
    private ControlIdentificationService identificationService;

    @Mock
    private RiskControlMappingService riskControlMappingService;

    @Mock
    private RiskControlMappingRepository riskControlMappingRepository;

    @Mock
    private ThreatModelLinkService threatModelLinkService;

    @Mock
    private ThreatModelLinkRepository threatModelLinkRepository;

    @Mock
    private ThreatModelRepository threatModelRepository;

    @Mock
    private ControlRepository controlRepository;

    @InjectMocks
    private ControlMappingConfirmationService service;

    private void stubThreatAndControl() {
        var threat = mock(ThreatModel.class);
        when(threat.getStride()).thenReturn(StrideCategory.SPOOFING);
        when(threat.getUid()).thenReturn("GC-TM-99");
        when(threatModelRepository.findByIdAndProjectId(THREAT_ID, PROJECT_ID)).thenReturn(Optional.of(threat));
        var control = mock(Control.class);
        // getTitle is only read when a new link is created; idempotent paths skip it.
        lenient().when(control.getTitle()).thenReturn("Access Enforcement");
        when(control.getUid()).thenReturn("AC-3");
        when(controlRepository.findByIdAndProjectId(CONTROL_ID, PROJECT_ID)).thenReturn(Optional.of(control));
        // The engine (real static identify) must select CONTROL_ID for a SPOOFING threat (IA/AC family),
        // so an AC-3 available control makes CONTROL_ID a legitimate candidate.
        when(identificationService.loadAvailableControls(PROJECT_ID))
                .thenReturn(List.of(availableControl(CONTROL_ID, "AC-3", Set.of("AC-3"))));
    }

    private static AvailableControl availableControl(UUID id, String uid, Set<String> frameworkIds) {
        return new AvailableControl(
                id,
                uid,
                "Title " + uid,
                null,
                null,
                "pack:nist:1.0",
                ControlCandidateSource.CONTROL_PACK,
                "nist",
                "1.0",
                "sha256:pack",
                "guidance",
                frameworkIds,
                true);
    }

    @Test
    void confirmRecordsBothCanonicalAggregates() {
        stubThreatAndControl();
        when(riskControlMappingRepository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                        CONTROL_ID, THREAT_ID, null))
                .thenReturn(false);
        var mapping = mock(RiskControlMapping.class);
        when(mapping.getId()).thenReturn(MAPPING_ID);
        when(riskControlMappingService.create(any(CreateRiskControlMappingCommand.class)))
                .thenReturn(mapping);
        when(threatModelLinkRepository.existsByThreatModelIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        THREAT_ID, ThreatModelLinkTargetType.CONTROL, CONTROL_ID, ThreatModelLinkType.MITIGATED_BY))
                .thenReturn(false);
        var link = mock(ThreatModelLink.class);
        when(link.getId()).thenReturn(LINK_ID);
        when(threatModelLinkService.create(eq(PROJECT_ID), eq(THREAT_ID), any(CreateThreatModelLinkCommand.class)))
                .thenReturn(link);

        var result =
                service.confirm(PROJECT_ID, THREAT_ID, CONTROL_ID, MappingControlRole.PREVENTIVE, "objective", "scope");

        assertThat(result.riskControlMappingId()).isEqualTo(MAPPING_ID);
        assertThat(result.threatModelLinkId()).isEqualTo(LINK_ID);
        assertThat(result.mappingCreated()).isTrue();
        assertThat(result.linkCreated()).isTrue();
    }

    @Test
    void confirmIsIdempotentWhenBothEdgesExist() {
        stubThreatAndControl();
        when(riskControlMappingRepository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                        CONTROL_ID, THREAT_ID, null))
                .thenReturn(true);
        var existingMapping = mock(RiskControlMapping.class);
        var existingControl = mock(Control.class);
        when(existingControl.getId()).thenReturn(CONTROL_ID);
        when(existingMapping.getControl()).thenReturn(existingControl);
        when(existingMapping.getId()).thenReturn(MAPPING_ID);
        when(riskControlMappingRepository.findByProjectIdAndThreatModelId(PROJECT_ID, THREAT_ID))
                .thenReturn(List.of(existingMapping));

        when(threatModelLinkRepository.existsByThreatModelIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        THREAT_ID, ThreatModelLinkTargetType.CONTROL, CONTROL_ID, ThreatModelLinkType.MITIGATED_BY))
                .thenReturn(true);
        var existingLink = mock(ThreatModelLink.class);
        when(existingLink.getLinkType()).thenReturn(ThreatModelLinkType.MITIGATED_BY);
        when(existingLink.getTargetEntityId()).thenReturn(CONTROL_ID);
        when(existingLink.getId()).thenReturn(LINK_ID);
        when(threatModelLinkRepository.findByThreatModelIdAndTargetType(THREAT_ID, ThreatModelLinkTargetType.CONTROL))
                .thenReturn(List.of(existingLink));

        var result = service.confirm(PROJECT_ID, THREAT_ID, CONTROL_ID, null, null, null);

        assertThat(result.riskControlMappingId()).isEqualTo(MAPPING_ID);
        assertThat(result.threatModelLinkId()).isEqualTo(LINK_ID);
        assertThat(result.mappingCreated()).isFalse();
        assertThat(result.linkCreated()).isFalse();
        verify(riskControlMappingService, never()).create(any());
        verify(threatModelLinkService, never()).create(any(), any(), any());
    }

    @Test
    void coverageUnionsBothEdges() {
        UUID controlViaMapping = UUID.fromString("66666666-6666-6666-6666-666666666600");
        UUID controlViaLink = UUID.fromString("77777777-7777-7777-7777-777777777700");
        when(threatModelRepository.existsByIdAndProjectId(THREAT_ID, PROJECT_ID))
                .thenReturn(true);

        var mappedControl = mock(Control.class);
        when(mappedControl.getId()).thenReturn(controlViaMapping);
        var mapping = mock(RiskControlMapping.class);
        when(mapping.getControl()).thenReturn(mappedControl);
        when(riskControlMappingRepository.findByProjectIdAndThreatModelId(PROJECT_ID, THREAT_ID))
                .thenReturn(List.of(mapping));

        var mitigatedLink = mock(ThreatModelLink.class);
        when(mitigatedLink.getLinkType()).thenReturn(ThreatModelLinkType.MITIGATED_BY);
        when(mitigatedLink.getTargetEntityId()).thenReturn(controlViaLink);
        var otherLink = mock(ThreatModelLink.class);
        when(otherLink.getLinkType()).thenReturn(ThreatModelLinkType.ASSOCIATED);
        when(threatModelLinkRepository.findByThreatModelIdAndTargetType(THREAT_ID, ThreatModelLinkTargetType.CONTROL))
                .thenReturn(List.of(mitigatedLink, otherLink));

        var cmControl = mock(Control.class);
        when(cmControl.getUid()).thenReturn("AC-3");
        when(cmControl.getTitle()).thenReturn("Access");
        when(controlRepository.findByIdAndProjectId(controlViaMapping, PROJECT_ID))
                .thenReturn(Optional.of(cmControl));
        var linkControl = mock(Control.class);
        when(linkControl.getUid()).thenReturn("IA-2");
        when(linkControl.getTitle()).thenReturn("Identify");
        when(controlRepository.findByIdAndProjectId(controlViaLink, PROJECT_ID)).thenReturn(Optional.of(linkControl));

        var coverage = service.controlsCoveringThreat(PROJECT_ID, THREAT_ID);

        assertThat(coverage.controls()).hasSize(2);
        // Sorted by control UID: AC-3 (via mapping), IA-2 (via link).
        assertThat(coverage.controls().get(0).controlUid()).isEqualTo("AC-3");
        assertThat(coverage.controls().get(0).viaRiskControlMapping()).isTrue();
        assertThat(coverage.controls().get(0).viaThreatModelLink()).isFalse();
        assertThat(coverage.controls().get(1).controlUid()).isEqualTo("IA-2");
        assertThat(coverage.controls().get(1).viaThreatModelLink()).isTrue();
        assertThat(coverage.controls().get(1).viaRiskControlMapping()).isFalse();
    }

    @Test
    void confirmCompletesPartiallyRecordedPair() {
        // Mapping already exists, link does not — confirm creates only the missing link.
        stubThreatAndControl();
        when(riskControlMappingRepository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                        CONTROL_ID, THREAT_ID, null))
                .thenReturn(true);
        var existingMapping = mock(RiskControlMapping.class);
        var existingControl = mock(Control.class);
        when(existingControl.getId()).thenReturn(CONTROL_ID);
        when(existingMapping.getControl()).thenReturn(existingControl);
        when(existingMapping.getId()).thenReturn(MAPPING_ID);
        when(riskControlMappingRepository.findByProjectIdAndThreatModelId(PROJECT_ID, THREAT_ID))
                .thenReturn(List.of(existingMapping));
        when(threatModelLinkRepository.existsByThreatModelIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        THREAT_ID, ThreatModelLinkTargetType.CONTROL, CONTROL_ID, ThreatModelLinkType.MITIGATED_BY))
                .thenReturn(false);
        var link = mock(ThreatModelLink.class);
        when(link.getId()).thenReturn(LINK_ID);
        when(threatModelLinkService.create(eq(PROJECT_ID), eq(THREAT_ID), any(CreateThreatModelLinkCommand.class)))
                .thenReturn(link);

        var result = service.confirm(PROJECT_ID, THREAT_ID, CONTROL_ID, null, null, null);

        assertThat(result.mappingCreated()).isFalse();
        assertThat(result.linkCreated()).isTrue();
        verify(riskControlMappingService, never()).create(any());
    }

    @Test
    void confirmDefaultsControlRoleToPreventive() {
        stubThreatAndControl();
        when(riskControlMappingRepository.existsByControlIdAndThreatModelIdAndOperationalAssetId(
                        CONTROL_ID, THREAT_ID, null))
                .thenReturn(false);
        var mapping = mock(RiskControlMapping.class);
        when(mapping.getId()).thenReturn(MAPPING_ID);
        when(riskControlMappingService.create(any(CreateRiskControlMappingCommand.class)))
                .thenReturn(mapping);
        when(threatModelLinkRepository.existsByThreatModelIdAndTargetTypeAndTargetEntityIdAndLinkType(
                        THREAT_ID, ThreatModelLinkTargetType.CONTROL, CONTROL_ID, ThreatModelLinkType.MITIGATED_BY))
                .thenReturn(false);
        var link = mock(ThreatModelLink.class);
        when(link.getId()).thenReturn(LINK_ID);
        when(threatModelLinkService.create(eq(PROJECT_ID), eq(THREAT_ID), any(CreateThreatModelLinkCommand.class)))
                .thenReturn(link);

        var captor = org.mockito.ArgumentCaptor.forClass(CreateRiskControlMappingCommand.class);
        service.confirm(PROJECT_ID, THREAT_ID, CONTROL_ID, null, null, null);
        verify(riskControlMappingService).create(captor.capture());
        assertThat(captor.getValue().controlRole()).isEqualTo(MappingControlRole.PREVENTIVE);
    }

    @Test
    void confirmRejectsControlThatIsNotAnEngineCandidate() {
        var threat = mock(ThreatModel.class);
        when(threat.getStride()).thenReturn(StrideCategory.SPOOFING);
        when(threat.getUid()).thenReturn("GC-TM-99");
        when(threatModelRepository.findByIdAndProjectId(THREAT_ID, PROJECT_ID)).thenReturn(Optional.of(threat));
        var control = mock(Control.class);
        when(control.getUid()).thenReturn("AU-2");
        when(controlRepository.findByIdAndProjectId(CONTROL_ID, PROJECT_ID)).thenReturn(Optional.of(control));
        // An audit-family (AU) control is not selected by the SPOOFING rule (IA/AC), so it is not a
        // candidate for this threat — confirmation must refuse to record a non-derived mitigation.
        when(identificationService.loadAvailableControls(PROJECT_ID))
                .thenReturn(List.of(availableControl(CONTROL_ID, "AU-2", Set.of("AU-2"))));

        assertThatThrownBy(() -> service.confirm(PROJECT_ID, THREAT_ID, CONTROL_ID, null, null, null))
                .isInstanceOf(DomainValidationException.class);
        verify(riskControlMappingService, never()).create(any());
        verify(threatModelLinkService, never()).create(any(), any(), any());
    }
}

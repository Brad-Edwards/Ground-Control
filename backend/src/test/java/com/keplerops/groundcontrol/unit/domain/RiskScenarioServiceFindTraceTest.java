package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioService;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType;
import com.keplerops.groundcontrol.domain.trace.SecurityTrace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from RiskScenarioServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class RiskScenarioServiceFindTraceTest {
    @Mock
    private RiskScenarioRepository riskScenarioRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository
            riskScenarioLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @InjectMocks
    private RiskScenarioService riskScenarioService;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private RiskScenario makeScenario() {
        var rs = new RiskScenario(
                project,
                "RS-001",
                "Credential stuffing on customer portal",
                "External threat actor",
                "Credential stuffing attack",
                "Customer authentication portal",
                "Data breach and unauthorized access");
        rs.setTimeHorizon("12 months");
        rs.setCreatedBy("system");
        setField(rs, "id", UUID.randomUUID());
        setField(rs, "createdAt", NOW);
        setField(rs, "updatedAt", NOW);
        return rs;
    }

    @Nested
    class FindTrace {

        @Test
        void composesAssetsControlsRequirementsAndArtifacts() {
            var rs = makeScenario();
            var assetId = UUID.randomUUID();
            var controlId = UUID.randomUUID();
            var reqId = UUID.randomUUID();

            var asset = new OperationalAsset(project, "ASSET-001", "Auth Service");
            setField(asset, "id", assetId);

            var control = new Control(project, "CTL-001", "MFA Control", ControlFunction.PREVENTIVE);
            setField(control, "id", controlId);

            var req = new Requirement(project, "GC-H003", "Threat traceability", "System shall trace threats");
            setField(req, "id", reqId);

            var assetLink = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.ASSET, assetId, null, RiskScenarioLinkType.AFFECTS);
            setField(assetLink, "id", UUID.randomUUID());

            var controlLink = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.CONTROL, controlId, null, RiskScenarioLinkType.MITIGATED_BY);
            setField(controlLink, "id", UUID.randomUUID());

            var reqLink = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.REQUIREMENT, reqId, null, RiskScenarioLinkType.AFFECTS);
            setField(reqLink, "id", UUID.randomUUID());

            var artifact = new TraceabilityLink(req, ArtifactType.PULL_REQUEST, "42", LinkType.IMPLEMENTS);
            setField(artifact, "id", UUID.randomUUID());

            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.ASSET))
                    .thenReturn(List.of(assetLink));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.CONTROL))
                    .thenReturn(List.of(controlLink));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(reqLink));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(requirementRepository.findByIdAndProjectId(reqId, projectId)).thenReturn(Optional.of(req));
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of(reqId)))
                    .thenReturn(List.of(artifact));

            SecurityTrace trace = riskScenarioService.findTrace(projectId, rs.getId());

            assertThat(trace.sourceType().name()).isEqualTo("RISK_SCENARIO");
            assertThat(trace.sourceId()).isEqualTo(rs.getId());
            assertThat(trace.sourceUid()).isEqualTo(rs.getUid());
            assertThat(trace.sourceTitle()).isEqualTo(rs.getTitle());
            assertThat(trace.assets()).hasSize(1);
            assertThat(trace.assets().get(0).getUid()).isEqualTo("ASSET-001");
            assertThat(trace.controls()).hasSize(1);
            assertThat(trace.controls().get(0).getUid()).isEqualTo("CTL-001");
            assertThat(trace.requirements()).hasSize(1);
            assertThat(trace.requirements().get(0).requirement().getUid()).isEqualTo("GC-H003");
            assertThat(trace.requirements().get(0).artifacts()).hasSize(1);
            assertThat(trace.requirements().get(0).artifacts().get(0).getArtifactIdentifier())
                    .isEqualTo("42");
        }

        @Test
        void throws404WhenRiskScenarioNotFound() {
            var id = UUID.randomUUID();
            when(riskScenarioRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> riskScenarioService.findTrace(projectId, id))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void skipsLinkWhenTargetEntityAbsent() {
            var rs = makeScenario();
            var missingAssetId = UUID.randomUUID();
            var assetLink = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.ASSET, missingAssetId, null, RiskScenarioLinkType.AFFECTS);
            setField(assetLink, "id", UUID.randomUUID());

            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.ASSET))
                    .thenReturn(List.of(assetLink));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.CONTROL))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByIdAndProjectId(missingAssetId, projectId))
                    .thenReturn(Optional.empty());
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of())).thenReturn(List.of());

            SecurityTrace trace = riskScenarioService.findTrace(projectId, rs.getId());

            assertThat(trace.assets()).isEmpty();
            assertThat(trace.controls()).isEmpty();
            assertThat(trace.requirements()).isEmpty();
        }

        @Test
        void returnsEmptyTraceWhenNoLinks() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.ASSET))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.CONTROL))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of())).thenReturn(List.of());

            SecurityTrace trace = riskScenarioService.findTrace(projectId, rs.getId());

            assertThat(trace.assets()).isEmpty();
            assertThat(trace.controls()).isEmpty();
            assertThat(trace.requirements()).isEmpty();
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesScenario() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.RISK_SCENARIO,
                            rs.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(riskScenarioLinkRepository.findByRiskScenarioId(rs.getId())).thenReturn(java.util.List.of());

            riskScenarioService.delete(projectId, rs.getId());

            verify(riskScenarioRepository).delete(rs);
        }

        @Test
        void rejectsDeleteWhenInboundAuditLinkReferencesScenario() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.RISK_SCENARIO,
                            rs.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(auditLinkRepository.findAuditUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.audits.state.AuditLinkTargetType.RISK_SCENARIO,
                            rs.getId(),
                            projectId))
                    .thenReturn(java.util.List.of("AUDIT-001"));

            var rsId = rs.getId();
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    ConflictException.class, () -> riskScenarioService.delete(projectId, rsId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("AuditLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("risk_scenario_referenced");
            assertThat(thrown.getDetail()).containsEntry("auditCount", 1);
            org.mockito.Mockito.verifyNoInteractions(riskScenarioLinkRepository);
            verify(riskScenarioRepository, never()).delete(rs);
        }

        @Test
        void rejectsDeleteWhenInboundFindingLinkReferencesScenario() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.RISK_SCENARIO,
                            rs.getId(),
                            projectId))
                    .thenReturn(java.util.List.of("FIND-001"));

            // FindingLink.targetEntityId is not an FK, so without this guard the
            // delete would leave dangling FindingLink rows (cycle-3 pre-push codex
            // review on issue #279, ADR-038).
            var rsId = rs.getId();
            var thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                    ConflictException.class, () -> riskScenarioService.delete(projectId, rsId));
            assertThat(thrown)
                    .isNotNull()
                    .hasMessageContaining("FindingLink references exist")
                    .extracting("errorCode")
                    .isEqualTo("risk_scenario_referenced");
            assertThat(thrown.getDetail()).containsEntry("findingCount", 1);
            // Parent + outbound-link cleanup must be skipped when the guard fires.
            org.mockito.Mockito.verifyNoInteractions(riskScenarioLinkRepository);
            verify(riskScenarioRepository, never()).delete(rs);
        }

        @Test
        void deletesOutboundLinksThroughRepositoryBeforeParent() {
            var rs = makeScenario();
            var outboundLinks =
                    java.util.List.of(new com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink(
                            rs,
                            com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType.CONTROL,
                            UUID.randomUUID(),
                            null,
                            com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType.MITIGATED_BY));
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(findingLinkRepository.findFindingUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            com.keplerops.groundcontrol.domain.findings.state.FindingLinkTargetType.RISK_SCENARIO,
                            rs.getId(),
                            projectId))
                    .thenReturn(java.util.List.of());
            when(riskScenarioLinkRepository.findByRiskScenarioId(rs.getId())).thenReturn(outboundLinks);

            riskScenarioService.delete(projectId, rs.getId());

            // Envers writes delete revisions only when Hibernate sees the link
            // delete. Driving outbound link deletes through the repository before
            // deleting the parent closes the parent-delete audit-history gap
            // (cycle-2 pre-push codex review on issue #279).
            var inOrder = org.mockito.Mockito.inOrder(riskScenarioLinkRepository, riskScenarioRepository);
            inOrder.verify(riskScenarioLinkRepository).deleteAll(outboundLinks);
            inOrder.verify(riskScenarioRepository).delete(rs);
        }
    }
}

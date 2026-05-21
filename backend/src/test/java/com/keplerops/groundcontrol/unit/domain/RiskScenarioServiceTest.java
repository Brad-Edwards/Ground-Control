package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenarioLink;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateRiskScenarioCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.RiskScenarioService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateRiskScenarioCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioStatus;
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

@ExtendWith(MockitoExtension.class)
class RiskScenarioServiceTest {

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
        rs.setVulnerability("Weak password policy");
        setField(rs, "id", UUID.randomUUID());
        setField(rs, "createdAt", NOW);
        setField(rs, "updatedAt", NOW);
        return rs;
    }

    @Nested
    class Create {

        @Test
        void createsRiskScenario() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(riskScenarioRepository.existsByProjectIdAndUid(projectId, "RS-001"))
                    .thenReturn(false);
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateRiskScenarioCommand(
                    projectId,
                    "RS-001",
                    "Credential stuffing",
                    "External actor",
                    "Credential stuffing",
                    "Auth portal",
                    "Weak passwords",
                    "Data breach",
                    "12 months");

            var result = riskScenarioService.create(command);

            assertThat(result.getUid()).isEqualTo("RS-001");
            assertThat(result.getTitle()).isEqualTo("Credential stuffing");
            assertThat(result.getThreatSource()).isEqualTo("External actor");
            assertThat(result.getVulnerability()).isEqualTo("Weak passwords");
            assertThat(result.getStatus()).isEqualTo(RiskScenarioStatus.DRAFT);
        }

        @Test
        void throwsOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(riskScenarioRepository.existsByProjectIdAndUid(projectId, "RS-001"))
                    .thenReturn(true);

            var command = new CreateRiskScenarioCommand(
                    projectId, "RS-001", "Title", "Source", "Event", "Object", null, "Consequence", "12m");

            assertThatThrownBy(() -> riskScenarioService.create(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void createsWithNullOptionalFields() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(riskScenarioRepository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateRiskScenarioCommand(
                    projectId, "RS-002", "Title", "Source", "Event", "Object", null, "Consequence", "6m");

            var result = riskScenarioService.create(command);

            assertThat(result.getVulnerability()).isNull();
            assertThat(result.getAffectedObject()).isEqualTo("Object");
        }
    }

    @Nested
    class Update {

        @Test
        void updatesRiskScenario() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateRiskScenarioCommand("Updated title", null, null, null, null, null, null);
            var result = riskScenarioService.update(projectId, rs.getId(), command);

            assertThat(result.getTitle()).isEqualTo("Updated title");
            assertThat(result.getThreatSource()).isEqualTo("External threat actor");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(riskScenarioRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            var command = new UpdateRiskScenarioCommand("Title", null, null, null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, id, command))
                    .isInstanceOf(NotFoundException.class);
        }

        // Issue #876 (codex review cycle 1, class finding): partial update must not
        // overwrite create-required fields with blank strings. RiskScenarioRequest
        // marks title / threatSource / threatEvent / affectedObject / consequence /
        // timeHorizon as @NotBlank; the update path must enforce the same contract
        // when a value is supplied. Mirrors the rejectBlankIfPresent pattern in
        // ThreatModelService.update.
        @Test
        void rejectsBlankTitle() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand("   ", null, null, null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsBlankThreatSource() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, "", null, null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("threatSource");
        }

        @Test
        void rejectsBlankThreatEvent() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, " ", null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("threatEvent");
        }

        @Test
        void rejectsBlankAffectedObject() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, "", null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("affectedObject");
        }

        @Test
        void rejectsBlankConsequence() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, null, null, "", null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("consequence");
        }

        @Test
        void rejectsBlankTimeHorizon() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, null, null, null, "   ");

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("timeHorizon");
        }

        @Test
        void allowsAbsentRequiredFields() {
            // A partial update that touches only the optional vulnerability field
            // must not be blocked by the new blank-if-present checks.
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateRiskScenarioCommand(null, null, null, null, "new vuln", null, null);
            var result = riskScenarioService.update(projectId, rs.getId(), command);

            assertThat(result.getVulnerability()).isEqualTo("new vuln");
            assertThat(result.getTitle()).isEqualTo("Credential stuffing on customer portal");
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void transitionsFromDraftToActive() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = riskScenarioService.transitionStatus(projectId, rs.getId(), RiskScenarioStatus.ACTIVE);

            assertThat(result.getStatus()).isEqualTo(RiskScenarioStatus.ACTIVE);
        }

        @Test
        void transitionsFromDraftToArchived() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = riskScenarioService.transitionStatus(projectId, rsId, RiskScenarioStatus.ARCHIVED);

            assertThat(result.getStatus()).isEqualTo(RiskScenarioStatus.ARCHIVED);
        }

        @Test
        void throwsOnInvalidTransition() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            assertThatThrownBy(() -> riskScenarioService.transitionStatus(projectId, rsId, RiskScenarioStatus.DRAFT))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsScenario() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));

            var result = riskScenarioService.getById(projectId, rs.getId());

            assertThat(result.getUid()).isEqualTo("RS-001");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(riskScenarioRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> riskScenarioService.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void listsScenarios() {
            when(riskScenarioRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(makeScenario()));

            var result = riskScenarioService.listByProject(projectId);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class FindLinkedRequirements {

        @Test
        void returnsRequirementsViaCanonicalRiskScenarioLinkPath() {
            var rs = makeScenario();
            var reqId = UUID.randomUUID();
            var req = new Requirement(project, "GC-H002", "Threat linking", "System shall link");
            setField(req, "id", reqId);

            var link = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.REQUIREMENT, reqId, null, RiskScenarioLinkType.AFFECTS);
            setField(link, "id", UUID.randomUUID());

            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(link));
            when(requirementRepository.findByIdAndProjectId(reqId, projectId)).thenReturn(Optional.of(req));

            var results = riskScenarioService.findLinkedRequirements(projectId, rs.getId());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getUid()).isEqualTo("GC-H002");
        }

        @Test
        void returnsEmptyWhenNoREQUIREMENTLinks() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());

            var results = riskScenarioService.findLinkedRequirements(projectId, rs.getId());

            assertThat(results).isEmpty();
        }

        @Test
        void isProjectScoped_crossProjectLinkNotReturned() {
            // A RiskScenarioLink targeting a requirement UUID from another project
            // must not appear in this project's result.
            var rs = makeScenario();
            var otherProjectReqId = UUID.randomUUID();

            var link = new RiskScenarioLink(
                    rs, RiskScenarioLinkTargetType.REQUIREMENT, otherProjectReqId, null, RiskScenarioLinkType.AFFECTS);
            setField(link, "id", UUID.randomUUID());

            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioLinkRepository.findByRiskScenarioIdAndTargetType(
                            rs.getId(), RiskScenarioLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(link));
            when(requirementRepository.findByIdAndProjectId(otherProjectReqId, projectId))
                    .thenReturn(Optional.empty());

            var results = riskScenarioService.findLinkedRequirements(projectId, rs.getId());

            assertThat(results).isEmpty();
        }

        @Test
        void throws404WhenScenarioNotFound() {
            var id = UUID.randomUUID();
            when(riskScenarioRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> riskScenarioService.findLinkedRequirements(projectId, id))
                    .isInstanceOf(NotFoundException.class);
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
            org.mockito.Mockito.verify(riskScenarioRepository, org.mockito.Mockito.never())
                    .delete(rs);
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
            org.mockito.Mockito.verify(riskScenarioRepository, org.mockito.Mockito.never())
                    .delete(rs);
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

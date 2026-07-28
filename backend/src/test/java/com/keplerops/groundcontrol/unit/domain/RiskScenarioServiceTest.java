package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
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

/** Split from RiskScenarioServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
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
                    "Data breach",
                    "12 months");

            var result = riskScenarioService.create(command);

            assertThat(result.getUid()).isEqualTo("RS-001");
            assertThat(result.getTitle()).isEqualTo("Credential stuffing");
            assertThat(result.getThreat()).isEqualTo("External actor");
            assertThat(result.getStatus()).isEqualTo(RiskScenarioStatus.DRAFT);
        }

        @Test
        void throwsOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(riskScenarioRepository.existsByProjectIdAndUid(projectId, "RS-001"))
                    .thenReturn(true);

            var command = new CreateRiskScenarioCommand(
                    projectId, "RS-001", "Title", "Source", "Event", "Object", "Consequence", "12m");

            assertThatThrownBy(() -> riskScenarioService.create(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void fairSentenceIsComputedFromFourAxes() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(riskScenarioRepository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateRiskScenarioCommand(
                    projectId,
                    "RS-002",
                    "Title",
                    "Attacker",
                    "Phishing",
                    "Employee credentials",
                    "Data exfiltration",
                    "6m");

            var result = riskScenarioService.create(command);

            assertThat(result.getFairSentence())
                    .isEqualTo("Attacker impacts Employee credentials via Phishing, causing Data exfiltration");
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

            var command = new UpdateRiskScenarioCommand("Updated title", null, null, null, null, null);
            var result = riskScenarioService.update(projectId, rs.getId(), command);

            assertThat(result.getTitle()).isEqualTo("Updated title");
            assertThat(result.getThreat()).isEqualTo("External threat actor");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(riskScenarioRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            var command = new UpdateRiskScenarioCommand("Title", null, null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, id, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsBlankTitle() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand("   ", null, null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsBlankThreat() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, "", null, null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("threat");
        }

        @Test
        void rejectsBlankMethod() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, " ", null, null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("method");
        }

        @Test
        void rejectsBlankAsset() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, "", null, null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("asset");
        }

        @Test
        void rejectsBlankEffect() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, null, "", null);

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("effect");
        }

        @Test
        void rejectsBlankTimeHorizon() {
            var rs = makeScenario();
            var rsId = rs.getId();
            when(riskScenarioRepository.findByIdAndProjectId(rsId, projectId)).thenReturn(Optional.of(rs));

            var command = new UpdateRiskScenarioCommand(null, null, null, null, null, "   ");

            assertThatThrownBy(() -> riskScenarioService.update(projectId, rsId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("timeHorizon");
        }

        @Test
        void allowsAbsentRequiredFields() {
            // A partial update that touches only the title must not be blocked
            // by the blank-if-present checks for fields not in the command.
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateRiskScenarioCommand("New title", null, null, null, null, null);
            var result = riskScenarioService.update(projectId, rs.getId(), command);

            assertThat(result.getTitle()).isEqualTo("New title");
            assertThat(result.getThreat()).isEqualTo("External threat actor");
        }

        @Test
        void updatesAllFourNarrativeAxes() {
            var rs = makeScenario();
            when(riskScenarioRepository.findByIdAndProjectId(rs.getId(), projectId))
                    .thenReturn(Optional.of(rs));
            when(riskScenarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateRiskScenarioCommand(
                    null,
                    "Nation-state advanced persistent threat",
                    "Spear-phishing with credential harvesting",
                    "Engineering source-code repository",
                    "Loss of intellectual property and regulatory disclosure",
                    null);
            var result = riskScenarioService.update(projectId, rs.getId(), command);

            assertThat(result.getThreat()).isEqualTo("Nation-state advanced persistent threat");
            assertThat(result.getMethod()).isEqualTo("Spear-phishing with credential harvesting");
            assertThat(result.getAsset()).isEqualTo("Engineering source-code repository");
            assertThat(result.getEffect()).isEqualTo("Loss of intellectual property and regulatory disclosure");
            assertThat(result.getFairSentence())
                    .isEqualTo("Nation-state advanced persistent threat impacts Engineering source-code repository"
                            + " via Spear-phishing with credential harvesting, causing Loss of intellectual"
                            + " property and regulatory disclosure");
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
}

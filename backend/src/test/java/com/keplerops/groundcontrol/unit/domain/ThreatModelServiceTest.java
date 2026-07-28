package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
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
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModel;
import com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository;
import com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelRepository;
import com.keplerops.groundcontrol.domain.threatmodels.service.CreateThreatModelCommand;
import com.keplerops.groundcontrol.domain.threatmodels.service.ThreatModelService;
import com.keplerops.groundcontrol.domain.threatmodels.service.UpdateThreatModelCommand;
import com.keplerops.groundcontrol.domain.threatmodels.state.StrideCategory;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkType;
import com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelStatus;
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

/** Split from ThreatModelServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class ThreatModelServiceTest {
    @Mock
    private ThreatModelRepository threatModelRepository;

    @Mock
    private ThreatModelLinkRepository threatModelLinkRepository;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private AssetLinkRepository assetLinkRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @Mock
    private OperationalAssetRepository operationalAssetRepository;

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private TraceabilityLinkRepository traceabilityLinkRepository;

    @InjectMocks
    private ThreatModelService threatModelService;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-04-11T12:00:00Z");

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private ThreatModel makeThreatModel() {
        var tm = new ThreatModel(
                project,
                "TM-001",
                "Credential stuffing against login portal",
                "External actor using leaked credential lists",
                "Automated credential replay against /login",
                "Account takeover and lateral movement into customer data");
        tm.setStride(StrideCategory.SPOOFING);
        tm.setNarrative("Observed 3x surge after breach dump release.");
        tm.setCreatedBy("analyst");
        setField(tm, "id", UUID.randomUUID());
        setField(tm, "createdAt", NOW);
        setField(tm, "updatedAt", NOW);
        return tm;
    }

    @Nested
    class Create {

        @Test
        void createsThreatModel() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(threatModelRepository.existsByProjectIdAndUid(projectId, "TM-001"))
                    .thenReturn(false);
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateThreatModelCommand(
                    projectId,
                    "TM-001",
                    "Credential stuffing",
                    "External actor",
                    "Credential replay",
                    "Account takeover",
                    StrideCategory.SPOOFING,
                    "narrative");

            var result = threatModelService.create(command);

            assertThat(result.getUid()).isEqualTo("TM-001");
            assertThat(result.getTitle()).isEqualTo("Credential stuffing");
            assertThat(result.getThreatSource()).isEqualTo("External actor");
            assertThat(result.getThreatEvent()).isEqualTo("Credential replay");
            assertThat(result.getEffect()).isEqualTo("Account takeover");
            assertThat(result.getStride()).isEqualTo(StrideCategory.SPOOFING);
            assertThat(result.getNarrative()).isEqualTo("narrative");
            assertThat(result.getStatus()).isEqualTo(ThreatModelStatus.DRAFT);
        }

        @Test
        void createsWithNullOptionalFields() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(threatModelRepository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command =
                    new CreateThreatModelCommand(projectId, "TM-002", "Title", "Source", "Event", "Effect", null, null);

            var result = threatModelService.create(command);

            assertThat(result.getStride()).isNull();
            assertThat(result.getNarrative()).isNull();
        }

        @Test
        void throwsOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(threatModelRepository.existsByProjectIdAndUid(projectId, "TM-001"))
                    .thenReturn(true);

            var command =
                    new CreateThreatModelCommand(projectId, "TM-001", "Title", "Source", "Event", "Effect", null, null);

            assertThatThrownBy(() -> threatModelService.create(command)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesThreatModel() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateThreatModelCommand(
                    "Updated title", null, null, null, StrideCategory.TAMPERING, null, false, false);
            var result = threatModelService.update(projectId, tm.getId(), command);

            assertThat(result.getTitle()).isEqualTo("Updated title");
            assertThat(result.getStride()).isEqualTo(StrideCategory.TAMPERING);
            assertThat(result.getThreatSource()).isEqualTo("External actor using leaked credential lists");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(threatModelRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            var command = new UpdateThreatModelCommand("Title", null, null, null, null, null, false, false);

            assertThatThrownBy(() -> threatModelService.update(projectId, id, command))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsBlankTitle() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));

            var command = new UpdateThreatModelCommand("   ", null, null, null, null, null, false, false);

            assertThatThrownBy(() -> threatModelService.update(projectId, tm.getId(), command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsBlankRequiredField() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));

            var command = new UpdateThreatModelCommand(null, null, "", null, null, null, false, false);

            assertThatThrownBy(() -> threatModelService.update(projectId, tm.getId(), command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("threatEvent");
        }

        @Test
        void rejectsBlankThreatSource() {
            var tm = makeThreatModel();
            var tmId = tm.getId();
            when(threatModelRepository.findByIdAndProjectId(tmId, projectId)).thenReturn(Optional.of(tm));

            var command = new UpdateThreatModelCommand(null, " ", null, null, null, null, false, false);

            assertThatThrownBy(() -> threatModelService.update(projectId, tmId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("threatSource");
        }

        @Test
        void rejectsBlankEffect() {
            var tm = makeThreatModel();
            var tmId = tm.getId();
            when(threatModelRepository.findByIdAndProjectId(tmId, projectId)).thenReturn(Optional.of(tm));

            var command = new UpdateThreatModelCommand(null, null, null, " ", null, null, false, false);

            assertThatThrownBy(() -> threatModelService.update(projectId, tmId, command))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("effect");
        }

        @Test
        void clearsStrideWhenFlagSet() {
            var tm = makeThreatModel();
            assertThat(tm.getStride()).isNotNull();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateThreatModelCommand(null, null, null, null, null, null, true, false);
            var result = threatModelService.update(projectId, tm.getId(), command);

            assertThat(result.getStride()).isNull();
            assertThat(result.getNarrative()).isEqualTo("Observed 3x surge after breach dump release.");
        }

        @Test
        void clearsNarrativeWhenFlagSet() {
            var tm = makeThreatModel();
            assertThat(tm.getNarrative()).isNotNull();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var command = new UpdateThreatModelCommand(null, null, null, null, null, null, false, true);
            var result = threatModelService.update(projectId, tm.getId(), command);

            assertThat(result.getNarrative()).isNull();
            assertThat(result.getStride()).isEqualTo(StrideCategory.SPOOFING);
        }

        @Test
        void clearStrideOverridesProvidedValue() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // clear flag wins over the supplied stride value
            var command =
                    new UpdateThreatModelCommand(null, null, null, null, StrideCategory.TAMPERING, null, true, false);
            var result = threatModelService.update(projectId, tm.getId(), command);

            assertThat(result.getStride()).isNull();
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void transitionsFromDraftToActive() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = threatModelService.transitionStatus(projectId, tm.getId(), ThreatModelStatus.ACTIVE);

            assertThat(result.getStatus()).isEqualTo(ThreatModelStatus.ACTIVE);
        }

        @Test
        void transitionsFromDraftToArchived() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = threatModelService.transitionStatus(projectId, tm.getId(), ThreatModelStatus.ARCHIVED);

            assertThat(result.getStatus()).isEqualTo(ThreatModelStatus.ARCHIVED);
        }

        @Test
        void throwsOnInvalidTransition() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));

            assertThatThrownBy(
                            () -> threatModelService.transitionStatus(projectId, tm.getId(), ThreatModelStatus.DRAFT))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsThreatModel() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));

            var result = threatModelService.getById(projectId, tm.getId());

            assertThat(result.getUid()).isEqualTo("TM-001");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(threatModelRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> threatModelService.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetByUid {

        @Test
        void returnsThreatModel() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByProjectIdAndUid(projectId, "TM-001"))
                    .thenReturn(Optional.of(tm));

            var result = threatModelService.getByUid("TM-001", projectId);

            assertThat(result.getId()).isEqualTo(tm.getId());
        }

        @Test
        void throwsWhenNotFound() {
            when(threatModelRepository.findByProjectIdAndUid(projectId, "TM-999"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> threatModelService.getByUid("TM-999", projectId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void listsThreatModels() {
            when(threatModelRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(makeThreatModel()));

            var result = threatModelService.listByProject(projectId);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class FindLinkedRequirements {

        @Test
        void returnsRequirementsForREQUIREMENTTypedLinks() {
            var tm = makeThreatModel();
            var reqId = UUID.randomUUID();
            var req = new Requirement(project, "GC-H002", "Threat linking", "System shall link");
            setField(req, "id", reqId);

            var link = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.REQUIREMENT, reqId, null, ThreatModelLinkType.AFFECTS);
            setField(link, "id", UUID.randomUUID());

            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(link));
            when(requirementRepository.findByIdAndProjectId(reqId, projectId)).thenReturn(Optional.of(req));

            var results = threatModelService.findLinkedRequirements(projectId, tm.getId());

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getUid()).isEqualTo("GC-H002");
        }

        @Test
        void returnsEmptyWhenNoREQUIREMENTLinks() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());

            var results = threatModelService.findLinkedRequirements(projectId, tm.getId());

            assertThat(results).isEmpty();
        }

        @Test
        void throws404WhenThreatModelNotInProject() {
            var id = UUID.randomUUID();
            when(threatModelRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> threatModelService.findLinkedRequirements(projectId, id))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void skipsLinkWhenRequirementNotFoundInProject() {
            // A link may reference a UUID that exists in another project but not
            // in this one — the canonical project-scoped lookup must silently
            // filter such links rather than throwing.
            var tm = makeThreatModel();
            var orphanReqId = UUID.randomUUID();

            var link = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.REQUIREMENT, orphanReqId, null, ThreatModelLinkType.AFFECTS);
            setField(link, "id", UUID.randomUUID());

            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(link));
            when(requirementRepository.findByIdAndProjectId(orphanReqId, projectId))
                    .thenReturn(Optional.empty());

            var results = threatModelService.findLinkedRequirements(projectId, tm.getId());

            assertThat(results).isEmpty();
        }
    }
}

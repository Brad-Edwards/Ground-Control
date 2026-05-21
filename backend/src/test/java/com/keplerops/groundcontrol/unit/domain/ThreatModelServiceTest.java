package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.model.OperationalAsset;
import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.assets.repository.OperationalAssetRepository;
import com.keplerops.groundcontrol.domain.assets.state.AssetLinkTargetType;
import com.keplerops.groundcontrol.domain.controls.model.Control;
import com.keplerops.groundcontrol.domain.controls.repository.ControlRepository;
import com.keplerops.groundcontrol.domain.controls.state.ControlFunction;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.model.TraceabilityLink;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.requirements.repository.TraceabilityLinkRepository;
import com.keplerops.groundcontrol.domain.requirements.state.ArtifactType;
import com.keplerops.groundcontrol.domain.requirements.state.LinkType;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import com.keplerops.groundcontrol.domain.riskscenarios.state.RiskScenarioLinkTargetType;
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

    @Nested
    class FindTrace {

        @Test
        void composesAssetsControlsRequirementsAndArtifacts() {
            var tm = makeThreatModel();
            var assetId = UUID.randomUUID();
            var controlId = UUID.randomUUID();
            var reqId = UUID.randomUUID();

            var asset = new OperationalAsset(project, "ASSET-001", "Auth Service");
            setField(asset, "id", assetId);

            var control = new Control(project, "CTL-001", "MFA Control", ControlFunction.PREVENTIVE);
            setField(control, "id", controlId);

            var req = new Requirement(project, "GC-H003", "Threat traceability", "System shall trace threats");
            setField(req, "id", reqId);

            var assetLink = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.ASSET, assetId, null, ThreatModelLinkType.AFFECTS);
            setField(assetLink, "id", UUID.randomUUID());

            var controlLink = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.CONTROL, controlId, null, ThreatModelLinkType.MITIGATED_BY);
            setField(controlLink, "id", UUID.randomUUID());

            var reqLink = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.REQUIREMENT, reqId, null, ThreatModelLinkType.AFFECTS);
            setField(reqLink, "id", UUID.randomUUID());

            var artifact = new TraceabilityLink(req, ArtifactType.PULL_REQUEST, "PR-42", LinkType.IMPLEMENTS);
            setField(artifact, "id", UUID.randomUUID());

            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.ASSET))
                    .thenReturn(List.of(assetLink));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.CONTROL))
                    .thenReturn(List.of(controlLink));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of(reqLink));
            when(operationalAssetRepository.findByIdAndProjectId(assetId, projectId))
                    .thenReturn(Optional.of(asset));
            when(controlRepository.findByIdAndProjectId(controlId, projectId)).thenReturn(Optional.of(control));
            when(requirementRepository.findByIdAndProjectId(reqId, projectId)).thenReturn(Optional.of(req));
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of(reqId)))
                    .thenReturn(List.of(artifact));

            SecurityTrace trace = threatModelService.findTrace(projectId, tm.getId());

            assertThat(trace.sourceType().name()).isEqualTo("THREAT_MODEL");
            assertThat(trace.sourceId()).isEqualTo(tm.getId());
            assertThat(trace.sourceUid()).isEqualTo(tm.getUid());
            assertThat(trace.sourceTitle()).isEqualTo(tm.getTitle());
            assertThat(trace.assets()).hasSize(1);
            assertThat(trace.assets().get(0).getUid()).isEqualTo("ASSET-001");
            assertThat(trace.controls()).hasSize(1);
            assertThat(trace.controls().get(0).getUid()).isEqualTo("CTL-001");
            assertThat(trace.requirements()).hasSize(1);
            assertThat(trace.requirements().get(0).requirement().getUid()).isEqualTo("GC-H003");
            assertThat(trace.requirements().get(0).artifacts()).hasSize(1);
            assertThat(trace.requirements().get(0).artifacts().get(0).getArtifactIdentifier())
                    .isEqualTo("PR-42");
        }

        @Test
        void throws404WhenThreatModelNotFound() {
            var id = UUID.randomUUID();
            when(threatModelRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> threatModelService.findTrace(projectId, id))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void skipsLinkWhenTargetEntityAbsent() {
            var tm = makeThreatModel();
            var missingAssetId = UUID.randomUUID();
            var assetLink = new ThreatModelLink(
                    tm, ThreatModelLinkTargetType.ASSET, missingAssetId, null, ThreatModelLinkType.AFFECTS);
            setField(assetLink, "id", UUID.randomUUID());

            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.ASSET))
                    .thenReturn(List.of(assetLink));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.CONTROL))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());
            when(operationalAssetRepository.findByIdAndProjectId(missingAssetId, projectId))
                    .thenReturn(Optional.empty());
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of())).thenReturn(List.of());

            SecurityTrace trace = threatModelService.findTrace(projectId, tm.getId());

            assertThat(trace.assets()).isEmpty();
            assertThat(trace.controls()).isEmpty();
            assertThat(trace.requirements()).isEmpty();
        }

        @Test
        void returnsEmptyTraceWhenNoLinks() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.ASSET))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.CONTROL))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByThreatModelIdAndTargetType(
                            tm.getId(), ThreatModelLinkTargetType.REQUIREMENT))
                    .thenReturn(List.of());
            when(traceabilityLinkRepository.findByRequirementIdIn(List.of())).thenReturn(List.of());

            SecurityTrace trace = threatModelService.findTrace(projectId, tm.getId());

            assertThat(trace.assets()).isEmpty();
            assertThat(trace.controls()).isEmpty();
            assertThat(trace.requirements()).isEmpty();
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesThreatModelWhenNoReverseLinks() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.THREAT_MODEL_ENTRY, tm.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.THREAT_MODEL, tm.getId(), projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByThreatModelId(tm.getId())).thenReturn(List.of());

            threatModelService.delete(projectId, tm.getId());

            verify(threatModelRepository).delete(tm);
        }

        @Test
        void deletesOutboundLinksThroughRepositoryBeforeParent() {
            var tm = makeThreatModel();
            var outboundLinks = List.of(new com.keplerops.groundcontrol.domain.threatmodels.model.ThreatModelLink(
                    tm,
                    com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkTargetType.ASSET,
                    UUID.randomUUID(),
                    null,
                    com.keplerops.groundcontrol.domain.threatmodels.state.ThreatModelLinkType.AFFECTS));
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.THREAT_MODEL_ENTRY, tm.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.THREAT_MODEL, tm.getId(), projectId))
                    .thenReturn(List.of());
            when(threatModelLinkRepository.findByThreatModelId(tm.getId())).thenReturn(outboundLinks);

            threatModelService.delete(projectId, tm.getId());

            // Envers writes delete revisions only when Hibernate sees the link
            // delete. Driving outbound link deletes through the repository before
            // deleting the parent closes the parent-delete audit-history gap
            // (cycle-2 pre-push codex review on issue #279, ADR-038).
            var inOrder = org.mockito.Mockito.inOrder(threatModelLinkRepository, threatModelRepository);
            inOrder.verify(threatModelLinkRepository).deleteAll(outboundLinks);
            inOrder.verify(threatModelRepository).delete(tm);
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(threatModelRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> threatModelService.delete(projectId, id)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsDeleteWhenAssetLinkReferencesThreatModel() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.THREAT_MODEL_ENTRY, tm.getId(), projectId))
                    .thenReturn(List.of("ASSET-001", "ASSET-002"));
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.THREAT_MODEL, tm.getId(), projectId))
                    .thenReturn(List.of());

            var thrown = catchThrowableOfType(
                    () -> threatModelService.delete(projectId, tm.getId()), ConflictException.class);
            assertThat(thrown).isNotNull().hasMessageContaining("reverse links");
            assertThat(thrown.getErrorCode()).isEqualTo("threat_model_referenced");
            var detail = thrown.getDetail();
            assertThat(detail).containsEntry("threatModelUid", tm.getUid());
            assertThat(detail).containsEntry("assetCount", 2);
            assertThat(detail).containsEntry("scenarioCount", 0);
            assertThat(detail.get("assetUids")).isEqualTo(List.of("ASSET-001", "ASSET-002"));
            assertThat(detail.get("scenarioUids")).isEqualTo(List.of());
        }

        @Test
        void rejectsDeleteWhenRiskScenarioLinkReferencesThreatModel() {
            var tm = makeThreatModel();
            when(threatModelRepository.findByIdAndProjectId(tm.getId(), projectId))
                    .thenReturn(Optional.of(tm));
            when(assetLinkRepository.findAssetUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            AssetLinkTargetType.THREAT_MODEL_ENTRY, tm.getId(), projectId))
                    .thenReturn(List.of());
            when(riskScenarioLinkRepository.findRiskScenarioUidsByTargetTypeAndTargetEntityIdAndProjectId(
                            RiskScenarioLinkTargetType.THREAT_MODEL, tm.getId(), projectId))
                    .thenReturn(List.of("RS-001"));

            var thrown = catchThrowableOfType(
                    () -> threatModelService.delete(projectId, tm.getId()), ConflictException.class);
            assertThat(thrown).isNotNull().hasMessageContaining("reverse links");
            assertThat(thrown.getErrorCode()).isEqualTo("threat_model_referenced");
            var detail = thrown.getDetail();
            assertThat(detail).containsEntry("threatModelUid", tm.getUid());
            assertThat(detail).containsEntry("assetCount", 0);
            assertThat(detail).containsEntry("scenarioCount", 1);
            assertThat(detail.get("assetUids")).isEqualTo(List.of());
            assertThat(detail.get("scenarioUids")).isEqualTo(List.of("RS-001"));
        }
    }
}

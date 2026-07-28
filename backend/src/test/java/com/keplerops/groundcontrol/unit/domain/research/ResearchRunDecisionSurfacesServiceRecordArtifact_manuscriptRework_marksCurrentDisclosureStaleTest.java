package com.keplerops.groundcontrol.unit.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.model.ProjectType;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.research.model.AutonomyLevel;
import com.keplerops.groundcontrol.domain.research.model.DisclosureStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactStatus;
import com.keplerops.groundcontrol.domain.research.model.ResearchArtifactType;
import com.keplerops.groundcontrol.domain.research.model.ResearchRun;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunArtifact;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunDisclosure;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunGateDecisionLog;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunReviewComment;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStage;
import com.keplerops.groundcontrol.domain.research.model.ResearchRunStatus;
import com.keplerops.groundcontrol.domain.research.repository.ResearchIntakeRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunArtifactRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunDisclosureRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateDecisionLogRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunGateRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySelectionRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunMethodologySourceRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRationaleEntryRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunRepository;
import com.keplerops.groundcontrol.domain.research.repository.ResearchRunReviewCommentRepository;
import com.keplerops.groundcontrol.domain.research.service.MethodologyCatalog;
import com.keplerops.groundcontrol.domain.research.service.RecordArtifactCommand;
import com.keplerops.groundcontrol.domain.research.service.ResearchRunService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Split from ResearchRunDecisionSurfacesServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResearchRunDecisionSurfacesServiceRecordArtifact_manuscriptRework_marksCurrentDisclosureStaleTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Mock
    private ResearchRunRepository runRepository;

    @Mock
    private ResearchRunArtifactRepository artifactRepository;

    @Mock
    private ResearchRunGateRepository gateRepository;

    @Mock
    private ResearchRunGateDecisionLogRepository decisionLogRepository;

    @Mock
    private ResearchRunReviewCommentRepository reviewCommentRepository;

    @Mock
    private ResearchRunRationaleEntryRepository rationaleRepository;

    @Mock
    private ResearchRunDisclosureRepository disclosureRepository;

    @Mock
    private ResearchRunDisclosureEntryRepository disclosureEntryRepository;

    @Mock
    private ResearchIntakeRepository intakeRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ResearchRunMethodologySelectionRepository selectionRepository;

    @Mock
    private ResearchRunMethodologySourceRepository sourceRepository;

    private ResearchRunService service;
    private Project project;

    // Real catalog (loads classpath:research/methodology-catalog.yaml).
    private final MethodologyCatalog methodologyCatalog = new MethodologyCatalog();

    @BeforeEach
    void setUp() {
        service = new ResearchRunService(
                runRepository,
                artifactRepository,
                gateRepository,
                decisionLogRepository,
                reviewCommentRepository,
                rationaleRepository,
                disclosureRepository,
                disclosureEntryRepository,
                intakeRepository,
                projectService,
                selectionRepository,
                sourceRepository,
                methodologyCatalog,
                mock(
                        com.keplerops.groundcontrol.domain.research.repository.MethodologyRequirementsContractRepository
                                .class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntryRepository.class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractEntrySourceLinkRepository.class),
                mock(
                        com.keplerops.groundcontrol.domain.research.repository
                                .MethodologyRequirementsContractRejectedAlternativeRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanCoverageRepository.class),
                mock(com.keplerops.groundcontrol.domain.research.repository.ProtocolPlanSectionRepository.class));
        project = new Project("research-p", "Research Project", ProjectType.RESEARCH);
        TestUtil.setField(project, "id", PROJECT_ID);
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(decisionLogRepository.save(any())).thenAnswer(inv -> {
            ResearchRunGateDecisionLog l = inv.getArgument(0);
            if (l.getId() == null) {
                TestUtil.setField(l, "id", UUID.randomUUID());
            }
            return l;
        });
        when(reviewCommentRepository.save(any())).thenAnswer(inv -> {
            ResearchRunReviewComment c = inv.getArgument(0);
            if (c.getId() == null) {
                TestUtil.setField(c, "id", UUID.randomUUID());
            }
            return c;
        });
        when(rationaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(disclosureRepository.save(any())).thenAnswer(inv -> {
            ResearchRunDisclosure d = inv.getArgument(0);
            if (d.getId() == null) {
                TestUtil.setField(d, "id", UUID.randomUUID());
            }
            return d;
        });
        when(disclosureEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.save(any())).thenAnswer(inv -> {
            ResearchRunArtifact a = inv.getArgument(0);
            if (a.getId() == null) {
                TestUtil.setField(a, "id", UUID.randomUUID());
            }
            return a;
        });
    }

    private ResearchRun runAt(ResearchRunStage stage, ResearchRunStatus status, AutonomyLevel autonomy) {
        var run = new ResearchRun(project, "RUN-1", autonomy);
        TestUtil.setField(run, "id", RUN_ID);
        TestUtil.setField(run, "currentStage", stage);
        TestUtil.setField(run, "status", status);
        run.setOwnerActor("owner-actor");
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private ResearchRunArtifact artifact(ResearchRun run, ResearchArtifactType type, ResearchArtifactStatus status) {
        var a = new ResearchRunArtifact(run, type, 1);
        TestUtil.setField(a, "id", UUID.randomUUID());
        TestUtil.setField(a, "status", status);
        return a;
    }

    private ResearchRunDisclosure disclosure(
            ResearchRun run, UUID manuscriptId, boolean aiNone, boolean uncertaintyNone, DisclosureStatus status) {
        return disclosure(run, manuscriptId, aiNone, uncertaintyNone, false, status);
    }

    private ResearchRunDisclosure disclosure(
            ResearchRun run,
            UUID manuscriptId,
            boolean aiNone,
            boolean uncertaintyNone,
            boolean humanNone,
            DisclosureStatus status) {
        var d = new ResearchRunDisclosure(run, manuscriptId, 1, aiNone, uncertaintyNone, humanNone, "owner-actor");
        TestUtil.setField(d, "id", UUID.randomUUID());
        TestUtil.setField(d, "status", status);
        return d;
    }

    // ----------------------------------------------- recordArtifact -> stale

    @Test
    void recordArtifact_manuscriptRework_marksCurrentDisclosureStale() {
        var run = runAt(ResearchRunStage.PROSE_DRAFTING, ResearchRunStatus.IN_PROGRESS, AutonomyLevel.AUTONOMOUS);
        var prior = artifact(run, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE);
        when(artifactRepository.findByResearchRunIdAndArtifactTypeAndStatus(
                        RUN_ID, ResearchArtifactType.MANUSCRIPT, ResearchArtifactStatus.ACTIVE))
                .thenReturn(Optional.of(prior));
        when(gateRepository.findByResearchRunIdAndGatePoint(any(), any())).thenReturn(Optional.empty());
        var current = disclosure(run, prior.getId(), true, true, DisclosureStatus.CURRENT);
        when(disclosureRepository.findFirstByResearchRunIdAndStatus(RUN_ID, DisclosureStatus.CURRENT))
                .thenReturn(Optional.of(current));

        service.recordArtifact(
                PROJECT_ID,
                RUN_ID,
                new RecordArtifactCommand(
                        ResearchArtifactType.MANUSCRIPT, "loc-v2", null, null, null, null, null, null, null));

        assertThat(current.getStatus()).isEqualTo(DisclosureStatus.STALE);
        verify(disclosureRepository).save(current);
    }
}

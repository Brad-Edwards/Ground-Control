package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.assets.repository.AssetLinkRepository;
import com.keplerops.groundcontrol.domain.controls.repository.ControlLinkRepository;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.findings.repository.FindingRepository;
import com.keplerops.groundcontrol.domain.findings.service.CreateFindingCommand;
import com.keplerops.groundcontrol.domain.findings.service.FindingService;
import com.keplerops.groundcontrol.domain.findings.service.UpdateFindingCommand;
import com.keplerops.groundcontrol.domain.findings.state.FindingSeverity;
import com.keplerops.groundcontrol.domain.findings.state.FindingStatus;
import com.keplerops.groundcontrol.domain.findings.state.FindingType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.repository.RiskScenarioLinkRepository;
import java.time.Instant;
import java.time.LocalDate;
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

/** Split from FindingServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class FindingServiceTest {
    @Mock
    private FindingRepository findingRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.findings.repository.FindingLinkRepository findingLinkRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private AssetLinkRepository assetLinkRepository;

    @Mock
    private ControlLinkRepository controlLinkRepository;

    @Mock
    private RiskScenarioLinkRepository riskScenarioLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.threatmodels.repository.ThreatModelLinkRepository
            threatModelLinkRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.audits.repository.AuditLinkRepository auditLinkRepository;

    @InjectMocks
    private FindingService findingService;

    private Project project;
    private UUID projectId;
    private static final Instant NOW = Instant.parse("2026-05-13T12:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    private com.keplerops.groundcontrol.domain.findings.model.Finding makeFinding() {
        var f = new com.keplerops.groundcontrol.domain.findings.model.Finding(
                project,
                "FIND-001",
                "MFA missing on admin portal",
                FindingType.CONTROL_DEFICIENCY,
                FindingSeverity.HIGH,
                "Admin portal accepts password-only auth.");
        f.setRootCauseAnalysis("Identity provider misconfigured during migration.");
        f.setOwner("alice");
        f.setDueDate(DUE);
        f.setCreatedBy("analyst");
        setField(f, "id", UUID.randomUUID());
        setField(f, "createdAt", NOW);
        setField(f, "updatedAt", NOW);
        return f;
    }

    @Nested
    class Create {

        @Test
        void createsFindingWithAllFields() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(findingRepository.existsByProjectIdAndUid(projectId, "FIND-001"))
                    .thenReturn(false);
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateFindingCommand(
                    projectId,
                    "FIND-001",
                    "MFA missing",
                    FindingType.CONTROL_DEFICIENCY,
                    FindingSeverity.HIGH,
                    "Admin portal accepts password-only auth.",
                    "Identity provider misconfigured.",
                    "alice",
                    DUE);

            var result = findingService.create(cmd);

            assertThat(result.getUid()).isEqualTo("FIND-001");
            assertThat(result.getTitle()).isEqualTo("MFA missing");
            assertThat(result.getFindingType()).isEqualTo(FindingType.CONTROL_DEFICIENCY);
            assertThat(result.getSeverity()).isEqualTo(FindingSeverity.HIGH);
            assertThat(result.getDescription()).isEqualTo("Admin portal accepts password-only auth.");
            assertThat(result.getRootCauseAnalysis()).isEqualTo("Identity provider misconfigured.");
            assertThat(result.getOwner()).isEqualTo("alice");
            assertThat(result.getDueDate()).isEqualTo(DUE);
            assertThat(result.getStatus()).isEqualTo(FindingStatus.OPEN);
        }

        @Test
        void createsWithNullOptionalFields() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(findingRepository.existsByProjectIdAndUid(any(), any())).thenReturn(false);
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new CreateFindingCommand(
                    projectId,
                    "FIND-002",
                    "Title",
                    FindingType.AUDIT_FINDING,
                    FindingSeverity.LOW,
                    "Description",
                    null,
                    null,
                    null);

            var result = findingService.create(cmd);

            assertThat(result.getRootCauseAnalysis()).isNull();
            assertThat(result.getOwner()).isNull();
            assertThat(result.getDueDate()).isNull();
        }

        @Test
        void throwsOnDuplicateUid() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(findingRepository.existsByProjectIdAndUid(projectId, "FIND-001"))
                    .thenReturn(true);

            var cmd = new CreateFindingCommand(
                    projectId,
                    "FIND-001",
                    "Title",
                    FindingType.AUDIT_FINDING,
                    FindingSeverity.LOW,
                    "Description",
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> findingService.create(cmd)).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Update {

        @Test
        void updatesFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateFindingCommand(
                    "Updated title", null, FindingSeverity.CRITICAL, null, null, null, null, false, false, false);
            var result = findingService.update(projectId, f.getId(), cmd);

            assertThat(result.getTitle()).isEqualTo("Updated title");
            assertThat(result.getSeverity()).isEqualTo(FindingSeverity.CRITICAL);
            // unchanged
            assertThat(result.getFindingType()).isEqualTo(FindingType.CONTROL_DEFICIENCY);
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(findingRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            var cmd = new UpdateFindingCommand("x", null, null, null, null, null, null, false, false, false);

            assertThatThrownBy(() -> findingService.update(projectId, id, cmd)).isInstanceOf(NotFoundException.class);
        }

        @Test
        void rejectsBlankTitle() {
            var f = makeFinding();
            var fId = f.getId();
            when(findingRepository.findByIdAndProjectId(fId, projectId)).thenReturn(Optional.of(f));

            var cmd = new UpdateFindingCommand("   ", null, null, null, null, null, null, false, false, false);

            assertThatThrownBy(() -> findingService.update(projectId, fId, cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("title");
        }

        @Test
        void rejectsBlankDescription() {
            var f = makeFinding();
            var fId = f.getId();
            when(findingRepository.findByIdAndProjectId(fId, projectId)).thenReturn(Optional.of(f));

            var cmd = new UpdateFindingCommand(null, null, null, "", null, null, null, false, false, false);

            assertThatThrownBy(() -> findingService.update(projectId, fId, cmd))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("description");
        }

        @Test
        void clearsRootCauseWhenFlagSet() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateFindingCommand(null, null, null, null, null, null, null, true, false, false);
            var result = findingService.update(projectId, f.getId(), cmd);

            assertThat(result.getRootCauseAnalysis()).isNull();
            assertThat(result.getOwner()).isEqualTo("alice");
        }

        @Test
        void clearsOwnerWhenFlagSet() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateFindingCommand(null, null, null, null, null, null, null, false, true, false);
            var result = findingService.update(projectId, f.getId(), cmd);

            assertThat(result.getOwner()).isNull();
            assertThat(result.getDueDate()).isEqualTo(DUE);
        }

        @Test
        void clearsDueDateWhenFlagSet() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateFindingCommand(null, null, null, null, null, null, null, false, false, true);
            var result = findingService.update(projectId, f.getId(), cmd);

            assertThat(result.getDueDate()).isNull();
            assertThat(result.getOwner()).isEqualTo("alice");
        }

        @Test
        void clearFlagOverridesProvidedValue() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var cmd = new UpdateFindingCommand(
                    null, null, null, null, "ignored", "ignored-owner", LocalDate.of(2030, 1, 1), true, true, true);
            var result = findingService.update(projectId, f.getId(), cmd);

            assertThat(result.getRootCauseAnalysis()).isNull();
            assertThat(result.getOwner()).isNull();
            assertThat(result.getDueDate()).isNull();
        }
    }

    @Nested
    class TransitionStatus {

        @Test
        void transitionsOpenToRemediationInProgress() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));
            when(findingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var result = findingService.transitionStatus(projectId, f.getId(), FindingStatus.REMEDIATION_IN_PROGRESS);

            assertThat(result.getStatus()).isEqualTo(FindingStatus.REMEDIATION_IN_PROGRESS);
        }

        @Test
        void throwsOnInvalidTransition() {
            var f = makeFinding();
            var fId = f.getId();
            when(findingRepository.findByIdAndProjectId(fId, projectId)).thenReturn(Optional.of(f));

            assertThatThrownBy(() -> findingService.transitionStatus(projectId, fId, FindingStatus.VERIFIED_CLOSED))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsFinding() {
            var f = makeFinding();
            when(findingRepository.findByIdAndProjectId(f.getId(), projectId)).thenReturn(Optional.of(f));

            var result = findingService.getById(projectId, f.getId());

            assertThat(result.getUid()).isEqualTo("FIND-001");
        }

        @Test
        void throwsWhenNotFound() {
            var id = UUID.randomUUID();
            when(findingRepository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> findingService.getById(projectId, id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetByUid {

        @Test
        void returnsFinding() {
            var f = makeFinding();
            when(findingRepository.findByProjectIdAndUid(projectId, "FIND-001")).thenReturn(Optional.of(f));

            var result = findingService.getByUid("FIND-001", projectId);

            assertThat(result.getId()).isEqualTo(f.getId());
        }

        @Test
        void throwsWhenNotFound() {
            when(findingRepository.findByProjectIdAndUid(projectId, "FIND-999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> findingService.getByUid("FIND-999", projectId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void listsFindings() {
            when(findingRepository.findByProjectIdOrderByCreatedAtDesc(projectId))
                    .thenReturn(List.of(makeFinding()));

            var result = findingService.listByProject(projectId);

            assertThat(result).hasSize(1);
        }
    }
}

package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import com.keplerops.groundcontrol.domain.baselines.model.Baseline;
import com.keplerops.groundcontrol.domain.baselines.repository.BaselineRepository;
import com.keplerops.groundcontrol.domain.baselines.service.BaselineService;
import com.keplerops.groundcontrol.domain.baselines.service.CreateBaselineCommand;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.repository.ProjectRepository;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BaselineServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PROJECT_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002");
    private static final UUID BASELINE_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_BASELINE_ID = UUID.fromString("b0000000-0000-0000-0000-000000000002");

    @Mock
    private BaselineRepository baselineRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private AsOfRevisionResolver asOfRevisionResolver;

    private BaselineService service;

    @BeforeEach
    void setUp() {
        service = new BaselineService(baselineRepository, projectRepository, entityManager, asOfRevisionResolver);
    }

    private Project makeProject() {
        var project = new Project("test-project", "Test Project");
        setField(project, "id", PROJECT_ID);
        return project;
    }

    private Baseline makeBaseline(UUID id, String name, int revisionNumber) {
        var project = makeProject();
        var baseline = new Baseline(project, name, "desc", revisionNumber, "test-actor");
        setField(baseline, "id", id);
        setField(baseline, "createdAt", Instant.now());
        return baseline;
    }

    private Baseline makeBaselineForProject(UUID id, String name, int revisionNumber, UUID projectId) {
        var project = new Project("other-project", "Other Project");
        setField(project, "id", projectId);
        var baseline = new Baseline(project, name, "desc", revisionNumber, "test-actor");
        setField(baseline, "id", id);
        setField(baseline, "createdAt", Instant.now());
        return baseline;
    }

    private static Requirement makeRequirement(UUID id, String uid, String title) {
        var req = new Requirement(null, uid, title, "Statement for " + uid);
        setField(req, "id", id);
        return req;
    }

    private static void setField(Object target, String fieldName, Object value) {
        TestUtil.setField(target, fieldName, value);
    }

    @Nested
    class Create {

        @Test
        void createsBaselineSuccessfully() {
            var project = makeProject();
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(baselineRepository.existsByProjectIdAndName(PROJECT_ID, "v1.0"))
                    .thenReturn(false);
            when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.of(42));
            when(baselineRepository.save(any(Baseline.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateBaselineCommand(PROJECT_ID, "v1.0", "First release");
            var result = service.create(command);

            assertThat(result.getName()).isEqualTo("v1.0");
            assertThat(result.getDescription()).isEqualTo("First release");
            assertThat(result.getRevisionNumber()).isEqualTo(42);
            assertThat(result.getProject().getId()).isEqualTo(PROJECT_ID);
        }

        @Test
        void mapsEmptyRevisionToOriginSentinelWhenNoRevisionsExistYet() {
            // AsOfRevisionResolver.currentRevision() returns Optional.empty() when revinfo has no
            // rows at all (nothing audited yet). Baseline.revisionNumber is a persisted int and
            // getRequirementsAtRevision(0) is defined to return List.of() — BaselineService owns
            // mapping the resolver's honest "no revision" to that local origin sentinel; the
            // resolver itself must never invent a 0 revision (see AsOfRevisionResolverTest).
            var project = makeProject();
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(baselineRepository.existsByProjectIdAndName(PROJECT_ID, "v1.0"))
                    .thenReturn(false);
            when(asOfRevisionResolver.currentRevision()).thenReturn(Optional.empty());
            when(baselineRepository.save(any(Baseline.class))).thenAnswer(inv -> inv.getArgument(0));

            var command = new CreateBaselineCommand(PROJECT_ID, "v1.0", "First release");
            var result = service.create(command);

            // 0 is the Baseline aggregate's origin sentinel, mapped here from Optional.empty().
            assertThat(result.getRevisionNumber()).isZero();
        }

        @Test
        void throwsConflictForDuplicateName() {
            var project = makeProject();
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(baselineRepository.existsByProjectIdAndName(PROJECT_ID, "v1.0"))
                    .thenReturn(true);

            var command = new CreateBaselineCommand(PROJECT_ID, "v1.0", "duplicate");
            assertThatThrownBy(() -> service.create(command)).isInstanceOf(ConflictException.class);
        }

        @Test
        void throwsNotFoundForMissingProject() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            var command = new CreateBaselineCommand(PROJECT_ID, "v1.0", null);
            assertThatThrownBy(() -> service.create(command)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsBaselineWhenFound() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 10);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));

            var result = service.getById(BASELINE_ID);
            assertThat(result.getName()).isEqualTo("v1.0");
        }

        @Test
        void throwsNotFoundWhenMissing() {
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(BASELINE_ID)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ListByProject {

        @Test
        void returnsBaselinesForProject() {
            var b1 = makeBaseline(BASELINE_ID, "v1.0", 10);
            var b2 = makeBaseline(OTHER_BASELINE_ID, "v2.0", 20);
            when(baselineRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                    .thenReturn(List.of(b2, b1));

            var result = service.listByProject(PROJECT_ID);
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    class GetSnapshot {

        @Test
        void returnsSnapshotWithRequirements() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 42);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));

            var req1 = makeRequirement(UUID.randomUUID(), "REQ-001", "First");
            var req2 = makeRequirement(UUID.randomUUID(), "REQ-002", "Second");

            var spyService = spy(service);
            doReturn(List.of(req1, req2)).when(spyService).getRequirementsAtRevision(42, PROJECT_ID);

            var snapshot = spyService.getSnapshot(BASELINE_ID);
            assertThat(snapshot.baselineId()).isEqualTo(BASELINE_ID);
            assertThat(snapshot.name()).isEqualTo("v1.0");
            assertThat(snapshot.revisionNumber()).isEqualTo(42);
            assertThat(snapshot.requirements()).hasSize(2);
        }

        @Test
        void returnsEmptySnapshotAtRevisionZero() {
            var baseline = makeBaseline(BASELINE_ID, "empty", 0);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));

            var spyService = spy(service);
            doReturn(List.of()).when(spyService).getRequirementsAtRevision(0, PROJECT_ID);

            var snapshot = spyService.getSnapshot(BASELINE_ID);
            assertThat(snapshot.requirements()).isEmpty();
        }
    }

    @Nested
    class Compare {

        @Test
        void detectsAddedRemovedAndModifiedRequirements() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 10);
            var other = makeBaseline(OTHER_BASELINE_ID, "v2.0", 20);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));
            when(baselineRepository.findById(OTHER_BASELINE_ID)).thenReturn(Optional.of(other));

            var sharedId = UUID.randomUUID();
            var removedId = UUID.randomUUID();
            var addedId = UUID.randomUUID();

            var baseReq = makeRequirement(sharedId, "REQ-001", "Original Title");
            var removedReq = makeRequirement(removedId, "REQ-002", "Removed");
            var modifiedReq = makeRequirement(sharedId, "REQ-001", "Modified Title");
            var addedReq = makeRequirement(addedId, "REQ-003", "Added");

            var spyService = spy(service);
            doReturn(List.of(baseReq, removedReq)).when(spyService).getRequirementsAtRevision(10, PROJECT_ID);
            doReturn(List.of(modifiedReq, addedReq)).when(spyService).getRequirementsAtRevision(20, PROJECT_ID);

            var comparison = spyService.compare(BASELINE_ID, OTHER_BASELINE_ID);

            assertThat(comparison.baselineName()).isEqualTo("v1.0");
            assertThat(comparison.otherBaselineName()).isEqualTo("v2.0");
            assertThat(comparison.added()).hasSize(1);
            assertThat(comparison.added().getFirst().getUid()).isEqualTo("REQ-003");
            assertThat(comparison.removed()).hasSize(1);
            assertThat(comparison.removed().getFirst().getUid()).isEqualTo("REQ-002");
            assertThat(comparison.modified()).hasSize(1);
            assertThat(comparison.modified().getFirst().uid()).isEqualTo("REQ-001");
        }

        @Test
        void throwsDomainValidationForCrossProjectComparison() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 10);
            var other = makeBaselineForProject(OTHER_BASELINE_ID, "v2.0", 20, OTHER_PROJECT_ID);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));
            when(baselineRepository.findById(OTHER_BASELINE_ID)).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.compare(BASELINE_ID, OTHER_BASELINE_ID))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("different projects");
        }

        @Test
        void returnsEmptyDiffForIdenticalBaselines() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 10);
            var other = makeBaseline(OTHER_BASELINE_ID, "v1.0-copy", 10);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));
            when(baselineRepository.findById(OTHER_BASELINE_ID)).thenReturn(Optional.of(other));

            var req = makeRequirement(UUID.randomUUID(), "REQ-001", "Same");

            var spyService = spy(service);
            doReturn(List.of(req)).when(spyService).getRequirementsAtRevision(anyInt(), eq(PROJECT_ID));

            var comparison = spyService.compare(BASELINE_ID, OTHER_BASELINE_ID);
            assertThat(comparison.added()).isEmpty();
            assertThat(comparison.removed()).isEmpty();
            assertThat(comparison.modified()).isEmpty();
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesBaselineSuccessfully() {
            var baseline = makeBaseline(BASELINE_ID, "v1.0", 10);
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(baseline));

            service.delete(BASELINE_ID);
            verify(baselineRepository).delete(baseline);
        }

        @Test
        void throwsNotFoundWhenMissing() {
            when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(BASELINE_ID)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class HasChanged {

        @Test
        void returnsFalseForIdenticalRequirements() {
            var a = makeRequirement(UUID.randomUUID(), "REQ-001", "Same");
            var b = makeRequirement(UUID.randomUUID(), "REQ-001", "Same");
            assertThat(service.hasChanged(a, b)).isFalse();
        }

        @Test
        void returnsTrueForDifferentTitle() {
            var a = makeRequirement(UUID.randomUUID(), "REQ-001", "Title A");
            var b = makeRequirement(UUID.randomUUID(), "REQ-001", "Title B");
            assertThat(service.hasChanged(a, b)).isTrue();
        }
    }
}

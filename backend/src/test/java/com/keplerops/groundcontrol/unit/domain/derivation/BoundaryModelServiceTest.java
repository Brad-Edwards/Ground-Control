package com.keplerops.groundcontrol.unit.domain.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.model.SystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelAssignmentRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelBoundaryRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelGapRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.BoundaryModelSnapshotRepository;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryDeclaration;
import com.keplerops.groundcontrol.domain.derivation.service.BoundaryModelService;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BoundaryModelServiceTest {

    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";
    private static final Instant NOW = Instant.parse("2026-06-13T11:00:00Z");

    @Mock
    private BoundaryModelSnapshotRepository snapshotRepository;

    @Mock
    private BoundaryModelBoundaryRepository boundaryRepository;

    @Mock
    private BoundaryModelAssignmentRepository assignmentRepository;

    @Mock
    private BoundaryModelGapRepository gapRepository;

    private BoundaryModelService service;
    private Project project;
    private DerivationRun run;

    @BeforeEach
    void setUp() {
        service = new BoundaryModelService(snapshotRepository, boundaryRepository, assignmentRepository, gapRepository);
        project = new Project("ground-control", "Ground Control");
        run = new DerivationRun(
                project,
                DerivationScopeMode.FULL_REPO,
                COMMIT,
                null,
                List.of(),
                List.of("java"),
                List.of("application"),
                "codex",
                NOW,
                2);
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(boundaryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assignmentRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gapRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void declaredOnlyOperationProducesVersionedBoundarySet() {
        var declaration = new BoundaryDeclaration(
                "declared-policy",
                "Policy and workflow",
                "Repo policy and workflow surfaces",
                List.of("tools/policy/**"),
                List.of("policy"));

        var result = service.build(project, run, List.of(), List.of(declaration));

        assertThat(result).isNotNull();
        assertThat(result.snapshot().getBoundaryCount()).isEqualTo(1);
        assertThat(result.snapshot().getAssignmentCount()).isZero();
        assertThat(result.snapshot().getGapCount()).isZero();
        assertThat(result.snapshot().getBoundarySetVersion()).startsWith("boundary-set/");
        assertThat(result.snapshot().getArchitectureModelVersion()).contains(COMMIT.substring(0, 12));
        assertThat(result.boundaries()).singleElement().satisfies(boundary -> {
            assertThat(boundary.getBoundaryKey()).isEqualTo("declared-policy");
            assertThat(boundary.getSource()).isEqualTo("DECLARED");
            assertThat(boundary.getPathSelectors()).containsExactly("tools/policy/**");
        });
    }

    @Test
    void matchingComponentReceivesBoundaryAssignment() {
        var facts = List.of(
                fact(
                        SystemModelFactKind.TRUST_BOUNDARY,
                        "boundary:backend-api",
                        null,
                        Map.of(
                                "boundaryKey", "backend-api",
                                "boundaryName", "Backend API",
                                "boundarySource", "derived",
                                "pathSelectors", List.of("backend/src/main/java/com/example/api/**"),
                                "surfaces", List.of("application"))),
                fact(
                        SystemModelFactKind.COMPONENT,
                        "component:api-controller",
                        "backend/src/main/java/com/example/api/FooController.java",
                        Map.of("surface", "application")));

        var result = service.build(project, run, facts, List.of());

        assertThat(result.assignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.getSourceFactKey()).isEqualTo("component:api-controller");
            assertThat(assignment.getBoundary().getBoundaryKey()).isEqualTo("backend-api");
            assertThat(assignment.getStrategy()).isEqualTo("PATH_SELECTOR");
        });
        assertThat(result.gaps()).isEmpty();
    }

    @Test
    void unassignableComponentBecomesModelingGapNotCaptureLimit() {
        var facts = List.of(
                fact(
                        SystemModelFactKind.TRUST_BOUNDARY,
                        "boundary:backend-api",
                        null,
                        Map.of(
                                "boundaryKey", "backend-api",
                                "boundaryName", "Backend API",
                                "boundarySource", "derived",
                                "pathSelectors", List.of("backend/src/main/java/com/example/api/**"),
                                "surfaces", List.of("application"))),
                fact(
                        SystemModelFactKind.DATA_FLOW,
                        "flow:frontend",
                        "frontend/src/App.tsx",
                        Map.of("surface", "frontend")));

        var result = service.build(project, run, facts, List.of());

        assertThat(result.assignments()).isEmpty();
        assertThat(result.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.getSourceFactKey()).isEqualTo("flow:frontend");
            assertThat(gap.getReason()).isEqualTo("UNASSIGNED_BOUNDARY");
            assertThat(gap.getSourcePath()).isEqualTo("frontend/src/App.tsx");
        });
    }

    @Test
    void assignableFactWithoutBoundaryInputsStillProducesGapSnapshot() {
        var facts = List.of(fact(
                SystemModelFactKind.COMPONENT,
                "component:worker",
                "backend/src/main/java/com/example/Worker.java",
                Map.of("surface", "application")));

        var result = service.build(project, run, facts, List.of());

        assertThat(result).isNotNull();
        assertThat(result.boundaries()).isEmpty();
        assertThat(result.assignments()).isEmpty();
        assertThat(result.snapshot().getBoundaryCount()).isZero();
        assertThat(result.snapshot().getAssignmentCount()).isZero();
        assertThat(result.snapshot().getGapCount()).isEqualTo(1);
        assertThat(result.gaps()).singleElement().satisfies(gap -> {
            assertThat(gap.getSourceFactKey()).isEqualTo("component:worker");
            assertThat(gap.getReason()).isEqualTo("UNASSIGNED_BOUNDARY");
            assertThat(gap.getSourcePath()).isEqualTo("backend/src/main/java/com/example/Worker.java");
        });
    }

    @Test
    void derivedAndDeclaredInputsMergeByStableBoundaryKey() {
        var facts = List.of(fact(
                SystemModelFactKind.TRUST_BOUNDARY,
                "boundary:backend-api",
                null,
                Map.of(
                        "boundaryKey", "backend-api",
                        "boundaryName", "Backend API",
                        "boundarySource", "derived",
                        "pathSelectors", List.of("backend/src/main/java/com/example/api/**"),
                        "surfaces", List.of("application"))));
        var declaration = new BoundaryDeclaration(
                "backend-api",
                "Declared API",
                "Declared override metadata",
                List.of("backend/src/test/java/com/example/api/**"),
                List.of("test"));

        var result = service.build(project, run, facts, List.of(declaration));

        assertThat(result.boundaries()).singleElement().satisfies(boundary -> {
            assertThat(boundary.getBoundaryKey()).isEqualTo("backend-api");
            assertThat(boundary.getSource()).isEqualTo("MERGED");
            assertThat(boundary.getDisplayName()).isEqualTo("Declared API");
            assertThat(boundary.getPathSelectors())
                    .containsExactly(
                            "backend/src/main/java/com/example/api/**", "backend/src/test/java/com/example/api/**");
        });
    }

    private SystemModelFact fact(SystemModelFactKind kind, String key, String sourcePath, Map<String, Object> payload) {
        return new SystemModelFact(
                project,
                run,
                new DerivedSystemModelFact(
                        kind,
                        key,
                        key,
                        "summary",
                        sourcePath,
                        payload,
                        new DerivationFactProvenance("adapter", "tool", "1.0.0", "rules", "1.0.0", COMMIT, NOW)));
    }
}

package com.keplerops.groundcontrol.unit.domain.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.audit.ActorHolder;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationCaptureLimit;
import com.keplerops.groundcontrol.domain.derivation.model.DerivationRun;
import com.keplerops.groundcontrol.domain.derivation.repository.DerivationCaptureLimitRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.DerivationRunRepository;
import com.keplerops.groundcontrol.domain.derivation.repository.SystemModelFactRepository;
import com.keplerops.groundcontrol.domain.derivation.service.CreateDerivationRunCommand;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapter;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterDescriptor;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRegistry;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationCaptureLimitDraft;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationRoutePlan;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationService;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class DerivationServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111114");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222214");
    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";
    private static final String BASE_COMMIT = "16792466cf2a1464792846b083d1bd885299b3c";
    private static final Instant DERIVED_AT = Instant.parse("2026-06-13T11:00:00Z");

    @Mock
    private DerivationRunRepository runRepository;

    @Mock
    private SystemModelFactRepository factRepository;

    @Mock
    private DerivationCaptureLimitRepository captureLimitRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private DerivationAdapterRegistry adapterRegistry;

    @Mock
    private TransactionTemplate transactionTemplate;

    private DerivationService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new DerivationService(
                runRepository,
                factRepository,
                captureLimitRepository,
                projectService,
                adapterRegistry,
                transactionTemplate);
        project = new Project("gc-test", "Ground Control Test");
    }

    @AfterEach
    void clearActor() {
        ActorHolder.clear();
    }

    @Test
    void runNormalizesScopeExecutesAdaptersAndPersistsValidatedRows() {
        stubProject();
        stubTransaction();
        stubPersistence();
        ActorHolder.set("codex");

        var adapter = adapter(request -> {
            assertThat(request.projectIdentifier()).isEqualTo("gc-test");
            assertThat(request.scope().mode()).isEqualTo(DerivationScopeMode.DIFF);
            assertThat(request.scope().commitSha()).isEqualTo(COMMIT);
            assertThat(request.scope().baseCommitSha()).isEqualTo(BASE_COMMIT);
            assertThat(request.scope().paths()).containsExactly("backend/src/App.java");
            assertThat(request.scope().languages()).containsExactly("java");
            assertThat(request.scope().surfaces()).containsExactly("application");
            return new DerivationAdapterResult(
                    List.of(fact("component:gc-test", request.scope().commitSha(), Map.of("scopeMode", "DIFF"))),
                    List.of(captureLimit(
                            "adapter-test",
                            CaptureLimitReason.UNSUPPORTED_SURFACE,
                            "java",
                            "cli",
                            request.scope().commitSha())));
        });
        when(adapterRegistry.route(any(), any()))
                .thenReturn(new DerivationRoutePlan(
                        List.of(adapter),
                        List.of(captureLimit(
                                "registry",
                                CaptureLimitReason.UNSUPPORTED_LANGUAGE,
                                "python",
                                "application",
                                COMMIT))));

        var result = service.run(new CreateDerivationRunCommand(
                PROJECT_ID,
                DerivationScopeMode.DIFF,
                COMMIT.toUpperCase(Locale.ROOT),
                BASE_COMMIT.toUpperCase(Locale.ROOT),
                List.of("./backend/src/App.java"),
                List.of("Java", "JAVA"),
                List.of("Application")));

        assertThat(result.run().getRequestedBy()).isEqualTo("codex");
        assertThat(result.run().getAdapterCount()).isEqualTo(1);
        assertThat(result.run().getFactCount()).isEqualTo(1);
        assertThat(result.run().getCaptureLimitCount()).isEqualTo(2);
        assertThat(result.facts()).singleElement().satisfies(row -> {
            assertThat(row.getFactKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(row.getCommitSha()).isEqualTo(COMMIT);
            assertThat(row.getAdapterId()).isEqualTo("adapter-test");
        });
        assertThat(result.captureLimits())
                .extracting(DerivationCaptureLimit::getReason)
                .containsExactly(CaptureLimitReason.UNSUPPORTED_LANGUAGE, CaptureLimitReason.UNSUPPORTED_SURFACE);
    }

    @Test
    void runRecordsToolExecutionFailureAsCaptureLimit() {
        stubProject();
        stubTransaction();
        stubPersistence();
        var adapter = adapter(request -> {
            throw new IllegalStateException("raw analyzer failure");
        });
        when(adapterRegistry.route(any(), any())).thenReturn(new DerivationRoutePlan(List.of(adapter), List.of()));

        var result = service.run(validCommand());

        assertThat(result.facts()).isEmpty();
        assertThat(result.captureLimits()).singleElement().satisfies(limit -> {
            assertThat(limit.getAdapterId()).isEqualTo("adapter-test");
            assertThat(limit.getReason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED);
            assertThat(limit.getDetail()).contains("raw tool output was not persisted");
            assertThat(limit.getCommitSha()).isEqualTo(COMMIT);
        });
    }

    @Test
    void runRejectsSensitiveNestedFactPayloadBeforePersistence() {
        stubProject();
        when(adapterRegistry.route(any(), any()))
                .thenReturn(new DerivationRoutePlan(
                        List.of(adapter(request -> DerivationAdapterResult.facts(List.of(fact(
                                "component:secret",
                                COMMIT,
                                Map.of("metadata", Map.of("raw_output", "do-not-store"))))))),
                        List.of()));

        assertThatThrownBy(() -> service.run(validCommand()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("blocked raw-content field");
        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void runRejectsFactProvenanceForDifferentCommit() {
        stubProject();
        when(adapterRegistry.route(any(), any()))
                .thenReturn(new DerivationRoutePlan(
                        List.of(adapter(request -> DerivationAdapterResult.facts(
                                List.of(fact("component:wrong-commit", BASE_COMMIT, Map.of()))))),
                        List.of()));

        assertThatThrownBy(() -> service.run(validCommand()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("provenance commitSha");
        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void runRejectsIncompleteCaptureLimitBeforePersistence() {
        stubProject();
        when(adapterRegistry.route(any(), any()))
                .thenReturn(new DerivationRoutePlan(
                        List.of(),
                        List.of(new DerivationCaptureLimitDraft(
                                "adapter-test",
                                CaptureLimitReason.TOOL_UNAVAILABLE,
                                "java",
                                "application",
                                "Unavailable",
                                BASE_COMMIT,
                                DERIVED_AT))));

        assertThatThrownBy(() -> service.run(validCommand()))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("Capture limit commitSha");
        verify(transactionTemplate, never()).execute(any(TransactionCallback.class));
    }

    @Test
    void runFailsWhenTransactionDoesNotReturnResult() {
        stubProject();
        when(adapterRegistry.route(any(), any())).thenReturn(new DerivationRoutePlan(List.of(), List.of()));
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenReturn(null);

        assertThatThrownBy(() -> service.run(validCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction returned no result");
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void runRejectsInvalidScopeBeforeRouting(CreateDerivationRunCommand command) {
        stubProject();

        assertThatThrownBy(() -> service.run(command)).isInstanceOf(DomainValidationException.class);
        verifyNoInteractions(adapterRegistry, transactionTemplate);
    }

    @Test
    void listMethodsRouteToProjectScopedRepositories() {
        var run = runEntity();
        when(runRepository.findByProjectIdOrderByRequestedAtDesc(PROJECT_ID)).thenReturn(List.of(run));
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.of(run));
        when(factRepository.findByProjectIdAndDerivationRunIdAndFactKindOrderByDerivedAtDesc(
                        PROJECT_ID, RUN_ID, SystemModelFactKind.COMPONENT))
                .thenReturn(List.of());
        when(factRepository.findByProjectIdAndDerivationRunIdOrderByDerivedAtDesc(PROJECT_ID, RUN_ID))
                .thenReturn(List.of());
        when(factRepository.findByProjectIdAndFactKindOrderByDerivedAtDesc(PROJECT_ID, SystemModelFactKind.COMPONENT))
                .thenReturn(List.of());
        when(factRepository.findByProjectIdOrderByDerivedAtDesc(PROJECT_ID)).thenReturn(List.of());
        when(captureLimitRepository.findByProjectIdAndDerivationRunIdAndReasonOrderByCapturedAtDesc(
                        PROJECT_ID, RUN_ID, CaptureLimitReason.TOOL_UNAVAILABLE))
                .thenReturn(List.of());
        when(captureLimitRepository.findByProjectIdAndDerivationRunIdOrderByCapturedAtDesc(PROJECT_ID, RUN_ID))
                .thenReturn(List.of());
        when(captureLimitRepository.findByProjectIdAndReasonOrderByCapturedAtDesc(
                        PROJECT_ID, CaptureLimitReason.TOOL_UNAVAILABLE))
                .thenReturn(List.of());
        when(captureLimitRepository.findByProjectIdOrderByCapturedAtDesc(PROJECT_ID))
                .thenReturn(List.of());

        assertThat(service.listRuns(PROJECT_ID)).containsExactly(run);
        assertThat(service.getRun(PROJECT_ID, RUN_ID)).isSameAs(run);
        service.listFacts(PROJECT_ID, RUN_ID, SystemModelFactKind.COMPONENT);
        service.listFacts(PROJECT_ID, RUN_ID, null);
        service.listFacts(PROJECT_ID, null, SystemModelFactKind.COMPONENT);
        service.listFacts(PROJECT_ID, null, null);
        service.listCaptureLimits(PROJECT_ID, RUN_ID, CaptureLimitReason.TOOL_UNAVAILABLE);
        service.listCaptureLimits(PROJECT_ID, RUN_ID, null);
        service.listCaptureLimits(PROJECT_ID, null, CaptureLimitReason.TOOL_UNAVAILABLE);
        service.listCaptureLimits(PROJECT_ID, null, null);

        verify(factRepository)
                .findByProjectIdAndDerivationRunIdAndFactKindOrderByDerivedAtDesc(
                        PROJECT_ID, RUN_ID, SystemModelFactKind.COMPONENT);
        verify(factRepository).findByProjectIdAndDerivationRunIdOrderByDerivedAtDesc(PROJECT_ID, RUN_ID);
        verify(factRepository)
                .findByProjectIdAndFactKindOrderByDerivedAtDesc(PROJECT_ID, SystemModelFactKind.COMPONENT);
        verify(factRepository).findByProjectIdOrderByDerivedAtDesc(PROJECT_ID);
        verify(captureLimitRepository)
                .findByProjectIdAndDerivationRunIdAndReasonOrderByCapturedAtDesc(
                        PROJECT_ID, RUN_ID, CaptureLimitReason.TOOL_UNAVAILABLE);
        verify(captureLimitRepository).findByProjectIdAndDerivationRunIdOrderByCapturedAtDesc(PROJECT_ID, RUN_ID);
        verify(captureLimitRepository)
                .findByProjectIdAndReasonOrderByCapturedAtDesc(PROJECT_ID, CaptureLimitReason.TOOL_UNAVAILABLE);
        verify(captureLimitRepository).findByProjectIdOrderByCapturedAtDesc(PROJECT_ID);
    }

    @Test
    void getRunRejectsWrongProject() {
        when(runRepository.findByIdAndProjectId(RUN_ID, PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(PROJECT_ID, RUN_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Derivation run not found");
    }

    private static Stream<CreateDerivationRunCommand> invalidCommands() {
        return Stream.of(
                new CreateDerivationRunCommand(
                        PROJECT_ID, null, COMMIT, null, List.of(), List.of("java"), List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.FULL_REPO,
                        "not-a-sha",
                        null,
                        List.of(),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.DIFF,
                        COMMIT,
                        null,
                        List.of(),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of(),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.FULL_REPO,
                        COMMIT,
                        null,
                        List.of("src/App.java"),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("/src/App.java"),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("src/../App.java"),
                        List.of("java"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("src/App.java"),
                        List.of(),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("src/App.java"),
                        List.of("-bad"),
                        List.of("application")),
                new CreateDerivationRunCommand(
                        PROJECT_ID,
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        List.of("src/App.java"),
                        List.of("java"),
                        List.of()));
    }

    private CreateDerivationRunCommand validCommand() {
        return new CreateDerivationRunCommand(
                PROJECT_ID,
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("backend/src/App.java"),
                List.of("java"),
                List.of("application"));
    }

    private void stubProject() {
        when(projectService.getById(PROJECT_ID)).thenReturn(project);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubTransaction() {
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            var callback = (TransactionCallback) invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private void stubPersistence() {
        when(runRepository.save(any(DerivationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(factRepository.saveAll(any())).thenAnswer(invocation -> copyIterable(invocation.getArgument(0)));
        when(captureLimitRepository.saveAll(any())).thenAnswer(invocation -> copyIterable(invocation.getArgument(0)));
    }

    private static <T> List<T> copyIterable(Iterable<T> values) {
        return StreamSupport.stream(values.spliterator(), false).toList();
    }

    private static DerivationAdapter adapter(Function<DerivationAdapterRequest, DerivationAdapterResult> handler) {
        return new DerivationAdapter() {
            private final DerivationAdapterDescriptor descriptor = new DerivationAdapterDescriptor(
                    "adapter-test",
                    "adapter-tool",
                    "1.0.0",
                    "adapter-rules",
                    "2026.06",
                    Set.of("java"),
                    Set.of("application"),
                    Set.of(DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET),
                    Set.of(SystemModelFactKind.COMPONENT));

            @Override
            public DerivationAdapterDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public DerivationAdapterResult derive(DerivationAdapterRequest request) {
                return handler.apply(request);
            }
        };
    }

    private static DerivedSystemModelFact fact(String key, String commitSha, Map<String, Object> payload) {
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                key,
                "Component",
                "Derived component",
                "backend/src/App.java",
                payload,
                new DerivationFactProvenance(
                        "adapter-test", "adapter-tool", "1.0.0", "adapter-rules", "2026.06", commitSha, DERIVED_AT));
    }

    private static DerivationCaptureLimitDraft captureLimit(
            String adapterId, CaptureLimitReason reason, String language, String surface, String commitSha) {
        return new DerivationCaptureLimitDraft(
                adapterId, reason, language, surface, "Not covered by this adapter", commitSha, DERIVED_AT);
    }

    private DerivationRun runEntity() {
        return new DerivationRun(
                project,
                DerivationScopeMode.PATH_SET,
                COMMIT,
                null,
                List.of("backend/src/App.java"),
                List.of("java"),
                List.of("application"),
                "codex",
                DERIVED_AT,
                1);
    }
}

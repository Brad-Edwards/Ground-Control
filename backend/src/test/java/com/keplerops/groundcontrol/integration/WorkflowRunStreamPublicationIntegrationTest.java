package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pins the transaction semantics of the live-stream change notification (issue #1436).
 *
 * <p>The unit test asserts which notifications the service publishes; only a real transaction can
 * show <em>when</em> they are delivered. Announcing a run before commit would let a subscriber
 * render a fact that then rolls back — a dashboard showing telemetry the database never kept.
 */
@Import(WorkflowRunStreamPublicationIntegrationTest.StreamPublicationTestConfig.class)
class WorkflowRunStreamPublicationIntegrationTest extends BaseIntegrationTest {

    private static final String PROJECT = "ground-control";

    /** A genuinely different project identifier, so the scoped read is exercised as real SQL. */
    private static final String FOREIGN_PROJECT = "other-project";

    @Autowired
    private WorkflowTelemetryService service;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowPhaseEventRepository phaseEventRepository;

    @Autowired
    private RecordingChangeListener listener;

    @Autowired
    private RollbackHarness rollbackHarness;

    @BeforeEach
    void clear() {
        phaseEventRepository.deleteAll();
        runRepository.deleteAll();
        listener.clear();
    }

    @Test
    void deliversTheRunNotificationOnlyAfterTheTransactionCommits() {
        var saved = service.recordRun(runCommand("1436-live-telemetry-sse-stream"));

        // Delivered after commit, and the entity it names is readable — which is exactly what the
        // stream hub relies on when it reloads the projection on the listener thread.
        assertThat(listener.afterCommit).hasSize(1);
        assertThat(listener.afterCommit.get(0).entityId()).isEqualTo(saved.getId());
        assertThat(listener.afterCommit.get(0).kind()).isEqualTo(WorkflowTelemetryChangeEvent.Kind.RUN);
        assertThat(runRepository.findByIdAndProject(saved.getId(), PROJECT)).isPresent();
    }

    @Test
    void deliversAPhaseEventNotificationWhoseProjectionIsAlreadyReadable() {
        var run = service.recordRun(runCommand("1436-live-telemetry-sse-stream"));
        listener.clear();

        var event = service.recordPhaseEvent(new RecordPhaseEventCommand(
                run.getId(),
                PROJECT,
                "ci",
                PhaseEventType.COMPLETED,
                null,
                Instant.parse("2026-07-27T10:00:00Z"),
                1200L,
                "clean",
                TelemetryProvenance.LIVE_EMISSION,
                null));

        assertThat(listener.afterCommit).hasSize(1);
        var change = listener.afterCommit.get(0);
        assertThat(change.kind()).isEqualTo(WorkflowTelemetryChangeEvent.Kind.PHASE_EVENT);
        assertThat(change.entityId()).isEqualTo(event.getId());
        // The hub re-reads project-scoped rather than trusting the notification, so that read must
        // already succeed by the time the notification is delivered.
        assertThat(service.getPhaseEvent(change.entityId(), PROJECT)).isNotNull();
    }

    @Test
    void bothStreamReadPathsDenyAForeignProjectAgainstTheRealDatabase() {
        // These two methods are the isolation boundary the stream hub reloads through on a delivery
        // thread, so "a stream is not an access-control exemption" rests entirely on them. A mocked
        // repository returning empty proves nothing here: it would keep passing if the project
        // predicate were dropped and the query became a plain findById. Persist under one project
        // and read as another, so only the real SQL can satisfy this.
        var run = service.recordRun(runCommand("1436-live-telemetry-sse-stream"));
        var event = service.recordPhaseEvent(new RecordPhaseEventCommand(
                run.getId(),
                PROJECT,
                "ci",
                PhaseEventType.COMPLETED,
                null,
                Instant.parse("2026-07-27T10:00:00Z"),
                1200L,
                "clean",
                TelemetryProvenance.LIVE_EMISSION,
                null));

        var runId = run.getId();
        var eventId = event.getId();

        assertThatThrownBy(() -> service.getRun(runId, FOREIGN_PROJECT)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getPhaseEvent(eventId, FOREIGN_PROJECT))
                .isInstanceOf(NotFoundException.class);

        // The owning project still resolves both, so the assertions above are denial and not a
        // lookup that was broken for everyone.
        assertThat(service.getRun(runId, PROJECT).getId()).isEqualTo(runId);
        assertThat(service.getPhaseEvent(eventId, PROJECT).getId()).isEqualTo(eventId);
    }

    @Test
    void announcesNothingWhenTheTransactionRollsBack() {
        var doomed = runCommand("1436-doomed");

        assertThatThrownBy(() -> rollbackHarness.recordThenFail(doomed)).isInstanceOf(IllegalStateException.class);

        // A rolled-back write is not a fact. Publishing it would push a run into every watching
        // dashboard that the database does not contain.
        assertThat(listener.afterCommit).isEmpty();
        assertThat(runRepository.findByProjectOrderByCreatedAtDesc(PROJECT, PageRequest.of(0, 10)))
                .isEmpty();
    }

    private static RecordWorkflowRunCommand runCommand(String branch) {
        return new RecordWorkflowRunCommand(
                PROJECT,
                "autarchy-ai/Ground-Control",
                1436,
                null,
                branch,
                "implement",
                "claude-code",
                null,
                Instant.parse("2026-07-27T09:00:00Z"),
                null,
                WorkflowRunState.RUNNING,
                WorkflowRunOutcome.NONE,
                TelemetryProvenance.LIVE_EMISSION,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Imported explicitly rather than component-scanned: these helpers exist for this test only,
     * and a stray {@code @Component} under the scanned package would join every other integration
     * test's context.
     */
    @TestConfiguration
    static class StreamPublicationTestConfig {

        @Bean
        RecordingChangeListener recordingChangeListener() {
            return new RecordingChangeListener();
        }

        @Bean
        RollbackHarness rollbackHarness(WorkflowTelemetryService service) {
            return new RollbackHarness(service);
        }
    }

    /** Captures notifications at the same phase the stream hub listens on. */
    static class RecordingChangeListener {

        private final List<WorkflowTelemetryChangeEvent> afterCommit = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onAfterCommit(WorkflowTelemetryChangeEvent change) {
            afterCommit.add(change);
        }

        void clear() {
            afterCommit.clear();
        }
    }

    /** Runs a record inside a transaction that then fails, so the publication must be discarded. */
    static class RollbackHarness {

        private final WorkflowTelemetryService service;

        RollbackHarness(WorkflowTelemetryService service) {
            this.service = service;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void recordThenFail(RecordWorkflowRunCommand command) {
            service.recordRun(command);
            throw new IllegalStateException("forced rollback after the telemetry write");
        }
    }
}

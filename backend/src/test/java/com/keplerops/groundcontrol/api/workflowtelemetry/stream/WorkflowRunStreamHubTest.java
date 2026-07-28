package com.keplerops.groundcontrol.api.workflowtelemetry.stream;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Split from WorkflowRunStreamHubTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class WorkflowRunStreamHubTest {
    private static final String PROJECT = "ground-control";
    private static final String OTHER_PROJECT = "other-project";
    private static final String PRINCIPAL = "operator";
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000001436");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-27T00:00:00Z");

    private WorkflowTelemetryService telemetryService;
    private ManualExecutorService deliveryExecutor;
    private ScheduledExecutorService scheduler;
    private WorkflowRunStreamProperties properties;
    private TestHub hub;

    @BeforeEach
    void setUp() {
        telemetryService = mock(WorkflowTelemetryService.class);
        deliveryExecutor = new ManualExecutorService();
        scheduler = mock(ScheduledExecutorService.class);
        properties = new WorkflowRunStreamProperties();
        properties.setMaxConnections(4);
        properties.setMaxConnectionsPerPrincipal(2);
        properties.setQueueCapacity(2);
        hub = new TestHub(telemetryService, streamObjectMapper(), properties, deliveryExecutor, scheduler);
    }

    /**
     * Mirrors the mapper Spring Boot injects in production. A bare {@code ObjectMapper} cannot write
     * {@code Instant} at all, so testing against one would hide the fact that every timestamp on the
     * wire depends on the JSR-310 module being registered.
     */
    private static ObjectMapper streamObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private RecordingEmitter subscribe(String project, String principal) {
        return (RecordingEmitter) hub.subscribe(project, principal);
    }

    private static String rendered(SseEmitter.SseEventBuilder builder) {
        var text = new StringBuilder();
        builder.build().forEach(part -> text.append(part.getData()));
        return text.toString();
    }

    private static WorkflowRun sampleRun() {
        var run = new WorkflowRun(PROJECT, "implement", TelemetryProvenance.LIVE_EMISSION);
        setField(run, "id", RUN_ID);
        run.setIssueNumber(1436);
        run.setBranch("1436-live-telemetry-sse-stream");
        run.setStartedAt(OCCURRED_AT);
        run.setFinalState(WorkflowRunState.READY_FOR_REVIEW);
        return run;
    }

    private static WorkflowPhaseEvent sampleEvent() {
        var event = new WorkflowPhaseEvent(
                RUN_ID, PROJECT, "ci", PhaseEventType.COMPLETED, OCCURRED_AT, 1000L, TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(0);
        event.setSourceId("ci:COMPLETED:0");
        return event;
    }

    /** Hub that hands out observable emitters instead of ones bound to a real response. */
    private static final class TestHub extends WorkflowRunStreamHub {

        private TestHub(
                WorkflowTelemetryService telemetryService,
                ObjectMapper objectMapper,
                WorkflowRunStreamProperties properties,
                java.util.concurrent.ExecutorService deliveryExecutor,
                ScheduledExecutorService heartbeatScheduler) {
            super(telemetryService, objectMapper, properties, deliveryExecutor, heartbeatScheduler);
        }

        @Override
        SseEmitter createEmitter(long timeoutMillis) {
            return new RecordingEmitter(timeoutMillis);
        }
    }

    /** {@link SseEmitter} that records writes instead of performing them, and can fail on demand. */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<SseEventBuilder> sent = new CopyOnWriteArrayList<>();
        /** Counted down on every successful send so a concurrent test can await delivery. */
        private final java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);

        private boolean failNextSend;
        /** When set, send() blocks on this latch — a client that stopped reading, not one that failed. */
        private java.util.concurrent.CountDownLatch blockOn;

        private boolean completed;
        private Runnable completionCallback = () -> {};
        private Runnable timeoutCallback = () -> {};
        private java.util.function.Consumer<Throwable> errorCallback = error -> {};

        private RecordingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failNextSend) {
                throw new IOException("client gone");
            }
            if (blockOn != null) {
                try {
                    blockOn.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while blocked", interrupted);
                }
            }
            sent.add(builder);
            delivered.countDown();
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallback = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallback = callback;
        }

        @Override
        public void onError(java.util.function.Consumer<Throwable> callback) {
            errorCallback = callback;
        }

        private void fireCompletion() {
            completionCallback.run();
        }

        private void fireTimeout() {
            timeoutCallback.run();
        }

        private void fireError() {
            errorCallback.accept(new IllegalStateException("broken pipe"));
        }
    }

    /** Executor that collects tasks until {@link #runAll()} pumps them, so delivery timing is explicit. */
    private static final class ManualExecutorService extends AbstractExecutorService {

        private final Deque<Runnable> pending = new ArrayDeque<>();
        private boolean rejectEverything;
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (rejectEverything) {
                throw new java.util.concurrent.RejectedExecutionException("saturated");
            }
            pending.add(command);
        }

        private void runAll() {
            while (!pending.isEmpty()) {
                pending.poll().run();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            pending.clear();
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            // Shutdown alone is not termination: queued tasks may still be pending.
            return shutdown && pending.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    // ---- project isolation ---------------------------------------------------------------------

    @Test
    void deliversOnlyToSubscribersOfTheEventsProject() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        var otherWatcher = subscribe(OTHER_PROJECT, "other-operator");
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        deliveryExecutor.runAll();

        assertThat(watcher.sent).hasSize(1);
        var frame = rendered(watcher.sent.get(0));
        assertThat(frame).contains("workflow-run").contains("\"startedAt\":\"2026-07-27T00:00:00Z\"");
        assertThat(otherWatcher.sent).isEmpty();
    }

    @Test
    void doesNotReadTheProjectionWhenNobodyIsWatchingThatProject() {
        subscribe(OTHER_PROJECT, PRINCIPAL);

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));

        verify(telemetryService, never()).getRun(any(), any());
    }

    @Test
    void deliversPhaseEventsAsTheRestProjectionShape() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        var eventId = UUID.randomUUID();
        when(telemetryService.getPhaseEvent(eventId, PROJECT)).thenReturn(sampleEvent());

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.phaseEvent(PROJECT, RUN_ID, eventId));
        deliveryExecutor.runAll();

        assertThat(watcher.sent).hasSize(1);
        var frame = rendered(watcher.sent.get(0));
        // The payload is the REST DTO, timestamps included — not a stream-only envelope.
        assertThat(frame)
                .contains("phase-event")
                .contains("\"phase\":\"ci\"")
                .contains("\"occurredAt\":\"2026-07-27T00:00:00Z\"");
    }

    // ---- caps ----------------------------------------------------------------------------------

    @Test
    void rejectsBeyondTheGlobalConnectionCap() {
        properties.setMaxConnectionsPerPrincipal(4);
        for (int i = 0; i < 4; i++) {
            subscribe(PROJECT, "principal-" + i);
        }

        assertThatThrownBy(() -> hub.subscribe(PROJECT, "one-too-many"))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(hub.connectionCount()).isEqualTo(4);
    }

    @Test
    void rejectsBeyondThePerPrincipalCapWhileGlobalCapacityRemains() {
        subscribe(PROJECT, PRINCIPAL);
        subscribe(PROJECT, PRINCIPAL);

        assertThatThrownBy(() -> hub.subscribe(PROJECT, PRINCIPAL)).isInstanceOf(ServiceUnavailableException.class);
        // Global capacity was not exhausted — a different principal still connects.
        assertThat(hub.subscribe(PROJECT, "someone-else")).isNotNull();
    }

    @Test
    void rejectsEveryConnectionWhileDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> hub.subscribe(PROJECT, PRINCIPAL)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void releasesCapacityWhenAConnectionCloses() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        subscribe(PROJECT, PRINCIPAL);
        watcher.failNextSend = true;
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        deliveryExecutor.runAll();

        // The failed connection freed its slot, so the principal is back under the cap.
        assertThat(hub.subscribe(PROJECT, PRINCIPAL)).isNotNull();
    }

    // ---- bounded backlog -----------------------------------------------------------------------

    @Test
    void disconnectsAConsumerThatOverflowsItsQueueRatherThanDroppingAnEvent() {
        var slow = subscribe(PROJECT, PRINCIPAL);
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

        // Never pump the executor: nothing drains, so queueCapacity=2 fills and the third overflows.
        for (int i = 0; i < 3; i++) {
            hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        }

        assertThat(hub.connectionCount()).isZero();
        assertThat(slow.completed).isTrue();
        assertThat(slow.sent).isEmpty();
    }

    @Test
    void oneBlockedConsumerDoesNotStopAnotherFromReceiving() throws Exception {
        // A client that stops reading blocks inside SseEmitter.send rather than throwing. With a
        // delivery pool smaller than the connection cap, the blocked write occupies a worker and the
        // healthy connection's drain task waits behind it — so the healthy client misses events it
        // could have received. Run this against a real pool sized like production's.
        var pool = java.util.concurrent.Executors.newFixedThreadPool(properties.getMaxConnections());
        try {
            var realHub = new TestHub(telemetryService, streamObjectMapper(), properties, pool, scheduler);
            var blocked = (RecordingEmitter) realHub.subscribe(PROJECT, "blocked");
            var healthy = (RecordingEmitter) realHub.subscribe(PROJECT, "healthy");
            var release = new java.util.concurrent.CountDownLatch(1);
            blocked.blockOn = release;
            when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

            realHub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));

            try {
                // The healthy subscriber receives while the other is still stuck mid-write.
                assertThat(healthy.delivered.await(5, TimeUnit.SECONDS))
                        .as("healthy subscriber should receive while another is blocked mid-write")
                        .isTrue();
                assertThat(blocked.sent).isEmpty();
            } finally {
                release.countDown();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneFailingConsumerDoesNotStopAnotherFromReceiving() {
        var failing = subscribe(PROJECT, "failing");
        var healthy = subscribe(PROJECT, "healthy");
        failing.failNextSend = true;
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        deliveryExecutor.runAll();

        assertThat(failing.completed).isTrue();
        assertThat(healthy.completed).isFalse();
        assertThat(healthy.sent).hasSize(1);
    }

    @Test
    void disconnectsWhenTheDeliveryExecutorRefusesWork() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        deliveryExecutor.rejectEverything = true;
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(sampleRun());

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));

        assertThat(watcher.completed).isTrue();
        assertThat(hub.connectionCount()).isZero();
    }

    // ---- ordering, heartbeat, cleanup -----------------------------------------------------------

    @Test
    void preservesPerConnectionOrdering() {
        properties.setQueueCapacity(8);
        var watcher = subscribe(PROJECT, PRINCIPAL);
        var first = sampleRun();
        var second = sampleRun();
        second.setFinalState(WorkflowRunState.MERGED);
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenReturn(first, second);

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        deliveryExecutor.runAll();

        assertThat(watcher.sent).hasSize(2);
        assertThat(rendered(watcher.sent.get(0))).contains("READY_FOR_REVIEW");
        assertThat(rendered(watcher.sent.get(1))).contains("MERGED");
    }

    @Test
    void heartbeatEnqueuesACommentWithNoProductPayload() {
        var watcher = subscribe(PROJECT, PRINCIPAL);

        hub.heartbeat();
        deliveryExecutor.runAll();

        assertThat(watcher.sent).hasSize(1);
        var frame = rendered(watcher.sent.get(0));
        assertThat(frame).startsWith(":").doesNotContain("data:");
    }

    @Test
    void schedulesTheHeartbeatAtTheConfiguredInterval() {
        properties.setHeartbeatInterval(Duration.ofSeconds(7));

        hub.startHeartbeat();

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(7000L), eq(7000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void heartbeatSurvivesADisconnectingConnection() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        watcher.failNextSend = true;

        hub.heartbeat();
        deliveryExecutor.runAll();
        // A throwing tick would be cancelled by the scheduler and silently kill every later
        // heartbeat, so the second tick must still run against the surviving registry.
        hub.heartbeat();

        assertThat(hub.connectionCount()).isZero();
    }

    @Test
    void deregistersExactlyOnceWhenCleanupCallbacksRace() {
        var watcher = subscribe(PROJECT, "racer");
        subscribe(PROJECT, "racer");

        watcher.fireCompletion();
        watcher.fireTimeout();
        watcher.fireError();

        assertThat(hub.connectionCount()).isEqualTo(1);
        // Exactly one slot was released: the principal can reclaim one and only one.
        assertThat(hub.subscribe(PROJECT, "racer")).isNotNull();
        assertThatThrownBy(() -> hub.subscribe(PROJECT, "racer")).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void shutdownClosesEveryConnectionAndStopsItsThreads() {
        var watcher = subscribe(PROJECT, PRINCIPAL);

        hub.shutdown();

        assertThat(watcher.completed).isTrue();
        assertThat(hub.connectionCount()).isZero();
        verify(scheduler).shutdownNow();
    }
}

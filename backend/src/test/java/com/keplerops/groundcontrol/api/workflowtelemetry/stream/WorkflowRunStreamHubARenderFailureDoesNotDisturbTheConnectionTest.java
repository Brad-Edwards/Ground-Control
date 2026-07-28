package com.keplerops.groundcontrol.api.workflowtelemetry.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import java.io.IOException;
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
class WorkflowRunStreamHubARenderFailureDoesNotDisturbTheConnectionTest {
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

    @Test
    void aRenderFailureDoesNotDisturbTheConnection() {
        var watcher = subscribe(PROJECT, PRINCIPAL);
        when(telemetryService.getRun(RUN_ID, PROJECT)).thenThrow(new IllegalStateException("projection gone"));

        hub.onTelemetryChange(WorkflowTelemetryChangeEvent.run(PROJECT, RUN_ID));
        deliveryExecutor.runAll();

        assertThat(hub.connectionCount()).isEqualTo(1);
        assertThat(watcher.sent).isEmpty();
    }
}

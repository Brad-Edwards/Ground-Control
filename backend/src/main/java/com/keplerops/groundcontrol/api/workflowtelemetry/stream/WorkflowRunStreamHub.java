package com.keplerops.groundcontrol.api.workflowtelemetry.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.api.workflowtelemetry.PhaseEventResponse;
import com.keplerops.groundcontrol.api.workflowtelemetry.WorkflowRunResponse;
import com.keplerops.groundcontrol.domain.exception.ServiceUnavailableException;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryChangeEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.WorkflowTelemetryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process fan-out of committed workflow-run telemetry to subscribed SSE connections (issue
 * #1436, ADR-061 #1436 amendment).
 *
 * <p>A {@code @Component} rather than a {@code @Service}: the ArchUnit rule
 * {@code services_must_reside_in_service_package} reserves that stereotype for domain services, and
 * this holds {@link SseEmitter}, a Spring Web type the domain layer must not import.
 *
 * <p>{@link SseEmitter} is a transport primitive, not backpressure, so the bounds here are enforced
 * together and none is optional: a global connection cap and a per-principal cap taken atomically,
 * a finite emitter lifetime, a heartbeat below that lifetime, a bounded FIFO per connection with at
 * most one drain active for it, and a bounded delivery executor. Threads that publish — the
 * committing transaction thread and the heartbeat scheduler — only ever {@code offer} to a FIFO;
 * the possibly-blocking socket write happens exclusively on a delivery thread.
 *
 * <p>Overflow, executor refusal, send failure, and timeout all close the connection rather than
 * silently discarding one update: a client that keeps its stream labelled live while missing an
 * event is worse than a client pushed onto the honest polling path.
 *
 * <p>Delivery is best-effort and may duplicate. Subscribers reconcile by entity id and refetch REST
 * snapshots on connect and reconnect, which is what closes the gap a lost in-memory notification or
 * a subscribe/fetch race would otherwise leave.
 */
@Component
public class WorkflowRunStreamHub {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunStreamHub.class);

    /** SSE event name for a run projection. Mirrors the REST {@code WorkflowRunResponse} shape. */
    static final String RUN_EVENT = "workflow-run";

    /** SSE event name for a phase-event projection. Mirrors the REST {@code PhaseEventResponse} shape. */
    static final String PHASE_EVENT = "phase-event";

    /** Principal label used when the security chain is disabled (dev/test) and no principal exists. */
    public static final String ANONYMOUS_PRINCIPAL = "anonymous";

    private final WorkflowTelemetryService telemetryService;
    private final ObjectMapper objectMapper;
    private final WorkflowRunStreamProperties properties;

    private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * Guards registration and deregistration so the global cap, the per-principal cap, and the
     * registry insert are one atomic decision. Without it two concurrent subscribers can both pass a
     * cap check that neither would pass afterwards.
     */
    private final ReentrantLock registryLock = new ReentrantLock();

    private final ExecutorService deliveryExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    // Both pools are ExecutorService (ScheduledExecutorService extends it), so injection must be
    // by name — otherwise the context fails to start with an ambiguous-bean error.
    public WorkflowRunStreamHub(
            WorkflowTelemetryService telemetryService,
            ObjectMapper objectMapper,
            WorkflowRunStreamProperties properties,
            @Qualifier("workflowRunStreamDeliveryExecutor") ExecutorService deliveryExecutor,
            @Qualifier("workflowRunStreamHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.deliveryExecutor = deliveryExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /** Started after construction rather than in the constructor so the schedule never sees a half-built hub. */
    @PostConstruct
    void startHeartbeat() {
        long intervalMs = properties.getHeartbeatInterval().toMillis();
        heartbeatScheduler.scheduleAtFixedRate(this::heartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Register a connection for {@code project} on behalf of {@code principal}.
     *
     * @throws ServiceUnavailableException when the stream is disabled or either cap is exhausted.
     *     Thrown before the response is committed, so it still renders through the standard
     *     {@code ErrorResponse} envelope.
     */
    public SseEmitter subscribe(String project, String principal) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("Workflow-run streaming is disabled");
        }
        var emitter = createEmitter(properties.getIdleTimeout().toMillis());
        var subscription = new Subscription(
                UUID.randomUUID(),
                project,
                principal,
                emitter,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()));

        registryLock.lock();
        try {
            if (subscriptions.size() >= properties.getMaxConnections()) {
                throw new ServiceUnavailableException("Workflow-run stream connection capacity reached");
            }
            if (countForPrincipal(principal) >= properties.getMaxConnectionsPerPrincipal()) {
                throw new ServiceUnavailableException(
                        "Workflow-run stream connection capacity reached for this principal");
            }
            subscriptions.put(subscription.id, subscription);
        } finally {
            registryLock.unlock();
        }

        // Every one of these can fire, and more than one can fire for the same connection; close()
        // is idempotent precisely because these callbacks race.
        emitter.onCompletion(() -> close(subscription, "completed"));
        emitter.onTimeout(() -> close(subscription, "idle_timeout"));
        emitter.onError(error -> close(subscription, "transport_error"));

        log.debug(
                "workflow_run_stream_subscribed: project={} principal={} connections={}",
                project,
                principal,
                subscriptions.size());
        return emitter;
    }

    /**
     * Deliver a committed change to the connections watching its project.
     *
     * <p>{@code REQUIRES_NEW} because an {@code AFTER_COMMIT} listener runs while the original
     * transaction is completing: reusing it to reload would fail. This mirrors the existing
     * post-commit listener precedent in the requirements domain.
     *
     * <p>Failures here are contained. Telemetry delivery is an observation of a workflow that has
     * already committed its result, so it must never propagate back into the write path.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onTelemetryChange(WorkflowTelemetryChangeEvent change) {
        var watchers = subscriptionsFor(change.project());
        if (watchers.isEmpty()) {
            // Nobody is watching this project, so the reload below would be a query with no reader.
            return;
        }
        try {
            var message = render(change);
            watchers.forEach(subscription -> offer(subscription, message));
        } catch (RuntimeException | JsonProcessingException failure) {
            // Bounded diagnostic only: identifiers and a failure class, never the payload.
            log.warn(
                    "workflow_run_stream_render_failed: project={} kind={} entity={} failure={}",
                    change.project(),
                    change.kind(),
                    change.entityId(),
                    failure.getClass().getSimpleName());
        }
    }

    private Message render(WorkflowTelemetryChangeEvent change) throws JsonProcessingException {
        if (change.kind() == WorkflowTelemetryChangeEvent.Kind.RUN) {
            var run = telemetryService.getRun(change.entityId(), change.project());
            return new Message(RUN_EVENT, objectMapper.writeValueAsString(WorkflowRunResponse.from(run)));
        }
        var event = telemetryService.getPhaseEvent(change.entityId(), change.project());
        return new Message(PHASE_EVENT, objectMapper.writeValueAsString(PhaseEventResponse.from(event)));
    }

    /**
     * Emitter construction seam. Production always builds a plain {@link SseEmitter}; tests
     * substitute one that records what was written, since a bounded transport is only credible if
     * the delivery and disconnect behaviour is actually observed.
     */
    SseEmitter createEmitter(long timeoutMillis) {
        return new SseEmitter(timeoutMillis);
    }

    /** Heartbeat tick. Only enqueues; the comment is written on a delivery thread like any payload. */
    void heartbeat() {
        try {
            subscriptions.values().forEach(subscription -> offer(subscription, Message.HEARTBEAT));
        } catch (RuntimeException failure) {
            // A scheduled task that throws is silently cancelled by the executor, which would
            // disable every future heartbeat. Swallow and keep the schedule alive.
            log.warn(
                    "workflow_run_stream_heartbeat_failed: failure={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void offer(Subscription subscription, Message message) {
        if (subscription.closed.get()) {
            return;
        }
        if (!subscription.queue.offer(message)) {
            // The connection is not keeping up. Disconnecting is the honest outcome: dropping this
            // message while leaving the stream open would leave the client silently behind.
            close(subscription, "queue_overflow");
            return;
        }
        schedule(subscription);
    }

    /**
     * Hand this connection to a delivery thread if one is not already assigned. Winning the
     * {@code draining} CAS here rather than inside the drain caps queued tasks at one per
     * connection, which is what keeps the executor's own queue bounded by the connection cap.
     */
    private void schedule(Subscription subscription) {
        if (subscription.closed.get() || !subscription.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            deliveryExecutor.execute(() -> drain(subscription));
        } catch (RejectedExecutionException rejected) {
            subscription.draining.set(false);
            close(subscription, "delivery_rejected");
        }
    }

    private void drain(Subscription subscription) {
        try {
            Message message;
            while (!subscription.closed.get() && (message = subscription.queue.poll()) != null) {
                subscription.emitter.send(toSseEvent(message));
            }
        } catch (Exception sendFailure) {
            // Includes the ordinary case of a client that navigated away mid-write. Expected, so it
            // is logged as a bounded reason rather than a stack trace.
            close(subscription, "send_failed");
        } finally {
            subscription.draining.set(false);
        }
        if (!subscription.queue.isEmpty()) {
            // An offer that landed between the failed poll and releasing the flag would otherwise
            // sit in the queue with no thread assigned to it.
            schedule(subscription);
        }
    }

    private static SseEmitter.SseEventBuilder toSseEvent(Message message) {
        if (message.eventName() == null) {
            return SseEmitter.event().comment("hb");
        }
        return SseEmitter.event().name(message.eventName()).data(message.data());
    }

    /** Close and deregister exactly once, however many racing callbacks arrive for this connection. */
    private void close(Subscription subscription, String reason) {
        if (!subscription.closed.compareAndSet(false, true)) {
            return;
        }
        registryLock.lock();
        try {
            subscriptions.remove(subscription.id);
        } finally {
            registryLock.unlock();
        }
        subscription.queue.clear();
        try {
            subscription.emitter.complete();
        } catch (RuntimeException alreadyFinished) {
            // The container may already have finished the async response; nothing left to release.
            log.trace("workflow_run_stream_complete_noop: reason={}", reason);
        }
        log.debug(
                "workflow_run_stream_closed: project={} principal={} reason={} connections={}",
                subscription.project,
                subscription.principal,
                reason,
                subscriptions.size());
    }

    private List<Subscription> subscriptionsFor(String project) {
        var matching = new ArrayList<Subscription>();
        for (var subscription : subscriptions.values()) {
            if (subscription.project.equals(project) && !subscription.closed.get()) {
                matching.add(subscription);
            }
        }
        return matching;
    }

    /** Called under {@link #registryLock}. Counting the registry keeps one structure authoritative. */
    private long countForPrincipal(String principal) {
        return subscriptions.values().stream()
                .filter(subscription -> subscription.principal.equals(principal))
                .count();
    }

    /** Visible for tests: how many connections are currently registered. */
    int connectionCount() {
        return subscriptions.size();
    }

    @PreDestroy
    void shutdown() {
        subscriptions.values().forEach(subscription -> close(subscription, "shutdown"));
        heartbeatScheduler.shutdownNow();
        deliveryExecutor.shutdownNow();
    }

    /** One queued SSE frame. A null {@code eventName} is a heartbeat comment with no product payload. */
    private record Message(String eventName, String data) {
        static final Message HEARTBEAT = new Message(null, null);
    }

    /** One registered connection and the state that bounds it. */
    private static final class Subscription {
        private final UUID id;
        private final String project;
        private final String principal;
        private final SseEmitter emitter;
        private final BlockingQueue<Message> queue;
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Subscription(
                UUID id, String project, String principal, SseEmitter emitter, BlockingQueue<Message> queue) {
            this.id = id;
            this.project = project;
            this.principal = principal;
            this.emitter = emitter;
            this.queue = queue;
        }
    }
}

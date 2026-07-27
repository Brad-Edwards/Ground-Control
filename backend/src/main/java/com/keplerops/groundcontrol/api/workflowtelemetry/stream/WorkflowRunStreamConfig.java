package com.keplerops.groundcontrol.api.workflowtelemetry.stream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Thread resources for the workflow-run live stream (issue #1436).
 *
 * <p>Deliberately dedicated pools rather than the shared MVC async or {@code @Async} executor: SSE
 * delivery can block on a slow client socket, and borrowing a shared pool would let one stalled
 * dashboard tab degrade unrelated request handling.
 */
@Configuration
public class WorkflowRunStreamConfig {

    /**
     * Bounded delivery pool, sized to the connection cap.
     *
     * <p>`SseEmitter.send` can block for as long as a client refuses to read. A pool smaller than the
     * connection cap therefore has a head-of-line problem: a couple of stalled clients occupy every
     * worker, healthy connections' drain tasks sit in the work queue, and those connections overflow
     * and get disconnected without ever receiving events that were deliverable. Giving the pool one
     * worker per possible connection is what makes "one slow client cannot stop another receiving"
     * true rather than merely intended.
     *
     * <p>Threads are created on demand and reaped after idle (`SynchronousQueue` with a zero core
     * size), so the usual cost is the handful of connections actually open, not the cap. The hub
     * assigns at most one in-flight drain per connection, so rejection can only occur when every
     * connection is simultaneously mid-write — genuine saturation, which closes that connection
     * rather than blocking a publisher.
     */
    @Bean
    public ExecutorService workflowRunStreamDeliveryExecutor(WorkflowRunStreamProperties properties) {
        return new ThreadPoolExecutor(
                0, properties.getMaxConnections(), 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                    var thread = new Thread(runnable, "wf-stream-delivery");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Single heartbeat thread. It only enqueues comments; it never performs a socket write itself. */
    @Bean
    public ScheduledExecutorService workflowRunStreamHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "wf-stream-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}

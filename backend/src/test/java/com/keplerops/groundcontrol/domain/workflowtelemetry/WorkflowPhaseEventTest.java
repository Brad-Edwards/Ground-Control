package com.keplerops.groundcontrol.domain.workflowtelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle-callback tests for {@link WorkflowPhaseEvent} (issue #1435).
 *
 * <p>Lives in the entity's own package because {@code onCreate} is the package-private
 * {@code @PrePersist} hook. It is the invariant that every row carries a deduplication identity no
 * matter which writer produced it, so it is worth proving without standing up JPA.
 */
class WorkflowPhaseEventTest {

    private static final Instant OCCURRED = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void derivesTheSourceIdOnPersistWhenNoWriterSuppliedOne() {
        // A writer that bypasses the service (a fixture, a future importer) would otherwise insert a
        // null identity and trip the NOT NULL constraint at commit.
        var event = event();
        event.setCycleIndex(2);

        event.onCreate();

        assertThat(event.getSourceId()).isEqualTo("ci:COMPLETED:2");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void treatsAnAbsentCycleIndexAsTheFirstAttempt() {
        var event = event();

        event.onCreate();

        assertThat(event.getSourceId()).isEqualTo("ci:COMPLETED:0");
    }

    @Test
    void keepsAnIdentityTheWriterAlreadyAttested() {
        var event = event();
        event.setSourceId("attested-key");

        event.onCreate();

        assertThat(event.getSourceId()).isEqualTo("attested-key");
    }

    @Test
    void replacesABlankIdentityRatherThanPersistingIt() {
        // A blank string satisfies NOT NULL but is not an identity: every event would collide on it.
        var event = event();
        event.setSourceId("   ");

        event.onCreate();

        assertThat(event.getSourceId()).isEqualTo("ci:COMPLETED:0");
    }

    private static WorkflowPhaseEvent event() {
        return new WorkflowPhaseEvent(
                UUID.randomUUID(),
                "ground-control",
                "ci",
                PhaseEventType.COMPLETED,
                OCCURRED,
                1000L,
                TelemetryProvenance.LIVE_EMISSION);
    }
}

package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.PhaseHotspot;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RunAggregate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entity fixtures shared by the workflow-run controller slices.
 *
 * <p>Its own type because the slice was split by endpoint (issue #1355); duplicating the builders
 * would let them drift from the entities they stand in for.
 */
final class WorkflowRunControllerFixtures {

    static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000859");

    static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");

    static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    private WorkflowRunControllerFixtures() {}

    static WorkflowRun sampleRun() {
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.ISSUE_THREAD);
        setField(run, "id", RUN_ID);
        run.setIssueNumber(859);
        run.setBranch("859-feature");
        run.setFinalState(WorkflowRunState.READY_FOR_REVIEW);
        return run;
    }

    static WorkflowPhaseEvent sampleEvent(UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.COMPLETED, FROM, 1000L, TelemetryProvenance.ISSUE_THREAD);
        event.setCycleIndex(1);
        event.setOutcome("clean");
        event.setSourceId("ci:COMPLETED:1");
        return event;
    }

    static WorkflowPhaseEvent startedEvent(UUID runId) {
        var event = new WorkflowPhaseEvent(
                runId, "ground-control", "ci", PhaseEventType.STARTED, FROM, null, TelemetryProvenance.LIVE_EMISSION);
        event.setCycleIndex(0);
        event.setSourceId("ci:STARTED:0");
        return event;
    }

    static RunAggregate sampleAggregate() {
        return new RunAggregate(
                FROM,
                TO,
                7,
                3,
                1,
                2,
                0,
                0,
                0,
                12.0,
                30.0,
                45.0,
                new BigDecimal("100.0000"),
                new BigDecimal("60.0000"),
                new BigDecimal("10.0000"),
                new BigDecimal("20.0000"),
                new BigDecimal("10.0000"),
                50,
                600,
                1_000_000,
                List.of(new PhaseHotspot("ci", 5, 2, 0, 1000L, 2000L, 3)));
    }
}

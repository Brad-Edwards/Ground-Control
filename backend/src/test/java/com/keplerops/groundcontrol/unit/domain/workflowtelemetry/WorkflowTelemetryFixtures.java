package com.keplerops.groundcontrol.unit.domain.workflowtelemetry;

import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunOutcome;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRunState;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordPhaseEventCommand;
import com.keplerops.groundcontrol.domain.workflowtelemetry.service.RecordWorkflowRunCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Command fixtures shared by the workflow-telemetry test classes.
 *
 * <p>Its own type because the tests were split by concern (issue #1355) and duplicating the
 * builder into each one would let them drift: a field added to the command would then be
 * exercised by whichever copy someone remembered to update.
 */
final class WorkflowTelemetryFixtures {

    static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");

    private WorkflowTelemetryFixtures() {}

    /** A phase-event command with the measurement fields absent, as a pre-#1355 emitter sends. */
    static RecordPhaseEventCommand phaseEvent(
            UUID runId, String phase, PhaseEventType eventType, Integer cycleIndex, String sourceId) {
        return new RecordPhaseEventCommand(
                runId,
                "ground-control",
                phase,
                eventType,
                cycleIndex,
                FROM,
                25L,
                null,
                TelemetryProvenance.LIVE_EMISSION,
                sourceId,
                null,
                null,
                null,
                null,
                // emitter null -> defaults ADR061_WORKFLOW_TELEMETRY; the ADR-036 step facts are
                // absent, as every lifecycle/station emitter sends (issue #1354).
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    static RunCommandBuilder runCommand() {
        return new RunCommandBuilder();
    }

    /** Small fluent builder so each validation test varies exactly one field. */
    static final class RunCommandBuilder {
        private String project = "ground-control";
        private String branch = "859-feature";
        private WorkflowRunState state = WorkflowRunState.RUNNING;
        private WorkflowRunOutcome outcome = WorkflowRunOutcome.NONE;
        private TelemetryProvenance provenance = TelemetryProvenance.ISSUE_THREAD;
        private BigDecimal cost = null;

        RunCommandBuilder withProject(String p) {
            this.project = p;
            return this;
        }

        RunCommandBuilder withBranch(String b) {
            this.branch = b;
            return this;
        }

        RunCommandBuilder withState(WorkflowRunState s) {
            this.state = s;
            return this;
        }

        RunCommandBuilder withOutcome(WorkflowRunOutcome o) {
            this.outcome = o;
            return this;
        }

        RunCommandBuilder withProvenance(TelemetryProvenance p) {
            this.provenance = p;
            return this;
        }

        RunCommandBuilder withCost(BigDecimal c) {
            this.cost = c;
            return this;
        }

        RecordWorkflowRunCommand build() {
            return new RecordWorkflowRunCommand(
                    project,
                    null,
                    859,
                    null,
                    branch,
                    "implement",
                    "claude-code",
                    Set.of("GC-O009"),
                    FROM,
                    null,
                    state,
                    outcome,
                    provenance,
                    null,
                    null,
                    null,
                    null,
                    cost,
                    null,
                    null);
        }
    }

    static WorkflowRun openRun(Instant at) {
        var run = new WorkflowRun("ground-control", "implement", TelemetryProvenance.LIVE_EMISSION);
        run.setIssueNumber(859);
        run.setBranch("859-feature");
        run.setStartedAt(at);
        return run;
    }

    static final Instant TO = Instant.parse("2026-06-02T00:00:00Z");

    static RecordWorkflowRunCommand liveCommand(Instant startedAt, Instant endedAt, WorkflowRunState state) {
        return new RecordWorkflowRunCommand(
                "ground-control",
                null,
                859,
                null,
                "859-feature",
                "implement",
                "claude-code",
                null,
                startedAt,
                endedAt,
                state,
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
}

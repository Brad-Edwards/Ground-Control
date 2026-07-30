package com.keplerops.groundcontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.workflowtelemetry.CapabilityTier;
import com.keplerops.groundcontrol.domain.workflowtelemetry.FindingSourceKind;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventEmitter;
import com.keplerops.groundcontrol.domain.workflowtelemetry.PhaseEventType;
import com.keplerops.groundcontrol.domain.workflowtelemetry.StationResult;
import com.keplerops.groundcontrol.domain.workflowtelemetry.TelemetryProvenance;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowGateFinding;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowPhaseEvent;
import com.keplerops.groundcontrol.domain.workflowtelemetry.WorkflowRun;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowGateFindingRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowPhaseEventRepository;
import com.keplerops.groundcontrol.domain.workflowtelemetry.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class WorkflowActivityProjectionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowPhaseEventRepository eventRepository;

    @Autowired
    private WorkflowGateFindingRepository findingRepository;

    @Test
    @Transactional
    void batchProjectionSelectsLatestProjectScopedFactsAndCountsFindings() {
        var run = new WorkflowRun("gc", "implement", TelemetryProvenance.LIVE_EMISSION);
        run.setStartedAt(Instant.parse("2026-07-30T09:00:00Z"));
        run = runRepository.saveAndFlush(run);

        var olderAttempt =
                lifecycle(run, "codex_review", PhaseEventType.COMPLETED, Instant.parse("2026-07-30T09:10:00Z"));
        olderAttempt.setStationId("codex_review");
        olderAttempt.setCycleIndex(9);
        olderAttempt.setStationResult(StationResult.PASS);

        var latestAttempt =
                lifecycle(run, "codex_review", PhaseEventType.FAILED, Instant.parse("2026-07-30T09:20:00Z"));
        latestAttempt.setStationId("codex_review");
        latestAttempt.setCycleIndex(2);
        latestAttempt.setStationResult(StationResult.FAIL);
        latestAttempt.setFindingsDropped(3);

        var routing = lifecycle(run, "planning", PhaseEventType.COMPLETED, Instant.parse("2026-07-30T09:15:00Z"));
        routing.setEmitter(PhaseEventEmitter.ADR036_STEP_JSONL);
        routing.setStepAlias("04");
        routing.setTier(CapabilityTier.HIGH);
        routing.setModel("claude-opus");

        var otherProject =
                lifecycle(run, "completion_gate", PhaseEventType.STARTED, Instant.parse("2026-07-30T09:30:00Z"));
        setProject(otherProject, "other");

        eventRepository.saveAllAndFlush(List.of(olderAttempt, latestAttempt, routing, otherProject));

        var finding = new WorkflowGateFinding(
                run.getId(),
                latestAttempt.getId(),
                "gc",
                "codex_review",
                FindingSourceKind.REVIEWER,
                "core",
                "finding-1");
        findingRepository.saveAndFlush(finding);

        assertThat(eventRepository.findLatestLifecycleEvents("gc", List.of(run.getId())))
                .extracting(WorkflowPhaseEvent::getPhase)
                .containsExactly("codex_review");
        assertThat(eventRepository.findLatestStationAttempts("gc", List.of(run.getId())))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getCycleIndex()).isEqualTo(2);
                    assertThat(event.getStationResult()).isEqualTo(StationResult.FAIL);
                });
        assertThat(eventRepository.findLatestRoutingObservations("gc", List.of(run.getId())))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getTier()).isEqualTo(CapabilityTier.HIGH);
                    assertThat(event.getModel()).isEqualTo("claude-opus");
                });
        assertThat(findingRepository.countByPhaseEventIds("gc", List.of(latestAttempt.getId())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getPhaseEventId()).isEqualTo(latestAttempt.getId());
                    assertThat(row.getFindingCount()).isEqualTo(1);
                });
    }

    private static WorkflowPhaseEvent lifecycle(
            WorkflowRun run, String phase, PhaseEventType type, Instant occurredAt) {
        return new WorkflowPhaseEvent(
                run.getId(), run.getProject(), phase, type, occurredAt, null, TelemetryProvenance.LIVE_EMISSION);
    }

    private static void setProject(WorkflowPhaseEvent event, String project) {
        try {
            var field = WorkflowPhaseEvent.class.getDeclaredField("project");
            field.setAccessible(true);
            field.set(event, project);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

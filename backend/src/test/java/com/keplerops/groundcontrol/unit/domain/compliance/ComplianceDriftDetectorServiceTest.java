package com.keplerops.groundcontrol.unit.domain.compliance;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.events.EvidenceExpiryEvent;
import com.keplerops.groundcontrol.domain.compliance.model.ComplianceDriftEvent;
import com.keplerops.groundcontrol.domain.compliance.repository.ComplianceDriftEventRepository;
import com.keplerops.groundcontrol.domain.compliance.service.ComplianceDriftDetectorService;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftCategory;
import com.keplerops.groundcontrol.domain.compliance.state.ComplianceDriftSeverity;
import com.keplerops.groundcontrol.domain.exception.ConflictException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ControlStateChangedEvent;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSignal;
import com.keplerops.groundcontrol.domain.riskscenarios.events.ReassessmentSourceEntityType;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComplianceDriftDetectorServiceTest {

    @Mock
    private ComplianceDriftEventRepository repository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ComplianceDriftDetectorService service;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void publishesControlStateChangedEvent() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.save(any(ComplianceDriftEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        var controlId = UUID.randomUUID();
        var signal = new ReassessmentSignal(
                projectId,
                ReassessmentTriggerCategory.CONTROL_STATE_CHANGED,
                ReassessmentSourceEntityType.CONTROL,
                controlId,
                Set.of("status"),
                Map.of(),
                Map.of(),
                Instant.parse("2026-05-30T00:00:00Z"));
        service.onControlStateChanged(new ControlStateChangedEvent(signal));

        ArgumentCaptor<ComplianceDriftEvent> captor = ArgumentCaptor.forClass(ComplianceDriftEvent.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getCategory()).isEqualTo(ComplianceDriftCategory.CONTROL_STATE_CHANGED);
        assertThat(saved.getSeverity()).isEqualTo(ComplianceDriftSeverity.WARN);
        assertThat(saved.getSourceEntityType()).isEqualTo("CONTROL");
        assertThat(saved.getSourceEntityId()).isEqualTo(controlId);
        assertThat(saved.getDetectedAt()).isEqualTo(Instant.parse("2026-05-30T00:00:00Z"));
    }

    @Test
    void downgradesSeverityWhenOnlyEffectivenessChanged() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.save(any(ComplianceDriftEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        var controlId = UUID.randomUUID();
        var signal = new ReassessmentSignal(
                projectId,
                ReassessmentTriggerCategory.CONTROL_STATE_CHANGED,
                ReassessmentSourceEntityType.CONTROL,
                controlId,
                Set.of("effectiveness"),
                Map.of(),
                Map.of(),
                Instant.parse("2026-05-30T00:00:00Z"));
        service.onControlStateChanged(new ControlStateChangedEvent(signal));

        ArgumentCaptor<ComplianceDriftEvent> captor = ArgumentCaptor.forClass(ComplianceDriftEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(ComplianceDriftSeverity.INFO);
    }

    @Test
    void doesNotEchoOldOrNewValuesInSummary() {
        // Security note: drift summary must not leak field VALUES — only
        // field NAMES join the summary. Confirms ADR-029 sanitization.
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.save(any(ComplianceDriftEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        var controlId = UUID.randomUUID();
        var signal = new ReassessmentSignal(
                projectId,
                ReassessmentTriggerCategory.CONTROL_STATE_CHANGED,
                ReassessmentSourceEntityType.CONTROL,
                controlId,
                Set.of("status"),
                Map.of("status", "SENSITIVE-OLD-VALUE"),
                Map.of("status", "SENSITIVE-NEW-VALUE"),
                Instant.parse("2026-05-30T00:00:00Z"));
        service.onControlStateChanged(new ControlStateChangedEvent(signal));

        ArgumentCaptor<ComplianceDriftEvent> captor = ArgumentCaptor.forClass(ComplianceDriftEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSummary())
                .contains("status")
                .doesNotContain("SENSITIVE-OLD-VALUE")
                .doesNotContain("SENSITIVE-NEW-VALUE");
    }

    @Test
    void publishesEvidenceExpiredEvent() {
        when(projectService.getById(projectId)).thenReturn(project);
        when(repository.existsBySourceAndCategory(eq(projectId), any(), anyString(), any()))
                .thenReturn(false);
        when(repository.save(any(ComplianceDriftEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        var artifactId = UUID.randomUUID();
        var expiresAt = Instant.parse("2026-05-29T00:00:00Z");
        service.onEvidenceExpired(new EvidenceExpiryEvent(projectId, artifactId, "EVD-XYZ", expiresAt));

        ArgumentCaptor<ComplianceDriftEvent> captor = ArgumentCaptor.forClass(ComplianceDriftEvent.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getCategory()).isEqualTo(ComplianceDriftCategory.EVIDENCE_EXPIRED);
        assertThat(saved.getSeverity()).isEqualTo(ComplianceDriftSeverity.WARN);
        assertThat(saved.getSourceEntityType()).isEqualTo("EVIDENCE_ARTIFACT");
        assertThat(saved.getSourceEntityId()).isEqualTo(artifactId);
        assertThat(saved.getSummary()).contains("EVD-XYZ");
    }

    @Test
    void evidenceExpiredIsIdempotentForSameArtifact() {
        when(repository.existsBySourceAndCategory(eq(projectId), any(), anyString(), any()))
                .thenReturn(true);

        var artifactId = UUID.randomUUID();
        service.onEvidenceExpired(
                new EvidenceExpiryEvent(projectId, artifactId, "EVD-XYZ", Instant.parse("2026-05-29T00:00:00Z")));

        verify(repository, never()).save(any());
    }

    @Test
    void livenessReturnsLastDetectedAndUnacknowledgedCount() {
        var last = Instant.parse("2026-05-30T00:00:00Z");
        when(repository.findLastDetectedAt(projectId)).thenReturn(Optional.of(last));
        when(repository.findUnacknowledgedByProjectId(projectId)).thenReturn(java.util.List.of(makeEvent()));

        var liveness = service.liveness(projectId);
        assertThat(liveness.lastDetectedAt()).isEqualTo(last);
        assertThat(liveness.unacknowledged()).isEqualTo(1);
        assertThat(liveness.sampledAt()).isAfter(last);
        assertThat(liveness.lagSinceLastEvent()).isPresent();
        // Sweep supplier not wired by default — lastSweepAt is null.
        assertThat(liveness.lastSweepAt()).isNull();
    }

    @Test
    void livenessSurfacesLastSweepAtWhenSupplierWired() {
        var last = Instant.parse("2026-05-30T00:00:00Z");
        var sweep = Instant.parse("2026-05-30T11:00:00Z");
        when(repository.findLastDetectedAt(projectId)).thenReturn(Optional.of(last));
        when(repository.findUnacknowledgedByProjectId(projectId)).thenReturn(java.util.List.of());
        service.setLastSweepAtSupplier(() -> sweep);

        var liveness = service.liveness(projectId);
        assertThat(liveness.lastSweepAt()).isEqualTo(sweep);
    }

    @Test
    void acknowledgeWritesOnce() {
        var id = UUID.randomUUID();
        var event = new ComplianceDriftEvent(
                project,
                ComplianceDriftCategory.EVIDENCE_EXPIRED,
                ComplianceDriftSeverity.WARN,
                "EVIDENCE_ARTIFACT",
                UUID.randomUUID(),
                "summary",
                Instant.parse("2026-05-30T00:00:00Z"));
        setField(event, "id", id);
        when(repository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.of(event));
        when(repository.acknowledgeIfUnset(eq(id), eq(projectId), any(), any())).thenReturn(1);

        service.acknowledge(projectId, id);

        verify(repository, times(1)).acknowledgeIfUnset(eq(id), eq(projectId), any(), any());
    }

    @Test
    void acknowledgeRejectsSecondCall() {
        var id = UUID.randomUUID();
        var event = new ComplianceDriftEvent(
                project,
                ComplianceDriftCategory.EVIDENCE_EXPIRED,
                ComplianceDriftSeverity.WARN,
                "EVIDENCE_ARTIFACT",
                UUID.randomUUID(),
                "summary",
                Instant.parse("2026-05-30T00:00:00Z"));
        setField(event, "id", id);
        event.setAcknowledgedAt(Instant.now());
        when(repository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.acknowledge(projectId, id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already acknowledged");
    }

    private ComplianceDriftEvent makeEvent() {
        return new ComplianceDriftEvent(
                project,
                ComplianceDriftCategory.EVIDENCE_EXPIRED,
                ComplianceDriftSeverity.WARN,
                "EVIDENCE_ARTIFACT",
                UUID.randomUUID(),
                "summary",
                Instant.parse("2026-05-30T00:00:00Z"));
    }

    @Test
    void getByIdNotFound() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndProjectId(id, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(projectId, id)).isInstanceOf(NotFoundException.class);
    }
}

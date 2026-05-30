package com.keplerops.groundcontrol.unit.infrastructure.compliance;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.compliance.events.EvidenceExpiryEvent;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceArtifact;
import com.keplerops.groundcontrol.domain.evidence.repository.EvidenceArtifactRepository;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.infrastructure.compliance.EvidenceExpirySweepJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EvidenceExpirySweepJobTest {

    @Mock
    private EvidenceArtifactRepository evidenceRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Clock fixedClock;
    private EvidenceExpirySweepJob job;
    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-05-30T12:00:00Z"), ZoneOffset.UTC);
        job = new EvidenceExpirySweepJob(evidenceRepository, eventPublisher, fixedClock);
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Test
    void dispatchesOneEventPerExpiredArtifact() {
        var a1 = buildExpiredArtifact("EVD-0001", Instant.parse("2026-05-29T00:00:00Z"));
        var a2 = buildExpiredArtifact("EVD-0002", Instant.parse("2026-05-29T06:00:00Z"));
        when(evidenceRepository.findExpiredAsOf(Instant.parse("2026-05-30T12:00:00Z")))
                .thenReturn(List.of(a1, a2));

        job.sweep();

        ArgumentCaptor<EvidenceExpiryEvent> captor = ArgumentCaptor.forClass(EvidenceExpiryEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(EvidenceExpiryEvent::uid).containsExactly("EVD-0001", "EVD-0002");
        assertThat(job.lastSweepAt()).isEqualTo(Instant.parse("2026-05-30T12:00:00Z"));
    }

    @Test
    void continuesSweepWhenSingleDispatchFails() {
        // A bad listener for one artifact must not abort the rest of the
        // sweep — otherwise one corrupt drift event silences continuous
        // monitoring entirely.
        var a1 = buildExpiredArtifact("EVD-0001", Instant.parse("2026-05-29T00:00:00Z"));
        var a2 = buildExpiredArtifact("EVD-0002", Instant.parse("2026-05-29T06:00:00Z"));
        when(evidenceRepository.findExpiredAsOf(any())).thenReturn(List.of(a1, a2));
        doThrow(new RuntimeException("listener boom"))
                .when(eventPublisher)
                .publishEvent(any(EvidenceExpiryEvent.class));

        job.sweep();

        verify(eventPublisher, times(2)).publishEvent(any(EvidenceExpiryEvent.class));
        // lastSweepAt is still set so the liveness probe reports the most
        // recent attempt, not the most recent success-only.
        assertThat(job.lastSweepAt()).isNotNull();
    }

    @Test
    void emptyListProducesNoEventsButRecordsLastSweepAt() {
        when(evidenceRepository.findExpiredAsOf(any())).thenReturn(List.of());
        job.sweep();
        verify(eventPublisher, times(0)).publishEvent(any());
        assertThat(job.lastSweepAt()).isEqualTo(Instant.parse("2026-05-30T12:00:00Z"));
    }

    private EvidenceArtifact buildExpiredArtifact(String uid, Instant expiresAt) {
        var a = new EvidenceArtifact(
                project,
                uid,
                "title-" + uid,
                "summary-" + uid,
                EvidenceType.ATTESTATION,
                "method-v1",
                Instant.parse("2026-04-01T00:00:00Z"));
        setField(a, "id", UUID.randomUUID());
        a.setExpiresAt(expiresAt);
        return a;
    }
}

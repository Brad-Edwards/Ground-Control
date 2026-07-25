package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.audit.repository.RevisionRepository;
import com.keplerops.groundcontrol.domain.audit.service.AsOfRevisionResolver;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AsOfRevisionResolverTest {

    @Mock
    private RevisionRepository revisionRepository;

    @InjectMocks
    private AsOfRevisionResolver resolver;

    @Test
    void resolveAsOf_convertsInstantToExactEpochMillisBoundary() {
        // Pins the millisecond-precision contract: the resolver must hand the repository the
        // exact epoch-millis value of the supplied Instant, not a truncated/rounded one — a
        // regression here would silently shift every inclusive-boundary resolution by a unit
        // the conformance suite (Testcontainers-only, no Sonar coverage) would catch but Sonar's
        // unit-only gate would not.
        Instant asOf = Instant.ofEpochMilli(1_700_000_123_456L);
        when(revisionRepository.findGreatestRevisionAtOrBefore(1_700_000_123_456L))
                .thenReturn(Optional.of(7));

        Optional<Integer> result = resolver.resolveAsOf(asOf);

        ArgumentCaptor<Long> millisCaptor = ArgumentCaptor.forClass(Long.class);
        verify(revisionRepository).findGreatestRevisionAtOrBefore(millisCaptor.capture());
        assertThat(millisCaptor.getValue()).isEqualTo(1_700_000_123_456L);
        assertThat(result).contains(7);
    }

    @Test
    void resolveAsOf_returnsEmptyWhenRepositoryFindsNoRevision() {
        when(revisionRepository.findGreatestRevisionAtOrBefore(123L)).thenReturn(Optional.empty());

        assertThat(resolver.resolveAsOf(Instant.ofEpochMilli(123L))).isEmpty();
    }

    @Test
    void resolveAsOf_rejectsNullInstant() {
        assertThatThrownBy(() -> resolver.resolveAsOf(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentRevision_delegatesToRepositoryGreatestRevision() {
        when(revisionRepository.findGreatestRevision()).thenReturn(Optional.of(42));

        assertThat(resolver.currentRevision()).contains(42);
    }

    @Test
    void currentRevision_returnsEmptyWhenNoRevisionsExist() {
        when(revisionRepository.findGreatestRevision()).thenReturn(Optional.empty());

        assertThat(resolver.currentRevision()).isEmpty();
    }
}

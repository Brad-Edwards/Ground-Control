package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import org.junit.jupiter.api.Test;

class BacklogItemStatusTest {

    @Test
    void candidateCanTransitionToReadyOrArchived() {
        assertThat(BacklogItemStatus.CANDIDATE.canTransitionTo(BacklogItemStatus.READY))
                .isTrue();
        assertThat(BacklogItemStatus.CANDIDATE.canTransitionTo(BacklogItemStatus.ARCHIVED))
                .isTrue();
        assertThat(BacklogItemStatus.CANDIDATE.canTransitionTo(BacklogItemStatus.IN_PROGRESS))
                .isFalse();
        assertThat(BacklogItemStatus.CANDIDATE.canTransitionTo(BacklogItemStatus.DONE))
                .isFalse();
        assertThat(BacklogItemStatus.CANDIDATE.canTransitionTo(BacklogItemStatus.CANDIDATE))
                .isFalse();
    }

    @Test
    void readyCanTransitionToInProgressCandidateOrArchived() {
        assertThat(BacklogItemStatus.READY.canTransitionTo(BacklogItemStatus.IN_PROGRESS))
                .isTrue();
        assertThat(BacklogItemStatus.READY.canTransitionTo(BacklogItemStatus.CANDIDATE))
                .isTrue();
        assertThat(BacklogItemStatus.READY.canTransitionTo(BacklogItemStatus.ARCHIVED))
                .isTrue();
        assertThat(BacklogItemStatus.READY.canTransitionTo(BacklogItemStatus.DONE))
                .isFalse();
        assertThat(BacklogItemStatus.READY.canTransitionTo(BacklogItemStatus.READY))
                .isFalse();
    }

    @Test
    void inProgressCanTransitionToDoneReadyOrArchived() {
        assertThat(BacklogItemStatus.IN_PROGRESS.canTransitionTo(BacklogItemStatus.DONE))
                .isTrue();
        assertThat(BacklogItemStatus.IN_PROGRESS.canTransitionTo(BacklogItemStatus.READY))
                .isTrue();
        assertThat(BacklogItemStatus.IN_PROGRESS.canTransitionTo(BacklogItemStatus.ARCHIVED))
                .isTrue();
        assertThat(BacklogItemStatus.IN_PROGRESS.canTransitionTo(BacklogItemStatus.CANDIDATE))
                .isFalse();
        assertThat(BacklogItemStatus.IN_PROGRESS.canTransitionTo(BacklogItemStatus.IN_PROGRESS))
                .isFalse();
    }

    @Test
    void doneCanOnlyTransitionToArchived() {
        assertThat(BacklogItemStatus.DONE.canTransitionTo(BacklogItemStatus.ARCHIVED))
                .isTrue();
        assertThat(BacklogItemStatus.DONE.canTransitionTo(BacklogItemStatus.CANDIDATE))
                .isFalse();
        assertThat(BacklogItemStatus.DONE.canTransitionTo(BacklogItemStatus.READY))
                .isFalse();
        assertThat(BacklogItemStatus.DONE.canTransitionTo(BacklogItemStatus.IN_PROGRESS))
                .isFalse();
        assertThat(BacklogItemStatus.DONE.canTransitionTo(BacklogItemStatus.DONE))
                .isFalse();
    }

    @Test
    void archivedCannotTransitionToAnything() {
        for (BacklogItemStatus target : BacklogItemStatus.values()) {
            assertThat(BacklogItemStatus.ARCHIVED.canTransitionTo(target))
                    .as("ARCHIVED -> %s should be false", target)
                    .isFalse();
        }
    }

    @Test
    void validTargetsReflectsAllowedTransitions() {
        assertThat(BacklogItemStatus.CANDIDATE.validTargets())
                .containsExactlyInAnyOrder(BacklogItemStatus.READY, BacklogItemStatus.ARCHIVED);
        assertThat(BacklogItemStatus.ARCHIVED.validTargets()).isEmpty();
    }
}

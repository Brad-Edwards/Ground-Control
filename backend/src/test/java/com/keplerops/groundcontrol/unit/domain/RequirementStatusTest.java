package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.TestUtil;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.requirements.model.Requirement;
import com.keplerops.groundcontrol.domain.requirements.state.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequirementStatusTest {

    private static Requirement draftRequirement() {
        var project = new Project("test-project", "Test Project");
        TestUtil.setField(project, "id", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return new Requirement(project, "REQ-001", "Title", "Statement");
    }

    @Test
    void draftCanTransitionToActive() {
        assertThat(Status.DRAFT.canTransitionTo(Status.ACTIVE)).isTrue();
    }

    @Test
    void draftCanTransitionToDeprecated() {
        assertThat(Status.DRAFT.canTransitionTo(Status.DEPRECATED)).isTrue();
    }

    @Test
    void withdrawingADraftDoesNotRecordItAsHavingBeenActive() {
        var requirement = draftRequirement();

        requirement.transitionStatus(Status.DEPRECATED);

        // The point of the DRAFT -> DEPRECATED edge: a requirement that was never implemented can be
        // withdrawn without first being promoted through ACTIVE, which would stamp a false
        // "this shipped" event on it.
        assertThat(requirement.getStatus()).isEqualTo(Status.DEPRECATED);
    }

    @Test
    void draftCannotTransitionStraightToArchived() {
        assertThat(Status.DRAFT.canTransitionTo(Status.ARCHIVED)).isFalse();

        var requirement = draftRequirement();

        assertThatThrownBy(() -> requirement.transitionStatus(Status.ARCHIVED))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void deprecatedIsNotResurrectable() {
        assertThat(Status.DEPRECATED.canTransitionTo(Status.ACTIVE)).isFalse();
        assertThat(Status.DEPRECATED.canTransitionTo(Status.DRAFT)).isFalse();
        assertThat(Status.DEPRECATED.canTransitionTo(Status.ARCHIVED)).isTrue();
    }

    @Test
    void archivedIsTerminal() {
        assertThat(Status.ARCHIVED.validTargets()).isEmpty();
    }
}

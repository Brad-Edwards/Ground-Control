package com.keplerops.groundcontrol.unit.domain.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.state.BacklogItemStatus;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import org.junit.jupiter.api.Test;

class BacklogItemTest {

    private Project project() {
        return new Project("p", "P");
    }

    @Test
    void constructorRejectsBlankUid() {
        assertThatThrownBy(() -> new BacklogItem(project(), "  ", "ok")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void constructorRejectsBlankTitle() {
        assertThatThrownBy(() -> new BacklogItem(project(), "ok", "  ")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void constructorRejectsNullProject() {
        assertThatThrownBy(() -> new BacklogItem(null, "ok", "ok")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void cannotTransitionToReadyWithoutAllComponents() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        assertThatThrownBy(() -> item.transitionStatus(BacklogItemStatus.READY))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("calibrated CoD");
    }

    @Test
    void canTransitionToReadyWithAllComponents() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        item.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        item.setTimeCriticality(CostOfDelayComponent.point(3, "alice"));
        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        item.setJobDuration(CostOfDelayComponent.point(2, "alice"));
        item.transitionStatus(BacklogItemStatus.READY);
        assertThat(item.getStatus()).isEqualTo(BacklogItemStatus.READY);
    }

    @Test
    void invalidStatusTransitionRejected() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        // CANDIDATE -> DONE is not allowed
        assertThatThrownBy(() -> item.transitionStatus(BacklogItemStatus.DONE))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void blankTitleRejectedOnUpdate() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        assertThatThrownBy(() -> item.setTitle("  ")).isInstanceOf(DomainValidationException.class);
    }
}

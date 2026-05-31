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
        var p = project();
        assertThatThrownBy(() -> new BacklogItem(p, "  ", "ok")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void constructorRejectsBlankTitle() {
        var p = project();
        assertThatThrownBy(() -> new BacklogItem(p, "ok", "  ")).isInstanceOf(DomainValidationException.class);
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

    @Test
    void transitionStatusRejectsNullTarget() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        assertThatThrownBy(() -> item.transitionStatus(null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("null");
    }

    @Test
    void toStringIncludesUidAndTitle() {
        var item = new BacklogItem(project(), "BI-42", "My Feature");
        assertThat(item.toString()).contains("BI-42").contains("My Feature");
    }

    @Test
    void hasAllComponentsReturnsFalseWhenAnyComponentMissing() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        // No components set yet.
        assertThat(item.hasAllComponents()).isFalse();

        item.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        assertThat(item.hasAllComponents()).isFalse();

        item.setTimeCriticality(CostOfDelayComponent.point(3, "alice"));
        assertThat(item.hasAllComponents()).isFalse();

        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        assertThat(item.hasAllComponents()).isFalse();

        item.setJobDuration(CostOfDelayComponent.point(2, "alice"));
        assertThat(item.hasAllComponents()).isTrue();
    }

    @Test
    void transitionFromReadyBackToCandidate() {
        var item = new BacklogItem(project(), "BI-1", "Feature");
        item.setUserBusinessValue(CostOfDelayComponent.point(5, "alice"));
        item.setTimeCriticality(CostOfDelayComponent.point(3, "alice"));
        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        item.setJobDuration(CostOfDelayComponent.point(2, "alice"));
        item.transitionStatus(BacklogItemStatus.READY);
        item.transitionStatus(BacklogItemStatus.CANDIDATE);
        assertThat(item.getStatus()).isEqualTo(BacklogItemStatus.CANDIDATE);
    }
}

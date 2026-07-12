package com.keplerops.groundcontrol.unit.domain.workflowexecution;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.workflowexecution.WorkflowExecutionId;
import org.junit.jupiter.api.Test;

class WorkflowExecutionIdTest {

    @Test
    void forImplementBuildsSlashFreeProjectPrefixedId() {
        assertThat(WorkflowExecutionId.forImplement("ground-control", 1278))
                .isEqualTo("gc-implement-ground-control-1278")
                .doesNotContain("/");
    }

    @Test
    void belongsToProjectMatchesOwnProject() {
        var id = WorkflowExecutionId.forImplement("ground-control", 1278);
        assertThat(WorkflowExecutionId.belongsToProject(id, "ground-control")).isTrue();
    }

    @Test
    void belongsToProjectRejectsOtherProject() {
        var id = WorkflowExecutionId.forImplement("ground-control", 1278);
        assertThat(WorkflowExecutionId.belongsToProject(id, "other-project")).isFalse();
    }

    @Test
    void belongsToProjectResolvesPrefixAmbiguity() {
        // "a-b-1" under project "a" would prefix-match "gc-implement-a-" but its suffix "b-1" is not
        // all digits, so it is correctly rejected; the same id belongs to project "a-b".
        var id = "gc-implement-a-b-1";
        assertThat(WorkflowExecutionId.belongsToProject(id, "a")).isFalse();
        assertThat(WorkflowExecutionId.belongsToProject(id, "a-b")).isTrue();
    }

    @Test
    void belongsToProjectRejectsNonDigitSuffixAndNulls() {
        assertThat(WorkflowExecutionId.belongsToProject("gc-implement-ground-control-x", "ground-control"))
                .isFalse();
        assertThat(WorkflowExecutionId.belongsToProject("gc-implement-ground-control-", "ground-control"))
                .isFalse();
        assertThat(WorkflowExecutionId.belongsToProject(null, "ground-control")).isFalse();
        assertThat(WorkflowExecutionId.belongsToProject("gc-implement-ground-control-1", null))
                .isFalse();
    }
}

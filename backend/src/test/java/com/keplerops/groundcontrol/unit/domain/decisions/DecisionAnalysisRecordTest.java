package com.keplerops.groundcontrol.unit.domain.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.decisions.model.DecisionAnalysisRecord;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionAnalysisRecordTest {

    private Project project() {
        return new Project("p", "P");
    }

    @Test
    void constructorRejectsBlankUidTitleOrModel() {
        assertThatThrownBy(() -> new DecisionAnalysisRecord(project(), "  ", "t", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DecisionAnalysisRecord(project(), "u", "  ", "m"))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DecisionAnalysisRecord(project(), "u", "t", "  "))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DecisionAnalysisRecord(null, "u", "t", "m"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void capturesAllPayloadFields() {
        var r = new DecisionAnalysisRecord(project(), "DR-1", "Buy vs build", "monte_carlo");
        r.setSummary("Chose buy");
        r.setInputs(Map.of("buy.cost.mean", 100_000, "build.cost.mean", 250_000));
        r.setSimulationParameters(Map.of("iterations", 10_000, "seed", 7));
        r.setResults(Map.of("buy.npv.p50", 50_000, "build.npv.p50", -20_000));
        r.setAlternatives(List.of("buy", "build", "defer"));
        r.setChosenAlternative("buy");
        r.setRationale("Buy dominates on cost and time-to-market");
        r.setCreatedBy("alice");

        assertThat(r.getModelName()).isEqualTo("monte_carlo");
        assertThat(r.getAlternatives()).containsExactly("buy", "build", "defer");
        assertThat(r.getInputs()).containsEntry("buy.cost.mean", 100_000);
        assertThat(r.getResults()).containsEntry("buy.npv.p50", 50_000);
        assertThat(r.getSimulationParameters()).containsEntry("seed", 7);
        assertThat(r.getChosenAlternative()).isEqualTo("buy");
        assertThat(r.getRationale()).startsWith("Buy dominates");
        assertThat(r.getCreatedBy()).isEqualTo("alice");
    }

    @Test
    void setTitleRejectsBlank() {
        var r = new DecisionAnalysisRecord(project(), "DR-1", "t", "m");
        assertThatThrownBy(() -> r.setTitle("  ")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void setModelNameRejectsBlank() {
        var r = new DecisionAnalysisRecord(project(), "DR-1", "t", "m");
        assertThatThrownBy(() -> r.setModelName("  ")).isInstanceOf(DomainValidationException.class);
    }
}

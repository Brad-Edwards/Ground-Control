package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.api.riskscenarios.TreatmentPlanResponse;
import com.keplerops.groundcontrol.domain.graph.model.GraphEntityType;
import com.keplerops.groundcontrol.domain.graph.model.GraphIds;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ActionItemStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TreatmentPlanResponseTest {

    private final Project project = new Project("gc", "Ground Control");
    private final UUID projectId = UUID.randomUUID();

    @Nested
    class FromWithAllFields {

        @Test
        void mapsAllFieldsFromDomainModel() {
            setField(project, "id", projectId);

            var scenario = new RiskScenario(project, "RS-1", "Scenario", "Actor", "Event", "Object", "Consequence");
            scenario.setTimeHorizon("12 months");
            var scenarioId = UUID.randomUUID();
            setField(scenario, "id", scenarioId);

            var record = new RiskRegisterRecord(project, "RR-1", "Register record");
            var recordId = UUID.randomUUID();
            setField(record, "id", recordId);

            var plan = new TreatmentPlan(project, "TP-1", "Plan title", record, TreatmentStrategy.MITIGATE);
            var planId = UUID.randomUUID();
            setField(plan, "id", planId);
            plan.setRiskScenario(scenario);
            plan.setOwner("alice");
            plan.setRationale("Reduce exposure");
            var dueDate = Instant.parse("2026-06-01T00:00:00Z");
            plan.setDueDate(dueDate);
            var actionItems = List.of(new ActionItem("alice", dueDate, ActionItemStatus.PLANNED, null, "deploy patch"));
            plan.setActionItems(actionItems);
            var triggers = List.of(new ReassessmentTrigger(
                    ReassessmentTriggerCategory.METHODOLOGY_SPECIFIC, null, null, null, "quarterly review"));
            plan.setReassessmentTriggers(triggers);
            var now = Instant.now();
            setField(plan, "createdAt", now);
            setField(plan, "updatedAt", now);

            var response = TreatmentPlanResponse.from(plan);

            assertThat(response.id()).isEqualTo(planId);
            assertThat(response.graphNodeId()).isEqualTo(GraphIds.nodeId(GraphEntityType.TREATMENT_PLAN, planId));
            assertThat(response.projectIdentifier()).isEqualTo("gc");
            assertThat(response.uid()).isEqualTo("TP-1");
            assertThat(response.title()).isEqualTo("Plan title");
            assertThat(response.riskRegisterRecordId()).isEqualTo(recordId);
            assertThat(response.riskRegisterRecordUid()).isEqualTo("RR-1");
            assertThat(response.riskScenarioId()).isEqualTo(scenarioId);
            assertThat(response.riskScenarioUid()).isEqualTo("RS-1");
            assertThat(response.strategy()).isEqualTo(TreatmentStrategy.MITIGATE);
            assertThat(response.owner()).isEqualTo("alice");
            assertThat(response.rationale()).isEqualTo("Reduce exposure");
            assertThat(response.dueDate()).isEqualTo(dueDate);
            assertThat(response.status()).isEqualTo(TreatmentPlanStatus.PLANNED);
            assertThat(response.actionItems()).isEqualTo(actionItems);
            assertThat(response.reassessmentTriggers()).hasSize(1);
            assertThat(response.reassessmentTriggers().get(0).category())
                    .isEqualTo(ReassessmentTriggerCategory.METHODOLOGY_SPECIFIC);
            assertThat(response.reassessmentTriggers().get(0).note()).isEqualTo("quarterly review");
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.updatedAt()).isEqualTo(now);
        }
    }

    @Nested
    class FromWithNullRiskScenario {

        @Test
        void setsScenarioFieldsToNullWhenRiskScenarioIsAbsent() {
            setField(project, "id", projectId);

            var record = new RiskRegisterRecord(project, "RR-2", "Register record 2");
            var recordId = UUID.randomUUID();
            setField(record, "id", recordId);

            var plan = new TreatmentPlan(project, "TP-2", "No scenario plan", record, TreatmentStrategy.ACCEPT);
            var planId = UUID.randomUUID();
            setField(plan, "id", planId);
            var now = Instant.now();
            setField(plan, "createdAt", now);
            setField(plan, "updatedAt", now);
            // riskScenario left null (default)

            var response = TreatmentPlanResponse.from(plan);

            assertThat(response.riskScenarioId()).isNull();
            assertThat(response.riskScenarioUid()).isNull();
            assertThat(response.riskRegisterRecordId()).isEqualTo(recordId);
            assertThat(response.strategy()).isEqualTo(TreatmentStrategy.ACCEPT);
        }
    }

    @Nested
    class FromWithNullOptionalFields {

        @Test
        void handlesNullOwnerRationaleDueDateActionItemsAndTriggers() {
            setField(project, "id", projectId);

            var record = new RiskRegisterRecord(project, "RR-3", "Record 3");
            setField(record, "id", UUID.randomUUID());

            var plan = new TreatmentPlan(project, "TP-3", "Minimal plan", record, TreatmentStrategy.AVOID);
            setField(plan, "id", UUID.randomUUID());
            var now = Instant.now();
            setField(plan, "createdAt", now);
            setField(plan, "updatedAt", now);
            // owner, rationale, dueDate, actionItems, reassessmentTriggers all null

            var response = TreatmentPlanResponse.from(plan);

            assertThat(response.owner()).isNull();
            assertThat(response.rationale()).isNull();
            assertThat(response.dueDate()).isNull();
            assertThat(response.actionItems()).isNull();
            assertThat(response.reassessmentTriggers()).isNull();
        }
    }

    @Nested
    class FromWithMethodologyBinding {

        @Test
        void mapsMethodologyProfileIdAndStrategyKey() {
            setField(project, "id", projectId);

            var registerRecord = new RiskRegisterRecord(project, "RR-4", "Record 4");
            setField(registerRecord, "id", UUID.randomUUID());

            var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
            var profileId = UUID.randomUUID();
            setField(profile, "id", profileId);

            var plan = new TreatmentPlan(project, "TP-4", "Other plan", registerRecord, TreatmentStrategy.OTHER);
            setField(plan, "id", UUID.randomUUID());
            plan.setMethodologyProfile(profile);
            plan.setMethodologyStrategyKey("RESIDUAL_TRANSFER");
            var now = Instant.now();
            setField(plan, "createdAt", now);
            setField(plan, "updatedAt", now);

            var response = TreatmentPlanResponse.from(plan);

            assertThat(response.methodologyProfileId()).isEqualTo(profileId);
            assertThat(response.methodologyStrategyKey()).isEqualTo("RESIDUAL_TRANSFER");
        }

        @Test
        void setsMethodologyProfileIdToNullWhenProfileIsAbsent() {
            setField(project, "id", projectId);

            var registerRecord = new RiskRegisterRecord(project, "RR-5", "Record 5");
            setField(registerRecord, "id", UUID.randomUUID());

            var plan = new TreatmentPlan(project, "TP-5", "Canonical plan", registerRecord, TreatmentStrategy.MITIGATE);
            setField(plan, "id", UUID.randomUUID());
            var now = Instant.now();
            setField(plan, "createdAt", now);
            setField(plan, "updatedAt", now);
            // methodologyProfile left null (default)

            var response = TreatmentPlanResponse.from(plan);

            assertThat(response.methodologyProfileId()).isNull();
            assertThat(response.methodologyStrategyKey()).isNull();
        }
    }
}

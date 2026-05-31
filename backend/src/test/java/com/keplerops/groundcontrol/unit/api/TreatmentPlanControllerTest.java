package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.riskscenarios.TreatmentPlanController;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskRegisterRecord;
import com.keplerops.groundcontrol.domain.riskscenarios.model.RiskScenario;
import com.keplerops.groundcontrol.domain.riskscenarios.model.TreatmentPlan;
import com.keplerops.groundcontrol.domain.riskscenarios.service.CreateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.service.TreatmentPlanService;
import com.keplerops.groundcontrol.domain.riskscenarios.service.UpdateTreatmentPlanCommand;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ActionItemStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentPlanStatus;
import com.keplerops.groundcontrol.domain.riskscenarios.state.TreatmentStrategy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(TreatmentPlanController.class)
class TreatmentPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TreatmentPlanService treatmentPlanService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000200");
    private static final UUID RECORD_ID = UUID.fromString("00000000-0000-0000-0000-000000000300");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000400");
    private static final UUID PROFILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000500");
    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");
    private static final Instant DUE = Instant.parse("2026-06-01T00:00:00Z");

    private TreatmentPlan makePlan() {
        return makePlan(true);
    }

    private TreatmentPlan makePlan(boolean withScenario) {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);

        var record = new RiskRegisterRecord(project, "RRR-001", "Register Record");
        setField(record, "id", RECORD_ID);

        var plan = new TreatmentPlan(project, "TP-001", "Enforce MFA", record, TreatmentStrategy.MITIGATE);
        plan.setOwner("Security Lead");
        plan.setRationale("Reduce credential theft risk");
        plan.setDueDate(DUE);
        plan.setActionItems(List.of(new ActionItem("Security Lead", DUE, ActionItemStatus.PLANNED, null, null)));
        plan.setReassessmentTriggers(List.of(new ReassessmentTrigger(
                ReassessmentTriggerCategory.METHODOLOGY_SPECIFIC, null, null, null, "Quarterly review")));
        setField(plan, "id", PLAN_ID);
        setField(plan, "createdAt", NOW);
        setField(plan, "updatedAt", NOW);

        if (withScenario) {
            var scenario =
                    new RiskScenario(project, "RS-001", "Credential theft", "Attacker", "Phishing", "IAM", "Breach");
            scenario.setTimeHorizon("12 months");
            setField(scenario, "id", SCENARIO_ID);
            plan.setRiskScenario(scenario);
        }

        return plan;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlan());

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "riskScenarioId": "%s",
                                  "strategy": "MITIGATE",
                                  "owner": "Security Lead",
                                  "rationale": "Reduce credential theft risk",
                                  "dueDate": "2026-06-01T00:00:00Z",
                                  "status": "PLANNED",
                                  "actionItems": [{"owner": "Security Lead", "dueDate": "2026-06-01T00:00:00Z", "status": "PLANNED"}],
                                  "reassessmentTriggers": [{"category": "METHODOLOGY_SPECIFIC", "note": "Quarterly review"}]
                                }
                                """
                                        .formatted(RECORD_ID, SCENARIO_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(PLAN_ID.toString())))
                .andExpect(jsonPath("$.graphNodeId", is("TREATMENT_PLAN:" + PLAN_ID)))
                .andExpect(jsonPath("$.projectIdentifier", is("ground-control")))
                .andExpect(jsonPath("$.uid", is("TP-001")))
                .andExpect(jsonPath("$.title", is("Enforce MFA")))
                .andExpect(jsonPath("$.riskRegisterRecordId", is(RECORD_ID.toString())))
                .andExpect(jsonPath("$.riskRegisterRecordUid", is("RRR-001")))
                .andExpect(jsonPath("$.riskScenarioId", is(SCENARIO_ID.toString())))
                .andExpect(jsonPath("$.riskScenarioUid", is("RS-001")))
                .andExpect(jsonPath("$.strategy", is("MITIGATE")))
                .andExpect(jsonPath("$.owner", is("Security Lead")))
                .andExpect(jsonPath("$.rationale", is("Reduce credential theft risk")))
                .andExpect(jsonPath("$.status", is("PLANNED")))
                .andExpect(jsonPath("$.actionItems", hasSize(1)))
                .andExpect(jsonPath("$.actionItems[0].owner", is("Security Lead")))
                .andExpect(jsonPath("$.actionItems[0].dueDate", is("2026-06-01T00:00:00Z")))
                .andExpect(jsonPath("$.actionItems[0].status", is("PLANNED")))
                .andExpect(jsonPath("$.reassessmentTriggers", hasSize(1)))
                .andExpect(jsonPath("$.reassessmentTriggers[0].category", is("METHODOLOGY_SPECIFIC")))
                .andExpect(jsonPath("$.reassessmentTriggers[0].note", is("Quarterly review")));
    }

    @Test
    void createReturns422WhenStatusIsInvalidEnumValue() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "status": "PROPOSED"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")))
                .andExpect(
                        jsonPath(
                                "$.error.message",
                                is(
                                        "Invalid value for field 'status'. Valid values: PLANNED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELED")))
                .andExpect(jsonPath("$.error.detail.field", is("status")))
                .andExpect(jsonPath("$.error.detail.validValues", hasItem("PLANNED")))
                .andExpect(jsonPath("$.error.detail.validValues", hasItem("CANCELED")));
    }

    @Test
    void createReturns422WhenUidMissing() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns422WhenTitleMissing() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns422WhenRiskRegisterRecordIdMissing() throws Exception {
        mockMvc.perform(
                        post("/api/v1/treatment-plans")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "strategy": "MITIGATE"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns422WhenStrategyMissing() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createWithNullRiskScenarioReturnsNullScenarioFields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlan(false));

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScenarioId", nullValue()))
                .andExpect(jsonPath("$.riskScenarioUid", nullValue()));
    }

    @Test
    void listReturnsPlansByProject() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.listByProject(PROJECT_ID)).thenReturn(List.of(makePlan()));

        mockMvc.perform(get("/api/v1/treatment-plans").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(PLAN_ID.toString())))
                .andExpect(jsonPath("$[0].uid", is("TP-001")));
    }

    @Test
    void listFiltersByRiskRegisterRecord() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.listByRiskRegisterRecord(PROJECT_ID, RECORD_ID))
                .thenReturn(List.of(makePlan()));

        mockMvc.perform(get("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .param("riskRegisterRecordId", RECORD_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].riskRegisterRecordId", is(RECORD_ID.toString())));
    }

    @Test
    void listReturnsEmptyWhenNone() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.listByProject(PROJECT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/treatment-plans").param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getByIdReturnsPlan() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.getById(PROJECT_ID, PLAN_ID)).thenReturn(makePlan());

        mockMvc.perform(get("/api/v1/treatment-plans/{id}", PLAN_ID).param("project", "ground-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(PLAN_ID.toString())))
                .andExpect(jsonPath("$.uid", is("TP-001")))
                .andExpect(jsonPath("$.title", is("Enforce MFA")))
                .andExpect(jsonPath("$.strategy", is("MITIGATE")));
    }

    @Test
    void updateReturnsPlan() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.update(eq(PROJECT_ID), eq(PLAN_ID), any())).thenReturn(makePlan());

        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "title": "Enforce MFA v2",
                                  "strategy": "AVOID",
                                  "owner": "CISO"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(PLAN_ID.toString())))
                .andExpect(jsonPath("$.uid", is("TP-001")))
                .andExpect(jsonPath("$.title", is("Enforce MFA")))
                .andExpect(jsonPath("$.strategy", is("MITIGATE")));
    }

    @Test
    void transitionStatusReturnsPlan() throws Exception {
        var plan = makePlan();
        plan.transitionStatus(TreatmentPlanStatus.IN_PROGRESS);
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.transitionStatus(PROJECT_ID, PLAN_ID, TreatmentPlanStatus.IN_PROGRESS))
                .thenReturn(plan);

        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}/status", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"status": "IN_PROGRESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
    }

    @Test
    void transitionStatusReturns422WhenStatusMissing() throws Exception {
        mockMvc.perform(put("/api/v1/treatment-plans/{id}/status", PLAN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deleteReturns204() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);

        mockMvc.perform(delete("/api/v1/treatment-plans/{id}", PLAN_ID).param("project", "ground-control"))
                .andExpect(status().isNoContent());

        verify(treatmentPlanService).delete(PROJECT_ID, PLAN_ID);
    }

    // -------------------------------------------------------------------------
    // C5: methodology binding fields plumb through create/update
    // -------------------------------------------------------------------------

    @Test
    void createPlumbsMethodologyBindingFieldsIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlan(false));

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Custom plan",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "OTHER",
                                  "methodologyProfileId": "%s",
                                  "methodologyStrategyKey": "CUSTOM_RESIDUAL"
                                }
                                """
                                        .formatted(RECORD_ID, PROFILE_ID)))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateTreatmentPlanCommand.class);
        verify(treatmentPlanService).create(captor.capture());
        assertThat(captor.getValue().methodologyProfileId()).isEqualTo(PROFILE_ID);
        assertThat(captor.getValue().methodologyStrategyKey()).isEqualTo("CUSTOM_RESIDUAL");
    }

    @Test
    void updatePlumbsMethodologyBindingFieldsIntoCommand() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.update(any(), any(), any())).thenReturn(makePlan(false));

        mockMvc.perform(put("/api/v1/treatment-plans/{id}", PLAN_ID)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "strategy": "OTHER",
                                  "methodologyProfileId": "%s",
                                  "methodologyStrategyKey": "UPDATED_KEY"
                                }
                                """
                                        .formatted(PROFILE_ID)))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateTreatmentPlanCommand.class);
        verify(treatmentPlanService).update(any(), any(), captor.capture());
        assertThat(captor.getValue().methodologyProfileId()).isEqualTo(PROFILE_ID);
        assertThat(captor.getValue().methodologyStrategyKey()).isEqualTo("UPDATED_KEY");
    }

    @Test
    void createPlumbsActionItemsIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlan(false));

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [
                                    {
                                      "owner": "Security Lead",
                                      "dueDate": "2026-06-01T00:00:00Z",
                                      "status": "PLANNED",
                                      "assignee": "alice",
                                      "description": "Roll out MFA"
                                    }
                                  ]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateTreatmentPlanCommand.class);
        verify(treatmentPlanService).create(captor.capture());
        var items = captor.getValue().actionItems();
        assertThat(items).isNotNull().hasSize(1);
        var item = items.get(0);
        assertThat(item.owner()).isEqualTo("Security Lead");
        assertThat(item.dueDate()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(item.status()).isEqualTo(ActionItemStatus.PLANNED);
        assertThat(item.assignee()).isEqualTo("alice");
        assertThat(item.description()).isEqualTo("Roll out MFA");
    }

    @Test
    void updatePlumbsActionItemsIntoCommand() throws Exception {
        when(projectService.requireProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.update(any(), any(), any())).thenReturn(makePlan(false));

        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "actionItems": [
                                    {
                                      "owner": "Updated Owner",
                                      "dueDate": "2026-07-15T00:00:00Z",
                                      "status": "IN_PROGRESS",
                                      "assignee": "bob"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateTreatmentPlanCommand.class);
        verify(treatmentPlanService).update(any(), any(), captor.capture());
        var items = captor.getValue().actionItems();
        assertThat(items).isNotNull().hasSize(1);
        var item = items.get(0);
        assertThat(item.owner()).isEqualTo("Updated Owner");
        assertThat(item.dueDate()).isEqualTo(Instant.parse("2026-07-15T00:00:00Z"));
        assertThat(item.status()).isEqualTo(ActionItemStatus.IN_PROGRESS);
        assertThat(item.assignee()).isEqualTo("bob");
        assertThat(item.description()).isNull();
    }

    @Test
    void createReturns422WhenServiceThrowsDomainValidationException() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any()))
                .thenThrow(new DomainValidationException(
                        "Treatment plan with strategy OTHER requires methodologyProfileId and methodologyStrategyKey"));

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Custom plan",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "OTHER"
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createReturns404WhenServiceThrowsNotFoundException() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any()))
                .thenThrow(new NotFoundException("Methodology profile not found: " + PROFILE_ID));

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Custom plan",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "OTHER",
                                  "methodologyProfileId": "%s",
                                  "methodologyStrategyKey": "KEY"
                                }
                                """
                                        .formatted(RECORD_ID, PROFILE_ID)))
                .andExpect(status().isNotFound());
    }

    private TreatmentPlan makePlanWithBinding() {
        var plan = makePlan(false);
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var profile = new MethodologyProfile(project, "CUSTOM", "Custom", "1.0", MethodologyFamily.CUSTOM);
        setField(profile, "id", PROFILE_ID);
        plan.setMethodologyProfile(profile);
        plan.setMethodologyStrategyKey("CUSTOM_RESIDUAL");
        return plan;
    }

    @Test
    void createResponseIncludesMethodologyBindingFields() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlanWithBinding());

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Custom plan",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "OTHER",
                                  "methodologyProfileId": "%s",
                                  "methodologyStrategyKey": "CUSTOM_RESIDUAL"
                                }
                                """
                                        .formatted(RECORD_ID, PROFILE_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.methodologyProfileId", is(PROFILE_ID.toString())))
                .andExpect(jsonPath("$.methodologyStrategyKey", is("CUSTOM_RESIDUAL")));
    }

    // -------------------------------------------------------------------------
    // C1: typed ActionItem Bean Validation (create path)
    // -------------------------------------------------------------------------

    @Test
    void createReturns422WhenActionItemOwnerIsNull() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [{"dueDate": "2026-06-01T00:00:00Z", "status": "PLANNED"}]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void createReturns422WhenActionItemOwnerIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [{"owner": "  ", "dueDate": "2026-06-01T00:00:00Z", "status": "PLANNED"}]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void createReturns422WhenActionItemDueDateIsNull() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [{"owner": "Security Lead", "status": "PLANNED"}]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void createReturns422WhenActionItemStatusIsInvalidEnum() throws Exception {
        // Jackson InvalidFormatException for a typed enum surfaces through the dedicated
        // handler in GlobalExceptionHandler.handleInvalidEnumFormat (422 with the
        // validation_error envelope + validValues hint), not the generic 400 fallback.
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [{"owner": "Security Lead", "dueDate": "2026-06-01T00:00:00Z", "status": "FOO"}]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")))
                .andExpect(jsonPath("$.error.detail.validValues", hasItem("PLANNED")))
                .andExpect(jsonPath("$.error.detail.validValues", hasItem("DONE")));
    }

    // -------------------------------------------------------------------------
    // C1: typed ActionItem Bean Validation (update path)
    // -------------------------------------------------------------------------

    @Test
    void updateReturns422WhenActionItemOwnerIsNull() throws Exception {
        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "actionItems": [{"dueDate": "2026-06-01T00:00:00Z", "status": "PLANNED"}]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void updateReturns422WhenActionItemDueDateIsNull() throws Exception {
        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "actionItems": [{"owner": "Security Lead", "status": "PLANNED"}]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void createReturns422WhenActionItemElementIsNull() throws Exception {
        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-001",
                                  "title": "Enforce MFA",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "actionItems": [null]
                                }
                                """
                                        .formatted(RECORD_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    @Test
    void updateReturns422WhenActionItemElementIsNull() throws Exception {
        mockMvc.perform(
                        put("/api/v1/treatment-plans/{id}", PLAN_ID)
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "actionItems": [null]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", is("validation_error")));
    }

    /**
     * GC-T015: the new monitoring fields (riskAssessmentResultId, monitoredRiskFactors,
     * updateCadence) plumb through {@link CreateTreatmentPlanCommand}. Argument captor
     * is the gate — verifies the controller passes the fields verbatim to the service.
     */
    @Test
    void createPlumbsMonitoringFieldsIntoCommand() throws Exception {
        when(projectService.resolveProjectId("ground-control")).thenReturn(PROJECT_ID);
        when(treatmentPlanService.create(any())).thenReturn(makePlan());
        var rarId = UUID.fromString("00000000-0000-0000-0000-000000000600");

        mockMvc.perform(post("/api/v1/treatment-plans")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "uid": "TP-NIST",
                                  "title": "NIST monitored plan",
                                  "riskRegisterRecordId": "%s",
                                  "strategy": "MITIGATE",
                                  "riskAssessmentResultId": "%s",
                                  "updateCadence": "P30D",
                                  "monitoredRiskFactors": [
                                    {"label": "Patch backlog", "category": "VULNERABILITY_CHANGED", "cadence": "P14D"}
                                  ]
                                }
                                """
                                        .formatted(RECORD_ID, rarId)))
                .andExpect(status().isCreated());

        var captor = org.mockito.ArgumentCaptor.forClass(CreateTreatmentPlanCommand.class);
        verify(treatmentPlanService).create(captor.capture());
        assertThat(captor.getValue().riskAssessmentResultId()).isEqualTo(rarId);
        assertThat(captor.getValue().updateCadence()).isEqualTo("P30D");
        assertThat(captor.getValue().monitoredRiskFactors()).hasSize(1);
        assertThat(captor.getValue().monitoredRiskFactors().get(0).label()).isEqualTo("Patch backlog");
        assertThat(captor.getValue().monitoredRiskFactors().get(0).category())
                .isEqualTo(ReassessmentTriggerCategory.VULNERABILITY_CHANGED);
        assertThat(captor.getValue().monitoredRiskFactors().get(0).cadence()).isEqualTo("P14D");
    }
}

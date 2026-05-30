package com.keplerops.groundcontrol.unit.api;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.api.backlog.BacklogItemController;
import com.keplerops.groundcontrol.domain.backlog.model.BacklogItem;
import com.keplerops.groundcontrol.domain.backlog.model.CostOfDelayComponent;
import com.keplerops.groundcontrol.domain.backlog.model.WsjfDistribution;
import com.keplerops.groundcontrol.domain.backlog.service.BacklogItemService;
import com.keplerops.groundcontrol.domain.backlog.service.WsjfAnalysisService;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(BacklogItemController.class)
class BacklogItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BacklogItemService backlogItemService;

    @MockitoBean
    private WsjfAnalysisService wsjfAnalysisService;

    @MockitoBean
    private ProjectService projectService;

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final Instant NOW = Instant.parse("2026-05-30T12:00:00Z");

    private BacklogItem make() {
        var project = new Project("ground-control", "Ground Control");
        setField(project, "id", PROJECT_ID);
        var item = new BacklogItem(project, "BI-1", "Add feature X");
        item.setUserBusinessValue(CostOfDelayComponent.triangular(2, 5, 8, "alice"));
        item.setTimeCriticality(CostOfDelayComponent.triangular(1, 3, 5, "alice"));
        item.setRiskReductionOpportunityEnablement(CostOfDelayComponent.point(2, "alice"));
        item.setJobDuration(CostOfDelayComponent.triangular(1, 2, 4, "alice"));
        item.setCreatedBy("alice");
        setField(item, "id", ITEM_ID);
        setField(item, "createdAt", NOW);
        setField(item, "updatedAt", NOW);
        return item;
    }

    @Test
    void createReturns201() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        when(backlogItemService.create(any())).thenReturn(make());

        mockMvc.perform(
                        post("/api/v1/backlog-items")
                                .param("project", "ground-control")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "uid": "BI-1",
                                  "title": "Add feature X",
                                  "userBusinessValue": {"kind":"TRIANGULAR","min":2,"mode":5,"max":8,"attributedTo":"alice"},
                                  "timeCriticality": {"kind":"TRIANGULAR","min":1,"mode":3,"max":5,"attributedTo":"alice"},
                                  "riskReductionOpportunityEnablement": {"kind":"POINT","min":2,"mode":2,"max":2,"attributedTo":"alice"},
                                  "jobDuration": {"kind":"TRIANGULAR","min":1,"mode":2,"max":4,"attributedTo":"alice"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(ITEM_ID.toString())))
                .andExpect(jsonPath("$.uid", is("BI-1")))
                .andExpect(jsonPath("$.status", is("CANDIDATE")))
                .andExpect(jsonPath("$.userBusinessValue.kind", is("TRIANGULAR")))
                .andExpect(jsonPath("$.userBusinessValue.attributedTo", is("alice")));
    }

    @Test
    void wsjfEndpointReturnsDistributionWithAdr035Envelope() throws Exception {
        when(projectService.resolveProjectId(any())).thenReturn(PROJECT_ID);
        var ubv = CostOfDelayComponent.point(5, "alice");
        var jd = CostOfDelayComponent.point(1, "alice");
        var dist = WsjfDistribution.compute(ubv, ubv, ubv, jd, 7L, 100);
        when(wsjfAnalysisService.computeForItem(eq(PROJECT_ID), eq(ITEM_ID), anyLong(), anyInt()))
                .thenReturn(dist);

        mockMvc.perform(get("/api/v1/backlog-items/" + ITEM_ID + "/wsjf")
                        .param("project", "ground-control")
                        .param("seed", "7")
                        .param("iterations", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisKind", is("wsjf")))
                .andExpect(jsonPath("$.scale", is("dimensionless")))
                .andExpect(jsonPath("$.units", is("value-per-week")))
                .andExpect(jsonPath("$.backlogItemId", is(ITEM_ID.toString())))
                .andExpect(jsonPath("$.iterations", is(100)))
                .andExpect(jsonPath("$.samples.length()", is(100)));
    }
}

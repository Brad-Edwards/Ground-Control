package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GC-GRC-011 in-loop control implementation gate, exercised end-to-end on a real control through
 * the full REST + service + repository + database stack.
 *
 * <p>This is the requirement's own <em>efficacy test</em>, not an existence test: it drives the
 * protected behavior (the status transition) and asserts the control effect (the transition is
 * refused until CODE implementation linkage AND efficacy-test evidence exist). If the
 * {@code ControlService} evidence guard is removed or bypassed, the {@code isConflict()}
 * expectations below flip to {@code isOk()} and this test goes red — which is exactly what a
 * GC-GRC-011 efficacy test must do.
 */
@AutoConfigureMockMvc
@Transactional
class ControlImplementationEvidenceGateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String controlId;

    @BeforeEach
    void createProposedControl() throws Exception {
        Map<String, Object> controlRequest = Map.of(
                "uid", "CTRL-GRC011-001",
                "title", "In-loop evidence gate control",
                "controlFunction", "PREVENTIVE");
        var result = mockMvc.perform(post("/api/v1/controls")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(controlRequest)))
                .andExpect(status().isCreated())
                .andReturn();
        controlId = objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText();
        transition("PROPOSED").andExpect(status().isOk());
    }

    @Test
    void blocksImplementedUntilBothCodeLinkAndEfficacyTestExist_thenAllowsOperational() throws Exception {
        // (1) No evidence at all: the transition into an in-force status is refused.
        transition("IMPLEMENTED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", is("control_missing_implementation_evidence")))
                .andExpect(jsonPath("$.error.detail.missingCodeLink", is(true)))
                .andExpect(jsonPath("$.error.detail.missingEfficacyTest", is(true)));

        // (2) CODE/IMPLEMENTS link present but still no efficacy test: still refused.
        addCodeImplementsLink();
        transition("IMPLEMENTED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.detail.missingCodeLink", is(false)))
                .andExpect(jsonPath("$.error.detail.missingEfficacyTest", is(true)));

        // (3) Both evidence kinds present: the control may enter IMPLEMENTED...
        addEfficacyTest();
        transition("IMPLEMENTED").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("IMPLEMENTED")));

        // ...and OPERATIONAL, which the gate also governs (clause c).
        transition("OPERATIONAL").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("OPERATIONAL")));
    }

    private org.springframework.test.web.servlet.ResultActions transition(String targetStatus) throws Exception {
        return mockMvc.perform(put("/api/v1/controls/{id}/status", controlId)
                .param("project", "ground-control")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + targetStatus + "\"}"));
    }

    private void addCodeImplementsLink() throws Exception {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("targetType", "CODE");
        link.put(
                "targetIdentifier",
                "backend/src/main/java/com/keplerops/groundcontrol/domain/controls/service/ControlService.java");
        link.put("linkType", "IMPLEMENTS");
        mockMvc.perform(post("/api/v1/controls/{controlId}/links", controlId)
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(link)))
                .andExpect(status().isCreated());
    }

    private void addEfficacyTest() throws Exception {
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("controlId", controlId);
        test.put("uid", "CT-GRC011-001");
        test.put("methodology", "RE_PERFORMANCE");
        test.put("testSteps", "Attempt PROPOSED->IMPLEMENTED transition after removing the CODE/efficacy evidence.");
        test.put("expectedResults", "Transition is refused with control_missing_implementation_evidence.");
        test.put("actualResults", "Transition refused; control remained PROPOSED.");
        test.put("conclusion", "EFFECTIVE");
        test.put("testerIdentity", "auditor@example.com");
        test.put("testDate", "2026-07-05");
        mockMvc.perform(post("/api/v1/control-tests")
                        .param("project", "ground-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(test)))
                .andExpect(status().isCreated());
    }
}

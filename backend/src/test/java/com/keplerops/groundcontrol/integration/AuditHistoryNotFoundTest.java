package com.keplerops.groundcontrol.integration;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Not-found responses from the audit history, timeline and diff endpoints.
 *
 * Split out of AuditHistoryIntegrationTest under issue #1467 for the 500-LOC
 * limit (docs/CODING_STANDARDS.md). These cases address ids that deliberately
 * do not exist, so unlike the rest of that class they read nothing an earlier
 * test wrote and do not belong in its ordered chain.
 */
@AutoConfigureMockMvc
class AuditHistoryNotFoundTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void historyForNonexistentRequirement_returns404() throws Exception {
        var fakeId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/requirements/" + fakeId + "/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void timeline_nonexistentRequirement_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/timeline"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }

    @Test
    void diff_nonexistentRequirement_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/requirements/" + UUID.randomUUID() + "/diff")
                        .param("fromRevision", "1")
                        .param("toRevision", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", is("not_found")));
    }
}

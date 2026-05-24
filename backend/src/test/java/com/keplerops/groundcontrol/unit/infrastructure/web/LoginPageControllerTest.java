package com.keplerops.groundcontrol.unit.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.keplerops.groundcontrol.infrastructure.web.LoginPageController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Slice coverage for {@link LoginPageController}.
 *
 * <p>The controller's job is to stream {@code static/login.html} as the response body for
 * {@code GET /login} without re-entering the security filter chain — a classpath read, a
 * content-type header, and a cache directive. We test all three plus the no-{@code forward:}
 * invariant (a {@code forward:} would be visible via {@code MockMvc.getForwardedUrl()}).
 *
 * <p>The {@code BrowserHttpFlowIntegrationTest} covers the same path at the HTTP level with a
 * real servlet container; this slice exists so the controller contributes to the SonarCloud
 * {@code new_coverage} unit-test metric (the Sonar job does not run Testcontainers).
 */
@WebMvcTest(controllers = LoginPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoginPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getLogin_returnsLoginHtmlBundle() throws Exception {
        MvcResult result = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("Login page must stream the login bundle HTML, not the main SPA shell")
                .contains("gc-test-marker: login bundle")
                .doesNotContain("gc-test-marker: main spa shell");
        assertThat(result.getResponse().getForwardedUrl())
                .as("ResponseEntity<Resource> must stream the body directly, NOT via a Servlet "
                        + "forward — a forward would re-enter the security filter chain on the "
                        + "forwarded path and risk the loop class fixed by issue #846.")
                .isNull();
    }

    @Test
    void getLogin_setsNoStoreCacheControl() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}

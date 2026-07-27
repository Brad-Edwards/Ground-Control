package com.keplerops.groundcontrol.unit.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.shared.security.ApiRequestPaths;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiRequestPathsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/requirements",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html",
                "/swagger-ui.html"
            })
    void matcherRecognizesApiAndDocumentationPaths(String path) {
        assertThat(ApiRequestPaths.matcher().matches(new MockHttpServletRequest("GET", path)))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/requirements", "/apiary", "/v3/api-docs-extra", "/swagger-uiish"})
    void matcherRejectsNonApiPaths(String path) {
        assertThat(ApiRequestPaths.matcher().matches(new MockHttpServletRequest("GET", path)))
                .isFalse();
    }
}

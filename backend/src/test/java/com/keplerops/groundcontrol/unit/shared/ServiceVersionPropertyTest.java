package com.keplerops.groundcontrol.unit.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies that the product version reaches the Spring Environment property that
 * logback-spring.xml reads (`info.app.version` -> SERVICE_VERSION). Build-info
 * (BuildProperties) is not on the Environment, so logback cannot read build.version
 * directly; the version is injected into application.yml at build time by
 * processResources token filtering (build.gradle.kts). This test fails if the
 * `@projectVersion@` token is not replaced or `info.app.version` is missing, which is
 * exactly the "service.version=unknown" regression (GC-P027 / #1399).
 */
class ServiceVersionPropertyTest {

    @Test
    void applicationYamlExposesResolvedProductVersion() {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        ClassPathResource resource = new ClassPathResource("application.yml");
        List<PropertySource<?>> sources;
        try {
            sources = loader.load("application.yml", resource);
        } catch (Exception e) {
            throw new AssertionError("application.yml could not be loaded from the test classpath", e);
        }

        Object value = null;
        for (PropertySource<?> source : sources) {
            Object candidate = source.getProperty("info.app.version");
            if (candidate != null) {
                value = candidate;
            }
        }

        assertThat(value)
                .as("info.app.version must be defined so logback SERVICE_VERSION resolves")
                .isNotNull();
        String version = value.toString();
        assertThat(version)
                .as("info.app.version must resolve to a real product version: processResources replaces the"
                        + " @projectVersion@ token and it is not the logback 'unknown' fallback")
                .doesNotContain("@")
                .isNotEqualTo("unknown")
                .matches("\\d+\\.\\d+\\.\\d+.*");
    }
}

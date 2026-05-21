package com.keplerops.groundcontrol.unit.domain.riskcontrol;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.riskcontrol.service.MethodologyInfluenceValidator;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import com.keplerops.groundcontrol.domain.riskscenarios.state.MethodologyFamily;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for MethodologyInfluenceValidator — covers C4 schema validation. */
class MethodologyInfluenceValidatorTest {

    private MethodologyInfluenceValidator validator;
    private Project project;
    private MethodologyProfile profile;

    @BeforeEach
    void setUp() {
        validator = new MethodologyInfluenceValidator();
        project = new Project("test", "Test");
        setField(project, "id", UUID.randomUUID());

        profile = new MethodologyProfile(project, "fair-v1", "FAIR v1", "1.0", MethodologyFamily.FAIR);
        setField(profile, "id", UUID.randomUUID());
    }

    @Test
    void nullSchemaAllowsAnyPayload() {
        // No inputSchema set — any influence map is valid
        assertThatCode(() -> validator.validate(profile, Map.of("anything", "goes")))
                .doesNotThrowAnyException();
    }

    @Test
    void emptySchemaAllowsAnyPayload() {
        profile.setInputSchema(Map.of());
        assertThatCode(() -> validator.validate(profile, Map.of("anything", "goes")))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredFieldThrowsValidationException() {
        profile.setInputSchema(Map.of(
                "required", List.of("threatEventFrequency", "lossEventFrequency"),
                "properties",
                        Map.of(
                                "threatEventFrequency", Map.of("type", "number"),
                                "lossEventFrequency", Map.of("type", "number"))));

        var influence = Map.<String, Object>of("threatEventFrequency", 0.5);
        assertThatThrownBy(() -> validator.validate(profile, influence))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("lossEventFrequency");
    }

    @Test
    void unknownFieldThrowsValidationException() {
        profile.setInputSchema(Map.of("properties", Map.of("threatEventFrequency", Map.of("type", "number"))));

        var influence = Map.<String, Object>of("threatEventFrequency", 0.5, "unknownField", "bad");
        assertThatThrownBy(() -> validator.validate(profile, influence))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("unknownField");
    }

    @Test
    void validPayloadPassesValidation() {
        profile.setInputSchema(Map.of(
                "required", List.of("threatEventFrequency"),
                "properties",
                        Map.of(
                                "threatEventFrequency", Map.of("type", "number"),
                                "lossEventFrequency", Map.of("type", "number"))));

        assertThatCode(() ->
                        validator.validate(profile, Map.of("threatEventFrequency", 0.5, "lossEventFrequency", 0.3)))
                .doesNotThrowAnyException();
    }

    @Test
    void nullProfileOrInfluenceIsNoOp() {
        assertThatCode(() -> validator.validate(null, Map.of("x", 1))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(profile, null)).doesNotThrowAnyException();
    }
}

package com.keplerops.groundcontrol.domain.riskcontrol.service;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.riskscenarios.model.MethodologyProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Validates a methodology influence payload against a {@link MethodologyProfile}'s
 * {@code inputSchema} (GC-T003 C4).
 *
 * <p>The schema is a {@code Map<String, Object>} that may carry optional field definitions
 * under a {@code "required"} key (a JSON-array-shaped list of required field names) and a
 * {@code "properties"} key (map of field name → field descriptor). This validator checks:
 * <ol>
 *   <li>All keys in {@code required} are present in the influence payload.
 *   <li>No unknown keys appear in the payload (any key not declared in {@code properties} is
 *       rejected if the profile schema defines at least one property).
 * </ol>
 *
 * <p>This is a reusable, profile-keyed service with no per-methodology branching in controllers.
 */
@Component
public class MethodologyInfluenceValidator {

    /**
     * Validates {@code influence} against {@code profile.inputSchema}.
     *
     * @throws DomainValidationException if the payload violates the schema constraints.
     */
    @SuppressWarnings("unchecked")
    public void validate(MethodologyProfile profile, Map<String, Object> influence) {
        if (profile == null || influence == null) {
            return;
        }

        var schema = profile.getInputSchema();
        if (schema == null || schema.isEmpty()) {
            // No schema constraints — any payload is valid.
            return;
        }

        var errors = new ArrayList<String>();

        // Check required fields
        if (schema.containsKey("required")) {
            var required = (List<Object>) schema.get("required");
            for (var req : required) {
                if (!influence.containsKey(req.toString())) {
                    errors.add("Missing required influence field: " + req);
                }
            }
        }

        // Check unknown keys (only when schema declares properties)
        if (schema.containsKey("properties")) {
            var properties = (Map<String, Object>) schema.get("properties");
            for (var key : influence.keySet()) {
                if (!properties.containsKey(key)) {
                    errors.add("Unknown influence field not declared in schema: " + key);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new DomainValidationException(
                    "Methodology influence payload failed schema validation: " + errors,
                    "methodology_influence_validation_error",
                    Map.of("errors", String.join("; ", errors), "profileKey", profile.getProfileKey()));
        }
    }
}

package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Split from AssetSubtypeValidatorTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
class AssetSubtypeValidatorSchemaBodyValidationTest {
    private final AssetSubtypeValidator validator = new AssetSubtypeValidator();

    @Nested
    class SchemaBodyValidation {

        @Test
        void nullSchemaBodyPassesWhenFieldsNotRequired() {
            // Lenient path (e.g. update on a DEPRECATED row): a null body is
            // the "no schema" sentinel and must not throw.
            assertThatCode(() -> validator.validateSchemaBody(null, false)).doesNotThrowAnyException();
        }

        @Test
        void nullSchemaBodyRejectedWhenFieldsRequired() {
            // ACTIVE registry path: a body with no fields means no enforceable
            // contract; codex over-cap finding 3 on #722.
            assertThatThrownBy(() -> validator.validateSchemaBody(null, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("schema_body_required");
        }

        @Test
        void emptyFieldsRejectedWhenFieldsRequired() {
            Map<String, Object> body = Map.of("fields", Map.of());
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("schema_body_required");
        }

        @Test
        void rejectsMalformedFieldsKeyword() {
            Map<String, Object> body = Map.of("fields", "not-a-map");

            assertThatThrownBy(() -> validator.validateSchemaBody(body, false))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsUnsupportedFieldType() {
            Map<String, Object> body = Map.of("fields", Map.of("x", Map.of("type", "WIDGET")));

            assertThatThrownBy(() -> validator.validateSchemaBody(body, false))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsUnknownRootKeyword() {
            // Codex cycle-3 finding 2: typo'd / unknown root keyword (e.g.
            // `allow_additional` snake_case mis-spelling) must NOT be
            // silently ignored.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fields", Map.of("name", Map.of("type", "STRING")));
            body.put("allow_additional", true);
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsTypeInapplicableKeyword() {
            // `minimum` on a STRING field is meaningless; must reject so the
            // author's intent surfaces.
            Map<String, Object> body = Map.of("fields", Map.of("name", Map.of("type", "STRING", "minimum", 0)));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsFieldNameExceedingMaxKeyLength() {
            String oversize = "x".repeat(AssetSubtypeValidator.MAX_KEY_LENGTH + 1);
            Map<String, Object> body = Map.of("fields", Map.of(oversize, Map.of("type", "STRING")));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsMinimumGreaterThanMaximum() {
            Map<String, Object> body =
                    Map.of("fields", Map.of("n", Map.of("type", "INTEGER", "minimum", 10, "maximum", 5)));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsRequiredCountExceedingUniversalKeyCap() {
            // Codex cycle-4 finding 2: a schema demanding more required fields
            // than the universal MAX_METADATA_KEYS is unsatisfiable.
            Map<String, Object> fields = new LinkedHashMap<>();
            for (int i = 0; i < AssetSubtypeValidator.MAX_METADATA_KEYS + 1; i++) {
                fields.put("f" + i, Map.of("type", "STRING", "required", true));
            }
            Map<String, Object> body = Map.of("fields", fields);
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsIntegerWithFractionalMinimum() {
            Map<String, Object> body =
                    Map.of("fields", Map.of("n", Map.of("type", "INTEGER", "minimum", 0.1, "maximum", 0.2)));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsStringMaxLengthExceedingUniversalLimit() {
            Map<String, Object> body = Map.of(
                    "fields",
                    Map.of(
                            "n",
                            Map.of("type", "STRING", "maxLength", AssetSubtypeValidator.MAX_STRING_VALUE_LENGTH + 1)));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        @Test
        void rejectsEnumValueExceedingUniversalStringLimit() {
            String huge = "v".repeat(AssetSubtypeValidator.MAX_STRING_VALUE_LENGTH + 1);
            Map<String, Object> body = Map.of("fields", Map.of("t", Map.of("type", "ENUM", "values", List.of(huge))));
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        /**
         * Codex cycle-5 finding 1: a present-null keyword must NOT be silently
         * treated as absent — that would let a malformed schema weaken its
         * own contract. Parameterized to cover all four keyword categories
         * (per Sonar S5976; was four separate tests).
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "present-null {0} rejected")
        @org.junit.jupiter.params.provider.MethodSource("presentNullKeywords")
        void rejectsPresentNullKeyword(String keyword, Map<String, Object> body) {
            assertThatThrownBy(() -> validator.validateSchemaBody(body, true))
                    .isInstanceOf(DomainValidationException.class)
                    .extracting(e -> ((DomainValidationException) e).getDetail().get("reason"))
                    .isEqualTo("invalid_schema_shape");
        }

        static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> presentNullKeywords() {
            Map<String, Object> requiredNull = new LinkedHashMap<>();
            requiredNull.put("type", "STRING");
            requiredNull.put("required", null);

            Map<String, Object> allowAdditionalNull = new LinkedHashMap<>();
            allowAdditionalNull.put("fields", Map.of("name", Map.of("type", "STRING")));
            allowAdditionalNull.put("allowAdditional", null);

            Map<String, Object> maxLengthNull = new LinkedHashMap<>();
            maxLengthNull.put("type", "STRING");
            maxLengthNull.put("maxLength", null);

            Map<String, Object> minimumNull = new LinkedHashMap<>();
            minimumNull.put("type", "INTEGER");
            minimumNull.put("minimum", null);

            return java.util.stream.Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of(
                            "required", Map.of("fields", Map.of("name", requiredNull))),
                    org.junit.jupiter.params.provider.Arguments.of("allowAdditional", allowAdditionalNull),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "maxLength", Map.of("fields", Map.of("name", maxLengthNull))),
                    org.junit.jupiter.params.provider.Arguments.of(
                            "minimum", Map.of("fields", Map.of("count", minimumNull))));
        }

        @Test
        void acceptsWellFormedSchema() {
            Map<String, Object> body = new LinkedHashMap<>();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("cloud_account_id", Map.of("type", "STRING", "required", true, "maxLength", 64));
            fields.put("tier", Map.of("type", "ENUM", "values", List.of("BRONZE", "SILVER", "GOLD")));
            fields.put("count", Map.of("type", "INTEGER", "minimum", 0, "maximum", 100));
            body.put("fields", fields);
            body.put("allowAdditional", false);

            assertThatCode(() -> validator.validateSchemaBody(body, true)).doesNotThrowAnyException();
        }
    }
}

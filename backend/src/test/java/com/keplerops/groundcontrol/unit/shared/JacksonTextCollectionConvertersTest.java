package com.keplerops.groundcontrol.unit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ActionItemStatus;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JacksonTextCollectionConvertersTest {

    @Nested
    class StringListConverterTests {

        private final JacksonTextCollectionConverters.StringListConverter converter =
                new JacksonTextCollectionConverters.StringListConverter();

        @Test
        void convertToDatabaseColumn_nullInput_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_nullInput_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_blankString_returnsNull() {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("   ")).isNull();
        }

        @Test
        void roundTrip_emptyList() {
            var original = List.<String>of();
            String json = converter.convertToDatabaseColumn(original);
            List<String> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).isEmpty();
        }

        @Test
        void roundTrip_populatedList() {
            var original = List.of("alpha", "beta", "gamma");
            String json = converter.convertToDatabaseColumn(original);
            List<String> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).containsExactly("alpha", "beta", "gamma");
        }

        @Test
        void convertToDatabaseColumn_producesValidJson() {
            var input = List.of("one", "two");
            String json = converter.convertToDatabaseColumn(input);

            assertThat(json).isEqualTo("[\"one\",\"two\"]");
        }

        @Test
        void convertToEntityAttribute_invalidJson_throwsIllegalArgument() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("{not valid json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unable to deserialize JSON column");
        }
    }

    @Nested
    class StringObjectMapConverterTests {

        private final JacksonTextCollectionConverters.StringObjectMapConverter converter =
                new JacksonTextCollectionConverters.StringObjectMapConverter();

        @Test
        void convertToDatabaseColumn_nullInput_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_nullInput_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_blankString_returnsNull() {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("  ")).isNull();
        }

        @Test
        void roundTrip_emptyMap() {
            var original = Map.<String, Object>of();
            String json = converter.convertToDatabaseColumn(original);
            Map<String, Object> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).isEmpty();
        }

        @Test
        void roundTrip_populatedMap() {
            var original = Map.<String, Object>of("key", "value", "count", 42);
            String json = converter.convertToDatabaseColumn(original);
            Map<String, Object> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).containsEntry("key", "value").containsEntry("count", 42);
        }

        @Test
        void roundTrip_nestedValues() {
            var original = Map.<String, Object>of("nested", Map.of("inner", "deep"));
            String json = converter.convertToDatabaseColumn(original);
            Map<String, Object> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).containsKey("nested");
            @SuppressWarnings("unchecked")
            var nested = (Map<String, Object>) restored.get("nested");
            assertThat(nested).containsEntry("inner", "deep");
        }

        @Test
        void convertToEntityAttribute_invalidJson_throwsIllegalArgument() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("[not a map]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unable to deserialize JSON column");
        }
    }

    @Nested
    class MapListConverterTests {

        private final JacksonTextCollectionConverters.MapListConverter converter =
                new JacksonTextCollectionConverters.MapListConverter();

        @Test
        void convertToDatabaseColumn_nullInput_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_nullInput_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_blankString_returnsNull() {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("\t")).isNull();
        }

        @Test
        void roundTrip_emptyList() {
            var original = List.<Map<String, Object>>of();
            String json = converter.convertToDatabaseColumn(original);
            List<Map<String, Object>> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).isEmpty();
        }

        @Test
        void roundTrip_populatedList() {
            var original = List.<Map<String, Object>>of(
                    Map.of("action", "deploy", "priority", 1), Map.of("action", "test", "priority", 2));
            String json = converter.convertToDatabaseColumn(original);
            List<Map<String, Object>> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).hasSize(2);
            assertThat(restored.get(0)).containsEntry("action", "deploy").containsEntry("priority", 1);
            assertThat(restored.get(1)).containsEntry("action", "test").containsEntry("priority", 2);
        }

        @Test
        void convertToEntityAttribute_invalidJson_throwsIllegalArgument() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("not json at all"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unable to deserialize JSON column");
        }
    }

    @Nested
    class ActionItemListConverterTests {

        private final JacksonTextCollectionConverters.ActionItemListConverter converter =
                new JacksonTextCollectionConverters.ActionItemListConverter();

        @Test
        void convertToDatabaseColumn_nullInput_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_nullInput_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_blankString_returnsNull() {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("  ")).isNull();
        }

        @Test
        void roundTrip_singleTypedActionItem() {
            var dueDate = Instant.parse("2026-06-01T00:00:00Z");
            var original = List.of(new ActionItem("alice", dueDate, ActionItemStatus.PLANNED, "bob", "Do the thing"));
            String json = converter.convertToDatabaseColumn(original);
            List<ActionItem> restored = converter.convertToEntityAttribute(json);

            assertThat(restored).hasSize(1);
            var item = restored.get(0);
            assertThat(item.owner()).isEqualTo("alice");
            assertThat(item.dueDate()).isEqualTo(dueDate);
            assertThat(item.status()).isEqualTo(ActionItemStatus.PLANNED);
            assertThat(item.assignee()).isEqualTo("bob");
            assertThat(item.description()).isEqualTo("Do the thing");
        }

        @Test
        void roundTrip_emptyList() {
            var original = List.<ActionItem>of();
            String json = converter.convertToDatabaseColumn(original);
            List<ActionItem> restored = converter.convertToEntityAttribute(json);
            assertThat(restored).isEmpty();
        }

        @Test
        void legacyV043Row_descriptionOnlyReadsLeniently() {
            // A pre-typed V043 row with only "description" key: unknown fields ignored,
            // required fields left null (lenient Jackson ignoreUnknown = true).
            String legacyJson = "[{\"description\":\"old text\"}]";
            List<ActionItem> restored = converter.convertToEntityAttribute(legacyJson);

            assertThat(restored).hasSize(1);
            var item = restored.get(0);
            assertThat(item.owner()).isNull();
            assertThat(item.dueDate()).isNull();
            assertThat(item.status()).isNull();
            assertThat(item.description()).isEqualTo("old text");
        }

        @Test
        void writePath_emitsCanonicalShape_noExtraKeys() {
            var dueDate = Instant.parse("2026-06-01T00:00:00Z");
            var item = new ActionItem("alice", dueDate, ActionItemStatus.IN_PROGRESS, null, null);
            String json = converter.convertToDatabaseColumn(List.of(item));

            // null fields (assignee, description) must be absent due to @JsonInclude(NON_NULL)
            assertThat(json).doesNotContain("assignee");
            assertThat(json).doesNotContain("description");
            assertThat(json).contains("\"owner\":\"alice\"");
            assertThat(json).contains("\"status\":\"IN_PROGRESS\"");
        }

        @Test
        void legacyRow_actionKey_foldedIntoDescription() {
            String legacyJson = "[{\"action\":\"do the thing\"}]";
            List<ActionItem> restored = converter.convertToEntityAttribute(legacyJson);

            assertThat(restored).hasSize(1);
            var item = restored.get(0);
            assertThat(item.description()).isEqualTo("action: do the thing");
        }

        @Test
        void legacyRow_multipleFreeTextKeys_concatenatedIntoDescription() {
            String legacyJson = "[{\"what\":\"x\",\"who\":\"alice\"}]";
            List<ActionItem> restored = converter.convertToEntityAttribute(legacyJson);

            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).description()).isEqualTo("what: x; who: alice");
        }

        @Test
        void legacyRow_descriptionPresent_legacyKeysNotFolded() {
            // When description is already present, legacy keys are not re-folded
            // (the converter keeps the canonical description verbatim).
            String legacyJson = "[{\"description\":\"canonical\",\"action\":\"ignored\"}]";
            List<ActionItem> restored = converter.convertToEntityAttribute(legacyJson);

            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).description()).isEqualTo("canonical");
        }

        @Test
        void legacyRow_unrecognisedKeysOnly_descriptionStaysNull() {
            // A row carrying keys that are NOT in the legacy free-text set drops to
            // null fields (the reader is lenient, not magical).
            String legacyJson = "[{\"random\":\"x\"}]";
            List<ActionItem> restored = converter.convertToEntityAttribute(legacyJson);

            assertThat(restored).hasSize(1);
            assertThat(restored.get(0).description()).isNull();
        }

        @Test
        void convertToEntityAttribute_nonArrayJson_throws() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("{\"not\":\"array\"}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected JSON array");
        }
    }
}

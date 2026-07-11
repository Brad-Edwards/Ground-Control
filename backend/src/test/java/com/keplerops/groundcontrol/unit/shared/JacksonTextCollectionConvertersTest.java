package com.keplerops.groundcontrol.unit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
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
}

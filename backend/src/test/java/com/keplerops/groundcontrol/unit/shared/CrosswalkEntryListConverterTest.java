package com.keplerops.groundcontrol.unit.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.state.CrosswalkVocabularySurface;
import com.keplerops.groundcontrol.domain.riskscenarios.state.NormalizedConcept;
import com.keplerops.groundcontrol.shared.persistence.JacksonTextCollectionConverters;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CrosswalkEntryListConverterTest {

    private final JacksonTextCollectionConverters.CrosswalkEntryListConverter converter =
            new JacksonTextCollectionConverters.CrosswalkEntryListConverter();

    @Nested
    class NullAndBlankHandling {

        @Test
        void convertToDatabaseColumn_null_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_null_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void convertToEntityAttribute_blank_returnsNull() {
            assertThat(converter.convertToEntityAttribute("")).isNull();
            assertThat(converter.convertToEntityAttribute("   ")).isNull();
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void emptyList_survivesRoundTrip() {
            var json = converter.convertToDatabaseColumn(List.of());
            var restored = converter.convertToEntityAttribute(json);
            assertThat(restored).isEmpty();
        }

        @Test
        void fullEntry_survivesRoundTrip() {
            var entry = new CrosswalkEntry(
                    NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "loss_event_frequency",
                    "Loss Event Frequency",
                    "Expected annual frequency of a loss event occurring",
                    "continuous",
                    "annual events",
                    "LEF = TEF × Vulnerability",
                    "Derived field; may be supplied directly if pre-calculated");

            var json = converter.convertToDatabaseColumn(List.of(entry));
            var restored = converter.convertToEntityAttribute(json);

            assertThat(restored).hasSize(1);
            var r = restored.get(0);
            assertThat(r.normalizedConcept()).isEqualTo(NormalizedConcept.LIKELIHOOD_OR_FREQUENCY);
            assertThat(r.vocabularySurface()).isEqualTo(CrosswalkVocabularySurface.INPUT_SCHEMA);
            assertThat(r.sourceFieldPath()).isEqualTo("loss_event_frequency");
            assertThat(r.sourceTermLabel()).isEqualTo("Loss Event Frequency");
            assertThat(r.sourceTermDefinition()).isEqualTo("Expected annual frequency of a loss event occurring");
            assertThat(r.scale()).isEqualTo("continuous");
            assertThat(r.units()).isEqualTo("annual events");
            assertThat(r.conversionRule()).isEqualTo("LEF = TEF × Vulnerability");
            assertThat(r.limitations()).isEqualTo("Derived field; may be supplied directly if pre-calculated");
        }

        @Test
        void minimalEntry_onlyRequiredFields_survivesRoundTrip() {
            var entry = new CrosswalkEntry(
                    NormalizedConcept.THREAT_SOURCE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "threat_source",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var json = converter.convertToDatabaseColumn(List.of(entry));
            var restored = converter.convertToEntityAttribute(json);

            assertThat(restored).hasSize(1);
            var r = restored.get(0);
            assertThat(r.normalizedConcept()).isEqualTo(NormalizedConcept.THREAT_SOURCE);
            assertThat(r.vocabularySurface()).isEqualTo(CrosswalkVocabularySurface.INPUT_SCHEMA);
            assertThat(r.sourceFieldPath()).isEqualTo("threat_source");
            assertThat(r.sourceTermLabel()).isNull();
            assertThat(r.sourceTermDefinition()).isNull();
            assertThat(r.scale()).isNull();
            assertThat(r.units()).isNull();
            assertThat(r.conversionRule()).isNull();
            assertThat(r.limitations()).isNull();
        }

        @Test
        void multipleEntries_preserveOrder() {
            var e1 = new CrosswalkEntry(
                    NormalizedConcept.THREAT_SOURCE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "threat_source",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            var e2 = new CrosswalkEntry(
                    NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                    CrosswalkVocabularySurface.INPUT_SCHEMA,
                    "primary_loss_magnitude",
                    null,
                    null,
                    "continuous",
                    "monetary",
                    null,
                    null);

            var json = converter.convertToDatabaseColumn(List.of(e1, e2));
            var restored = converter.convertToEntityAttribute(json);

            assertThat(restored).hasSize(2);
            assertThat(restored.get(0).normalizedConcept()).isEqualTo(NormalizedConcept.THREAT_SOURCE);
            assertThat(restored.get(1).normalizedConcept()).isEqualTo(NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE);
        }

        @Test
        void allNineNormalizedConcepts_roundTrip() {
            // C1: every normalized concept must be expressible. GC-T016 split
            // grew the enum to 12 (added PRIMARY_LOSS_MAGNITUDE /
            // SECONDARY_LOSS_MAGNITUDE alongside the generic
            // IMPACT_OR_LOSS_MAGNITUDE).
            var entries = List.of(
                    new CrosswalkEntry(
                            NormalizedConcept.THREAT_SOURCE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f1",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.THREAT_EVENT,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f2",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.VULNERABILITY_OR_EXPOSURE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f3",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.ASSET,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f4",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.PROCESS_OR_OBJECTIVE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f5",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.CONSEQUENCE_OR_EFFECT,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f6",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.CONTROL,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f7",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.LIKELIHOOD_OR_FREQUENCY,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f8",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.IMPACT_OR_LOSS_MAGNITUDE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f9",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.PRIMARY_LOSS_MAGNITUDE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f10a",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.SECONDARY_LOSS_MAGNITUDE,
                            CrosswalkVocabularySurface.INPUT_SCHEMA,
                            "f10b",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    new CrosswalkEntry(
                            NormalizedConcept.TREATMENT,
                            CrosswalkVocabularySurface.TREATMENT_STRATEGY_VOCABULARY,
                            "f10",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));

            var json = converter.convertToDatabaseColumn(entries);
            var restored = converter.convertToEntityAttribute(json);
            assertThat(restored).hasSize(NormalizedConcept.values().length);
            assertThat(restored.stream().map(CrosswalkEntry::normalizedConcept).toList())
                    .containsExactly(NormalizedConcept.values());
        }
    }
}

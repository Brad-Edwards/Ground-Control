package com.keplerops.groundcontrol.shared.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keplerops.groundcontrol.domain.audits.model.AuditPhase;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.RegisteredControlPackEntry;
import com.keplerops.groundcontrol.domain.packregistry.model.TrustPolicyRule;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ActionItem;
import com.keplerops.groundcontrol.domain.riskscenarios.model.CrosswalkEntry;
import com.keplerops.groundcontrol.domain.riskscenarios.model.ReassessmentTrigger;
import com.keplerops.groundcontrol.domain.riskscenarios.state.ReassessmentTriggerCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JacksonTextCollectionConverters {

    private JacksonTextCollectionConverters() {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private abstract static class AbstractJsonTextConverter<T> implements AttributeConverter<T, String> {

        private final TypeReference<T> typeReference;

        protected AbstractJsonTextConverter(TypeReference<T> typeReference) {
            this.typeReference = typeReference;
        }

        @Override
        public String convertToDatabaseColumn(T attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Unable to serialize JSON column", exception);
            }
        }

        @Override
        public T convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                return OBJECT_MAPPER.readValue(dbData, typeReference);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to deserialize JSON column", exception);
            }
        }
    }

    @Converter
    public static class StringListConverter extends AbstractJsonTextConverter<List<String>> {

        public StringListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class StringObjectMapConverter extends AbstractJsonTextConverter<Map<String, Object>> {

        public StringObjectMapConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class MapListConverter extends AbstractJsonTextConverter<List<Map<String, Object>>> {

        public MapListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class PackDependencyListConverter extends AbstractJsonTextConverter<List<PackDependency>> {

        public PackDependencyListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class RegisteredControlPackEntryListConverter
            extends AbstractJsonTextConverter<List<RegisteredControlPackEntry>> {

        public RegisteredControlPackEntryListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class TrustPolicyRuleListConverter extends AbstractJsonTextConverter<List<TrustPolicyRule>> {

        public TrustPolicyRuleListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class StringSetConverter extends AbstractJsonTextConverter<Set<String>> {

        public StringSetConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class EvidenceSourceRefListConverter extends AbstractJsonTextConverter<List<EvidenceSourceRef>> {

        public EvidenceSourceRefListConverter() {
            super(new TypeReference<>() {});
        }
    }

    @Converter
    public static class AuditPhaseListConverter extends AbstractJsonTextConverter<List<AuditPhase>> {

        public AuditPhaseListConverter() {
            super(new TypeReference<>() {});
        }
    }

    /**
     * Persistence converter for {@code TreatmentPlan.actionItems} (per GC-T004 / C6, issue #862).
     *
     * <p>Writes the canonical typed shape only. On read, legacy rows persisted under the
     * earlier {@code List<Map<String, Object>>} contract may carry free-text keys instead of
     * the canonical {@code description}. The reader recognises the known historical keys
     * and folds them into {@code description} so the canonical fields surface intact and no
     * legacy content is silently dropped. Recognised legacy keys come from V043 and ad-hoc
     * pre-typed writes.
     */
    @Converter
    public static class ActionItemListConverter implements AttributeConverter<List<ActionItem>, String> {

        private static final List<String> LEGACY_FREE_TEXT_KEYS =
                List.of("action", "task", "item", "what", "who", "when", "done", "note", "notes", "summary");

        private static final List<String> CANONICAL_KEYS =
                List.of("owner", "dueDate", "status", "assignee", "description");

        @Override
        public String convertToDatabaseColumn(List<ActionItem> attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Unable to serialize action_items JSON", exception);
            }
        }

        @Override
        public List<ActionItem> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(dbData);
                if (root.isNull()) {
                    return null;
                }
                if (!root.isArray()) {
                    throw new IllegalArgumentException(
                            "Expected JSON array for action_items, got " + root.getNodeType());
                }
                List<ActionItem> items = new ArrayList<>(root.size());
                for (JsonNode node : root) {
                    items.add(readNode(node));
                }
                return items;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to deserialize action_items JSON", exception);
            }
        }

        private static ActionItem readNode(JsonNode node) throws IOException {
            if (node == null || node.isNull()) {
                return null;
            }
            if (!node.isObject()) {
                return OBJECT_MAPPER.treeToValue(node, ActionItem.class);
            }
            ObjectNode obj = (ObjectNode) node;
            String synthesised = synthesiseLegacyDescription(obj);
            if (synthesised == null) {
                return OBJECT_MAPPER.treeToValue(obj, ActionItem.class);
            }
            ObjectNode copy = obj.deepCopy();
            copy.put("description", synthesised);
            return OBJECT_MAPPER.treeToValue(copy, ActionItem.class);
        }

        /**
         * Build a description from legacy free-text keys when the row lacks a canonical
         * description. Returns null when no legacy content needs preserving (canonical
         * description is already present or the row carries no recognised legacy keys).
         */
        private static String synthesiseLegacyDescription(ObjectNode obj) {
            if (hasNonNullText(obj, "description")) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String key : LEGACY_FREE_TEXT_KEYS) {
                if (CANONICAL_KEYS.contains(key)) {
                    continue;
                }
                if (hasNonNullText(obj, key)) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(key).append(": ").append(obj.get(key).asText());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static boolean hasNonNullText(ObjectNode obj, String key) {
            JsonNode v = obj.get(key);
            return v != null && !v.isNull() && !v.asText().isEmpty();
        }
    }

    /**
     * Persistence converter for {@code MethodologyProfile.crosswalkEntries} (per GC-T012).
     */
    @Converter
    public static class CrosswalkEntryListConverter extends AbstractJsonTextConverter<List<CrosswalkEntry>> {

        public CrosswalkEntryListConverter() {
            super(new TypeReference<>() {});
        }
    }

    /**
     * Persistence converter for {@code TreatmentPlan.reassessmentTriggers} (per GC-T004 / C8, issue #863).
     *
     * <p>Writes the canonical typed shape only. On read, legacy {@code List<String>} rows
     * persisted under the pre-C8 contract are folded into typed triggers with
     * {@code category = METHODOLOGY_SPECIFIC} and {@code note = <legacy string>} so no
     * legacy content is silently dropped. Blank legacy strings are skipped — they convey
     * no signal and would otherwise materialise as a category-only METHODOLOGY_SPECIFIC
     * trigger with no payload.
     */
    @Converter
    public static class ReassessmentTriggerListConverter
            implements AttributeConverter<List<ReassessmentTrigger>, String> {

        @Override
        public String convertToDatabaseColumn(List<ReassessmentTrigger> attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return OBJECT_MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Unable to serialize reassessment_triggers JSON", exception);
            }
        }

        @Override
        // S1168: JPA AttributeConverter contract returns null for NULL columns (matches every
        // sibling converter in this file); an empty list would persist as `[]` and lose the
        // null-vs-empty distinction that downstream readers rely on.
        @SuppressWarnings("java:S1168")
        public List<ReassessmentTrigger> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                JsonNode root = OBJECT_MAPPER.readTree(dbData);
                if (root.isNull()) {
                    return null;
                }
                if (!root.isArray()) {
                    throw new IllegalArgumentException(
                            "Expected JSON array for reassessment_triggers, got " + root.getNodeType());
                }
                List<ReassessmentTrigger> items = new ArrayList<>(root.size());
                for (JsonNode node : root) {
                    var item = readNode(node);
                    if (item != null) {
                        items.add(item);
                    }
                }
                return items;
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to deserialize reassessment_triggers JSON", exception);
            }
        }

        private static ReassessmentTrigger readNode(JsonNode node) throws IOException {
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isTextual()) {
                return readLegacyStringNode(node);
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "Expected JSON object or legacy string for reassessment trigger, got " + node.getNodeType());
            }
            return OBJECT_MAPPER.treeToValue(node, ReassessmentTrigger.class);
        }

        private static ReassessmentTrigger readLegacyStringNode(JsonNode node) {
            String legacy = node.asText();
            if (legacy == null || legacy.isBlank()) {
                return null;
            }
            return new ReassessmentTrigger(ReassessmentTriggerCategory.METHODOLOGY_SPECIFIC, null, null, null, legacy);
        }
    }
}

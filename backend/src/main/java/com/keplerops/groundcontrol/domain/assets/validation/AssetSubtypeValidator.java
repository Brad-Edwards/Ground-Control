package com.keplerops.groundcontrol.domain.assets.validation;

import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.CHARACTERS;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.EXCEEDS;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.FIELD_KEYWORDS_BY_TYPE;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_FIELDS;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_MAXIMUM;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_MINIMUM;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_REQUIRED;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_TYPE;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.KW_VALUES;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.K_ACTUAL;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.K_FIELD;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.K_KEYWORD;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.K_LIMIT;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.K_REASON;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.R_INVALID_SCHEMA_SHAPE;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.SCHEMA_FIELD_PREFIX;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.castStringObjectMap;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.detail;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.fail;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.parseFieldType;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.readBooleanKeyword;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.readEnumValues;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.readNumberKeyword;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.unsatisfiableRange;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.validateIntegerBounds;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.validateMetadataAgainstField;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.validateScalarValue;
import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidatorSupport.validateStringFieldBounds;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Enforces GC-M011 bounds and (optional) per-subtype schemas on asset metadata.
 *
 * <p>Universal bounds always apply; a registered ACTIVE schema for the asset's
 * {@code (project, assetType, subtype)} triple layers structural validation on
 * top. The validator is the single component behind {@code AssetService} that
 * owns these checks — controllers, MCP handlers, and migrations do not
 * duplicate the rules.
 */
@Component
public class AssetSubtypeValidator {

    public static final int MAX_METADATA_KEYS = 50;
    public static final int MAX_KEY_LENGTH = 100;
    public static final int MAX_STRING_VALUE_LENGTH = 4096;
    private static final String R_SCHEMA_BODY_REQUIRED = "schema_body_required";
    private static final String KW_ALLOW_ADDITIONAL = "allowAdditional";
    private static final String ROOT_PATH = "<root>";

    private static final Set<String> ROOT_KEYWORDS = Set.of(KW_FIELDS, KW_ALLOW_ADDITIONAL);

    public void validateMetadataBounds(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        if (metadata.size() > MAX_METADATA_KEYS) {
            throw fail(
                    "Asset metadata exceeds maximum of " + MAX_METADATA_KEYS + " keys",
                    detail(K_REASON, "too_many_keys", K_LIMIT, MAX_METADATA_KEYS, K_ACTUAL, metadata.size()));
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw fail("Asset metadata key must not be blank", detail(K_REASON, "blank_key"));
            }
            if (key.length() > MAX_KEY_LENGTH) {
                throw fail(
                        "Asset metadata key '" + truncate(key) + EXCEEDS + MAX_KEY_LENGTH + CHARACTERS,
                        detail(K_REASON, "key_too_long", K_FIELD, truncate(key), K_LIMIT, MAX_KEY_LENGTH));
            }
            validateScalarValue(key, entry.getValue());
        }
    }

    public void validateAgainstSchema(Map<String, Object> metadata, Map<String, Object> schemaBody) {
        validateMetadataBounds(metadata);
        if (schemaBody == null) {
            return;
        }
        Map<String, Object> fields = readFieldsMap(schemaBody);
        boolean allowAdditional = readBooleanKeyword(schemaBody, KW_ALLOW_ADDITIONAL, ROOT_PATH, false);

        Map<String, Object> effective = metadata == null ? Map.of() : metadata;
        rejectUndeclaredMetadataKeys(effective, fields, allowAdditional);
        for (Map.Entry<String, Object> fieldEntry : fields.entrySet()) {
            validateMetadataAgainstField(fieldEntry, effective);
        }
    }

    private void rejectUndeclaredMetadataKeys(
            Map<String, Object> metadata, Map<String, Object> fields, boolean allowAdditional) {
        if (allowAdditional) {
            return;
        }
        for (String key : metadata.keySet()) {
            if (!fields.containsKey(key)) {
                throw fail(
                        "Asset metadata key '" + key + "' is not declared by the subtype schema",
                        detail(K_REASON, "unknown_field", K_FIELD, key));
            }
        }
    }

    /**
     * Validate a schema body's shape *without* validating any metadata against
     * it. Used by the registry write path so a malformed schema cannot be
     * installed and only fail later at asset-write time. {@code requireFields}
     * controls whether a body with no {@code fields} object (or an empty one)
     * is acceptable: ACTIVE registry rows must declare at least one field —
     * otherwise the registry would advertise "schema layering" while
     * enforcing nothing.
     */
    public void validateSchemaBody(Map<String, Object> schemaBody, boolean requireFields) {
        if (schemaBody == null) {
            if (requireFields) {
                throw fail(
                        "Subtype schema body is required",
                        detail(K_REASON, R_SCHEMA_BODY_REQUIRED, K_FIELD, ROOT_PATH));
            }
            return;
        }
        rejectUnknownKeywords(ROOT_PATH, schemaBody, ROOT_KEYWORDS);
        Map<String, Object> fields = readRequiredFields(schemaBody, requireFields);
        readBooleanKeyword(schemaBody, KW_ALLOW_ADDITIONAL, ROOT_PATH, false);
        int requiredCount = walkAndValidateSchemaFields(fields);
        rejectImpossibleRequiredCount(requiredCount);
    }

    private Map<String, Object> readRequiredFields(Map<String, Object> schemaBody, boolean requireFields) {
        Object rawFields = schemaBody.get(KW_FIELDS);
        if (requireFields && rawFields == null) {
            throw fail(
                    "Subtype schema body must declare a 'fields' object",
                    detail(K_REASON, R_SCHEMA_BODY_REQUIRED, K_FIELD, KW_FIELDS));
        }
        Map<String, Object> fields = readFieldsMap(schemaBody);
        if (requireFields && fields.isEmpty()) {
            throw fail(
                    "Subtype schema 'fields' object must declare at least one field",
                    detail(K_REASON, R_SCHEMA_BODY_REQUIRED, K_FIELD, KW_FIELDS));
        }
        return fields;
    }

    private int walkAndValidateSchemaFields(Map<String, Object> fields) {
        int requiredCount = 0;
        for (Map.Entry<String, Object> fieldEntry : fields.entrySet()) {
            if (validateSchemaField(fieldEntry)) {
                requiredCount++;
            }
        }
        return requiredCount;
    }

    /** Returns true if the field is required, so the caller can tally. */
    private boolean validateSchemaField(Map.Entry<String, Object> fieldEntry) {
        String name = fieldEntry.getKey();
        validateSchemaFieldName(name);
        Map<String, Object> def = castStringObjectMap(KW_FIELDS + "." + name, fieldEntry.getValue());
        FieldType type = parseFieldType(name, def.get(KW_TYPE));
        rejectUnknownKeywords(name, def, FIELD_KEYWORDS_BY_TYPE.get(type));
        boolean required = readBooleanKeyword(def, KW_REQUIRED, name, false);
        validateFieldTypeBounds(name, def, type);
        return required;
    }

    private void validateSchemaFieldName(String name) {
        if (name == null || name.isBlank()) {
            throw fail(
                    "Subtype schema field name must not be blank",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, KW_FIELDS));
        }
        if (name.length() > MAX_KEY_LENGTH) {
            throw fail(
                    "Subtype schema field name '" + name + EXCEEDS + MAX_KEY_LENGTH + CHARACTERS,
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, name, K_LIMIT, MAX_KEY_LENGTH));
        }
    }

    private void validateFieldTypeBounds(String name, Map<String, Object> def, FieldType type) {
        switch (type) {
            case STRING -> validateStringFieldBounds(name, def);
            case INTEGER -> validateIntegerBounds(name, def);
            case NUMBER -> validateNumericRange(name, def);
            case ENUM -> validateEnumBounds(name, def);
            case BOOLEAN -> {
                // No bounds for BOOLEAN.
            }
            default -> throw new IllegalStateException("Unhandled field type: " + type);
        }
    }

    private void validateNumericRange(String name, Map<String, Object> def) {
        BigDecimal min = readNumberKeyword(def, KW_MINIMUM, name);
        BigDecimal max = readNumberKeyword(def, KW_MAXIMUM, name);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw unsatisfiableRange(name, min, max);
        }
    }

    private void validateEnumBounds(String name, Map<String, Object> def) {
        for (String v : readEnumValues(def, name)) {
            if (v.length() > MAX_STRING_VALUE_LENGTH) {
                throw fail(
                        SCHEMA_FIELD_PREFIX + name + "' ENUM value exceeds universal limit " + MAX_STRING_VALUE_LENGTH,
                        detail(
                                K_REASON, R_INVALID_SCHEMA_SHAPE,
                                K_FIELD, name,
                                K_KEYWORD, KW_VALUES,
                                K_LIMIT, MAX_STRING_VALUE_LENGTH));
            }
        }
    }

    private void rejectImpossibleRequiredCount(int requiredCount) {
        if (requiredCount > MAX_METADATA_KEYS) {
            throw fail(
                    "Subtype schema declares " + requiredCount + " required fields, exceeding the universal "
                            + "metadata-key limit of " + MAX_METADATA_KEYS,
                    detail(
                            K_REASON, R_INVALID_SCHEMA_SHAPE,
                            K_FIELD, KW_FIELDS,
                            K_LIMIT, MAX_METADATA_KEYS,
                            K_ACTUAL, requiredCount));
        }
    }

    private void rejectUnknownKeywords(String fieldName, Map<String, Object> map, Set<String> allowed) {
        if (allowed == null) {
            return;
        }
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw fail(
                        SCHEMA_FIELD_PREFIX + fieldName + "' has unsupported keyword '" + key + "'",
                        detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
            }
        }
    }

    private Map<String, Object> readFieldsMap(Map<String, Object> schemaBody) {
        Object rawFields = schemaBody.get(KW_FIELDS);
        if (rawFields == null) {
            return Map.of();
        }
        if (!(rawFields instanceof Map<?, ?>)) {
            throw fail(
                    "Subtype schema 'fields' must be an object",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, KW_FIELDS));
        }
        return castStringObjectMap(KW_FIELDS, rawFields);
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_KEY_LENGTH) {
            return s;
        }
        return s.substring(0, MAX_KEY_LENGTH) + "…";
    }

    enum FieldType {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        ENUM
    }
}

package com.keplerops.groundcontrol.domain.assets.validation;

import static com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator.MAX_STRING_VALUE_LENGTH;

import com.keplerops.groundcontrol.domain.assets.validation.AssetSubtypeValidator.FieldType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless helpers split out of {@link AssetSubtypeValidator} under issue #1467
 * for the 500-LOC limit (docs/CODING_STANDARDS.md).
 *
 * Every method here touches no instance state, so it is static and the
 * original keeps a static import for each -- call sites are unchanged.
 */
final class AssetSubtypeValidatorSupport {

    private AssetSubtypeValidatorSupport() {}

    static final String ERROR_CODE = "asset_metadata_invalid";

    // Detail-map keys (Sonar S1192).
    static final String K_REASON = "reason";
    static final String K_LIMIT = "limit";
    static final String K_ACTUAL = "actual";
    static final String K_FIELD = "field";
    static final String K_KEYWORD = "keyword";
    static final String K_ACTUAL_TYPE = "actualType";
    static final String K_EXPECTED_TYPE = "expectedType";
    static final String K_MINIMUM = "minimum";
    static final String K_MAXIMUM = "maximum";

    // Detail-map reason values (Sonar S1192).
    static final String R_INVALID_SCHEMA_SHAPE = "invalid_schema_shape";

    // Schema-language keywords.
    static final String KW_FIELDS = "fields";
    static final String KW_REQUIRED = "required";
    static final String KW_MAX_LENGTH = "maxLength";
    static final String KW_MINIMUM = "minimum";
    static final String KW_MAXIMUM = "maximum";
    static final String KW_VALUES = "values";
    static final String KW_TYPE = "type";

    // Message-construction fragments.
    static final String SCHEMA_FIELD_PREFIX = "Subtype schema field '";
    static final String ASSET_FIELD_PREFIX = "Asset metadata field '";
    static final String EXCEEDS = "' exceeds ";
    static final String CHARACTERS = " characters";

    static final Map<FieldType, Set<String>> FIELD_KEYWORDS_BY_TYPE = Map.of(
            FieldType.STRING, Set.of(KW_TYPE, KW_REQUIRED, KW_MAX_LENGTH),
            FieldType.INTEGER, Set.of(KW_TYPE, KW_REQUIRED, KW_MINIMUM, KW_MAXIMUM),
            FieldType.NUMBER, Set.of(KW_TYPE, KW_REQUIRED, KW_MINIMUM, KW_MAXIMUM),
            FieldType.BOOLEAN, Set.of(KW_TYPE, KW_REQUIRED),
            FieldType.ENUM, Set.of(KW_TYPE, KW_REQUIRED, KW_VALUES));

    static Integer readIntBound(Map<String, Object> def, String key, String fieldName) {
        if (!def.containsKey(key)) {
            return null;
        }
        Object raw = def.get(key);
        // Boolean is not a subclass of Number — pattern check alone excludes it.
        // Present-null is rejected (see readBooleanKeyword rationale).
        if (!(raw instanceof Number n)) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' has non-numeric '" + key + "'",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
        }
        BigDecimal value = toBigDecimal(n);
        if (value.scale() > 0 && value.stripTrailingZeros().scale() > 0) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' '" + key + "' must be an integer",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
        }
        try {
            int as = value.intValueExact();
            if (as < 0) {
                throw fail(
                        SCHEMA_FIELD_PREFIX + fieldName + "' '" + key + "' must be non-negative",
                        detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
            }
            return as;
        } catch (ArithmeticException ex) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' '" + key + "' is out of integer range",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
        }
    }

    static void validateScalarValue(String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            if (s.length() > MAX_STRING_VALUE_LENGTH) {
                throw fail(
                        "Asset metadata value for '" + key + EXCEEDS + MAX_STRING_VALUE_LENGTH + CHARACTERS,
                        detail(K_REASON, "string_value_too_long", K_FIELD, key, K_LIMIT, MAX_STRING_VALUE_LENGTH));
            }
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return;
        }
        throw fail(
                "Asset metadata value for '" + key + "' must be a string, number, boolean, or null",
                detail(
                        K_REASON,
                        "unsupported_value_type",
                        K_FIELD,
                        key,
                        K_ACTUAL_TYPE,
                        value.getClass().getSimpleName()));
    }

    static void validateIntegerBounds(String name, Map<String, Object> def) {
        BigDecimal min = readNumberKeyword(def, KW_MINIMUM, name);
        BigDecimal max = readNumberKeyword(def, KW_MAXIMUM, name);
        // INTEGER min/max must themselves be whole numbers — fractional bounds
        // are nonsensical for an integer field and a pair like (0.1, 0.2) is
        // unsatisfiable while passing a naive min<=max check.
        if (min != null && hasFractionalPart(min)) {
            throw fail(
                    "Subtype schema INTEGER field '" + name + "' has fractional minimum",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, name, K_KEYWORD, KW_MINIMUM));
        }
        if (max != null && hasFractionalPart(max)) {
            throw fail(
                    "Subtype schema INTEGER field '" + name + "' has fractional maximum",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, name, K_KEYWORD, KW_MAXIMUM));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw unsatisfiableRange(name, min, max);
        }
    }

    static Map<String, Object> castStringObjectMap(String path, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new DomainValidationException(
                    "Subtype schema '" + path + "' must be an object",
                    ERROR_CODE,
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, path));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new DomainValidationException(
                        "Subtype schema '" + path + "' keys must be strings",
                        ERROR_CODE,
                        detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, path));
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    static List<String> readEnumValues(Map<String, Object> def, String fieldName) {
        Object raw = def.get(KW_VALUES);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw fail(
                    "Subtype schema ENUM field '" + fieldName + "' must declare a non-empty 'values' array",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, KW_VALUES));
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw fail(
                        "Subtype schema ENUM field '" + fieldName + "' 'values' must be strings",
                        detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, KW_VALUES));
            }
            out.add(s);
        }
        return out;
    }

    static void validateStringFieldBounds(String name, Map<String, Object> def) {
        Integer maxLength = readIntBound(def, KW_MAX_LENGTH, name);
        // STRING values are also bounded by the universal MAX_STRING_VALUE_LENGTH;
        // a maxLength larger than that can never reject a real overrun, so reject
        // it as foot-gun-prone schema authoring.
        if (maxLength != null && maxLength > MAX_STRING_VALUE_LENGTH) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + name + "' has maxLength " + maxLength + " exceeding universal limit "
                            + MAX_STRING_VALUE_LENGTH,
                    detail(
                            K_REASON, R_INVALID_SCHEMA_SHAPE,
                            K_FIELD, name,
                            K_KEYWORD, KW_MAX_LENGTH,
                            K_LIMIT, MAX_STRING_VALUE_LENGTH));
        }
    }

    static void validateMetadataAgainstField(Map.Entry<String, Object> fieldEntry, Map<String, Object> metadata) {
        String name = fieldEntry.getKey();
        Map<String, Object> def = castStringObjectMap(KW_FIELDS + "." + name, fieldEntry.getValue());
        FieldType type = parseFieldType(name, def.get(KW_TYPE));
        boolean required = readBooleanKeyword(def, KW_REQUIRED, name, false);
        Object value = metadata.get(name);
        if (!metadata.containsKey(name) || value == null) {
            if (required) {
                throw fail(
                        "Required asset metadata field '" + name + "' is missing",
                        detail(K_REASON, "required_field_missing", K_FIELD, name, K_EXPECTED_TYPE, type.name()));
            }
            return;
        }
        validateTyped(name, value, type, def);
    }

    static FieldType parseFieldType(String fieldName, Object raw) {
        if (!(raw instanceof String s)) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' must declare a string 'type'",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName));
        }
        try {
            return FieldType.valueOf(s);
        } catch (IllegalArgumentException ex) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' has unsupported type '" + s + "'",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_ACTUAL_TYPE, s));
        }
    }

    static Map<String, Serializable> detail(Object... pairs) {
        Map<String, Serializable> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = (String) pairs[i];
            out.put(key, asSerializable(pairs[i + 1]));
        }
        return out;
    }

    static void validateTyped(String name, Object value, FieldType type, Map<String, Object> def) {
        switch (type) {
            case STRING -> validateString(name, value, def);
            case INTEGER -> validateInteger(name, value, def);
            case NUMBER -> validateNumber(name, value, def);
            case BOOLEAN -> validateBoolean(name, value);
            case ENUM -> validateEnum(name, value, def);
            default -> throw new IllegalStateException("Unhandled field type: " + type);
        }
    }

    static boolean readBooleanKeyword(Map<String, Object> def, String key, String fieldName, boolean defaultValue) {
        if (!def.containsKey(key)) {
            return defaultValue;
        }
        Object raw = def.get(key);
        // Distinguish "key absent" (default) from "key present with null value"
        // (malformed — reject). Without this guard a schema with `required: null`
        // or `allowAdditional: null` silently weakens its own contract.
        if (!(raw instanceof Boolean b)) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' has non-boolean '" + key + "'",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
        }
        return b;
    }

    static DomainValidationException fail(String message, Map<String, Serializable> detail) {
        return new DomainValidationException(message, ERROR_CODE, detail);
    }

    static void validateNumber(String name, Object value, Map<String, Object> def) {
        if (!(value instanceof Number n)) {
            throw typeMismatch(name, value, FieldType.NUMBER);
        }
        checkRange(name, toBigDecimal(n), def);
    }

    static void validateInteger(String name, Object value, Map<String, Object> def) {
        // Boolean is not a subclass of Number in Java, so the pattern check
        // alone rejects Boolean values — no explicit Boolean guard needed.
        if (!(value instanceof Number n)) {
            throw typeMismatch(name, value, FieldType.INTEGER);
        }
        BigDecimal decimal = toBigDecimal(n);
        if (decimal.scale() > 0 && decimal.stripTrailingZeros().scale() > 0) {
            throw typeMismatch(name, value, FieldType.INTEGER);
        }
        checkRange(name, decimal, def);
    }

    static void validateBoolean(String name, Object value) {
        if (!(value instanceof Boolean)) {
            throw typeMismatch(name, value, FieldType.BOOLEAN);
        }
    }

    static void validateString(String name, Object value, Map<String, Object> def) {
        if (!(value instanceof String s)) {
            throw typeMismatch(name, value, FieldType.STRING);
        }
        Integer maxLength = readIntBound(def, KW_MAX_LENGTH, name);
        if (maxLength != null && s.length() > maxLength) {
            throw fail(
                    ASSET_FIELD_PREFIX + name + "' exceeds maxLength " + maxLength,
                    detail(K_REASON, "string_too_long", K_FIELD, name, K_LIMIT, maxLength));
        }
    }

    static void validateEnum(String name, Object value, Map<String, Object> def) {
        List<String> allowed = readEnumValues(def, name);
        if (!(value instanceof String s)) {
            throw typeMismatch(name, value, FieldType.ENUM);
        }
        if (!allowed.contains(s)) {
            throw fail(
                    ASSET_FIELD_PREFIX + name + "' must be one of the allowed enum values",
                    detail(K_REASON, "enum_value_not_allowed", K_FIELD, name, K_ACTUAL, s));
        }
    }

    static DomainValidationException typeMismatch(String name, Object value, FieldType expected) {
        return fail(
                ASSET_FIELD_PREFIX + name + "' expected type " + expected,
                detail(
                        K_REASON,
                        "type_mismatch",
                        K_FIELD,
                        name,
                        K_EXPECTED_TYPE,
                        expected.name(),
                        K_ACTUAL_TYPE,
                        classNameOrNull(value)));
    }

    static String classNameOrNull(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getSimpleName();
    }

    static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (n instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (n instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (n instanceof Integer i) {
            return BigDecimal.valueOf(i);
        }
        return BigDecimal.valueOf(n.doubleValue());
    }

    static void checkRange(String name, BigDecimal value, Map<String, Object> def) {
        BigDecimal min = readNumberKeyword(def, KW_MINIMUM, name);
        if (min != null && value.compareTo(min) < 0) {
            throw fail(
                    ASSET_FIELD_PREFIX + name + "' is below minimum",
                    detail(K_REASON, "below_minimum", K_FIELD, name, K_MINIMUM, min.toPlainString()));
        }
        BigDecimal max = readNumberKeyword(def, KW_MAXIMUM, name);
        if (max != null && value.compareTo(max) > 0) {
            throw fail(
                    ASSET_FIELD_PREFIX + name + "' is above maximum",
                    detail(K_REASON, "above_maximum", K_FIELD, name, K_MAXIMUM, max.toPlainString()));
        }
    }

    static BigDecimal readNumberKeyword(Map<String, Object> def, String key, String fieldName) {
        if (!def.containsKey(key)) {
            return null;
        }
        Object raw = def.get(key);
        // Boolean is not a subclass of Number — pattern check alone excludes it.
        // Present-null is rejected (see readBooleanKeyword rationale).
        if (!(raw instanceof Number n)) {
            throw fail(
                    SCHEMA_FIELD_PREFIX + fieldName + "' has non-numeric '" + key + "'",
                    detail(K_REASON, R_INVALID_SCHEMA_SHAPE, K_FIELD, fieldName, K_KEYWORD, key));
        }
        return toBigDecimal(n);
    }

    static Serializable asSerializable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Serializable s) {
            return s;
        }
        return value.toString();
    }

    static DomainValidationException unsatisfiableRange(String name, BigDecimal min, BigDecimal max) {
        return fail(
                SCHEMA_FIELD_PREFIX + name + "' has minimum > maximum (unsatisfiable)",
                detail(
                        K_REASON,
                        R_INVALID_SCHEMA_SHAPE,
                        K_FIELD,
                        name,
                        K_MINIMUM,
                        min.toPlainString(),
                        K_MAXIMUM,
                        max.toPlainString()));
    }

    static boolean hasFractionalPart(BigDecimal value) {
        return value.scale() > 0 && value.stripTrailingZeros().scale() > 0;
    }
}

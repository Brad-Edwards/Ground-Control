package com.keplerops.groundcontrol.api.requirements;

import com.keplerops.groundcontrol.domain.requirements.service.FieldChange;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility for truncating field-level audit diff values in API responses.
 *
 * <p>String values longer than {@link #MAX_PREVIEW_LENGTH} are truncated to that length unless
 * {@code expand=true} is requested.
 */
public final class AuditDiffTruncation {

    public static final int MAX_PREVIEW_LENGTH = 200;

    private AuditDiffTruncation() {}

    /**
     * Truncates a single string value to {@link #MAX_PREVIEW_LENGTH} characters when {@code expand}
     * is false. Non-string values are returned unchanged. Returns {@code null} as-is.
     *
     * @return {@code Object[2]} where {@code [0]} is the (possibly truncated) value and
     *     {@code [1]} is a {@code Boolean} indicating whether truncation occurred.
     */
    private static Object[] truncateValue(Object value, boolean expand) {
        if (value instanceof String s && s.length() > MAX_PREVIEW_LENGTH && !expand) {
            return new Object[] {s.substring(0, MAX_PREVIEW_LENGTH), true};
        }
        return new Object[] {value, false};
    }

    /**
     * Converts a {@link FieldChange} to a {@link FieldChangeResponse}, applying truncation to
     * string values when {@code expand} is false.
     */
    public static FieldChangeResponse toResponse(FieldChange fc, boolean expand) {
        var oldResult = truncateValue(fc.oldValue(), expand);
        var newResult = truncateValue(fc.newValue(), expand);
        boolean truncated = (Boolean) oldResult[1] || (Boolean) newResult[1];
        return new FieldChangeResponse(oldResult[0], newResult[0], truncated);
    }

    /**
     * Converts a map of {@link FieldChange} entries to a map of {@link FieldChangeResponse},
     * preserving insertion order and applying truncation when {@code expand} is false.
     */
    public static Map<String, FieldChangeResponse> toResponses(Map<String, FieldChange> changes, boolean expand) {
        var result = new LinkedHashMap<String, FieldChangeResponse>(changes.size());
        for (var entry : changes.entrySet()) {
            result.put(entry.getKey(), toResponse(entry.getValue(), expand));
        }
        return result;
    }
}

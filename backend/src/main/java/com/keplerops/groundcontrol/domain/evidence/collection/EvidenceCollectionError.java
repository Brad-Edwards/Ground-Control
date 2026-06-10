package com.keplerops.groundcontrol.domain.evidence.collection;

import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceCollectionError(
        String errorCode, String message, String target, boolean retryable, Map<String, Object> detail) {

    public EvidenceCollectionError {
        if (errorCode == null || errorCode.isBlank()) {
            throw new DomainValidationException("Evidence collection errorCode must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new DomainValidationException("Evidence collection error message must not be blank");
        }
        detail = sanitizeDetail(detail);
    }

    @Override
    public Map<String, Object> detail() {
        return Map.copyOf(detail);
    }

    private static Map<String, Object> sanitizeDetail(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key != null && !isSecretKey(key)) {
                sanitized.put(key, entry.getValue());
            }
        }
        return Map.copyOf(sanitized);
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password")
                || lower.contains("credential");
    }
}

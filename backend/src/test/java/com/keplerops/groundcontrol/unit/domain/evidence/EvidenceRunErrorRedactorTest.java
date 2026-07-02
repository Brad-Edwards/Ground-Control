package com.keplerops.groundcontrol.unit.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.evidence.campaign.service.EvidenceRunErrorRedactor;
import org.junit.jupiter.api.Test;

class EvidenceRunErrorRedactorTest {

    @Test
    void blankInputReturnsNull() {
        assertThat(EvidenceRunErrorRedactor.redact(null)).isNull();
        assertThat(EvidenceRunErrorRedactor.redact("   ")).isNull();
    }

    @Test
    void preservesBenignText() {
        assertThat(EvidenceRunErrorRedactor.redact("provider returned 503 after 3 retries"))
                .isEqualTo("provider returned 503 after 3 retries");
    }

    @Test
    void redactsBearerToken() {
        var out = EvidenceRunErrorRedactor.redact("rejected: Bearer sk-supersecrettoken0123456789ABCDEF");
        assertThat(out).contains("[redacted]").doesNotContain("sk-supersecrettoken");
    }

    @Test
    void redactsUrlUserinfo() {
        var out = EvidenceRunErrorRedactor.redact("could not connect to https://admin:hunter2@internal.example.com/x");
        assertThat(out).contains("[redacted]@internal.example.com").doesNotContain("hunter2");
    }

    @Test
    void redactsSecretKeyValuePairs() {
        assertThat(EvidenceRunErrorRedactor.redact("token=abc123 failed")).doesNotContain("abc123");
        assertThat(EvidenceRunErrorRedactor.redact("password: hunter2")).doesNotContain("hunter2");
        assertThat(EvidenceRunErrorRedactor.redact("api_key=\"AKIAEXAMPLEKEY\""))
                .doesNotContain("AKIAEXAMPLEKEY");
    }

    @Test
    void redactsLongOpaqueToken() {
        var out = EvidenceRunErrorRedactor.redact("response body: eyJhbGciOiJIUzI1NiwidHlwIjoiSldUIn0aGVsbG8");
        assertThat(out).contains("[redacted]");
    }

    @Test
    void boundsLength() {
        // Space-separated short words: no token-shaped run to redact, so only truncation applies.
        String longText = "err ".repeat(EvidenceRunErrorRedactor.MAX_LENGTH).strip();
        assertThat(longText.length()).isGreaterThan(EvidenceRunErrorRedactor.MAX_LENGTH);
        assertThat(EvidenceRunErrorRedactor.redact(longText)).hasSize(EvidenceRunErrorRedactor.MAX_LENGTH);
    }
}

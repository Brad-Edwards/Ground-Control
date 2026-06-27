package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that RemoteRefSanitizer strips credential-bearing fields (userinfo, query, fragment)
 * from remote references before they are persisted, while preserving scheme, host, port, and path.
 */
class RemoteRefSanitizerTest {

    // ── Null / blank inputs ───────────────────────────────────────────────────

    @Test
    void nullInputReturnsNull() {
        assertThat(RemoteRefSanitizer.sanitize(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankInputReturnedUnchanged(String blank) {
        assertThat(RemoteRefSanitizer.sanitize(blank)).isEqualTo(blank);
    }

    // ── Non-URL inputs (no scheme) ────────────────────────────────────────────

    @Test
    void registryStylePathWithNoSchemeReturnedUnchanged() {
        // Terraform public registry path — not a URL, no credential risk
        var input = "hashicorp/consul/aws";
        assertThat(RemoteRefSanitizer.sanitize(input)).isEqualTo(input);
    }

    @Test
    void gitPrefixWithNonUrlSegmentReturnedUnchanged() {
        // git:: prefix, but the part after it has no scheme → original returned
        var input = "git::hashicorp/consul/aws";
        assertThat(RemoteRefSanitizer.sanitize(input)).isEqualTo(input);
    }

    // ── Credential stripping in well-formed URLs ──────────────────────────────

    @Test
    void urlWithUserinfoStripsUserinfoButPreservesHostAndPath() {
        var result = RemoteRefSanitizer.sanitize("https://user:pass@host.example.com/path");

        assertThat(result).doesNotContain("user").doesNotContain("pass");
        assertThat(result).contains("host.example.com").contains("/path");
        assertThat(result).startsWith("https://");
    }

    @Test
    void urlWithQueryStringStripsQuery() {
        var result = RemoteRefSanitizer.sanitize("https://host.example.com/path?token=abc123");

        assertThat(result).doesNotContain("token").doesNotContain("abc123");
        assertThat(result).contains("host.example.com").contains("/path");
    }

    @Test
    void urlWithFragmentStripsFragment() {
        var result = RemoteRefSanitizer.sanitize("https://host.example.com/path#v1.2");

        assertThat(result).doesNotContain("#").doesNotContain("v1.2");
        assertThat(result).contains("host.example.com").contains("/path");
    }

    @Test
    void urlWithPortPreservesPort() {
        var result = RemoteRefSanitizer.sanitize("https://host.example.com:8080/path");

        assertThat(result).contains(":8080");
        assertThat(result).contains("host.example.com").contains("/path");
    }

    @Test
    void urlWithAllSensitivePartsStripped() {
        var result = RemoteRefSanitizer.sanitize("https://user:secret@host.example.com:443/path?q=v&tok=x#frag");

        assertThat(result).doesNotContain("user").doesNotContain("secret");
        assertThat(result).doesNotContain("q=v").doesNotContain("tok=x").doesNotContain("frag");
        assertThat(result).contains("host.example.com").contains(":443").contains("/path");
    }

    // ── Terraform VCS prefix preservation ────────────────────────────────────

    @Test
    void gitPrefixWithCredentialUrlStripsCredentialsAndPreservesPrefix() {
        var result = RemoteRefSanitizer.sanitize("git::https://user:secret@host.example.com/repo.git?token=abc#v1");

        assertThat(result).startsWith("git::");
        assertThat(result).doesNotContain("user").doesNotContain("secret");
        assertThat(result).doesNotContain("token=abc").doesNotContain("#v1");
        assertThat(result).contains("host.example.com").contains("/repo.git");
    }

    @Test
    void sshPrefixWithUrlSanitized() {
        var result = RemoteRefSanitizer.sanitize("ssh::https://user:pass@git.example.com/repo");

        assertThat(result).startsWith("ssh::");
        assertThat(result).doesNotContain("user").doesNotContain("pass");
        assertThat(result).contains("git.example.com");
    }

    // ── Best-effort fallback for malformed URIs ───────────────────────────────

    @Test
    void malformedUriWithUserinfoStrippedByBestEffort() {
        // Space in host makes URI(str) throw URISyntaxException → falls back to stripBestEffort
        var result = RemoteRefSanitizer.sanitize("http://user:pass@ho st.example.com/path");

        assertThat(result).doesNotContain("user").doesNotContain("pass");
        assertThat(result).contains("ho st.example.com").contains("/path");
        assertThat(result).startsWith("http://");
    }

    @Test
    void malformedUriWithQueryStrippedByBestEffort() {
        // URISyntaxException triggers stripBestEffort; query must be stripped
        var result = RemoteRefSanitizer.sanitize("http://ho st.example.com/path?token=secret");

        assertThat(result).doesNotContain("token").doesNotContain("secret");
        assertThat(result).contains("ho st.example.com").contains("/path");
    }

    @Test
    void malformedUriWithFragmentOnlyStrippedByBestEffort() {
        // Fragment present (no query) — covers the fragIdx < cutIdx branch in stripBestEffort
        var result = RemoteRefSanitizer.sanitize("http://ho st.example.com/path#commit-abc");

        assertThat(result).doesNotContain("#").doesNotContain("commit-abc");
        assertThat(result).contains("/path");
    }

    @Test
    void malformedUriWithFragmentBeforeQueryStrippedByBestEffort() {
        // Both present; fragment index precedes query index (unusual but legal)
        // Ensure the minimum cutIdx is chosen
        var result = RemoteRefSanitizer.sanitize("http://ho st.example.com/path#frag?q=v");

        assertThat(result).doesNotContain("#frag").doesNotContain("q=v");
        assertThat(result).contains("/path");
    }

    @Test
    void malformedUriWithNoSchemeInToParsedReturnsWithPrefix() {
        // git:: prefix, toParse has no "://" AND fails URI parse (e.g. spaces) → schemeSep < 0
        // path → stripBestEffort returns prefix + raw unchanged
        var result = RemoteRefSanitizer.sanitize("git::path with spaces");

        // No "://" in toParse, so stripBestEffort returns it verbatim (prefix + raw)
        assertThat(result).isEqualTo("git::path with spaces");
    }
}

package com.keplerops.groundcontrol.domain.evidence.campaign.service;

import java.util.regex.Pattern;

/**
 * Redacts and length-bounds the free text recorded on an {@code EvidenceCampaignRun} so the
 * run telemetry - which any authenticated project member can read - cannot become an
 * exfiltration channel for secrets or sensitive provider data (GC-S005 / GC-TM-011).
 *
 * <p>Adapter error messages and exception text are attacker- or provider-influenced: they can
 * echo signed URLs, bearer tokens, header values, credentials, or PII-heavy object listings.
 * Whitespace trimming and truncation alone do not make such text safe. This redactor strips
 * URL userinfo, {@code key=value} secret pairs, {@code Bearer} tokens, and long opaque
 * token-shaped runs before bounding the result. It is defense-in-depth on the message text;
 * the run also leads with a controlled error code/category, and raw exception detail is kept
 * out of the persisted summary entirely (the service stores only the exception category).
 */
public final class EvidenceRunErrorRedactor {

    public static final int MAX_LENGTH = 512;
    private static final String REDACTED = "[redacted]";

    // scheme://user:pass@host -> scheme://[redacted]@host
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.\\-]*://)[^/@\\s]*@");
    // bearer <token> -> Bearer [redacted]
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");
    // sensitive key = value (quoted or bare) -> key=[redacted]
    private static final Pattern SECRET_KV =
            Pattern.compile("(?i)\\b(authorization|auth|token|secret|password|passwd|pwd|api[_-]?key"
                    + "|access[_-]?key|client[_-]?secret|credential|cred)\\b\\s*[=:]\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
    // long opaque token-shaped run (base64/hex/JWT-ish), 32+ chars -> [redacted]
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9+/_\\-]{32,}={0,2}");

    private EvidenceRunErrorRedactor() {
        // utility
    }

    /** Redact secret-shaped substrings and bound the length. Returns null for blank input. */
    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        s = URL_USERINFO.matcher(s).replaceAll("$1" + REDACTED + "@");
        s = SECRET_KV.matcher(s).replaceAll("$1=" + REDACTED);
        s = BEARER.matcher(s).replaceAll("Bearer " + REDACTED);
        s = LONG_TOKEN.matcher(s).replaceAll(REDACTED);
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }
}

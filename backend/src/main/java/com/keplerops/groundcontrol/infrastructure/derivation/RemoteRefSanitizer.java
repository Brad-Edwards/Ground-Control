package com.keplerops.groundcontrol.infrastructure.derivation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Sanitizes remote reference strings (URLs, VCS module sources) by stripping userinfo,
 * query string, and fragment before they are persisted in derivation fact payloads,
 * labels, and summaries. This prevents leakage of embedded credentials such as bearer
 * tokens, signed query parameters, or HTTP basic-auth user:pass pairs.
 *
 * <p>Scheme, host, port, and path are preserved. For strings that are not URLs (e.g.,
 * Terraform public registry paths like {@code hashicorp/consul/aws}), the original value
 * is returned unchanged.
 *
 * <p>Terraform-style source prefixes such as {@code git::} or {@code hg::} are recognised
 * and preserved around the sanitized URL portion.
 */
final class RemoteRefSanitizer {

    private RemoteRefSanitizer() {}

    /**
     * Returns a sanitized copy of {@code raw} with userinfo (user:pass@), query string, and
     * fragment removed.
     */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        // Handle Terraform-style VCS prefixes: git::, hg::, ssh::, etc.
        String prefix = "";
        String toParse = raw;
        int colonColonIdx = raw.indexOf("::");
        if (colonColonIdx >= 0) {
            prefix = raw.substring(0, colonColonIdx + 2);
            toParse = raw.substring(colonColonIdx + 2);
        }

        try {
            var uri = new URI(toParse);
            if (uri.getScheme() == null) {
                // Not a URL (e.g., "hashicorp/consul/aws") — no credential risk
                return raw;
            }
            // Rebuild stripping userinfo, query, and fragment
            var sb = new StringBuilder(prefix);
            sb.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) {
                sb.append(uri.getHost());
                if (uri.getPort() != -1) {
                    sb.append(':').append(uri.getPort());
                }
            }
            var path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                sb.append(path);
            }
            return sb.toString();
        } catch (URISyntaxException e) {
            // Fall back to best-effort stripping when URI parsing fails
            return stripBestEffort(prefix, toParse);
        }
    }

    private static String stripBestEffort(String prefix, String raw) {
        int schemeSep = raw.indexOf("://");
        if (schemeSep < 0) {
            return prefix + raw;
        }
        String scheme = raw.substring(0, schemeSep);
        String afterScheme = raw.substring(schemeSep + 3);

        // Strip userinfo (user:pass@) — only the portion before the first '/'
        int atIdx = afterScheme.indexOf('@');
        int slashIdx = afterScheme.indexOf('/');
        if (atIdx >= 0 && (slashIdx < 0 || atIdx < slashIdx)) {
            afterScheme = afterScheme.substring(atIdx + 1);
        }

        // Strip query string and fragment
        int queryIdx = afterScheme.indexOf('?');
        int fragIdx = afterScheme.indexOf('#');
        int cutIdx = -1;
        if (queryIdx >= 0) cutIdx = queryIdx;
        if (fragIdx >= 0 && (cutIdx < 0 || fragIdx < cutIdx)) cutIdx = fragIdx;
        if (cutIdx >= 0) {
            afterScheme = afterScheme.substring(0, cutIdx);
        }

        return prefix + scheme + "://" + afterScheme;
    }
}

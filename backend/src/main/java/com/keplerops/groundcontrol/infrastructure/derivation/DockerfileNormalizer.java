package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

class DockerfileNormalizer {

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(secret|password|passwd|pass|token|key|credential|cert|private|api_key|apikey|auth)");
    private static final Pattern MOUNT_SECRET_ID = Pattern.compile("--mount=type=secret[^\\s]*\\bid=([^,\\s]+)");

    List<DerivedSystemModelFact> normalize(
            String surface,
            String relativePath,
            String content,
            String adapterId,
            String commitSha,
            String rulesetVersion,
            Instant derivedAt) {
        var facts = new ArrayList<DerivedSystemModelFact>();
        var provenance = new DerivationFactProvenance(
                adapterId, "iac-pipeline", rulesetVersion, "iac-pipeline-rules", rulesetVersion, commitSha, derivedAt);

        for (String line : parseLines(content)) {
            facts.addAll(processDockerfileLine(surface, relativePath, line, provenance));
        }

        return List.copyOf(facts);
    }

    private List<DerivedSystemModelFact> processDockerfileLine(
            String surface, String relativePath, String line, DerivationFactProvenance provenance) {
        var trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return List.of();
        }
        var spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) {
            return List.of();
        }
        var instruction = trimmed.substring(0, spaceIdx).toUpperCase(Locale.ROOT);
        var rest = trimmed.substring(spaceIdx + 1).trim();
        var facts = new ArrayList<DerivedSystemModelFact>();
        switch (instruction) {
            case "FROM" -> facts.addAll(normalizeFrom(surface, relativePath, rest, provenance));
            case "ARG" -> addIfNonNull(facts, normalizeArg(surface, relativePath, rest, provenance));
            case "ENV" -> addIfNonNull(facts, normalizeEnv(surface, relativePath, rest, provenance));
            case "RUN" -> addIfNonNull(facts, normalizeRun(surface, relativePath, trimmed, provenance));
            case "USER" -> addIfNonNull(facts, normalizeUser(surface, relativePath, rest, provenance));
            case "ADD" -> addIfNonNull(facts, normalizeAdd(surface, relativePath, rest, provenance));
            default -> {
                /* Not a tracked instruction */
            }
        }
        return facts;
    }

    private static void addIfNonNull(List<DerivedSystemModelFact> facts, DerivedSystemModelFact fact) {
        if (fact != null) {
            facts.add(fact);
        }
    }

    private List<DerivedSystemModelFact> normalizeFrom(
            String surface, String relativePath, String rest, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        // Remove leading flags (e.g., --platform=...)
        var remaining = rest;
        while (remaining.startsWith("--")) {
            var next = remaining.indexOf(' ');
            if (next < 0) return facts;
            remaining = remaining.substring(next + 1).trim();
        }

        // Split off AS <stage>
        String stageName = null;
        var asIndex = remaining.toUpperCase(Locale.ROOT).lastIndexOf(" AS ");
        if (asIndex >= 0) {
            stageName = remaining.substring(asIndex + 4).trim();
            remaining = remaining.substring(0, asIndex).trim();
        }

        var imageName = remaining.trim();
        if (imageName.isBlank()) return facts;

        // Emit COMPONENT for the image
        var compPayload = new LinkedHashMap<String, Object>();
        compPayload.put(IacFactKeys.SURFACE, surface);
        compPayload.put(IacFactKeys.ARTIFACT_KIND, "docker-image");
        compPayload.put(IacFactKeys.SOURCE_PATH, relativePath);
        if (stageName != null) {
            compPayload.put("buildStage", stageName);
        }
        var compKey = buildFactKey(
                surface,
                SystemModelFactKind.COMPONENT,
                provenance.adapterId(),
                relativePath,
                "from:" + imageName + ":" + stageName);
        facts.add(new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                compKey,
                "Docker image: " + imageName,
                "Dockerfile FROM instruction referencing " + imageName,
                relativePath,
                compPayload,
                provenance));

        // Check for external registry
        var registryHostname = extractRegistryHostname(imageName);
        if (registryHostname != null) {
            var regPayload = new LinkedHashMap<String, Object>();
            regPayload.put(IacFactKeys.SURFACE, surface);
            regPayload.put(IacFactKeys.ARTIFACT_KIND, "image-registry");
            regPayload.put("registryTarget", registryHostname);
            regPayload.put(IacFactKeys.SOURCE_PATH, relativePath);
            var regKey = buildFactKey(
                    surface,
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    provenance.adapterId(),
                    relativePath,
                    "registry:" + registryHostname);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    regKey,
                    "Image registry: " + registryHostname,
                    "Dockerfile pulls from external registry " + registryHostname,
                    relativePath,
                    regPayload,
                    provenance));
        }

        return facts;
    }

    private DerivedSystemModelFact normalizeArg(
            String surface, String relativePath, String rest, DerivationFactProvenance provenance) {
        // Extract name (before =)
        var eqIdx = rest.indexOf('=');
        var name = eqIdx >= 0 ? rest.substring(0, eqIdx).trim() : rest.trim();
        if (name.isBlank() || !SECRET_LIKE.matcher(name).find()) {
            return null;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_REF, name);
        payload.put(IacFactKeys.SECRET_SCOPE, "build-arg");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface, SystemModelFactKind.SECRET_USAGE, provenance.adapterId(), relativePath, "arg:" + name);
        return new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                factKey,
                "Build arg secret: " + name,
                "Dockerfile ARG with secret-like name " + name,
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact normalizeEnv(
            String surface, String relativePath, String rest, DerivationFactProvenance provenance) {
        // ENV name=value or ENV name value
        String name;
        var eqIdx = rest.indexOf('=');
        var spIdx = rest.indexOf(' ');
        if (eqIdx >= 0 && (spIdx < 0 || eqIdx < spIdx)) {
            name = rest.substring(0, eqIdx).trim();
        } else if (spIdx >= 0) {
            name = rest.substring(0, spIdx).trim();
        } else {
            name = rest.trim();
        }
        if (name.isBlank() || !SECRET_LIKE.matcher(name).find()) {
            return null;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_REF, name);
        payload.put(IacFactKeys.SECRET_SCOPE, "build-env");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface, SystemModelFactKind.SECRET_USAGE, provenance.adapterId(), relativePath, "env:" + name);
        return new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                factKey,
                "Build env secret: " + name,
                "Dockerfile ENV with secret-like name " + name,
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact normalizeRun(
            String surface, String relativePath, String fullLine, DerivationFactProvenance provenance) {
        if (!fullLine.toUpperCase(Locale.ROOT).contains("--MOUNT=TYPE=SECRET")
                && !fullLine.toLowerCase(Locale.ROOT).contains("--mount=type=secret")) {
            return null;
        }
        var matcher = MOUNT_SECRET_ID.matcher(fullLine);
        if (!matcher.find()) {
            return null;
        }
        var id = matcher.group(1);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_REF, id);
        payload.put(IacFactKeys.SECRET_SCOPE, "build-secret");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface, SystemModelFactKind.SECRET_USAGE, provenance.adapterId(), relativePath, "run-secret:" + id);
        return new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                factKey,
                "Build secret mount: " + id,
                "Dockerfile RUN mounts build secret " + id,
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact normalizeUser(
            String surface, String relativePath, String rest, DerivationFactProvenance provenance) {
        var user = rest.trim();
        if (!"root".equalsIgnoreCase(user) && !"0".equals(user)) {
            return null;
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "dockerfile-stage");
        payload.put(IacFactKeys.PRIVILEGED_OPERATION, "user-root");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey =
                buildFactKey(surface, SystemModelFactKind.COMPONENT, provenance.adapterId(), relativePath, "user-root");
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                factKey,
                "Root user in Dockerfile",
                "Dockerfile sets USER to root",
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact normalizeAdd(
            String surface, String relativePath, String rest, DerivationFactProvenance provenance) {
        // ADD <url> <dest> — only if url starts with http:// or https://
        var parts = rest.trim().split("\\s+", 2);
        if (parts.length < 1) return null;
        var url = parts[0];
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        // Sanitize: strip userinfo, query string, and fragment before persisting
        var sanitizedUrl = RemoteRefSanitizer.sanitize(url);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "remote-fetch");
        payload.put("registryTarget", sanitizedUrl);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.EXTERNAL_INTERACTION,
                provenance.adapterId(),
                relativePath,
                "add-remote:" + sanitizedUrl);
        return new DerivedSystemModelFact(
                SystemModelFactKind.EXTERNAL_INTERACTION,
                factKey,
                "Remote fetch: " + sanitizedUrl,
                "Dockerfile ADD fetches from remote URL " + sanitizedUrl,
                relativePath,
                payload,
                provenance);
    }

    /** Join continuation lines and return logical lines. */
    private static List<String> parseLines(String content) {
        var rawLines = content.split("\n", -1);
        var result = new ArrayList<String>();
        var builder = new StringBuilder();
        for (String raw : rawLines) {
            // Strip trailing \r
            var line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.endsWith("\\")) {
                builder.append(line, 0, line.length() - 1);
            } else {
                builder.append(line);
                result.add(builder.toString());
                builder.setLength(0);
            }
        }
        if (!builder.isEmpty()) {
            result.add(builder.toString());
        }
        return result;
    }

    static String extractRegistryHostname(String image) {
        if (image == null || image.isBlank()) return null;
        int slash = image.indexOf('/');
        if (slash <= 0) return null;
        String prefix = image.substring(0, slash);
        if (prefix.contains(".") || prefix.contains(":")) {
            return prefix;
        }
        return null;
    }

    /**
     * Builds a stable fact key using semantic identity only: surface, factKind, adapterId,
     * sourcePath, and uniqueKey. commitSha is intentionally excluded so that the same topology
     * across different commits produces identical keys (ADR-058).
     */
    private static String buildFactKey(
            String surface, SystemModelFactKind factKind, String adapterId, String relativePath, String uniqueKey) {
        return "iac:%s:%s:%s"
                .formatted(
                        surface,
                        factKind.name().toLowerCase(Locale.ROOT),
                        sha256(adapterId, surface, relativePath, factKind.name(), uniqueKey));
    }

    private static String sha256(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

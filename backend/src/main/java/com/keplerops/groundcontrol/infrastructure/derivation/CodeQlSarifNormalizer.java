package com.keplerops.groundcontrol.infrastructure.derivation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterResult;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class CodeQlSarifNormalizer {

    private static final int MAX_LOCATIONS = 50;
    private static final int MAX_CODE_FLOW_LOCATIONS = 100;

    private final ObjectMapper objectMapper;

    CodeQlSarifNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DerivationAdapterResult normalize(
            String language,
            String queryPack,
            String sarifJson,
            DerivationAdapterRequest request,
            String toolVersion,
            Instant derivedAt) {
        try {
            var root = objectMapper.readTree(sarifJson);
            var facts = new ArrayList<DerivedSystemModelFact>();
            for (JsonNode run : iterable(root.path("runs"))) {
                var rules = rulesById(run);
                var resultIndex = 0;
                for (JsonNode result : iterable(run.path("results"))) {
                    var fact = normalizeResult(
                            language, queryPack, request, toolVersion, derivedAt, rules, result, resultIndex);
                    if (fact != null) {
                        facts.add(fact);
                    }
                    resultIndex++;
                }
            }
            return DerivationAdapterResult.facts(facts);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse CodeQL SARIF output", exception);
        }
    }

    private DerivedSystemModelFact normalizeResult(
            String language,
            String queryPack,
            DerivationAdapterRequest request,
            String toolVersion,
            Instant derivedAt,
            Map<String, RuleMetadata> rules,
            JsonNode result,
            int resultIndex) {
        var ruleId = text(result, "ruleId");
        var rule = rules.getOrDefault(ruleId, RuleMetadata.empty(ruleId));
        var message = text(result.path("message"), "text");
        var primaryLocations = locationsFrom(result.path("locations"), MAX_LOCATIONS);
        var primary = primaryLocations.isEmpty() ? null : primaryLocations.getFirst();
        var codeFlowLocations = codeFlowLocationsFrom(result);
        var allLocations = new ArrayList<SarifLocation>();
        allLocations.addAll(primaryLocations);
        allLocations.addAll(codeFlowLocations);
        if (primary == null
                || !matchesScope(
                        allLocations, request.scope().mode(), request.scope().paths())) {
            return null;
        }

        var factKind = classifyFactKind(rule, message, !codeFlowLocations.isEmpty());
        var findingIdentity = findingIdentity(result, primary, resultIndex);
        var factKey = factKey(
                language, queryPack, request.scope().commitSha(), factKind, ruleId, primary, message, findingIdentity);
        var provenance = new DerivationFactProvenance(
                "codeql-derivation",
                "CodeQL",
                toolVersion,
                "codeql-query-packs",
                queryPack,
                request.scope().commitSha(),
                derivedAt);
        var payload =
                payload(language, queryPack, request, rule, message, resultIndex, primaryLocations, codeFlowLocations);
        return new DerivedSystemModelFact(
                factKind,
                factKey,
                firstNonBlank(rule.shortDescription(), rule.name(), message, ruleId, "CodeQL finding"),
                firstNonBlank(message, rule.shortDescription(), rule.name(), "CodeQL finding"),
                primary.path(),
                payload,
                provenance);
    }

    private Map<String, Object> payload(
            String language,
            String queryPack,
            DerivationAdapterRequest request,
            RuleMetadata rule,
            String message,
            int resultIndex,
            List<SarifLocation> primaryLocations,
            List<SarifLocation> codeFlowLocations) {
        var boundaries = boundaries(primaryLocations, codeFlowLocations);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("language", language);
        payload.put("queryPack", queryPack);
        payload.put("scopeMode", request.scope().mode().name());
        payload.put("ruleId", rule.id());
        payload.put("ruleName", rule.name());
        payload.put("ruleTags", rule.tags());
        payload.put("severity", rule.severity());
        payload.put("message", message);
        payload.put("resultIndex", resultIndex);
        payload.put(
                "locations",
                primaryLocations.stream().map(SarifLocation::toPayload).toList());
        payload.put(
                "codeFlowLocations",
                codeFlowLocations.stream().map(SarifLocation::toPayload).toList());
        payload.put("boundaryCrossing", boundaries.size() > 1);
        payload.put("boundaries", List.copyOf(boundaries));
        return payload;
    }

    private static SystemModelFactKind classifyFactKind(RuleMetadata rule, String message, boolean hasCodeFlows) {
        var text = (rule.id() + " " + rule.name() + " " + rule.shortDescription() + " " + rule.tags() + " " + message)
                .toLowerCase(Locale.ROOT);
        if (text.contains("secret") || text.contains("credential")) {
            return SystemModelFactKind.SECRET_USAGE;
        }
        if (text.contains("entry-point")
                || text.contains("entry point")
                || text.contains("endpoint")
                || text.contains("route")
                || text.contains("controller")) {
            return SystemModelFactKind.ENTRY_POINT;
        }
        if (text.contains("taint") || (hasCodeFlows && text.contains("source") && text.contains("sink"))) {
            return SystemModelFactKind.TAINT_PATH;
        }
        if (hasCodeFlows || text.contains("data-flow") || text.contains("data flow") || text.contains("reachab")) {
            return SystemModelFactKind.DATA_FLOW;
        }
        if (text.contains("external") || text.contains("http") || text.contains("network")) {
            return SystemModelFactKind.EXTERNAL_INTERACTION;
        }
        return SystemModelFactKind.DATA_FLOW;
    }

    private static boolean matchesScope(
            List<SarifLocation> locations, DerivationScopeMode mode, List<String> requestedPaths) {
        if (mode == DerivationScopeMode.FULL_REPO) {
            return true;
        }
        if (requestedPaths == null || requestedPaths.isEmpty()) {
            return false;
        }
        return locations.stream().map(SarifLocation::path).anyMatch(path -> requestedPaths.stream()
                .map(CodeQlSarifNormalizer::normalizePath)
                .anyMatch(requested -> path.equals(requested) || path.startsWith(requested + "/")));
    }

    private Map<String, RuleMetadata> rulesById(JsonNode run) {
        var rules = new LinkedHashMap<String, RuleMetadata>();
        for (JsonNode rule : iterable(run.path("tool").path("driver").path("rules"))) {
            var id = text(rule, "id");
            if (id.isBlank()) {
                continue;
            }
            var metadata = new RuleMetadata(
                    id,
                    text(rule, "name"),
                    text(rule.path("shortDescription"), "text"),
                    tags(rule.path("properties").path("tags")),
                    text(rule.path("properties"), "security-severity"));
            rules.put(id, metadata);
        }
        return rules;
    }

    private static List<SarifLocation> codeFlowLocationsFrom(JsonNode result) {
        var locations = new ArrayList<SarifLocation>();
        for (JsonNode codeFlow : iterable(result.path("codeFlows"))) {
            for (JsonNode threadFlow : iterable(codeFlow.path("threadFlows"))) {
                for (JsonNode threadFlowLocation : iterable(threadFlow.path("locations"))) {
                    var location = locationFrom(threadFlowLocation.path("location"));
                    if (location != null) {
                        locations.add(location);
                    }
                    if (locations.size() >= MAX_CODE_FLOW_LOCATIONS) {
                        return List.copyOf(locations);
                    }
                }
            }
        }
        return List.copyOf(locations);
    }

    private static List<SarifLocation> locationsFrom(JsonNode locationsNode, int maxLocations) {
        var locations = new ArrayList<SarifLocation>();
        for (JsonNode locationNode : iterable(locationsNode)) {
            var location = locationFrom(locationNode);
            if (location != null) {
                locations.add(location);
            }
            if (locations.size() >= maxLocations) {
                break;
            }
        }
        return List.copyOf(locations);
    }

    private static SarifLocation locationFrom(JsonNode locationNode) {
        var physical = locationNode.path("physicalLocation");
        var path = normalizePath(text(physical.path("artifactLocation"), "uri"));
        if (path.isBlank()) {
            return null;
        }
        var region = physical.path("region");
        return new SarifLocation(
                path,
                intOrNull(region, "startLine"),
                intOrNull(region, "startColumn"),
                intOrNull(region, "endLine"),
                intOrNull(region, "endColumn"));
    }

    private static Set<String> boundaries(List<SarifLocation> primary, List<SarifLocation> codeFlow) {
        var boundaries = new LinkedHashSet<String>();
        codeFlow.stream()
                .map(SarifLocation::path)
                .map(CodeQlSarifNormalizer::boundaryOf)
                .forEach(boundaries::add);
        primary.stream()
                .map(SarifLocation::path)
                .map(CodeQlSarifNormalizer::boundaryOf)
                .forEach(boundaries::add);
        boundaries.remove("");
        return boundaries;
    }

    private static String boundaryOf(String path) {
        var normalized = normalizePath(path);
        var slash = normalized.indexOf('/');
        return slash <= 0 ? normalized : normalized.substring(0, slash);
    }

    private static String factKey(
            String language,
            String queryPack,
            String commitSha,
            SystemModelFactKind factKind,
            String ruleId,
            SarifLocation primary,
            String message,
            String findingIdentity) {
        return "codeql:%s:%s:%s"
                .formatted(
                        language,
                        factKind.name().toLowerCase(Locale.ROOT),
                        sha256(
                                language,
                                queryPack,
                                commitSha,
                                factKind.name(),
                                ruleId,
                                primary.path(),
                                message,
                                findingIdentity));
    }

    private static String findingIdentity(JsonNode result, SarifLocation primary, int resultIndex) {
        var fingerprintMaterial = fingerprintMaterial(result);
        if (!fingerprintMaterial.isBlank()) {
            return fingerprintMaterial;
        }
        return "resultIndex=%d;startLine=%s;startColumn=%s;endLine=%s;endColumn=%s"
                .formatted(
                        resultIndex,
                        primary.startLine(),
                        primary.startColumn(),
                        primary.endLine(),
                        primary.endColumn());
    }

    private static String fingerprintMaterial(JsonNode result) {
        var fields = new TreeMap<String, String>();
        collectFingerprintFields("fingerprints", result.path("fingerprints"), fields);
        collectFingerprintFields("partialFingerprints", result.path("partialFingerprints"), fields);
        if (fields.isEmpty()) {
            return "";
        }
        var material = new StringBuilder();
        fields.forEach(
                (key, value) -> material.append(key).append('=').append(value).append(';'));
        return material.toString();
    }

    private static void collectFingerprintFields(String prefix, JsonNode node, Map<String, String> target) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode()) {
                target.put(prefix + "." + entry.getKey(), entry.getValue().asText());
            }
        });
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

    private static List<String> tags(JsonNode tagsNode) {
        var tags = new ArrayList<String>();
        for (JsonNode tag : iterable(tagsNode)) {
            if (!tag.asText("").isBlank()) {
                tags.add(tag.asText());
            }
        }
        return List.copyOf(tags);
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        var normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Integer intOrNull(JsonNode node, String field) {
        var value = node.path(field);
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node::elements : List.<JsonNode>of();
    }

    private record RuleMetadata(String id, String name, String shortDescription, List<String> tags, String severity) {

        private RuleMetadata {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        static RuleMetadata empty(String ruleId) {
            return new RuleMetadata(ruleId, "", "", List.of(), "");
        }
    }

    private record SarifLocation(
            String path, Integer startLine, Integer startColumn, Integer endLine, Integer endColumn) {

        Map<String, Object> toPayload() {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("path", path);
            if (startLine != null) {
                payload.put("startLine", startLine);
            }
            if (startColumn != null) {
                payload.put("startColumn", startColumn);
            }
            if (endLine != null) {
                payload.put("endLine", endLine);
            }
            if (endColumn != null) {
                payload.put("endColumn", endColumn);
            }
            return payload;
        }
    }
}

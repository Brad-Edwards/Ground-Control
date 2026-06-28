package com.keplerops.groundcontrol.infrastructure.derivation;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

class TerraformNormalizer {

    private static final String BLOCK_VARIABLE = "variable";
    private static final String LABEL_UNKNOWN = "unknown";

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(secret|password|passwd|pass|token|key|credential|cert|private|api_key|apikey|auth)");

    // [^{]* captures everything up to the opening brace — a single star over a negated char class
    // with no nested quantifiers, eliminating S2631. Quoted labels are extracted from group 2
    // by the existing QUOTED_LABEL pattern loop, preserving identical parsing behaviour.
    private static final Pattern BLOCK_HEADER = Pattern.compile("^(\\w++)([^{]*)\\{\\s*$");
    private static final Pattern QUOTED_LABEL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern SOURCE_VALUE = Pattern.compile("^\\s*source\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SENSITIVE_TRUE = Pattern.compile("^\\s*sensitive\\s*=\\s*true\\s*$");

    // List<String> instead of String[] so the record's auto-generated equals/hashCode/toString
    // work correctly (fixes S6218 — array components in records require manual overrides).
    private record BlockHeaderResult(List<String> entry, List<DerivedSystemModelFact> facts) {}

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

        Deque<List<String>> blockStack = new ArrayDeque<>();
        int depth = 0;

        for (String rawLine : content.split("\n", -1)) {
            var line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            var trimmed = line.trim();
            if (!trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                depth = processNonCommentLine(trimmed, depth, blockStack, facts, surface, relativePath, provenance);
            }
        }

        return List.copyOf(facts);
    }

    /**
     * Dispatches a single non-comment, non-blank line to the appropriate handler and returns the
     * updated block depth. Extracted from normalize() to keep that method's cognitive complexity
     * below the S3776 threshold.
     */
    private int processNonCommentLine(
            String trimmed,
            int depth,
            Deque<List<String>> blockStack,
            List<DerivedSystemModelFact> facts,
            String surface,
            String relativePath,
            DerivationFactProvenance provenance) {
        if ("}".equals(trimmed) || "}\"".equals(trimmed)) {
            if (!blockStack.isEmpty()) {
                blockStack.pop();
                return depth - 1;
            }
        } else if (trimmed.endsWith("{")) {
            var currentTop = blockStack.isEmpty() ? null : blockStack.peek().get(0);
            var result = processBlockHeader(trimmed, depth, currentTop, surface, relativePath, provenance);
            if (result != null) {
                facts.addAll(result.facts());
                blockStack.push(result.entry());
                return depth + 1;
            }
        } else if (depth > 0 && !blockStack.isEmpty()) {
            facts.addAll(processBodyLine(trimmed, blockStack, surface, relativePath, provenance));
        }
        return depth;
    }

    private BlockHeaderResult processBlockHeader(
            String trimmed,
            int depth,
            String currentTopBlockType,
            String surface,
            String relativePath,
            DerivationFactProvenance provenance) {
        var headerMatcher = BLOCK_HEADER.matcher(trimmed);
        if (!headerMatcher.matches()) {
            return null;
        }
        var blockType = headerMatcher.group(1);
        var labels = parseLabels(headerMatcher.group(2).trim());
        var entry = buildEntry(blockType, labels);
        var facts = new ArrayList<DerivedSystemModelFact>();

        if (depth == 0) {
            facts.addAll(handleTopLevelBlock(surface, relativePath, blockType, labels, provenance));
        }
        if (depth > 0 && "provisioner".equals(blockType)) {
            facts.add(emitProvisionerFact(labels, depth, surface, relativePath, provenance));
        }
        if (depth == 1 && "terraform".equals(currentTopBlockType) && "backend".equals(blockType)) {
            facts.add(emitBackendFact(labels, surface, relativePath, provenance));
        }

        return new BlockHeaderResult(entry, facts);
    }

    private static List<String> parseLabels(String labelsStr) {
        var labels = new ArrayList<String>();
        var matcher = QUOTED_LABEL.matcher(labelsStr);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        return labels;
    }

    private static List<String> buildEntry(String blockType, List<String> labels) {
        var entry = new ArrayList<String>();
        entry.add(blockType);
        entry.addAll(labels);
        return List.copyOf(entry);
    }

    private DerivedSystemModelFact emitProvisionerFact(
            List<String> labels, int depth, String surface, String relativePath, DerivationFactProvenance provenance) {
        var provType = labels.isEmpty() ? LABEL_UNKNOWN : labels.get(0);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "terraform-provisioner");
        payload.put(IacFactKeys.PRIVILEGED_OPERATION, "remote-exec");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.COMPONENT,
                provenance.adapterId(),
                relativePath,
                "provisioner:" + provType + ":" + depth);
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                factKey,
                "Terraform provisioner: " + provType,
                "Terraform resource uses " + provType + " provisioner",
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact emitBackendFact(
            List<String> labels, String surface, String relativePath, DerivationFactProvenance provenance) {
        var backendType = labels.isEmpty() ? LABEL_UNKNOWN : labels.get(0);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "remote-state-boundary");
        payload.put(IacFactKeys.DEPLOY_TARGET, backendType);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.TRUST_BOUNDARY,
                provenance.adapterId(),
                relativePath,
                "backend:" + backendType);
        return new DerivedSystemModelFact(
                SystemModelFactKind.TRUST_BOUNDARY,
                factKey,
                "Remote state backend: " + backendType,
                "Terraform uses remote state backend " + backendType,
                relativePath,
                payload,
                provenance);
    }

    private List<DerivedSystemModelFact> processBodyLine(
            String trimmed,
            Deque<List<String>> blockStack,
            String surface,
            String relativePath,
            DerivationFactProvenance provenance) {
        var currentBlock = blockStack.peek();
        var blockType = currentBlock.get(0);
        var currentLabel = currentBlock.size() > 1 ? currentBlock.get(1) : "";
        var facts = new ArrayList<DerivedSystemModelFact>();
        facts.addAll(handleModuleSourceLine(trimmed, blockType, currentLabel, surface, relativePath, provenance));
        facts.addAll(handleSensitiveMarker(trimmed, blockType, currentLabel, surface, relativePath, provenance));
        return facts;
    }

    private List<DerivedSystemModelFact> handleModuleSourceLine(
            String trimmed,
            String blockType,
            String currentLabel,
            String surface,
            String relativePath,
            DerivationFactProvenance provenance) {
        if (!"module".equals(blockType)) {
            return List.of();
        }
        var sourceMatcher = SOURCE_VALUE.matcher(trimmed);
        if (!sourceMatcher.find()) {
            return List.of();
        }
        var sourceValue = sourceMatcher.group(1);
        if (!sourceValue.contains("://") && !sourceValue.contains("/")) {
            return List.of();
        }
        var sanitizedSource = RemoteRefSanitizer.sanitize(sourceValue);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "remote-module");
        payload.put("registryTarget", sanitizedSource);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.EXTERNAL_INTERACTION,
                provenance.adapterId(),
                relativePath,
                "module-source:" + currentLabel);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.EXTERNAL_INTERACTION,
                factKey,
                "Remote module source: " + sanitizedSource,
                "Terraform module " + currentLabel + " uses remote source " + sanitizedSource,
                relativePath,
                payload,
                provenance));
    }

    private List<DerivedSystemModelFact> handleSensitiveMarker(
            String trimmed,
            String blockType,
            String label,
            String surface,
            String relativePath,
            DerivationFactProvenance provenance) {
        if (!SENSITIVE_TRUE.matcher(trimmed).matches()) {
            return List.of();
        }
        return switch (blockType) {
            case BLOCK_VARIABLE -> List.of(emitSensitiveVariable(label, surface, relativePath, provenance));
            case "output" -> List.of(emitSensitiveOutput(label, surface, relativePath, provenance));
            default -> List.of();
        };
    }

    private DerivedSystemModelFact emitSensitiveVariable(
            String label, String surface, String relativePath, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "sensitive-variable");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                provenance.adapterId(),
                relativePath,
                "sensitive-var:" + label);
        return new DerivedSystemModelFact(
                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                factKey,
                "Sensitive variable: " + label,
                "Terraform variable " + label + " is marked sensitive",
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact emitSensitiveOutput(
            String label, String surface, String relativePath, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "sensitive-output");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                provenance.adapterId(),
                relativePath,
                "sensitive-output:" + label);
        return new DerivedSystemModelFact(
                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                factKey,
                "Sensitive output: " + label,
                "Terraform output " + label + " is marked sensitive",
                relativePath,
                payload,
                provenance);
    }

    private List<DerivedSystemModelFact> handleTopLevelBlock(
            String surface,
            String relativePath,
            String blockType,
            List<String> labels,
            DerivationFactProvenance provenance) {
        return switch (blockType) {
            case "resource" -> emitResourceFacts(labels, surface, relativePath, provenance);
            case "module" -> List.of(emitModuleFact(labels, surface, relativePath, provenance));
            case "provider" -> emitProviderFacts(labels, surface, relativePath, provenance);
            case BLOCK_VARIABLE -> emitVariableSecretFacts(labels, surface, relativePath, provenance);
            default -> List.of();
        };
    }

    private List<DerivedSystemModelFact> emitResourceFacts(
            List<String> labels, String surface, String relativePath, DerivationFactProvenance provenance) {
        if (labels.size() < 2) {
            return List.of();
        }
        var resourceType = labels.get(0);
        var resourceName = labels.get(1);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "terraform-resource");
        payload.put(IacFactKeys.DEPLOY_TARGET, resourceType);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.COMPONENT,
                provenance.adapterId(),
                relativePath,
                "resource:" + resourceType + ":" + resourceName);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                factKey,
                "Terraform resource: " + resourceType + "." + resourceName,
                "Terraform manages resource " + resourceType + " named " + resourceName,
                relativePath,
                payload,
                provenance));
    }

    private DerivedSystemModelFact emitModuleFact(
            List<String> labels, String surface, String relativePath, DerivationFactProvenance provenance) {
        var moduleName = labels.isEmpty() ? LABEL_UNKNOWN : labels.get(0);
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "terraform-module");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface, SystemModelFactKind.COMPONENT, provenance.adapterId(), relativePath, "module:" + moduleName);
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                factKey,
                "Terraform module: " + moduleName,
                "Terraform module call " + moduleName,
                relativePath,
                payload,
                provenance);
    }

    private List<DerivedSystemModelFact> emitProviderFacts(
            List<String> labels, String surface, String relativePath, DerivationFactProvenance provenance) {
        var providerName = labels.isEmpty() ? LABEL_UNKNOWN : labels.get(0);
        var compPayload = new LinkedHashMap<String, Object>();
        compPayload.put(IacFactKeys.SURFACE, surface);
        compPayload.put(IacFactKeys.ARTIFACT_KIND, "terraform-provider");
        compPayload.put(IacFactKeys.DEPLOY_TARGET, providerName);
        compPayload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var compKey = buildFactKey(
                surface,
                SystemModelFactKind.COMPONENT,
                provenance.adapterId(),
                relativePath,
                "provider:" + providerName);
        var regPayload = new LinkedHashMap<String, Object>();
        regPayload.put(IacFactKeys.SURFACE, surface);
        regPayload.put(IacFactKeys.ARTIFACT_KIND, "provider-registry");
        regPayload.put("registryTarget", providerName);
        regPayload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var regKey = buildFactKey(
                surface,
                SystemModelFactKind.EXTERNAL_INTERACTION,
                provenance.adapterId(),
                relativePath,
                "provider-registry:" + providerName);
        return List.of(
                new DerivedSystemModelFact(
                        SystemModelFactKind.COMPONENT,
                        compKey,
                        "Terraform provider: " + providerName,
                        "Terraform uses provider " + providerName,
                        relativePath,
                        compPayload,
                        provenance),
                new DerivedSystemModelFact(
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        regKey,
                        "Provider registry: " + providerName,
                        "Terraform fetches provider " + providerName + " from registry",
                        relativePath,
                        regPayload,
                        provenance));
    }

    private List<DerivedSystemModelFact> emitVariableSecretFacts(
            List<String> labels, String surface, String relativePath, DerivationFactProvenance provenance) {
        var varName = labels.isEmpty() ? LABEL_UNKNOWN : labels.get(0);
        if (!SECRET_LIKE.matcher(varName).find()) {
            return List.of();
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_REF, varName);
        payload.put(IacFactKeys.SECRET_SCOPE, BLOCK_VARIABLE);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var factKey = buildFactKey(
                surface,
                SystemModelFactKind.SECRET_USAGE,
                provenance.adapterId(),
                relativePath,
                "var-secret:" + varName);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                factKey,
                "Secret variable: " + varName,
                "Terraform variable " + varName + " has secret-like name",
                relativePath,
                payload,
                provenance));
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

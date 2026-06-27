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

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(secret|password|passwd|pass|token|key|credential|cert|private|api_key|apikey|auth)");
    private static final Pattern BLOCK_HEADER = Pattern.compile("^(\\w+)((?:\\s+\"[^\"]*\")*)\\s*\\{\\s*$");
    private static final Pattern QUOTED_LABEL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern SOURCE_VALUE = Pattern.compile("^\\s*source\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern SENSITIVE_TRUE = Pattern.compile("^\\s*sensitive\\s*=\\s*true\\s*$");

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

        // Block stack: each entry is String[] {blockType, label1, label2, ...}
        Deque<String[]> blockStack = new ArrayDeque<>();
        int depth = 0;

        var lines = content.split("\n", -1);
        for (String rawLine : lines) {
            var line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            var trimmed = line.trim();

            // Skip comments
            if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            // Check if this is a closing brace
            if ("}".equals(trimmed) || "}\"".equals(trimmed)) {
                if (!blockStack.isEmpty()) {
                    blockStack.pop();
                    depth--;
                }
                continue;
            }

            // Check for block header (ends with {)
            if (trimmed.endsWith("{")) {
                var headerMatcher = BLOCK_HEADER.matcher(trimmed);
                if (headerMatcher.matches()) {
                    var blockType = headerMatcher.group(1);
                    var labelsStr = headerMatcher.group(2).trim();
                    var labels = new ArrayList<String>();
                    var labelMatcher = QUOTED_LABEL.matcher(labelsStr);
                    while (labelMatcher.find()) {
                        labels.add(labelMatcher.group(1));
                    }

                    var entry = new String[1 + labels.size()];
                    entry[0] = blockType;
                    for (int i = 0; i < labels.size(); i++) {
                        entry[i + 1] = labels.get(i);
                    }

                    // Emit facts for top-level blocks (depth == 0 before opening)
                    if (depth == 0) {
                        facts.addAll(handleTopLevelBlock(surface, relativePath, blockType, labels, provenance));
                    }

                    // Handle nested provisioner block
                    if (depth > 0 && "provisioner".equals(blockType)) {
                        var provType = labels.isEmpty() ? "unknown" : labels.get(0);
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("surface", surface);
                        payload.put("artifactKind", "terraform-provisioner");
                        payload.put("privilegedOperation", "remote-exec");
                        payload.put("sourcePath", relativePath);
                        var factKey = buildFactKey(
                                surface,
                                SystemModelFactKind.COMPONENT,
                                provenance.adapterId(),
                                relativePath,
                                provenance.commitSha(),
                                "provisioner:" + provType + ":" + depth);
                        facts.add(new DerivedSystemModelFact(
                                SystemModelFactKind.COMPONENT,
                                factKey,
                                "Terraform provisioner: " + provType,
                                "Terraform resource uses " + provType + " provisioner",
                                relativePath,
                                payload,
                                provenance));
                    }

                    // Handle nested backend block inside terraform{}
                    if (depth == 1
                            && !blockStack.isEmpty()
                            && "terraform".equals(blockStack.peek()[0])
                            && "backend".equals(blockType)) {
                        var backendType = labels.isEmpty() ? "unknown" : labels.get(0);
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("surface", surface);
                        payload.put("artifactKind", "remote-state-boundary");
                        payload.put("deployTarget", backendType);
                        payload.put("sourcePath", relativePath);
                        var factKey = buildFactKey(
                                surface,
                                SystemModelFactKind.TRUST_BOUNDARY,
                                provenance.adapterId(),
                                relativePath,
                                provenance.commitSha(),
                                "backend:" + backendType);
                        facts.add(new DerivedSystemModelFact(
                                SystemModelFactKind.TRUST_BOUNDARY,
                                factKey,
                                "Remote state backend: " + backendType,
                                "Terraform uses remote state backend " + backendType,
                                relativePath,
                                payload,
                                provenance));
                    }

                    blockStack.push(entry);
                    depth++;
                    continue;
                }
            }

            // Body lines at depth > 0
            if (depth > 0 && !blockStack.isEmpty()) {
                var currentBlock = blockStack.peek();
                var currentBlockType = currentBlock[0];
                var currentLabel = currentBlock.length > 1 ? currentBlock[1] : "";

                // module source check (depth == 1 inside a module block)
                if ("module".equals(currentBlockType)) {
                    var sourceMatcher = SOURCE_VALUE.matcher(trimmed);
                    if (sourceMatcher.find()) {
                        var sourceValue = sourceMatcher.group(1);
                        if (sourceValue.contains("://") || sourceValue.contains("/")) {
                            var payload = new LinkedHashMap<String, Object>();
                            payload.put("surface", surface);
                            payload.put("artifactKind", "remote-module");
                            payload.put("registryTarget", sourceValue);
                            payload.put("sourcePath", relativePath);
                            var factKey = buildFactKey(
                                    surface,
                                    SystemModelFactKind.EXTERNAL_INTERACTION,
                                    provenance.adapterId(),
                                    relativePath,
                                    provenance.commitSha(),
                                    "module-source:" + currentLabel);
                            facts.add(new DerivedSystemModelFact(
                                    SystemModelFactKind.EXTERNAL_INTERACTION,
                                    factKey,
                                    "Remote module source: " + sourceValue,
                                    "Terraform module " + currentLabel + " uses remote source " + sourceValue,
                                    relativePath,
                                    payload,
                                    provenance));
                        }
                    }
                }

                // variable/output sensitive = true
                if (("variable".equals(currentBlockType) || "output".equals(currentBlockType))
                        && SENSITIVE_TRUE.matcher(trimmed).matches()) {
                    if ("variable".equals(currentBlockType)) {
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("surface", surface);
                        payload.put("artifactKind", "sensitive-variable");
                        payload.put("sourcePath", relativePath);
                        var factKey = buildFactKey(
                                surface,
                                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                                provenance.adapterId(),
                                relativePath,
                                provenance.commitSha(),
                                "sensitive-var:" + currentLabel);
                        facts.add(new DerivedSystemModelFact(
                                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                                factKey,
                                "Sensitive variable: " + currentLabel,
                                "Terraform variable " + currentLabel + " is marked sensitive",
                                relativePath,
                                payload,
                                provenance));
                    } else {
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("surface", surface);
                        payload.put("artifactKind", "sensitive-output");
                        payload.put("sourcePath", relativePath);
                        var factKey = buildFactKey(
                                surface,
                                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                                provenance.adapterId(),
                                relativePath,
                                provenance.commitSha(),
                                "sensitive-output:" + currentLabel);
                        facts.add(new DerivedSystemModelFact(
                                SystemModelFactKind.DATA_CLASSIFICATION_HINT,
                                factKey,
                                "Sensitive output: " + currentLabel,
                                "Terraform output " + currentLabel + " is marked sensitive",
                                relativePath,
                                payload,
                                provenance));
                    }
                }
            }
        }

        return List.copyOf(facts);
    }

    private List<DerivedSystemModelFact> handleTopLevelBlock(
            String surface,
            String relativePath,
            String blockType,
            List<String> labels,
            DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        switch (blockType) {
            case "resource" -> {
                if (labels.size() >= 2) {
                    var resourceType = labels.get(0);
                    var resourceName = labels.get(1);
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("surface", surface);
                    payload.put("artifactKind", "terraform-resource");
                    payload.put("deployTarget", resourceType);
                    payload.put("sourcePath", relativePath);
                    var factKey = buildFactKey(
                            surface,
                            SystemModelFactKind.COMPONENT,
                            provenance.adapterId(),
                            relativePath,
                            provenance.commitSha(),
                            "resource:" + resourceType + ":" + resourceName);
                    facts.add(new DerivedSystemModelFact(
                            SystemModelFactKind.COMPONENT,
                            factKey,
                            "Terraform resource: " + resourceType + "." + resourceName,
                            "Terraform manages resource " + resourceType + " named " + resourceName,
                            relativePath,
                            payload,
                            provenance));
                }
            }
            case "module" -> {
                var moduleName = labels.isEmpty() ? "unknown" : labels.get(0);
                var payload = new LinkedHashMap<String, Object>();
                payload.put("surface", surface);
                payload.put("artifactKind", "terraform-module");
                payload.put("sourcePath", relativePath);
                var factKey = buildFactKey(
                        surface,
                        SystemModelFactKind.COMPONENT,
                        provenance.adapterId(),
                        relativePath,
                        provenance.commitSha(),
                        "module:" + moduleName);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.COMPONENT,
                        factKey,
                        "Terraform module: " + moduleName,
                        "Terraform module call " + moduleName,
                        relativePath,
                        payload,
                        provenance));
            }
            case "provider" -> {
                var providerName = labels.isEmpty() ? "unknown" : labels.get(0);
                var compPayload = new LinkedHashMap<String, Object>();
                compPayload.put("surface", surface);
                compPayload.put("artifactKind", "terraform-provider");
                compPayload.put("deployTarget", providerName);
                compPayload.put("sourcePath", relativePath);
                var compKey = buildFactKey(
                        surface,
                        SystemModelFactKind.COMPONENT,
                        provenance.adapterId(),
                        relativePath,
                        provenance.commitSha(),
                        "provider:" + providerName);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.COMPONENT,
                        compKey,
                        "Terraform provider: " + providerName,
                        "Terraform uses provider " + providerName,
                        relativePath,
                        compPayload,
                        provenance));
                var regPayload = new LinkedHashMap<String, Object>();
                regPayload.put("surface", surface);
                regPayload.put("artifactKind", "provider-registry");
                regPayload.put("registryTarget", providerName);
                regPayload.put("sourcePath", relativePath);
                var regKey = buildFactKey(
                        surface,
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        provenance.adapterId(),
                        relativePath,
                        provenance.commitSha(),
                        "provider-registry:" + providerName);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        regKey,
                        "Provider registry: " + providerName,
                        "Terraform fetches provider " + providerName + " from registry",
                        relativePath,
                        regPayload,
                        provenance));
            }
            case "variable" -> {
                var varName = labels.isEmpty() ? "unknown" : labels.get(0);
                if (SECRET_LIKE.matcher(varName).find()) {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("surface", surface);
                    payload.put("secretRef", varName);
                    payload.put("secretScope", "variable");
                    payload.put("sourcePath", relativePath);
                    var factKey = buildFactKey(
                            surface,
                            SystemModelFactKind.SECRET_USAGE,
                            provenance.adapterId(),
                            relativePath,
                            provenance.commitSha(),
                            "var-secret:" + varName);
                    facts.add(new DerivedSystemModelFact(
                            SystemModelFactKind.SECRET_USAGE,
                            factKey,
                            "Secret variable: " + varName,
                            "Terraform variable " + varName + " has secret-like name",
                            relativePath,
                            payload,
                            provenance));
                }
            }
            default -> {
                // terraform{}, output{}, locals{}, data{}, etc — no top-level fact emitted here
                // (backend nested inside terraform{} is handled in the body-line pass)
            }
        }

        return facts;
    }

    private static String buildFactKey(
            String surface,
            SystemModelFactKind factKind,
            String adapterId,
            String relativePath,
            String commitSha,
            String uniqueKey) {
        return "iac:%s:%s:%s"
                .formatted(
                        surface,
                        factKind.name().toLowerCase(Locale.ROOT),
                        sha256(adapterId, surface, relativePath, commitSha, factKind.name(), uniqueKey));
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

package com.keplerops.groundcontrol.infrastructure.derivation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationFactProvenance;
import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

class DockerComposeNormalizer {

    private static final String SCOPE_ENV = "environment";
    private static final String SVC = "Service ";

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(secret|password|passwd|pass|token|key|credential|cert|private|api_key|apikey|auth)");
    private static final List<String> SENSITIVE_BIND_PREFIXES = List.of("/etc", "/proc", "/sys", "/root");

    /**
     * Bundles the three parameters common to every fact-building helper (surface, relativePath,
     * provenance) so that helper signatures stay within the S107 7-parameter limit.
     */
    private record FactContext(String surface, String relativePath, DerivationFactProvenance provenance) {}

    private final YAMLMapper yamlMapper;

    DockerComposeNormalizer() {
        this.yamlMapper = new YAMLMapper();
    }

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

        JsonNode root;
        try {
            root = yamlMapper.readTree(content);
        } catch (IOException exception) {
            // Propagate parse failures so the adapter can emit a sanitized capture limit.
            // Do not embed raw file content in the message to avoid leaking secrets.
            throw new IllegalStateException("Failed to parse docker-compose YAML", exception);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }

        var servicesNode = root.path("services");
        if (!servicesNode.isObject()) {
            return List.of();
        }

        var serviceFields = servicesNode.fields();
        while (serviceFields.hasNext()) {
            var entry = serviceFields.next();
            facts.addAll(normalizeService(surface, relativePath, entry.getKey(), entry.getValue(), provenance));
        }

        return List.copyOf(facts);
    }

    private List<DerivedSystemModelFact> normalizeService(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();
        facts.add(emitServiceComponent(surface, relativePath, serviceName, provenance));
        facts.addAll(emitImageRegistryFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitPrivilegedFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitVolumeFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitSecretFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitEnvFileFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitEnvironmentSecretFacts(surface, relativePath, serviceName, serviceNode, provenance));
        facts.addAll(emitPortFacts(surface, relativePath, serviceName, serviceNode, provenance));
        return facts;
    }

    private DerivedSystemModelFact emitServiceComponent(
            String surface, String relativePath, String serviceName, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "compose-service");
        payload.put("serviceName", serviceName);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface, SystemModelFactKind.COMPONENT, provenance.adapterId(), relativePath, "service:" + serviceName);
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                key,
                "Compose service: " + serviceName,
                "Docker Compose service " + serviceName,
                relativePath,
                payload,
                provenance);
    }

    private List<DerivedSystemModelFact> emitImageRegistryFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var imageNode = serviceNode.path("image");
        if (!imageNode.isTextual()) {
            return List.of();
        }
        var registryHostname = DockerfileNormalizer.extractRegistryHostname(imageNode.asText());
        if (registryHostname == null) {
            return List.of();
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "image-registry");
        payload.put("registryTarget", registryHostname);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface,
                SystemModelFactKind.EXTERNAL_INTERACTION,
                provenance.adapterId(),
                relativePath,
                "image-registry:" + serviceName + ":" + registryHostname);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.EXTERNAL_INTERACTION,
                key,
                "Image registry: " + registryHostname,
                SVC + serviceName + " pulls from external registry " + registryHostname,
                relativePath,
                payload,
                provenance));
    }

    private List<DerivedSystemModelFact> emitPrivilegedFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var ctx = new FactContext(surface, relativePath, provenance);
        var facts = new ArrayList<DerivedSystemModelFact>();

        var privilegedNode = serviceNode.path("privileged");
        if (privilegedNode.isBoolean() && privilegedNode.asBoolean()) {
            facts.add(buildTrustBoundaryFact(
                    ctx,
                    "container-privilege-boundary",
                    "privileged-container",
                    "Privileged container: " + serviceName,
                    SVC + serviceName + " runs as privileged container",
                    "privileged:" + serviceName));
        }

        emitCapAddFact(ctx, serviceName, serviceNode.path("cap_add")).ifPresent(facts::add);

        var networkModeNode = serviceNode.path("network_mode");
        if (networkModeNode.isTextual() && "host".equals(networkModeNode.asText())) {
            facts.add(buildTrustBoundaryFact(
                    ctx,
                    "network-boundary",
                    "host-network",
                    "Host network: " + serviceName,
                    SVC + serviceName + " uses host network mode",
                    "host-network:" + serviceName));
        }

        var pidNode = serviceNode.path("pid");
        if (pidNode.isTextual() && "host".equals(pidNode.asText())) {
            facts.add(buildTrustBoundaryFact(
                    ctx,
                    "process-boundary",
                    "host-pid",
                    "Host PID namespace: " + serviceName,
                    SVC + serviceName + " shares host PID namespace",
                    "host-pid:" + serviceName));
        }

        var ipcNode = serviceNode.path("ipc");
        if (ipcNode.isTextual() && "host".equals(ipcNode.asText())) {
            facts.add(buildTrustBoundaryFact(
                    ctx,
                    "ipc-boundary",
                    "host-ipc",
                    "Host IPC: " + serviceName,
                    SVC + serviceName + " shares host IPC namespace",
                    "host-ipc:" + serviceName));
        }

        var userNode = serviceNode.path("user");
        if (userNode.isTextual()) {
            var user = userNode.asText();
            if ("root".equalsIgnoreCase(user) || "0".equals(user)) {
                facts.add(buildTrustBoundaryFact(
                        ctx,
                        "user-boundary",
                        "root-user",
                        "Root user container: " + serviceName,
                        SVC + serviceName + " runs as root user",
                        "root-user:" + serviceName));
            }
        }

        return facts;
    }

    /**
     * Emits a TRUST_BOUNDARY fact for Linux capability additions, or empty if the node is absent or
     * not an array. Extracted from emitPrivilegedFacts to keep that method's cognitive complexity
     * below the S3776 threshold.
     */
    private Optional<DerivedSystemModelFact> emitCapAddFact(FactContext ctx, String serviceName, JsonNode capAddNode) {
        if (!capAddNode.isArray() || capAddNode.size() == 0) {
            return Optional.empty();
        }
        var caps = new ArrayList<String>();
        for (JsonNode cap : capAddNode) {
            caps.add(cap.asText());
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, ctx.surface());
        payload.put(IacFactKeys.ARTIFACT_KIND, "container-privilege-boundary");
        payload.put(IacFactKeys.PRIVILEGED_OPERATION, "capability-add");
        payload.put("securitySignals", caps);
        payload.put(IacFactKeys.SOURCE_PATH, ctx.relativePath());
        var key = buildFactKey(
                ctx.surface(),
                SystemModelFactKind.TRUST_BOUNDARY,
                ctx.provenance().adapterId(),
                ctx.relativePath(),
                "cap-add:" + serviceName);
        return Optional.of(new DerivedSystemModelFact(
                SystemModelFactKind.TRUST_BOUNDARY,
                key,
                "Capability add: " + serviceName,
                SVC + serviceName + " adds Linux capabilities",
                ctx.relativePath(),
                payload,
                ctx.provenance()));
    }

    private List<DerivedSystemModelFact> emitVolumeFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var volumesNode = serviceNode.path("volumes");
        if (!volumesNode.isArray()) {
            return List.of();
        }
        var facts = new ArrayList<DerivedSystemModelFact>();
        for (JsonNode vol : volumesNode) {
            facts.addAll(processVolumeEntry(vol, surface, relativePath, serviceName, provenance));
        }
        return facts;
    }

    private List<DerivedSystemModelFact> processVolumeEntry(
            JsonNode vol,
            String surface,
            String relativePath,
            String serviceName,
            DerivationFactProvenance provenance) {
        String hostPath = null;
        String volStr = null;
        if (vol.isTextual()) {
            volStr = vol.asText();
            var colonIdx = volStr.indexOf(':');
            hostPath = colonIdx >= 0 ? volStr.substring(0, colonIdx) : null;
        } else if (vol.isObject()) {
            var sourceNode = vol.path("source");
            if (sourceNode.isTextual()) {
                hostPath = sourceNode.asText();
                volStr = hostPath + ":" + vol.path("target").asText("");
            }
        }
        if (hostPath == null) {
            return List.of();
        }
        var ctx = new FactContext(surface, relativePath, provenance);
        var facts = new ArrayList<DerivedSystemModelFact>();
        if (hostPath.contains("/var/run/docker.sock")) {
            facts.add(buildTrustBoundaryFact(
                    ctx,
                    "container-daemon-boundary",
                    "docker-socket-mount",
                    "Docker socket mount: " + serviceName,
                    SVC + serviceName + " mounts the Docker socket",
                    "docker-sock:" + serviceName));
        }
        for (String sensitivePrefix : SENSITIVE_BIND_PREFIXES) {
            if (hostPath.equals(sensitivePrefix) || hostPath.startsWith(sensitivePrefix + "/")) {
                var key = volStr != null ? volStr : hostPath;
                facts.add(buildTrustBoundaryFact(
                        ctx,
                        "sensitive-mount-boundary",
                        "sensitive-bind-mount",
                        "Sensitive bind mount: " + serviceName,
                        SVC + serviceName + " mounts sensitive host path " + hostPath,
                        "sensitive-mount:" + serviceName + ":" + key));
                break;
            }
        }
        return facts;
    }

    private List<DerivedSystemModelFact> emitSecretFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var secretsNode = serviceNode.path("secrets");
        if (!secretsNode.isArray()) {
            return List.of();
        }
        var ctx = new FactContext(surface, relativePath, provenance);
        var facts = new ArrayList<DerivedSystemModelFact>();
        for (JsonNode secretEntry : secretsNode) {
            var secretName = resolveSecretName(secretEntry);
            if (secretName != null) {
                facts.add(buildSecretUsageFact(
                        ctx,
                        secretName,
                        "compose-secret",
                        "Compose secret: " + secretName,
                        SVC + serviceName + " uses compose secret " + secretName,
                        "compose-secret:" + serviceName + ":" + secretName));
            }
        }
        return facts;
    }

    private static String resolveSecretName(JsonNode secretEntry) {
        if (secretEntry.isTextual()) {
            return secretEntry.asText();
        }
        if (secretEntry.isObject()) {
            var sourceField = secretEntry.path("source");
            if (sourceField.isTextual()) {
                return sourceField.asText();
            }
            var fields = secretEntry.fieldNames();
            if (fields.hasNext()) {
                return fields.next();
            }
        }
        return null;
    }

    private List<DerivedSystemModelFact> emitEnvFileFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var envFileNode = serviceNode.path("env_file");
        if (envFileNode.isMissingNode() || envFileNode.isNull()) {
            return List.of();
        }
        var hasEnvFile = (envFileNode.isTextual() && !envFileNode.asText().isBlank())
                || (envFileNode.isArray() && envFileNode.size() > 0);
        if (!hasEnvFile) {
            return List.of();
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_SCOPE, "env-file");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface,
                SystemModelFactKind.SECRET_USAGE,
                provenance.adapterId(),
                relativePath,
                "env-file:" + serviceName);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                key,
                "Env file: " + serviceName,
                SVC + serviceName + " loads environment from file",
                relativePath,
                payload,
                provenance));
    }

    private List<DerivedSystemModelFact> emitEnvironmentSecretFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        // SCOPE_ENV == "environment" — use the constant for the YAML path key to avoid
        // duplicating the literal (fixes S1192).
        var envNode = serviceNode.path(SCOPE_ENV);
        if (envNode.isObject()) {
            return collectEnvObjectSecrets(envNode, surface, relativePath, serviceName, provenance);
        }
        if (envNode.isArray()) {
            return collectEnvArraySecrets(envNode, surface, relativePath, serviceName, provenance);
        }
        return List.of();
    }

    private List<DerivedSystemModelFact> collectEnvObjectSecrets(
            JsonNode envNode,
            String surface,
            String relativePath,
            String serviceName,
            DerivationFactProvenance provenance) {
        var ctx = new FactContext(surface, relativePath, provenance);
        var facts = new ArrayList<DerivedSystemModelFact>();
        var envFields = envNode.fields();
        while (envFields.hasNext()) {
            var envEntry = envFields.next();
            var key = envEntry.getKey();
            if (SECRET_LIKE.matcher(key).find()) {
                facts.add(buildSecretUsageFact(
                        ctx,
                        key,
                        SCOPE_ENV,
                        "Environment secret: " + key,
                        SVC + serviceName + " has secret-like env var " + key,
                        "env-secret:" + serviceName + ":" + key));
            }
        }
        return facts;
    }

    private List<DerivedSystemModelFact> collectEnvArraySecrets(
            JsonNode envNode,
            String surface,
            String relativePath,
            String serviceName,
            DerivationFactProvenance provenance) {
        var ctx = new FactContext(surface, relativePath, provenance);
        var facts = new ArrayList<DerivedSystemModelFact>();
        for (JsonNode item : envNode) {
            if (!item.isTextual()) {
                continue;
            }
            var text = item.asText();
            var eqIdx = text.indexOf('=');
            var key = eqIdx >= 0 ? text.substring(0, eqIdx) : text;
            if (SECRET_LIKE.matcher(key).find()) {
                facts.add(buildSecretUsageFact(
                        ctx,
                        key,
                        SCOPE_ENV,
                        "Environment secret: " + key,
                        SVC + serviceName + " has secret-like env var " + key,
                        "env-secret:" + serviceName + ":" + key));
            }
        }
        return facts;
    }

    private List<DerivedSystemModelFact> emitPortFacts(
            String surface,
            String relativePath,
            String serviceName,
            JsonNode serviceNode,
            DerivationFactProvenance provenance) {
        var portsNode = serviceNode.path("ports");
        if (!portsNode.isArray() || portsNode.size() == 0) {
            return List.of();
        }
        var portStrings = new ArrayList<String>();
        for (JsonNode port : portsNode) {
            portStrings.add(port.asText());
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "published-port");
        payload.put(IacFactKeys.EXPOSURE_PATH, portStrings);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface,
                SystemModelFactKind.EXTERNAL_INTERACTION,
                provenance.adapterId(),
                relativePath,
                "ports:" + serviceName);
        return List.of(new DerivedSystemModelFact(
                SystemModelFactKind.EXTERNAL_INTERACTION,
                key,
                "Published ports: " + serviceName,
                SVC + serviceName + " publishes ports to host",
                relativePath,
                payload,
                provenance));
    }

    /**
     * Builds a TRUST_BOUNDARY fact from a {@link FactContext} plus the fact-specific fields.
     * Replaces the old 9-parameter signature (which also carried an unused serviceName) with 6
     * parameters — callers embed the service name in the label and uniqueKeySuffix strings they
     * supply (fixes S107 and S1172).
     */
    private DerivedSystemModelFact buildTrustBoundaryFact(
            FactContext ctx,
            String artifactKind,
            String privilegedOperation,
            String label,
            String summary,
            String uniqueKeySuffix) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, ctx.surface());
        payload.put(IacFactKeys.ARTIFACT_KIND, artifactKind);
        payload.put(IacFactKeys.PRIVILEGED_OPERATION, privilegedOperation);
        payload.put(IacFactKeys.SOURCE_PATH, ctx.relativePath());
        var key = buildFactKey(
                ctx.surface(),
                SystemModelFactKind.TRUST_BOUNDARY,
                ctx.provenance().adapterId(),
                ctx.relativePath(),
                uniqueKeySuffix);
        return new DerivedSystemModelFact(
                SystemModelFactKind.TRUST_BOUNDARY, key, label, summary, ctx.relativePath(), payload, ctx.provenance());
    }

    /**
     * Builds a SECRET_USAGE fact from a {@link FactContext} plus the fact-specific fields.
     * Replaces the old 8-parameter signature with 6 parameters (fixes S107).
     */
    private DerivedSystemModelFact buildSecretUsageFact(
            FactContext ctx, String secretRef, String secretScope, String label, String summary, String uniqueKey) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, ctx.surface());
        payload.put(IacFactKeys.SECRET_REF, secretRef);
        payload.put(IacFactKeys.SECRET_SCOPE, secretScope);
        payload.put(IacFactKeys.SOURCE_PATH, ctx.relativePath());
        var factKey = buildFactKey(
                ctx.surface(),
                SystemModelFactKind.SECRET_USAGE,
                ctx.provenance().adapterId(),
                ctx.relativePath(),
                uniqueKey);
        return new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                factKey,
                label,
                summary,
                ctx.relativePath(),
                payload,
                ctx.provenance());
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

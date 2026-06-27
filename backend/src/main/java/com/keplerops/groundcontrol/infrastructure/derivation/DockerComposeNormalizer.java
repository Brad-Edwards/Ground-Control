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
import java.util.regex.Pattern;

class DockerComposeNormalizer {

    private static final Pattern SECRET_LIKE =
            Pattern.compile("(?i)(secret|password|passwd|pass|token|key|credential|cert|private|api_key|apikey|auth)");
    private static final List<String> SENSITIVE_BIND_PREFIXES = List.of("/etc", "/proc", "/sys", "/root");

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

        // 1. Emit COMPONENT for the service
        var compPayload = new LinkedHashMap<String, Object>();
        compPayload.put("surface", surface);
        compPayload.put("artifactKind", "compose-service");
        compPayload.put("serviceName", serviceName);
        compPayload.put("sourcePath", relativePath);
        var compKey = buildFactKey(
                surface, SystemModelFactKind.COMPONENT, provenance.adapterId(), relativePath, "service:" + serviceName);
        facts.add(new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                compKey,
                "Compose service: " + serviceName,
                "Docker Compose service " + serviceName,
                relativePath,
                compPayload,
                provenance));

        // 2. Image registry
        var imageNode = serviceNode.path("image");
        if (imageNode.isTextual()) {
            var imageName = imageNode.asText();
            var registryHostname = DockerfileNormalizer.extractRegistryHostname(imageName);
            if (registryHostname != null) {
                var regPayload = new LinkedHashMap<String, Object>();
                regPayload.put("surface", surface);
                regPayload.put("artifactKind", "image-registry");
                regPayload.put("registryTarget", registryHostname);
                regPayload.put("sourcePath", relativePath);
                var regKey = buildFactKey(
                        surface,
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        provenance.adapterId(),
                        relativePath,
                        "image-registry:" + serviceName + ":" + registryHostname);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        regKey,
                        "Image registry: " + registryHostname,
                        "Service " + serviceName + " pulls from external registry " + registryHostname,
                        relativePath,
                        regPayload,
                        provenance));
            }
        }

        // 3. Privileged conditions
        // privileged: true
        var privilegedNode = serviceNode.path("privileged");
        if (privilegedNode.isBoolean() && privilegedNode.asBoolean()) {
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "container-privilege-boundary");
            tbPayload.put("privilegedOperation", "privileged-container");
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    "privileged:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Privileged container: " + serviceName,
                    "Service " + serviceName + " runs as privileged container",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // cap_add
        var capAddNode = serviceNode.path("cap_add");
        if (capAddNode.isArray() && capAddNode.size() > 0) {
            var caps = new ArrayList<String>();
            for (JsonNode cap : capAddNode) {
                caps.add(cap.asText());
            }
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "container-privilege-boundary");
            tbPayload.put("privilegedOperation", "capability-add");
            tbPayload.put("securitySignals", caps);
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    "cap-add:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Capability add: " + serviceName,
                    "Service " + serviceName + " adds Linux capabilities",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // network_mode: host
        var networkModeNode = serviceNode.path("network_mode");
        if (networkModeNode.isTextual() && "host".equals(networkModeNode.asText())) {
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "network-boundary");
            tbPayload.put("privilegedOperation", "host-network");
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    "host-network:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Host network: " + serviceName,
                    "Service " + serviceName + " uses host network mode",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // pid: host
        var pidNode = serviceNode.path("pid");
        if (pidNode.isTextual() && "host".equals(pidNode.asText())) {
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "process-boundary");
            tbPayload.put("privilegedOperation", "host-pid");
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    "host-pid:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Host PID namespace: " + serviceName,
                    "Service " + serviceName + " shares host PID namespace",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // ipc: host
        var ipcNode = serviceNode.path("ipc");
        if (ipcNode.isTextual() && "host".equals(ipcNode.asText())) {
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "ipc-boundary");
            tbPayload.put("privilegedOperation", "host-ipc");
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    "host-ipc:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Host IPC: " + serviceName,
                    "Service " + serviceName + " shares host IPC namespace",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // user: root or user: "0"
        var userNode = serviceNode.path("user");
        if (userNode.isTextual()) {
            var user = userNode.asText();
            if ("root".equalsIgnoreCase(user) || "0".equals(user)) {
                var tbPayload = new LinkedHashMap<String, Object>();
                tbPayload.put("surface", surface);
                tbPayload.put("artifactKind", "user-boundary");
                tbPayload.put("privilegedOperation", "root-user");
                tbPayload.put("sourcePath", relativePath);
                var tbKey = buildFactKey(
                        surface,
                        SystemModelFactKind.TRUST_BOUNDARY,
                        provenance.adapterId(),
                        relativePath,
                        "root-user:" + serviceName);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.TRUST_BOUNDARY,
                        tbKey,
                        "Root user container: " + serviceName,
                        "Service " + serviceName + " runs as root user",
                        relativePath,
                        tbPayload,
                        provenance));
            }
        }

        // Volumes
        var volumesNode = serviceNode.path("volumes");
        if (volumesNode.isArray()) {
            for (JsonNode vol : volumesNode) {
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

                // Docker socket
                if (hostPath != null && hostPath.contains("/var/run/docker.sock")) {
                    var tbPayload = new LinkedHashMap<String, Object>();
                    tbPayload.put("surface", surface);
                    tbPayload.put("artifactKind", "container-daemon-boundary");
                    tbPayload.put("privilegedOperation", "docker-socket-mount");
                    tbPayload.put("sourcePath", relativePath);
                    var tbKey = buildFactKey(
                            surface,
                            SystemModelFactKind.TRUST_BOUNDARY,
                            provenance.adapterId(),
                            relativePath,
                            "docker-sock:" + serviceName);
                    facts.add(new DerivedSystemModelFact(
                            SystemModelFactKind.TRUST_BOUNDARY,
                            tbKey,
                            "Docker socket mount: " + serviceName,
                            "Service " + serviceName + " mounts the Docker socket",
                            relativePath,
                            tbPayload,
                            provenance));
                }

                // Sensitive bind mounts
                if (hostPath != null) {
                    for (String sensitivePrefix : SENSITIVE_BIND_PREFIXES) {
                        if (hostPath.equals(sensitivePrefix) || hostPath.startsWith(sensitivePrefix + "/")) {
                            var tbPayload = new LinkedHashMap<String, Object>();
                            tbPayload.put("surface", surface);
                            tbPayload.put("artifactKind", "sensitive-mount-boundary");
                            tbPayload.put("privilegedOperation", "sensitive-bind-mount");
                            tbPayload.put("sourcePath", relativePath);
                            var key = volStr != null ? volStr : hostPath;
                            var tbKey = buildFactKey(
                                    surface,
                                    SystemModelFactKind.TRUST_BOUNDARY,
                                    provenance.adapterId(),
                                    relativePath,
                                    "sensitive-mount:" + serviceName + ":" + key);
                            facts.add(new DerivedSystemModelFact(
                                    SystemModelFactKind.TRUST_BOUNDARY,
                                    tbKey,
                                    "Sensitive bind mount: " + serviceName,
                                    "Service " + serviceName + " mounts sensitive host path " + hostPath,
                                    relativePath,
                                    tbPayload,
                                    provenance));
                            break;
                        }
                    }
                }
            }
        }

        // 4. Secrets
        var secretsNode = serviceNode.path("secrets");
        if (secretsNode.isArray()) {
            for (JsonNode secretEntry : secretsNode) {
                String secretName = null;
                if (secretEntry.isTextual()) {
                    secretName = secretEntry.asText();
                } else if (secretEntry.isObject()) {
                    var sourceField = secretEntry.path("source");
                    secretName = sourceField.isTextual() ? sourceField.asText() : null;
                    if (secretName == null) {
                        // Try first field name as the key
                        var fields = secretEntry.fieldNames();
                        if (fields.hasNext()) secretName = fields.next();
                    }
                }
                if (secretName != null) {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("surface", surface);
                    payload.put("secretRef", secretName);
                    payload.put("secretScope", "compose-secret");
                    payload.put("sourcePath", relativePath);
                    var factKey = buildFactKey(
                            surface,
                            SystemModelFactKind.SECRET_USAGE,
                            provenance.adapterId(),
                            relativePath,
                            "compose-secret:" + serviceName + ":" + secretName);
                    facts.add(new DerivedSystemModelFact(
                            SystemModelFactKind.SECRET_USAGE,
                            factKey,
                            "Compose secret: " + secretName,
                            "Service " + serviceName + " uses compose secret " + secretName,
                            relativePath,
                            payload,
                            provenance));
                }
            }
        }

        // 5. env_file
        var envFileNode = serviceNode.path("env_file");
        if (!envFileNode.isMissingNode() && !envFileNode.isNull()) {
            boolean hasEnvFile = false;
            if (envFileNode.isTextual() && !envFileNode.asText().isBlank()) {
                hasEnvFile = true;
            } else if (envFileNode.isArray() && envFileNode.size() > 0) {
                hasEnvFile = true;
            }
            if (hasEnvFile) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("surface", surface);
                payload.put("secretScope", "env-file");
                payload.put("sourcePath", relativePath);
                var factKey = buildFactKey(
                        surface,
                        SystemModelFactKind.SECRET_USAGE,
                        provenance.adapterId(),
                        relativePath,
                        "env-file:" + serviceName);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.SECRET_USAGE,
                        factKey,
                        "Env file: " + serviceName,
                        "Service " + serviceName + " loads environment from file",
                        relativePath,
                        payload,
                        provenance));
            }
        }

        // 6. environment — scan keys for secret-like names
        var envNode = serviceNode.path("environment");
        if (envNode.isObject()) {
            var envFields = envNode.fields();
            while (envFields.hasNext()) {
                var envEntry = envFields.next();
                var key = envEntry.getKey();
                if (SECRET_LIKE.matcher(key).find()) {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("surface", surface);
                    payload.put("secretRef", key);
                    payload.put("secretScope", "environment");
                    payload.put("sourcePath", relativePath);
                    var factKey = buildFactKey(
                            surface,
                            SystemModelFactKind.SECRET_USAGE,
                            provenance.adapterId(),
                            relativePath,
                            "env-secret:" + serviceName + ":" + key);
                    facts.add(new DerivedSystemModelFact(
                            SystemModelFactKind.SECRET_USAGE,
                            factKey,
                            "Environment secret: " + key,
                            "Service " + serviceName + " has secret-like env var " + key,
                            relativePath,
                            payload,
                            provenance));
                }
            }
        } else if (envNode.isArray()) {
            // List of "KEY=VALUE" strings
            for (JsonNode item : envNode) {
                if (item.isTextual()) {
                    var text = item.asText();
                    var eqIdx = text.indexOf('=');
                    var key = eqIdx >= 0 ? text.substring(0, eqIdx) : text;
                    if (SECRET_LIKE.matcher(key).find()) {
                        var payload = new LinkedHashMap<String, Object>();
                        payload.put("surface", surface);
                        payload.put("secretRef", key);
                        payload.put("secretScope", "environment");
                        payload.put("sourcePath", relativePath);
                        var factKey = buildFactKey(
                                surface,
                                SystemModelFactKind.SECRET_USAGE,
                                provenance.adapterId(),
                                relativePath,
                                "env-secret:" + serviceName + ":" + key);
                        facts.add(new DerivedSystemModelFact(
                                SystemModelFactKind.SECRET_USAGE,
                                factKey,
                                "Environment secret: " + key,
                                "Service " + serviceName + " has secret-like env var " + key,
                                relativePath,
                                payload,
                                provenance));
                    }
                }
            }
        }

        // 7. ports
        var portsNode = serviceNode.path("ports");
        if (portsNode.isArray() && portsNode.size() > 0) {
            var portStrings = new ArrayList<String>();
            for (JsonNode port : portsNode) {
                portStrings.add(port.asText());
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("surface", surface);
            payload.put("artifactKind", "published-port");
            payload.put("exposurePath", portStrings);
            payload.put("sourcePath", relativePath);
            var factKey = buildFactKey(
                    surface,
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    provenance.adapterId(),
                    relativePath,
                    "ports:" + serviceName);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    factKey,
                    "Published ports: " + serviceName,
                    "Service " + serviceName + " publishes ports to host",
                    relativePath,
                    payload,
                    provenance));
        }

        return facts;
    }

    /**
     * Builds a stable fact key using semantic identity only: surface, factKind, adapterId,
     * sourcePath, and uniqueKey. commitSha is intentionally excluded so that the same
     * topology across different commits produces identical keys (ADR-058).
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

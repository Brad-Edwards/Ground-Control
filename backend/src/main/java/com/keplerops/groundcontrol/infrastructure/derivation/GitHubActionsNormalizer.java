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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

class GitHubActionsNormalizer {

    private static final String RUNNER_SELF_HOSTED = "self-hosted";
    private static final String TRUST_UNTRUSTED = "untrusted";

    private static final Set<String> UNTRUSTED_TRIGGERS =
            Set.of("pull_request_target", "workflow_run", "schedule", "workflow_dispatch");
    private static final Set<String> TRUSTED_ORGS = Set.of(
            "actions",
            "github",
            "docker",
            "aws-actions",
            "google-github-actions",
            "azure",
            "hashicorp",
            "slsa-framework");
    // \w matches [A-Za-z0-9_]; equivalent and preferred per S4248.
    private static final Pattern SECRET_REF_PATTERN = Pattern.compile("\\$\\{\\{\\s*secrets\\.(\\w+)\\s*\\}\\}");
    private static final Pattern DEPLOY_KEYWORD = Pattern.compile("(?i)(login|push|publish|deploy|release|registry)");

    private record RunnerInfo(String kind, String trustLevel) {}

    private final YAMLMapper yamlMapper;

    GitHubActionsNormalizer() {
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
        JsonNode root;
        try {
            root = yamlMapper.readTree(content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse GitHub Actions YAML workflow", exception);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }

        var provenance = new DerivationFactProvenance(
                adapterId, "iac-pipeline", rulesetVersion, "iac-pipeline-rules", rulesetVersion, commitSha, derivedAt);

        // Process triggers from "on:" key (YAML parses "on" as boolean true, so access via get("on"))
        var onNode = root.get("on");
        if (onNode == null) {
            onNode = root.get("true");
        }
        if (onNode != null) {
            facts.addAll(normalizeTriggers(surface, relativePath, onNode, provenance));
        }

        // Process top-level permissions
        var topPermissions = root.path("permissions");
        if (topPermissions.isObject()) {
            var secretFact = processOidcPermission(surface, relativePath, topPermissions, "top-level", provenance);
            if (secretFact != null) {
                facts.add(secretFact);
            }
        }

        // Process jobs
        var jobsNode = root.path("jobs");
        if (jobsNode.isObject()) {
            var jobFields = jobsNode.fields();
            while (jobFields.hasNext()) {
                var entry = jobFields.next();
                facts.addAll(normalizeJob(surface, relativePath, entry.getKey(), entry.getValue(), provenance));
            }
        }

        return List.copyOf(facts);
    }

    private List<DerivedSystemModelFact> normalizeTriggers(
            String surface, String relativePath, JsonNode onNode, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();
        var triggers = new LinkedHashSet<String>();

        if (onNode.isTextual()) {
            triggers.add(onNode.asText());
        } else if (onNode.isArray()) {
            for (JsonNode item : onNode) {
                if (item.isTextual()) {
                    triggers.add(item.asText());
                }
            }
        } else if (onNode.isObject()) {
            Iterator<String> fieldNames = onNode.fieldNames();
            while (fieldNames.hasNext()) {
                triggers.add(fieldNames.next());
            }
        }

        for (String triggerKind : triggers) {
            var trust = UNTRUSTED_TRIGGERS.contains(triggerKind) ? TRUST_UNTRUSTED : "trusted";
            var payload = new LinkedHashMap<String, Object>();
            payload.put(IacFactKeys.SURFACE, surface);
            payload.put("triggerKind", triggerKind);
            payload.put("triggerTrust", trust);
            payload.put(IacFactKeys.SOURCE_PATH, relativePath);
            var uniqueKey = "trigger:" + triggerKind;
            var factKey = buildFactKey(
                    surface, SystemModelFactKind.ENTRY_POINT, provenance.adapterId(), relativePath, uniqueKey);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.ENTRY_POINT,
                    factKey,
                    "GitHub Actions trigger: " + triggerKind,
                    "Workflow trigger on " + triggerKind,
                    relativePath,
                    payload,
                    provenance));
        }
        return facts;
    }

    private List<DerivedSystemModelFact> normalizeJob(
            String surface, String relativePath, String jobId, JsonNode jobNode, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();
        var runner = determineRunnerKind(jobNode);

        facts.add(emitJobComponent(surface, relativePath, jobId, runner, provenance));

        if (RUNNER_SELF_HOSTED.equals(runner.kind())) {
            facts.add(emitSelfHostedBoundary(surface, relativePath, jobId, provenance));
        }

        var jobPermissions = jobNode.path("permissions");
        if (jobPermissions.isObject()) {
            var secretFact = processOidcPermission(surface, relativePath, jobPermissions, jobId, provenance);
            if (secretFact != null) {
                facts.add(secretFact);
            }
        }

        var jobSecrets = jobNode.path("secrets");
        if (jobSecrets.isTextual() && "inherit".equals(jobSecrets.asText())) {
            facts.add(emitSecretsInherit(surface, relativePath, jobId, provenance));
        }

        var stepsNode = jobNode.path("steps");
        if (stepsNode.isArray()) {
            int stepIdx = 0;
            for (JsonNode step : stepsNode) {
                facts.addAll(normalizeStep(surface, relativePath, step, stepIdx, provenance));
                stepIdx++;
            }
        }

        return facts;
    }

    private RunnerInfo determineRunnerKind(JsonNode jobNode) {
        var runsOnNode = jobNode.path("runs-on");
        if (runsOnNode.isTextual() && RUNNER_SELF_HOSTED.equals(runsOnNode.asText())) {
            return new RunnerInfo(RUNNER_SELF_HOSTED, TRUST_UNTRUSTED);
        }
        if (runsOnNode.isArray()) {
            for (JsonNode item : runsOnNode) {
                if (RUNNER_SELF_HOSTED.equals(item.asText())) {
                    return new RunnerInfo(RUNNER_SELF_HOSTED, TRUST_UNTRUSTED);
                }
            }
        }
        return new RunnerInfo("github-hosted", "trusted");
    }

    private DerivedSystemModelFact emitJobComponent(
            String surface, String relativePath, String jobId, RunnerInfo runner, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "workflow-job");
        payload.put("jobId", jobId);
        payload.put("runnerKind", runner.kind());
        payload.put("runnerTrustLevel", runner.trustLevel());
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface, SystemModelFactKind.COMPONENT, provenance.adapterId(), relativePath, "job:" + jobId);
        return new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                key,
                "Workflow job: " + jobId,
                "CI/CD workflow job " + jobId + " running on " + runner.kind(),
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact emitSelfHostedBoundary(
            String surface, String relativePath, String jobId, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.ARTIFACT_KIND, "runner-boundary");
        payload.put("runnerTrustLevel", TRUST_UNTRUSTED);
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface,
                SystemModelFactKind.TRUST_BOUNDARY,
                provenance.adapterId(),
                relativePath,
                "runner-boundary:" + jobId);
        return new DerivedSystemModelFact(
                SystemModelFactKind.TRUST_BOUNDARY,
                key,
                "Self-hosted runner boundary: " + jobId,
                "Job " + jobId + " runs on an untrusted self-hosted runner",
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact emitSecretsInherit(
            String surface, String relativePath, String jobId, DerivationFactProvenance provenance) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(IacFactKeys.SURFACE, surface);
        payload.put(IacFactKeys.SECRET_SCOPE, "inherit");
        payload.put(IacFactKeys.SOURCE_PATH, relativePath);
        var key = buildFactKey(
                surface,
                SystemModelFactKind.SECRET_USAGE,
                provenance.adapterId(),
                relativePath,
                "secrets-inherit:" + jobId);
        return new DerivedSystemModelFact(
                SystemModelFactKind.SECRET_USAGE,
                key,
                "Inherited secrets in job: " + jobId,
                "Job " + jobId + " inherits all caller secrets",
                relativePath,
                payload,
                provenance);
    }

    private DerivedSystemModelFact processOidcPermission(
            String surface,
            String relativePath,
            JsonNode permissionsNode,
            String scope,
            DerivationFactProvenance provenance) {
        var idTokenNode = permissionsNode.path("id-token");
        if (!idTokenNode.isMissingNode() && "write".equals(idTokenNode.asText(""))) {
            var permsMap = new LinkedHashMap<String, Object>();
            var fields = permissionsNode.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                permsMap.put(entry.getKey(), entry.getValue().asText());
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put(IacFactKeys.SURFACE, surface);
            payload.put(IacFactKeys.SECRET_SCOPE, "oidc");
            payload.put(IacFactKeys.SECRET_REF, "id-token");
            payload.put("permissionSet", permsMap);
            payload.put(IacFactKeys.SOURCE_PATH, relativePath);
            var uniqueKey = "oidc-permission:" + scope;
            var factKey = buildFactKey(
                    surface, SystemModelFactKind.SECRET_USAGE, provenance.adapterId(), relativePath, uniqueKey);
            return new DerivedSystemModelFact(
                    SystemModelFactKind.SECRET_USAGE,
                    factKey,
                    "OIDC id-token write permission: " + scope,
                    "Workflow grants id-token:write for OIDC authentication in scope " + scope,
                    relativePath,
                    payload,
                    provenance);
        }
        return null;
    }

    private List<DerivedSystemModelFact> normalizeStep(
            String surface, String relativePath, JsonNode step, int stepIndex, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        // "uses" field
        var usesNode = step.path("uses");
        if (usesNode.isTextual()) {
            facts.addAll(normalizeUsesStep(surface, relativePath, usesNode.asText(), provenance));
        }

        // "name" field — deploy keyword check
        var nameNode = step.path("name");
        if (nameNode.isTextual()) {
            var stepName = nameNode.asText();
            if (DEPLOY_KEYWORD.matcher(stepName).find()) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put(IacFactKeys.SURFACE, surface);
                payload.put(IacFactKeys.ARTIFACT_KIND, "deploy-step");
                payload.put(IacFactKeys.EXPOSURE_PATH, stepName);
                payload.put(IacFactKeys.SOURCE_PATH, relativePath);
                var uniqueKey = "deploy-step:" + stepIndex + ":" + stepName;
                var factKey = buildFactKey(
                        surface, SystemModelFactKind.DATA_FLOW, provenance.adapterId(), relativePath, uniqueKey);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.DATA_FLOW,
                        factKey,
                        "Deploy step: " + stepName,
                        "Step with deployment keyword: " + stepName,
                        relativePath,
                        payload,
                        provenance));
            }
        }

        // Scan "with" and "env" maps for secret references
        facts.addAll(scanMapForSecrets(surface, relativePath, step.path("with"), "step.with", stepIndex, provenance));
        facts.addAll(scanMapForSecrets(surface, relativePath, step.path("env"), "step.env", stepIndex, provenance));

        return facts;
    }

    private List<DerivedSystemModelFact> normalizeUsesStep(
            String surface, String relativePath, String uses, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        // Parse "owner/action@ref"
        var atIndex = uses.indexOf('@');
        var actionPart = atIndex >= 0 ? uses.substring(0, atIndex) : uses;
        var slashIndex = actionPart.indexOf('/');
        var owner = slashIndex >= 0 ? actionPart.substring(0, slashIndex) : actionPart;

        if (!TRUSTED_ORGS.contains(owner.toLowerCase(Locale.ROOT))) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(IacFactKeys.SURFACE, surface);
            payload.put(IacFactKeys.ARTIFACT_KIND, "third-party-action");
            payload.put("registryTarget", actionPart);
            payload.put(IacFactKeys.SOURCE_PATH, relativePath);
            var uniqueKey = "third-party-action:" + uses;
            var factKey = buildFactKey(
                    surface, SystemModelFactKind.EXTERNAL_INTERACTION, provenance.adapterId(), relativePath, uniqueKey);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    factKey,
                    "Third-party action: " + actionPart,
                    "Workflow uses third-party action " + actionPart,
                    relativePath,
                    payload,
                    provenance));
        }

        // Check if uses name contains deploy keywords
        if (DEPLOY_KEYWORD.matcher(uses).find()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(IacFactKeys.SURFACE, surface);
            payload.put(IacFactKeys.ARTIFACT_KIND, "deploy-step");
            payload.put(IacFactKeys.EXPOSURE_PATH, uses);
            payload.put(IacFactKeys.SOURCE_PATH, relativePath);
            var uniqueKey = "deploy-action:" + uses;
            var factKey = buildFactKey(
                    surface, SystemModelFactKind.DATA_FLOW, provenance.adapterId(), relativePath, uniqueKey);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.DATA_FLOW,
                    factKey,
                    "Deploy action: " + uses,
                    "Workflow uses deploy/publish action " + uses,
                    relativePath,
                    payload,
                    provenance));
        }

        return facts;
    }

    private List<DerivedSystemModelFact> scanMapForSecrets(
            String surface,
            String relativePath,
            JsonNode mapNode,
            String exposurePath,
            int stepIndex,
            DerivationFactProvenance provenance) {
        if (mapNode == null || mapNode.isMissingNode() || !mapNode.isObject()) {
            return List.of();
        }
        var facts = new ArrayList<DerivedSystemModelFact>();
        var fields = mapNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            var value = entry.getValue().asText("");
            var matcher = SECRET_REF_PATTERN.matcher(value);
            while (matcher.find()) {
                var secretName = matcher.group(1);
                var payload = new LinkedHashMap<String, Object>();
                payload.put(IacFactKeys.SURFACE, surface);
                payload.put(IacFactKeys.SECRET_REF, secretName);
                payload.put(IacFactKeys.SECRET_SCOPE, "repository");
                payload.put(IacFactKeys.EXPOSURE_PATH, exposurePath);
                payload.put(IacFactKeys.SOURCE_PATH, relativePath);
                var uniqueKey = "secret-ref:" + secretName + ":" + exposurePath + ":" + stepIndex;
                var factKey = buildFactKey(
                        surface, SystemModelFactKind.SECRET_USAGE, provenance.adapterId(), relativePath, uniqueKey);
                facts.add(new DerivedSystemModelFact(
                        SystemModelFactKind.SECRET_USAGE,
                        factKey,
                        "Secret reference: " + secretName,
                        "Step references secret " + secretName + " via " + exposurePath,
                        relativePath,
                        payload,
                        provenance));
            }
        }
        return facts;
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

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
    private static final Pattern SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{\\{\\s*secrets\\.([A-Za-z0-9_]+)\\s*\\}\\}");
    private static final Pattern DEPLOY_KEYWORD = Pattern.compile("(?i)(login|push|publish|deploy|release|registry)");

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
            var trust = UNTRUSTED_TRIGGERS.contains(triggerKind) ? "untrusted" : "trusted";
            var payload = new LinkedHashMap<String, Object>();
            payload.put("surface", surface);
            payload.put("triggerKind", triggerKind);
            payload.put("triggerTrust", trust);
            payload.put("sourcePath", relativePath);
            var uniqueKey = "trigger:" + triggerKind;
            var factKey = buildFactKey(
                    surface,
                    SystemModelFactKind.ENTRY_POINT,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    uniqueKey);
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

        // Determine runner kind
        var runsOnNode = jobNode.path("runs-on");
        var runnerKind = "github-hosted";
        var runnerTrustLevel = "trusted";
        if (runsOnNode.isTextual() && "self-hosted".equals(runsOnNode.asText())) {
            runnerKind = "self-hosted";
            runnerTrustLevel = "untrusted";
        } else if (runsOnNode.isArray()) {
            for (JsonNode item : runsOnNode) {
                if ("self-hosted".equals(item.asText())) {
                    runnerKind = "self-hosted";
                    runnerTrustLevel = "untrusted";
                    break;
                }
            }
        }

        // Emit COMPONENT for the job
        var compPayload = new LinkedHashMap<String, Object>();
        compPayload.put("surface", surface);
        compPayload.put("artifactKind", "workflow-job");
        compPayload.put("jobId", jobId);
        compPayload.put("runnerKind", runnerKind);
        compPayload.put("runnerTrustLevel", runnerTrustLevel);
        compPayload.put("sourcePath", relativePath);
        var compKey = buildFactKey(
                surface,
                SystemModelFactKind.COMPONENT,
                provenance.adapterId(),
                relativePath,
                provenance.commitSha(),
                "job:" + jobId);
        facts.add(new DerivedSystemModelFact(
                SystemModelFactKind.COMPONENT,
                compKey,
                "Workflow job: " + jobId,
                "CI/CD workflow job " + jobId + " running on " + runnerKind,
                relativePath,
                compPayload,
                provenance));

        // Self-hosted runner → trust boundary
        if ("self-hosted".equals(runnerKind)) {
            var tbPayload = new LinkedHashMap<String, Object>();
            tbPayload.put("surface", surface);
            tbPayload.put("artifactKind", "runner-boundary");
            tbPayload.put("runnerTrustLevel", "untrusted");
            tbPayload.put("sourcePath", relativePath);
            var tbKey = buildFactKey(
                    surface,
                    SystemModelFactKind.TRUST_BOUNDARY,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    "runner-boundary:" + jobId);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.TRUST_BOUNDARY,
                    tbKey,
                    "Self-hosted runner boundary: " + jobId,
                    "Job " + jobId + " runs on an untrusted self-hosted runner",
                    relativePath,
                    tbPayload,
                    provenance));
        }

        // Per-job permissions
        var jobPermissions = jobNode.path("permissions");
        if (jobPermissions.isObject()) {
            var secretFact = processOidcPermission(surface, relativePath, jobPermissions, jobId, provenance);
            if (secretFact != null) {
                facts.add(secretFact);
            }
        }

        // secrets: inherit at job level
        var jobSecrets = jobNode.path("secrets");
        if (jobSecrets.isTextual() && "inherit".equals(jobSecrets.asText())) {
            var inheritPayload = new LinkedHashMap<String, Object>();
            inheritPayload.put("surface", surface);
            inheritPayload.put("secretScope", "inherit");
            inheritPayload.put("sourcePath", relativePath);
            var inheritKey = buildFactKey(
                    surface,
                    SystemModelFactKind.SECRET_USAGE,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    "secrets-inherit:" + jobId);
            facts.add(new DerivedSystemModelFact(
                    SystemModelFactKind.SECRET_USAGE,
                    inheritKey,
                    "Inherited secrets in job: " + jobId,
                    "Job " + jobId + " inherits all caller secrets",
                    relativePath,
                    inheritPayload,
                    provenance));
        }

        // Steps
        var stepsNode = jobNode.path("steps");
        if (stepsNode.isArray()) {
            int stepIndex = 0;
            for (JsonNode step : stepsNode) {
                facts.addAll(normalizeStep(surface, relativePath, jobId, step, stepIndex, provenance));
                stepIndex++;
            }
        }

        return facts;
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
            payload.put("surface", surface);
            payload.put("secretScope", "oidc");
            payload.put("secretRef", "id-token");
            payload.put("permissionSet", permsMap);
            payload.put("sourcePath", relativePath);
            var uniqueKey = "oidc-permission:" + scope;
            var factKey = buildFactKey(
                    surface,
                    SystemModelFactKind.SECRET_USAGE,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    uniqueKey);
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
            String surface,
            String relativePath,
            String jobId,
            JsonNode step,
            int stepIndex,
            DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        // "uses" field
        var usesNode = step.path("uses");
        if (usesNode.isTextual()) {
            var uses = usesNode.asText();
            facts.addAll(normalizeUsesStep(surface, relativePath, uses, stepIndex, provenance));
        }

        // "name" field — deploy keyword check
        var nameNode = step.path("name");
        if (nameNode.isTextual()) {
            var stepName = nameNode.asText();
            if (DEPLOY_KEYWORD.matcher(stepName).find()) {
                var payload = new LinkedHashMap<String, Object>();
                payload.put("surface", surface);
                payload.put("artifactKind", "deploy-step");
                payload.put("exposurePath", stepName);
                payload.put("sourcePath", relativePath);
                var uniqueKey = "deploy-step:" + stepIndex + ":" + stepName;
                var factKey = buildFactKey(
                        surface,
                        SystemModelFactKind.DATA_FLOW,
                        provenance.adapterId(),
                        relativePath,
                        provenance.commitSha(),
                        uniqueKey);
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
            String surface, String relativePath, String uses, int stepIndex, DerivationFactProvenance provenance) {
        var facts = new ArrayList<DerivedSystemModelFact>();

        // Parse "owner/action@ref"
        var atIndex = uses.indexOf('@');
        var actionPart = atIndex >= 0 ? uses.substring(0, atIndex) : uses;
        var slashIndex = actionPart.indexOf('/');
        var owner = slashIndex >= 0 ? actionPart.substring(0, slashIndex) : actionPart;

        if (!TRUSTED_ORGS.contains(owner.toLowerCase(Locale.ROOT))) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("surface", surface);
            payload.put("artifactKind", "third-party-action");
            payload.put("registryTarget", actionPart);
            payload.put("sourcePath", relativePath);
            var uniqueKey = "third-party-action:" + uses;
            var factKey = buildFactKey(
                    surface,
                    SystemModelFactKind.EXTERNAL_INTERACTION,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    uniqueKey);
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
            payload.put("surface", surface);
            payload.put("artifactKind", "deploy-step");
            payload.put("exposurePath", uses);
            payload.put("sourcePath", relativePath);
            var uniqueKey = "deploy-action:" + uses;
            var factKey = buildFactKey(
                    surface,
                    SystemModelFactKind.DATA_FLOW,
                    provenance.adapterId(),
                    relativePath,
                    provenance.commitSha(),
                    uniqueKey);
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
                payload.put("surface", surface);
                payload.put("secretRef", secretName);
                payload.put("secretScope", "repository");
                payload.put("exposurePath", exposurePath);
                payload.put("sourcePath", relativePath);
                var uniqueKey = "secret-ref:" + secretName + ":" + exposurePath + ":" + stepIndex;
                var factKey = buildFactKey(
                        surface,
                        SystemModelFactKind.SECRET_USAGE,
                        provenance.adapterId(),
                        relativePath,
                        provenance.commitSha(),
                        uniqueKey);
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

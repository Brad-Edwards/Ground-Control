package com.keplerops.groundcontrol.domain.research.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.research.model.MethodProfile;
import com.keplerops.groundcontrol.domain.research.model.MethodProfileSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * GC-RSCH-F006 / ADR-077 — backend-owned methodology catalog. Loads the versioned
 * method profiles from {@code classpath:research/methodology-catalog.yaml} once at
 * startup, validates them fail-closed, and exposes them as immutable reference
 * data. This is the single source of truth the methodology selection gate derives
 * its required-source set from; the skill-side mirror is kept honest by a policy
 * drift check.
 *
 * <p>The catalog is intentionally not a generic loader: it parses one bounded
 * schema (catalog version + a list of method profiles, each with a key, label,
 * version, and a non-empty required-source list) and rejects anything malformed.
 * Parse or validation failure is an {@link IllegalStateException} so the
 * application fails to start rather than serving an unknown or vacuous catalog.
 */
@Component
public final class MethodologyCatalog {

    private static final Logger log = LoggerFactory.getLogger(MethodologyCatalog.class);

    static final String DEFAULT_RESOURCE = "research/methodology-catalog.yaml";

    private static final String UNKNOWN_METHOD_CODE = "research_run_methodology_unknown_method";

    private final String catalogVersion;
    private final Map<String, MethodProfile> profilesByKey;

    @Autowired
    public MethodologyCatalog() {
        this(DEFAULT_RESOURCE);
    }

    /**
     * Load the catalog from an explicit classpath resource. Spring uses the no-arg
     * constructor (the default-constructor rule when several are present); this
     * overload exists so tests can point the loader at fixture resources to
     * exercise the fail-closed validation paths.
     */
    public MethodologyCatalog(String resourcePath) {
        var root = parse(resourcePath);
        this.catalogVersion = requireText(root.get("catalog_version"), "catalog_version");
        this.profilesByKey = buildProfiles(root, this.catalogVersion);
        log.info(
                "methodology_catalog_loaded: catalog_version={} methods={}",
                this.catalogVersion,
                this.profilesByKey.size());
    }

    private static JsonNode parse(String resourcePath) {
        var resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("Methodology catalog resource not found: " + resourcePath);
        }
        try (InputStream in = resource.getInputStream()) {
            var root = new YAMLMapper().readTree(in);
            if (root == null || !root.isObject()) {
                throw new IllegalStateException("Methodology catalog is empty or not a mapping: " + resourcePath);
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read methodology catalog: " + resourcePath, e);
        }
    }

    private static Map<String, MethodProfile> buildProfiles(JsonNode root, String catalogVersion) {
        var methods = root.get("methods");
        if (methods == null || !methods.isArray() || methods.isEmpty()) {
            throw new IllegalStateException("Methodology catalog must declare at least one method");
        }
        var byKey = new LinkedHashMap<String, MethodProfile>();
        for (var method : methods) {
            var key = requireText(method.get("key"), "method.key");
            var label = requireText(method.get("label"), "method.label (key=" + key + ")");
            var version = requireText(method.get("version"), "method.version (key=" + key + ")");
            if (byKey.containsKey(key)) {
                throw new IllegalStateException("Duplicate methodology method key: " + key);
            }
            var sources = buildSources(method.get("required_sources"), key);
            byKey.put(key, new MethodProfile(key, label, version, catalogVersion, sources));
        }
        return Map.copyOf(byKey);
    }

    private static List<MethodProfileSource> buildSources(JsonNode requiredSources, String methodKey) {
        if (requiredSources == null || !requiredSources.isArray() || requiredSources.isEmpty()) {
            throw new IllegalStateException("Method '" + methodKey + "' must declare at least one required source");
        }
        var sources = new ArrayList<MethodProfileSource>();
        for (var source : requiredSources) {
            var ref = requireText(source.get("ref"), "required_sources[].ref (key=" + methodKey + ")");
            var titleNode = source.get("title");
            var title = titleNode != null && !titleNode.isNull() ? titleNode.asText() : null;
            sources.add(new MethodProfileSource(ref, title));
        }
        return List.copyOf(sources);
    }

    private static String requireText(JsonNode node, String field) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException("Methodology catalog field is missing or blank: " + field);
        }
        return node.asText().trim();
    }

    /** Look up a method profile by key (empty when the catalog has no such method). */
    public Optional<MethodProfile> findProfile(String methodKey) {
        if (methodKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profilesByKey.get(methodKey.trim()));
    }

    /**
     * Resolve a method profile by key, or reject the selection with a
     * {@link DomainValidationException} ({@code research_run_methodology_unknown_method})
     * when the catalog does not define it.
     */
    public MethodProfile requireProfile(String methodKey) {
        return findProfile(methodKey)
                .orElseThrow(() -> new DomainValidationException(
                        "Unknown methodology method key: " + methodKey,
                        UNKNOWN_METHOD_CODE,
                        Map.of("methodKey", methodKey == null ? "" : methodKey)));
    }

    /** All method profiles in catalog order. */
    public List<MethodProfile> allProfiles() {
        return List.copyOf(profilesByKey.values());
    }

    /** The loaded catalog version. */
    public String catalogVersion() {
        return catalogVersion;
    }
}

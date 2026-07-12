package com.keplerops.groundcontrol.domain.packregistry.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.packregistry.model.PackDependency;
import com.keplerops.groundcontrol.domain.packregistry.model.PackRegistryEntry;
import com.keplerops.groundcontrol.domain.packregistry.state.PackType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PackRegistryImportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String FIELD_PACK_ID = "packId";

    private final ObjectMapper objectMapper;
    private final PackRegistryService packRegistryService;

    public PackRegistryImportService(ObjectMapper objectMapper, PackRegistryService packRegistryService) {
        this.objectMapper = objectMapper;
        this.packRegistryService = packRegistryService;
    }

    public PackRegistryEntry importEntry(UUID projectId, byte[] content, PackRegistryImportOptions options) {
        return packRegistryService.registerEntry(toRegisterCommand(projectId, content, options));
    }

    public RegisterPackCommand toRegisterCommand(UUID projectId, byte[] content, PackRegistryImportOptions options) {
        var root = parseJson(content);
        var format = detectFormat(root, options.format());
        return switch (format) {
            case GC_MANIFEST -> toManifestRegisterCommand(projectId, root, options);
            case AUTO -> throw new DomainValidationException("AUTO format must be resolved before conversion");
        };
    }

    private JsonNode parseJson(byte[] content) {
        try {
            return objectMapper.readTree(content);
        } catch (IOException e) {
            throw new DomainValidationException("Import file must be valid JSON: " + e.getMessage());
        }
    }

    private PackRegistryImportFormat detectFormat(JsonNode root, PackRegistryImportFormat requestedFormat) {
        if (requestedFormat != null && requestedFormat != PackRegistryImportFormat.AUTO) {
            return requestedFormat;
        }
        if (root.has(FIELD_PACK_ID) && root.has("packType")) {
            return PackRegistryImportFormat.GC_MANIFEST;
        }
        throw new DomainValidationException("Could not detect import format. Provide options.format as GC_MANIFEST.");
    }

    private RegisterPackCommand toManifestRegisterCommand(
            UUID projectId, JsonNode root, PackRegistryImportOptions options) {
        var packId = firstNonBlank(options.packId(), text(root, FIELD_PACK_ID));
        var packType = parsePackType(firstNonBlank(null, text(root, "packType")));
        var version = firstNonBlank(options.version(), text(root, "version"));
        var publisher = firstNonBlank(options.publisher(), text(root, "publisher"));
        var description = firstNonBlank(options.description(), text(root, "description"));
        var sourceUrl = firstNonBlank(options.sourceUrl(), text(root, "sourceUrl"));
        var checksum = firstNonBlank(options.checksum(), text(root, "checksum"));
        var signatureInfo = mergeMaps(asMap(root.get("signatureInfo")), options.signatureInfo());
        var compatibility = mergeMaps(asMap(root.get("compatibility")), options.compatibility());
        var dependencies =
                options.dependencies() != null ? options.dependencies() : parseDependencies(root.get("dependencies"));
        var provenance = mergeMaps(asMap(root.get("provenance")), options.provenance());
        var registryMetadata = mergeMaps(asMap(root.get("registryMetadata")), options.registryMetadata());

        if (packId == null) {
            throw new DomainValidationException("Imported manifest is missing packId");
        }
        if (version == null) {
            throw new DomainValidationException("Imported manifest is missing version");
        }

        return new RegisterPackCommand(
                projectId,
                packId,
                packType,
                version,
                publisher,
                description,
                sourceUrl,
                checksum,
                signatureInfo,
                compatibility,
                dependencies,
                EmptyPackRegistrationContent.INSTANCE,
                provenance,
                registryMetadata);
    }

    private PackType parsePackType(String raw) {
        if (raw == null) {
            throw new DomainValidationException("Imported manifest is missing packType");
        }
        try {
            return PackType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("Unsupported packType: " + raw);
        }
    }

    private List<PackDependency> parseDependencies(JsonNode dependenciesNode) {
        if (dependenciesNode == null || dependenciesNode.isMissingNode() || dependenciesNode.isNull()) {
            return null;
        }
        if (!dependenciesNode.isArray()) {
            throw new DomainValidationException("dependencies must be an array");
        }
        var dependencies = new ArrayList<PackDependency>();
        for (var dependency : dependenciesNode) {
            var packId = text(dependency, FIELD_PACK_ID);
            if (packId == null) {
                throw new DomainValidationException("Each dependency must include packId");
            }
            dependencies.add(new PackDependency(packId, text(dependency, "versionConstraint")));
        }
        return dependencies;
    }

    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new DomainValidationException("Expected a JSON object");
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            return value.toString();
        }
        return normalizeWhitespace(value.asText());
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return null;
        }
        var normalized = trimTrailingHorizontalWhitespaceBeforeNewlines(value.replace("\r\n", "\n"))
                .trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> overrides) {
        if ((base == null || base.isEmpty()) && (overrides == null || overrides.isEmpty())) {
            return null;
        }
        var merged = new LinkedHashMap<String, Object>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged;
    }

    private String trimTrailingHorizontalWhitespaceBeforeNewlines(String value) {
        var normalized = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (!isHorizontalWhitespace(current)) {
                normalized.append(current);
                index++;
            } else {
                int whitespaceStart = index;
                while (index < value.length() && isHorizontalWhitespace(value.charAt(index))) {
                    index++;
                }
                if (index >= value.length() || value.charAt(index) != '\n') {
                    normalized.append(value, whitespaceStart, index);
                }
            }
        }
        return normalized.toString();
    }

    private boolean isHorizontalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }
}

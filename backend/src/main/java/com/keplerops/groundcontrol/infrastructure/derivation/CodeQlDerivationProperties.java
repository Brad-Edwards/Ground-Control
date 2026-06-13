package com.keplerops.groundcontrol.infrastructure.derivation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groundcontrol.derivation.codeql", ignoreUnknownFields = false)
public class CodeQlDerivationProperties {

    private static final Pattern PINNED_PACK =
            Pattern.compile("^[a-z0-9_.-]+/[a-z0-9_.-]+@\\d+\\.\\d+\\.\\d+(?:[-+][a-z0-9_.-]+)?$");

    private boolean enabled = true;
    private String cliPath = "codeql";
    private Path repositoryRoot = Path.of(".");
    private Duration timeout = Duration.ofMinutes(10);
    private long maxOutputBytes = 20L * 1024L * 1024L;
    private boolean buildModeNone = true;
    private Map<String, String> queryPacks = defaultQueryPacks();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCliPath() {
        return cliPath;
    }

    public void setCliPath(String cliPath) {
        this.cliPath = cliPath;
    }

    public Path getRepositoryRoot() {
        return repositoryRoot;
    }

    public void setRepositoryRoot(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public long getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(long maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public boolean isBuildModeNone() {
        return buildModeNone;
    }

    public void setBuildModeNone(boolean buildModeNone) {
        this.buildModeNone = buildModeNone;
    }

    public Map<String, String> getQueryPacks() {
        return Map.copyOf(queryPacks);
    }

    public void setQueryPacks(Map<String, String> queryPacks) {
        var normalized = new LinkedHashMap<String, String>();
        if (queryPacks != null) {
            queryPacks.forEach((language, queryPack) -> {
                if (language != null && queryPack != null) {
                    normalized.put(normalizeLanguage(language), queryPack.trim());
                }
            });
        }
        this.queryPacks = normalized;
    }

    Optional<String> queryPackFor(String projectIdentifier, String language) {
        var normalized = normalizeLanguage(language);
        var projectScopedKey = normalizeKey(projectIdentifier) + "." + normalized;
        var queryPack = queryPacks.get(projectScopedKey);
        if (queryPack == null) {
            queryPack = queryPacks.get(normalized);
        }
        if (queryPack == null && "typescript".equals(normalized)) {
            queryPack = queryPacks.get(normalizeKey(projectIdentifier) + ".javascript");
        }
        if (queryPack == null && "typescript".equals(normalized)) {
            queryPack = queryPacks.get("javascript");
        }
        return Optional.ofNullable(queryPack).filter(value -> !value.isBlank());
    }

    boolean hasAnyPinnedQueryPack() {
        return queryPacks.values().stream().anyMatch(CodeQlDerivationProperties::isPinnedQueryPack);
    }

    static boolean isPinnedQueryPack(String queryPack) {
        return queryPack != null
                && PINNED_PACK
                        .matcher(queryPack.trim().toLowerCase(Locale.ROOT))
                        .matches();
    }

    static String normalizeLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> defaultQueryPacks() {
        var defaults = new LinkedHashMap<String, String>();
        defaults.put("java", "codeql/java-queries@1.10.1");
        defaults.put("javascript", "codeql/javascript-queries@2.2.1");
        defaults.put("typescript", "codeql/javascript-queries@2.2.1");
        defaults.put("python", "codeql/python-queries@1.6.1");
        return defaults;
    }
}

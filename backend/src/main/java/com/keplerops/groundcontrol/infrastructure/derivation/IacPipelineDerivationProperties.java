package com.keplerops.groundcontrol.infrastructure.derivation;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groundcontrol.derivation.iac", ignoreUnknownFields = false)
public class IacPipelineDerivationProperties {

    private boolean enabled = true;
    private Path repositoryRoot = Path.of(".");
    private long maxFileBytes = 512L * 1024L;
    private int maxFiles = 1000;
    private List<String> excludedPaths =
            List.of(".git", ".gc", ".claude/worktrees", "node_modules", "dist", "target", "build");
    private List<String> enabledSurfaces = List.of("github-actions", "dockerfile", "docker-compose", "terraform");
    private String rulesetVersion = "1.0.0";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getRepositoryRoot() {
        return repositoryRoot;
    }

    public void setRepositoryRoot(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths == null ? List.of() : List.copyOf(excludedPaths);
    }

    public List<String> getEnabledSurfaces() {
        return enabledSurfaces;
    }

    public void setEnabledSurfaces(List<String> enabledSurfaces) {
        this.enabledSurfaces = enabledSurfaces == null ? List.of() : List.copyOf(enabledSurfaces);
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public void setRulesetVersion(String rulesetVersion) {
        this.rulesetVersion = rulesetVersion;
    }
}

package com.keplerops.groundcontrol.infrastructure.derivation;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "groundcontrol.derivation.boundary", ignoreUnknownFields = false)
public class BoundaryDerivationProperties {

    private boolean enabled = true;
    private Path repositoryRoot = Path.of(".");
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

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public void setRulesetVersion(String rulesetVersion) {
        this.rulesetVersion = rulesetVersion;
    }
}

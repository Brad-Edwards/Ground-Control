package com.keplerops.groundcontrol.domain.identity.state;

/**
 * Closed, versioned product permission catalog from ADR-085. Role rows may bundle these keys,
 * but administrators cannot create new permission strings or executable policy expressions.
 */
public enum PermissionKey {
    API_ACCESS("Use authenticated REST and MCP surfaces", false),
    IDENTITY_ADMIN("Administer identity users, groups, roles, and grants", false),
    EMBEDDINGS_ADMIN("Administer embeddings", false),
    ANALYSIS_SWEEP("Run privileged analysis sweeps", false),
    PACK_REGISTRY_ADMIN("Administer pack registry, trust policy, and install records", false),
    MCP_USAGE_READ("Read cross-project MCP usage telemetry", false),
    WORKFLOW_RUN_CROSS_PROJECT_READ("Read cross-project workflow-run telemetry", false),
    RESEARCH_OPERATION_AUTHORIZE("Decide and consume research operation authorizations", true),
    PROJECT_READ("Read an admitted project", true),
    PROJECT_WRITE("Change an admitted project", true),
    PROJECT_ACCESS_ADMIN("Administer admission to an existing project", true);

    public static final int CATALOG_VERSION = 1;

    private final String description;
    private final boolean projectCapable;

    PermissionKey(String description, boolean projectCapable) {
        this.description = description;
        this.projectCapable = projectCapable;
    }

    public String getDescription() {
        return description;
    }

    public boolean isProjectCapable() {
        return projectCapable;
    }
}

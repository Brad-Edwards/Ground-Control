package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/**
 * Input to the project-scoped repository-binding resolution activity. Schema:
 * {@code gc.workflow.repository-binding.v1#/$defs/ResolveRepositoryBindingInput}.
 */
public record ResolveRepositoryBindingInput(String project) {}

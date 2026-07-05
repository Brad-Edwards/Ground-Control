package com.keplerops.groundcontrol.infrastructure.temporal.implement.contract;

/** Activity payload. Schema: {@code gc.workflow.merged-artifacts.v1#/$defs/MergedArtifactsInput}. */
public record MergedArtifactsInput(RepositoryBinding repository, int prNumber) {}

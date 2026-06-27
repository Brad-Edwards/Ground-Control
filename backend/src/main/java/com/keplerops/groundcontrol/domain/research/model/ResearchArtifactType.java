package com.keplerops.groundcontrol.domain.research.model;

/**
 * GC-RSCH-F003 / ADR-064 — closed set of research-run lifecycle artifact types,
 * one per {@link ResearchRunStage}. An artifact record is the durable manifest
 * entry that proves a stage produced its output and unblocks the next stage; it
 * is lifecycle metadata, never the artifact content itself.
 *
 * <p>The artifact-type to producing-stage mapping is declared one-directionally
 * on {@link ResearchRunStage#outputArtifactType()} to avoid a mutual enum
 * static-initialisation cycle; {@link #producingStage()} resolves it lazily.
 */
public enum ResearchArtifactType {
    METHODOLOGY_REQUIREMENTS,
    PROTOCOL_PLAN,
    SEARCH_LOG,
    SCREENING_RESULT,
    CHARTING_DATA,
    SYNTHESIS,
    ARGUMENT_MAP,
    MANUSCRIPT;

    /** The stage that produces this artifact type. */
    public ResearchRunStage producingStage() {
        for (var stage : ResearchRunStage.values()) {
            if (stage.outputArtifactType() == this) {
                return stage;
            }
        }
        throw new IllegalStateException("No producing stage for artifact type " + this);
    }
}

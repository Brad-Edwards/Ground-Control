package com.keplerops.groundcontrol.domain.research.model;

import java.util.Optional;

/**
 * GC-RSCH-R001 / ADR-064 — the eight distinct lifecycle stages of a research run.
 *
 * <p>The vocabulary is closed and explicit; it intentionally does not reuse
 * {@code workflow_phase_event.phase} strings or the lit-review skills' phase
 * numbers. Stages run strictly in declared order (the {@code sequence} field is
 * the ordering authority — never {@link Enum#ordinal()}). Each non-entry stage
 * names the predecessor artifact that must be present and ACTIVE before it may
 * start (the prerequisite matrix), and each stage names the single output
 * artifact it produces. The service layer is the sole authority for advancing
 * the stage; controllers and MCP handlers never re-implement this graph.
 */
public enum ResearchRunStage {
    METHODOLOGY_SELECTION(1, ResearchArtifactType.METHODOLOGY_REQUIREMENTS),
    PROTOCOL_PLANNING(2, ResearchArtifactType.PROTOCOL_PLAN),
    SOURCE_SEARCH(3, ResearchArtifactType.SEARCH_LOG),
    SCREENING(4, ResearchArtifactType.SCREENING_RESULT),
    CHARTING(5, ResearchArtifactType.CHARTING_DATA),
    SYNTHESIS(6, ResearchArtifactType.SYNTHESIS),
    ARGUMENT_CONSTRUCTION(7, ResearchArtifactType.ARGUMENT_MAP),
    PROSE_DRAFTING(8, ResearchArtifactType.MANUSCRIPT);

    private final int sequence;
    private final ResearchArtifactType outputArtifactType;

    ResearchRunStage(int sequence, ResearchArtifactType outputArtifactType) {
        this.sequence = sequence;
        this.outputArtifactType = outputArtifactType;
    }

    /** 1-based position in the lifecycle; the ordering authority. */
    public int sequence() {
        return sequence;
    }

    /** The single artifact type this stage produces (its completion evidence). */
    public ResearchArtifactType outputArtifactType() {
        return outputArtifactType;
    }

    /** The stage that immediately follows this one, or empty for the final stage. */
    public Optional<ResearchRunStage> next() {
        return bySequence(sequence + 1);
    }

    /** The stage immediately preceding this one, or empty for the entry stage. */
    public Optional<ResearchRunStage> previous() {
        return bySequence(sequence - 1);
    }

    /** Whether this stage is at or beyond {@code other} in the lifecycle. */
    public boolean isAtOrAfter(ResearchRunStage other) {
        return this.sequence >= other.sequence;
    }

    /** Whether this is the final stage (no successor). */
    public boolean isFinal() {
        return next().isEmpty();
    }

    /**
     * The artifact that the predecessor stage must have produced before this
     * stage may start. Empty for the entry stage, which only requires a started
     * run.
     */
    public Optional<ResearchArtifactType> requiredPredecessorArtifact() {
        return previous().map(ResearchRunStage::outputArtifactType);
    }

    private static Optional<ResearchRunStage> bySequence(int sequence) {
        for (var stage : values()) {
            if (stage.sequence == sequence) {
                return Optional.of(stage);
            }
        }
        return Optional.empty();
    }
}

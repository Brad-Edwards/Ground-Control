package com.keplerops.groundcontrol.domain.interchange.service;

/**
 * Per-entity-kind counts from a {@code GrcInterchangeImporter} run.
 *
 * <p>{@code created} and {@code updated} reflect domain-entity changes;
 * {@code provenanceWritten} reflects {@code GrcInterchangeProvenance} rows
 * written or refreshed. The two diverge only when the import is idempotent
 * (re-import of the same bundle increments provenance refresh count without
 * creating duplicate domain entities).
 */
public record GrcInterchangeImportResult(
        int assetsCreated,
        int assetsUpdated,
        int riskScenariosCreated,
        int riskScenariosUpdated,
        int controlsCreated,
        int controlsUpdated,
        int findingsCreated,
        int findingsUpdated,
        int evidenceArtifactsCreated,
        int evidenceArtifactsUpdated,
        int provenanceWritten) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int assetsCreated;
        private int assetsUpdated;
        private int riskScenariosCreated;
        private int riskScenariosUpdated;
        private int controlsCreated;
        private int controlsUpdated;
        private int findingsCreated;
        private int findingsUpdated;
        private int evidenceArtifactsCreated;
        private int evidenceArtifactsUpdated;
        private int provenanceWritten;

        public Builder assetCreated() {
            assetsCreated++;
            return this;
        }

        public Builder assetUpdated() {
            assetsUpdated++;
            return this;
        }

        public Builder riskScenarioCreated() {
            riskScenariosCreated++;
            return this;
        }

        public Builder riskScenarioUpdated() {
            riskScenariosUpdated++;
            return this;
        }

        public Builder controlCreated() {
            controlsCreated++;
            return this;
        }

        public Builder controlUpdated() {
            controlsUpdated++;
            return this;
        }

        public Builder findingCreated() {
            findingsCreated++;
            return this;
        }

        public Builder findingUpdated() {
            findingsUpdated++;
            return this;
        }

        public Builder evidenceArtifactCreated() {
            evidenceArtifactsCreated++;
            return this;
        }

        public Builder evidenceArtifactUpdated() {
            evidenceArtifactsUpdated++;
            return this;
        }

        public Builder provenanceWritten() {
            provenanceWritten++;
            return this;
        }

        public GrcInterchangeImportResult build() {
            return new GrcInterchangeImportResult(
                    assetsCreated,
                    assetsUpdated,
                    riskScenariosCreated,
                    riskScenariosUpdated,
                    controlsCreated,
                    controlsUpdated,
                    findingsCreated,
                    findingsUpdated,
                    evidenceArtifactsCreated,
                    evidenceArtifactsUpdated,
                    provenanceWritten);
        }
    }
}

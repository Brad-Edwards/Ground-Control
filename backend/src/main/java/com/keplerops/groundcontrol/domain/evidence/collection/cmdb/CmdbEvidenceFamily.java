package com.keplerops.groundcontrol.domain.evidence.collection.cmdb;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilyDescriptor;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceFamilySpec;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import java.util.List;

/**
 * The five CMDB and asset-management evidence families GC-S004 requires an adapter to be
 * capable of collecting across ServiceNow, Snipe-IT, and Jamf.
 *
 * <p>Each family maps onto the GC-S001 collection port as data, not as a dedicated Java
 * interface: it carries an {@link EvidenceFamilySpec} with a canonical {@code scopeType}
 * (carried in {@code EvidenceCollectionScope}) and a canonical {@code schemaId} (carried in
 * {@code EvidenceCollectionOutputSchema}). Every family is summarized as
 * {@link EvidenceType#OBSERVATION_SUMMARY} — an adapter must not present asset inventory,
 * configuration-item, patch, license, or end-of-life posture as a control-effectiveness
 * conclusion. {@link #summaryFields()} names the bounded, normalized fields a collected
 * summary carries; raw provider exports, full asset/device inventories, license keys,
 * serial-number lists, installed-software dumps, and custom-field payloads stay out.
 */
public enum CmdbEvidenceFamily implements EvidenceFamilyDescriptor {
    ASSET_INVENTORY(new EvidenceFamilySpec(
            "cmdb-asset-inventory",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.ASSET_SOURCE_REF,
                    "totalAssetCount",
                    "activeAssetCount",
                    "inactiveAssetCount",
                    "unmanagedAssetCount",
                    Field.EVALUATED_THROUGH))),
    CI_STATUS(new EvidenceFamilySpec(
            "cmdb-ci-status",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    "configurationItemRef",
                    "ciClass",
                    "operationalCount",
                    "nonOperationalCount",
                    "retiredCount",
                    Field.EVALUATED_THROUGH))),
    PATCH_LEVEL(new EvidenceFamilySpec(
            "cmdb-patch-level",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.ASSET_SOURCE_REF,
                    "patchBaselineRef",
                    "compliantAssetCount",
                    "missingPatchCount",
                    "stalePatchCount",
                    Field.EVALUATED_THROUGH))),
    LICENSE_COMPLIANCE(new EvidenceFamilySpec(
            "cmdb-license-compliance",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    "licenseRef",
                    "compliantSeatCount",
                    "noncompliantSeatCount",
                    "overAllocatedSeatCount",
                    "underAllocatedSeatCount",
                    Field.EVALUATED_THROUGH))),
    EOL_TRACKING(new EvidenceFamilySpec(
            "cmdb-eol-tracking",
            EvidenceType.OBSERVATION_SUMMARY,
            List.of(
                    Field.ASSET_SOURCE_REF,
                    "supportedCount",
                    "endOfLifeCount",
                    "endOfSupportCount",
                    "unknownLifecycleCount",
                    Field.EVALUATED_THROUGH)));

    /** Summary-field tokens shared across families; defined once so the literals are not duplicated. */
    private static final class Field {
        private static final String ASSET_SOURCE_REF = "assetSourceRef";
        private static final String EVALUATED_THROUGH = "evaluatedThrough";

        private Field() {}
    }

    private final EvidenceFamilySpec spec;

    CmdbEvidenceFamily(EvidenceFamilySpec spec) {
        this.spec = spec;
    }

    @Override
    public EvidenceFamilySpec familySpec() {
        return spec;
    }

    /** Resolves a family by its canonical scope type, surfacing an unsupported category. */
    public static CmdbEvidenceFamily fromScopeType(String scopeType) {
        return EvidenceFamilyDescriptor.resolveByScopeType(
                values(), scopeType, "Unsupported CMDB evidence family scope: ");
    }
}

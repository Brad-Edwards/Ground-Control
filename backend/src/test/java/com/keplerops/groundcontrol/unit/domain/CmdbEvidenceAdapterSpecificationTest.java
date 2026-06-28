package com.keplerops.groundcontrol.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionAdapter;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionError;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionOutputSchema;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRateLimit;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionRequest;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionResult;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionScope;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceCollectionStatus;
import com.keplerops.groundcontrol.domain.evidence.collection.EvidenceConnectionConfig;
import com.keplerops.groundcontrol.domain.evidence.collection.cmdb.CmdbEvidenceFamily;
import com.keplerops.groundcontrol.domain.evidence.collection.cmdb.CmdbEvidenceProvider;
import com.keplerops.groundcontrol.domain.evidence.collection.cmdb.CmdbEvidenceSpecification;
import com.keplerops.groundcontrol.domain.evidence.model.EvidenceSourceRef;
import com.keplerops.groundcontrol.domain.evidence.service.CreateEvidenceArtifactCommand;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceSourceKind;
import com.keplerops.groundcontrol.domain.evidence.state.EvidenceType;
import com.keplerops.groundcontrol.domain.exception.DomainValidationException;
import com.keplerops.groundcontrol.domain.plugins.service.PluginDescriptor;
import com.keplerops.groundcontrol.domain.plugins.state.PluginType;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Conformance specification for GC-S004 CMDB and asset-management evidence adapters. Verifies
 * the normative provider/family/schema/capability contract and that a stub adapter built
 * directly on the GC-S001 {@link EvidenceCollectionAdapter} port (no CMDB sub-interface)
 * conforms.
 */
class CmdbEvidenceAdapterSpecificationTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000213");
    private static final Instant NOW = Instant.parse("2026-06-28T08:00:00Z");
    private static final EvidenceCollectionRateLimit RATE_LIMIT =
            new EvidenceCollectionRateLimit(600, Duration.ofMinutes(1), 599, NOW.plusSeconds(60));

    /** Minimal conforming adapter built directly on the GC-S001 port (no CMDB sub-interface). */
    static final class StubCmdbEvidenceAdapter implements EvidenceCollectionAdapter {

        private final CmdbEvidenceProvider provider;
        private final CmdbEvidenceFamily family;
        private final boolean simulatePartial;

        StubCmdbEvidenceAdapter(CmdbEvidenceProvider provider, CmdbEvidenceFamily family) {
            this(provider, family, false);
        }

        StubCmdbEvidenceAdapter(CmdbEvidenceProvider provider, CmdbEvidenceFamily family, boolean simulatePartial) {
            this.provider = provider;
            this.family = family;
            this.simulatePartial = simulatePartial;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    provider.key() + "-cmdb-evidence",
                    "1.0.0",
                    "Collects CMDB and asset evidence for " + provider.key(),
                    PluginType.EVIDENCE_COLLECTOR,
                    CmdbEvidenceSpecification.capabilitiesFor(provider),
                    Map.of("provider", provider.key()));
        }

        @Override
        public EvidenceCollectionOutputSchema outputSchema() {
            return CmdbEvidenceSpecification.outputSchema(family);
        }

        @Override
        public EvidenceCollectionRateLimit rateLimitPolicy() {
            return RATE_LIMIT;
        }

        @Override
        public EvidenceCollectionResult collect(EvidenceCollectionRequest request) {
            CmdbEvidenceFamily requested =
                    CmdbEvidenceFamily.fromScopeType(request.scope().scopeType());
            if (simulatePartial) {
                // A throttled provider must surface partial collection via status + errors,
                // never as a clean empty SUCCEEDED.
                var error = new EvidenceCollectionError(
                        "rate_limited", "Provider throttled", "table:cmdb_ci", true, Map.of());
                return new EvidenceCollectionResult(
                        descriptor().name(),
                        "1.0.0",
                        EvidenceCollectionStatus.RATE_LIMITED,
                        CmdbEvidenceSpecification.outputSchema(requested),
                        NOW,
                        List.of(),
                        List.of(),
                        List.of(error),
                        RATE_LIMIT);
            }
            var command = new CreateEvidenceArtifactCommand(
                    request.projectId(),
                    "EVID-CMDB-" + requested.name(),
                    "CMDB " + requested.scopeType() + " summary",
                    "Bounded CMDB and asset summary; counts and external references only",
                    requested.evidenceType(),
                    descriptor().name() + "-v1",
                    NOW,
                    null,
                    "HIGH",
                    null,
                    List.of(new EvidenceSourceRef(
                            EvidenceSourceKind.EXTERNAL, null, provider.key() + ":asset/AST-1", "primary")));
            return new EvidenceCollectionResult(
                    descriptor().name(),
                    "1.0.0",
                    EvidenceCollectionStatus.SUCCEEDED,
                    CmdbEvidenceSpecification.outputSchema(requested),
                    NOW,
                    List.of(command),
                    List.of(provider.key() + ":asset/AST-1"),
                    List.of(),
                    RATE_LIMIT);
        }
    }

    @Nested
    class Providers {

        @Test
        void declaresServiceNowSnipeItAndJamf() {
            assertThat(CmdbEvidenceSpecification.supportedProviders())
                    .containsExactlyInAnyOrder(
                            CmdbEvidenceProvider.SERVICENOW, CmdbEvidenceProvider.SNIPE_IT, CmdbEvidenceProvider.JAMF);
            assertThat(CmdbEvidenceProvider.SERVICENOW.key()).isEqualTo("servicenow");
            assertThat(CmdbEvidenceProvider.SNIPE_IT.key()).isEqualTo("snipe-it");
            assertThat(CmdbEvidenceProvider.JAMF.key()).isEqualTo("jamf");
            assertThat(CmdbEvidenceProvider.SERVICENOW.capabilityToken()).isEqualTo("provider:servicenow");
        }

        @Test
        void resolvesByKeyCaseInsensitively() {
            assertThat(CmdbEvidenceProvider.fromKey("ServiceNow")).isEqualTo(CmdbEvidenceProvider.SERVICENOW);
            assertThat(CmdbEvidenceProvider.fromKey("SNIPE-IT")).isEqualTo(CmdbEvidenceProvider.SNIPE_IT);
        }

        @Test
        void rejectsUnsupportedProvider() {
            assertThatThrownBy(() -> CmdbEvidenceProvider.fromKey("lansweeper"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported CMDB evidence provider");
        }
    }

    @Nested
    class Families {

        @Test
        void declaresAllFiveFamiliesAsObservationSummaries() {
            assertThat(CmdbEvidenceSpecification.supportedFamilies())
                    .containsExactlyInAnyOrder(
                            CmdbEvidenceFamily.ASSET_INVENTORY,
                            CmdbEvidenceFamily.CI_STATUS,
                            CmdbEvidenceFamily.PATCH_LEVEL,
                            CmdbEvidenceFamily.LICENSE_COMPLIANCE,
                            CmdbEvidenceFamily.EOL_TRACKING);
            assertThat(CmdbEvidenceFamily.values()).allSatisfy(family -> {
                assertThat(family.evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
                assertThat(family.scopeType()).startsWith("cmdb-");
                assertThat(family.schemaId()).isEqualTo(family.scopeType());
                assertThat(family.summaryFields()).isNotEmpty();
                assertThat(family.capabilityToken()).isEqualTo("family:" + family.scopeType());
            });
        }

        @Test
        void scopeTypesAreDistinct() {
            long distinct = Arrays.stream(CmdbEvidenceFamily.values())
                    .map(CmdbEvidenceFamily::scopeType)
                    .distinct()
                    .count();
            assertThat(distinct).isEqualTo(CmdbEvidenceFamily.values().length);
        }

        @Test
        void resolvesByScopeType() {
            assertThat(CmdbEvidenceFamily.fromScopeType("cmdb-patch-level")).isEqualTo(CmdbEvidenceFamily.PATCH_LEVEL);
        }

        @Test
        void rejectsUnsupportedScope() {
            assertThatThrownBy(() -> CmdbEvidenceFamily.fromScopeType("cmdb-unknown"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported CMDB evidence family");
        }
    }

    @Nested
    class OutputSchemas {

        @ParameterizedTest
        @EnumSource(CmdbEvidenceFamily.class)
        void buildsVersionedSchemaMetadataForEveryFamily(CmdbEvidenceFamily family) {
            // schemaId / schemaVersion / evidenceType are independently derived and add genuine
            // coverage here. The canonical field set is pinned exhaustively, with spec-literal
            // expectations, by pinsCanonicalFieldNamesPerFamilyAsLiterals — asserting payloadShape
            // against family.summaryFields() here would only re-derive the same source twice.
            var schema = CmdbEvidenceSpecification.outputSchema(family);
            assertThat(schema.schemaId()).isEqualTo(family.schemaId());
            assertThat(schema.schemaVersion()).isEqualTo("1.0.0");
            assertThat(schema.evidenceType()).isEqualTo(family.evidenceType());
        }

        @Test
        void pinsCanonicalFieldNamesPerFamilyAsLiterals() {
            // Expected field sets are literals drawn from the GC-S004 spec, NOT from the enum
            // under test, so a regression in any family's summaryFields() is caught here rather
            // than silently passing a derived-expectation wiring check.
            Map<CmdbEvidenceFamily, List<String>> expected = new LinkedHashMap<>();
            expected.put(
                    CmdbEvidenceFamily.ASSET_INVENTORY,
                    List.of(
                            "assetSourceRef",
                            "totalAssetCount",
                            "activeAssetCount",
                            "inactiveAssetCount",
                            "unmanagedAssetCount",
                            "evaluatedThrough"));
            expected.put(
                    CmdbEvidenceFamily.CI_STATUS,
                    List.of(
                            "configurationItemRef",
                            "ciClass",
                            "operationalCount",
                            "nonOperationalCount",
                            "retiredCount",
                            "evaluatedThrough"));
            expected.put(
                    CmdbEvidenceFamily.PATCH_LEVEL,
                    List.of(
                            "assetSourceRef",
                            "patchBaselineRef",
                            "compliantAssetCount",
                            "missingPatchCount",
                            "stalePatchCount",
                            "evaluatedThrough"));
            expected.put(
                    CmdbEvidenceFamily.LICENSE_COMPLIANCE,
                    List.of(
                            "licenseRef",
                            "compliantSeatCount",
                            "noncompliantSeatCount",
                            "overAllocatedSeatCount",
                            "underAllocatedSeatCount",
                            "evaluatedThrough"));
            expected.put(
                    CmdbEvidenceFamily.EOL_TRACKING,
                    List.of(
                            "assetSourceRef",
                            "supportedCount",
                            "endOfLifeCount",
                            "endOfSupportCount",
                            "unknownLifecycleCount",
                            "evaluatedThrough"));

            // Forces a new family to add its literal expectation here rather than ride the loop.
            assertThat(expected).containsOnlyKeys(CmdbEvidenceFamily.values());
            expected.forEach((family, fields) -> {
                assertThat(family.summaryFields())
                        .as("summaryFields for %s", family)
                        .containsExactlyElementsOf(fields);
                assertThat(CmdbEvidenceSpecification.outputSchema(family)
                                .payloadShape()
                                .keySet())
                        .as("payloadShape keys for %s", family)
                        .containsExactlyInAnyOrderElementsOf(fields);
            });
        }

        @Test
        void exposesAllFiveSchemasInOrder() {
            assertThat(CmdbEvidenceSpecification.outputSchemas())
                    .hasSize(5)
                    .extracting(EvidenceCollectionOutputSchema::schemaId)
                    .containsExactly(
                            "cmdb-asset-inventory",
                            "cmdb-ci-status",
                            "cmdb-patch-level",
                            "cmdb-license-compliance",
                            "cmdb-eol-tracking");
        }

        @Test
        void rejectsNullFamily() {
            assertThatThrownBy(() -> CmdbEvidenceSpecification.outputSchema(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Capabilities {

        @Test
        void includesCmdbProviderAndFamilyTokens() {
            var capabilities = CmdbEvidenceSpecification.capabilitiesFor(CmdbEvidenceProvider.SERVICENOW);
            assertThat(capabilities)
                    .contains(
                            "evidence:cmdb",
                            "provider:servicenow",
                            "family:cmdb-asset-inventory",
                            "family:cmdb-ci-status",
                            "family:cmdb-patch-level",
                            "family:cmdb-license-compliance",
                            "family:cmdb-eol-tracking");
        }

        @Test
        void rejectsNullProvider() {
            assertThatThrownBy(() -> CmdbEvidenceSpecification.capabilitiesFor(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Conformance {

        @Test
        void acceptsConformantAdapterDescriptor() {
            var adapter =
                    new StubCmdbEvidenceAdapter(CmdbEvidenceProvider.SERVICENOW, CmdbEvidenceFamily.ASSET_INVENTORY);
            assertThat(adapter.descriptor().type()).isEqualTo(PluginType.EVIDENCE_COLLECTOR);
            assertThat(adapter.descriptor().capabilities()).contains(CmdbEvidenceSpecification.CAPABILITY_CMDB);
            CmdbEvidenceSpecification.requireConformant(adapter.descriptor());
        }

        @Test
        void rejectsNonEvidenceCollector() {
            var bad = new PluginDescriptor("x", "1.0.0", "", PluginType.VALIDATOR, Set.of("evidence:cmdb"), Map.of());
            assertThatThrownBy(() -> CmdbEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("EVIDENCE_COLLECTOR");
        }

        @Test
        void rejectsMissingCmdbCapability() {
            var bad = new PluginDescriptor(
                    "x", "1.0.0", "", PluginType.EVIDENCE_COLLECTOR, Set.of("scope:other"), Map.of());
            assertThatThrownBy(() -> CmdbEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("evidence:cmdb");
        }

        @Test
        void rejectsNullDescriptor() {
            assertThatThrownBy(() -> CmdbEvidenceSpecification.requireConformant(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class CollectionConformance {

        @Test
        void collectsBoundedExternalSummariesForEachFamily() {
            for (CmdbEvidenceFamily family : CmdbEvidenceFamily.values()) {
                var adapter = new StubCmdbEvidenceAdapter(CmdbEvidenceProvider.SERVICENOW, family);
                var result = adapter.collect(request(family));
                assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
                assertThat(result.schema().schemaId()).isEqualTo(family.schemaId());
                assertThat(result.artifacts()).singleElement().satisfies(artifact -> {
                    assertThat(artifact.evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
                    assertThat(artifact.sources()).singleElement().satisfies(source -> assertThat(source.sourceKind())
                            .isEqualTo(EvidenceSourceKind.EXTERNAL));
                });
            }
        }

        @Test
        void providerErrorDetailStripsSecrets() {
            var error = new EvidenceCollectionError(
                    "provider_forbidden",
                    "Missing read permission",
                    "table:cmdb_ci",
                    false,
                    Map.of(
                            "family", "cmdb-asset-inventory",
                            "apiToken", "raw-token-value",
                            "basicAuthPassword", "raw-password-value"));
            assertThat(error.detail())
                    .containsEntry("family", "cmdb-asset-inventory")
                    .doesNotContainKeys("apiToken", "basicAuthPassword");
        }

        @Test
        void partialCollectionReportedViaStatusNotEmptySuccess() {
            var adapter =
                    new StubCmdbEvidenceAdapter(CmdbEvidenceProvider.SERVICENOW, CmdbEvidenceFamily.PATCH_LEVEL, true);
            var result = adapter.collect(request(CmdbEvidenceFamily.PATCH_LEVEL));
            assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.RATE_LIMITED);
            assertThat(result.errors()).isNotEmpty();
            assertThat(result.artifacts()).isEmpty();
        }
    }

    private static EvidenceCollectionRequest request(CmdbEvidenceFamily family) {
        var connection = new EvidenceConnectionConfig(
                "servicenow-prod-readonly",
                URI.create("https://example.service-now.com"),
                "secret://cmdb/servicenow-prod-readonly",
                Map.of("instance", "example"));
        var scope = new EvidenceCollectionScope(
                family.scopeType(), Map.of("includeRetired", true), NOW.minus(Duration.ofDays(90)), NOW, 100);
        return new EvidenceCollectionRequest(PROJECT_ID, connection, scope, RATE_LIMIT, Map.of("dryRun", true));
    }
}

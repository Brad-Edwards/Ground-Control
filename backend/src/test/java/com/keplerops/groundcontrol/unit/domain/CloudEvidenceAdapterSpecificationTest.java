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
import com.keplerops.groundcontrol.domain.evidence.collection.cloud.CloudEvidenceFamily;
import com.keplerops.groundcontrol.domain.evidence.collection.cloud.CloudEvidenceProvider;
import com.keplerops.groundcontrol.domain.evidence.collection.cloud.CloudEvidenceSpecification;
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
 * Conformance specification for GC-S003 cloud infrastructure evidence adapters. Verifies
 * the normative provider/family/schema/capability contract and that a stub adapter built
 * directly on the GC-S001 {@link EvidenceCollectionAdapter} port (no cloud sub-interface)
 * conforms.
 */
class CloudEvidenceAdapterSpecificationTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000212");
    private static final Instant NOW = Instant.parse("2026-06-25T08:00:00Z");
    private static final EvidenceCollectionRateLimit RATE_LIMIT =
            new EvidenceCollectionRateLimit(600, Duration.ofMinutes(1), 599, NOW.plusSeconds(60));

    /** Minimal conforming adapter built directly on the GC-S001 port (no cloud sub-interface). */
    static final class StubCloudEvidenceAdapter implements EvidenceCollectionAdapter {

        private final CloudEvidenceProvider provider;
        private final CloudEvidenceFamily family;

        StubCloudEvidenceAdapter(CloudEvidenceProvider provider, CloudEvidenceFamily family) {
            this.provider = provider;
            this.family = family;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    provider.key() + "-cloud-evidence",
                    "1.0.0",
                    "Collects cloud infrastructure evidence for " + provider.key(),
                    PluginType.EVIDENCE_COLLECTOR,
                    CloudEvidenceSpecification.capabilitiesFor(provider),
                    Map.of("provider", provider.key()));
        }

        @Override
        public EvidenceCollectionOutputSchema outputSchema() {
            return CloudEvidenceSpecification.outputSchema(family);
        }

        @Override
        public EvidenceCollectionRateLimit rateLimitPolicy() {
            return RATE_LIMIT;
        }

        @Override
        public EvidenceCollectionResult collect(EvidenceCollectionRequest request) {
            CloudEvidenceFamily requested =
                    CloudEvidenceFamily.fromScopeType(request.scope().scopeType());
            var command = new CreateEvidenceArtifactCommand(
                    request.projectId(),
                    "EVID-CLOUD-" + requested.name(),
                    "Cloud " + requested.scopeType() + " summary",
                    "Bounded cloud infrastructure summary; counts and external references only",
                    requested.evidenceType(),
                    descriptor().name() + "-v1",
                    NOW,
                    null,
                    "HIGH",
                    null,
                    List.of(new EvidenceSourceRef(
                            EvidenceSourceKind.EXTERNAL,
                            null,
                            provider.key() + ":account/123/resource/r-1",
                            "primary")));
            return new EvidenceCollectionResult(
                    descriptor().name(),
                    "1.0.0",
                    EvidenceCollectionStatus.SUCCEEDED,
                    CloudEvidenceSpecification.outputSchema(requested),
                    NOW,
                    List.of(command),
                    List.of(provider.key() + ":account/123/resource/r-1"),
                    List.of(),
                    RATE_LIMIT);
        }
    }

    @Nested
    class Providers {

        @Test
        void declaresAwsAzureAndGcp() {
            assertThat(CloudEvidenceSpecification.supportedProviders())
                    .containsExactlyInAnyOrder(
                            CloudEvidenceProvider.AWS, CloudEvidenceProvider.AZURE, CloudEvidenceProvider.GCP);
            assertThat(CloudEvidenceProvider.AWS.key()).isEqualTo("aws");
            assertThat(CloudEvidenceProvider.AZURE.key()).isEqualTo("azure");
            assertThat(CloudEvidenceProvider.GCP.key()).isEqualTo("gcp");
            assertThat(CloudEvidenceProvider.AWS.capabilityToken()).isEqualTo("provider:aws");
        }

        @Test
        void resolvesByKeyCaseInsensitively() {
            assertThat(CloudEvidenceProvider.fromKey("AWS")).isEqualTo(CloudEvidenceProvider.AWS);
            assertThat(CloudEvidenceProvider.fromKey("Gcp")).isEqualTo(CloudEvidenceProvider.GCP);
        }

        @Test
        void rejectsUnsupportedProvider() {
            assertThatThrownBy(() -> CloudEvidenceProvider.fromKey("oracle"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported cloud evidence provider");
        }
    }

    @Nested
    class Families {

        @Test
        void declaresAllFiveFamiliesAsObservationSummaries() {
            assertThat(CloudEvidenceSpecification.supportedFamilies())
                    .containsExactlyInAnyOrder(
                            CloudEvidenceFamily.SECURITY_GROUP_CONFIG,
                            CloudEvidenceFamily.ENCRYPTION_AT_REST,
                            CloudEvidenceFamily.LOGGING_CONFIG,
                            CloudEvidenceFamily.BACKUP_POLICY,
                            CloudEvidenceFamily.COMPLIANCE_SCAN);
            assertThat(CloudEvidenceFamily.values()).allSatisfy(family -> {
                assertThat(family.evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
                assertThat(family.scopeType()).startsWith("cloud-");
                assertThat(family.schemaId()).isEqualTo(family.scopeType());
                assertThat(family.summaryFields()).isNotEmpty();
                assertThat(family.capabilityToken()).isEqualTo("family:" + family.scopeType());
            });
        }

        @Test
        void scopeTypesAreDistinct() {
            long distinct = Arrays.stream(CloudEvidenceFamily.values())
                    .map(CloudEvidenceFamily::scopeType)
                    .distinct()
                    .count();
            assertThat(distinct).isEqualTo(CloudEvidenceFamily.values().length);
        }

        @Test
        void resolvesByScopeType() {
            assertThat(CloudEvidenceFamily.fromScopeType("cloud-encryption-at-rest"))
                    .isEqualTo(CloudEvidenceFamily.ENCRYPTION_AT_REST);
        }

        @Test
        void rejectsUnsupportedScope() {
            assertThatThrownBy(() -> CloudEvidenceFamily.fromScopeType("cloud-unknown"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported cloud evidence family");
        }
    }

    @Nested
    class OutputSchemas {

        @ParameterizedTest
        @EnumSource(CloudEvidenceFamily.class)
        void buildsVersionedSchemaWithExactFieldsForEveryFamily(CloudEvidenceFamily family) {
            var schema = CloudEvidenceSpecification.outputSchema(family);
            assertThat(schema.schemaId()).isEqualTo(family.schemaId());
            assertThat(schema.schemaVersion()).isEqualTo("1.0.0");
            assertThat(schema.evidenceType()).isEqualTo(family.evidenceType());

            Map<String, Object> expectedPayloadShape = new LinkedHashMap<>();
            for (String field : family.summaryFields()) {
                expectedPayloadShape.put(field, "summary");
            }
            assertThat(schema.payloadShape()).containsExactlyInAnyOrderEntriesOf(expectedPayloadShape);
        }

        @Test
        void pinsCanonicalFieldNamesPerFamilyAsLiterals() {
            // Expected field sets are literals drawn from the GC-S003 spec, NOT from the enum
            // under test, so a regression in any family's summaryFields() is caught here rather
            // than silently passing a derived-expectation wiring check.
            Map<CloudEvidenceFamily, List<String>> expected = new LinkedHashMap<>();
            expected.put(
                    CloudEvidenceFamily.SECURITY_GROUP_CONFIG,
                    List.of(
                            "groupRef",
                            "ruleCount",
                            "publicIngressCount",
                            "unrestrictedIngressCount",
                            "evaluatedThrough"));
            expected.put(
                    CloudEvidenceFamily.ENCRYPTION_AT_REST,
                    List.of(
                            "resourceRef",
                            "resourceType",
                            "encryptedResourceCount",
                            "unencryptedResourceCount",
                            "evaluatedThrough"));
            expected.put(
                    CloudEvidenceFamily.LOGGING_CONFIG,
                    List.of("resourceRef", "logCategory", "enabledLogCount", "disabledLogCount", "evaluatedThrough"));
            expected.put(
                    CloudEvidenceFamily.BACKUP_POLICY,
                    List.of(
                            "resourceRef",
                            "retentionDays",
                            "protectedResourceCount",
                            "unprotectedResourceCount",
                            "evaluatedThrough"));
            expected.put(
                    CloudEvidenceFamily.COMPLIANCE_SCAN,
                    List.of(
                            "scannerSource",
                            "controlRef",
                            "passCount",
                            "failCount",
                            "notApplicableCount",
                            "evaluatedThrough"));

            // Forces a new family to add its literal expectation here rather than ride the loop.
            assertThat(expected).containsOnlyKeys(CloudEvidenceFamily.values());
            expected.forEach((family, fields) -> {
                assertThat(family.summaryFields())
                        .as("summaryFields for %s", family)
                        .containsExactlyElementsOf(fields);
                assertThat(CloudEvidenceSpecification.outputSchema(family)
                                .payloadShape()
                                .keySet())
                        .as("payloadShape keys for %s", family)
                        .containsExactlyInAnyOrderElementsOf(fields);
            });
        }

        @Test
        void exposesAllFiveSchemasInOrder() {
            assertThat(CloudEvidenceSpecification.outputSchemas())
                    .hasSize(5)
                    .extracting(EvidenceCollectionOutputSchema::schemaId)
                    .containsExactly(
                            "cloud-security-group-config",
                            "cloud-encryption-at-rest",
                            "cloud-logging-config",
                            "cloud-backup-policy",
                            "cloud-compliance-scan");
        }

        @Test
        void rejectsNullFamily() {
            assertThatThrownBy(() -> CloudEvidenceSpecification.outputSchema(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Capabilities {

        @Test
        void includesCloudProviderAndFamilyTokens() {
            var capabilities = CloudEvidenceSpecification.capabilitiesFor(CloudEvidenceProvider.AWS);
            assertThat(capabilities)
                    .contains(
                            "evidence:cloud-infrastructure",
                            "provider:aws",
                            "family:cloud-security-group-config",
                            "family:cloud-encryption-at-rest",
                            "family:cloud-logging-config",
                            "family:cloud-backup-policy",
                            "family:cloud-compliance-scan");
        }

        @Test
        void rejectsNullProvider() {
            assertThatThrownBy(() -> CloudEvidenceSpecification.capabilitiesFor(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Conformance {

        @Test
        void acceptsConformantAdapterDescriptor() {
            var adapter =
                    new StubCloudEvidenceAdapter(CloudEvidenceProvider.AWS, CloudEvidenceFamily.SECURITY_GROUP_CONFIG);
            assertThat(adapter.descriptor().type()).isEqualTo(PluginType.EVIDENCE_COLLECTOR);
            assertThat(adapter.descriptor().capabilities())
                    .contains(CloudEvidenceSpecification.CAPABILITY_CLOUD_INFRASTRUCTURE);
            CloudEvidenceSpecification.requireConformant(adapter.descriptor());
        }

        @Test
        void rejectsNonEvidenceCollector() {
            var bad = new PluginDescriptor(
                    "x", "1.0.0", "", PluginType.VALIDATOR, Set.of("evidence:cloud-infrastructure"), Map.of());
            assertThatThrownBy(() -> CloudEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("EVIDENCE_COLLECTOR");
        }

        @Test
        void rejectsMissingCloudCapability() {
            var bad = new PluginDescriptor(
                    "x", "1.0.0", "", PluginType.EVIDENCE_COLLECTOR, Set.of("scope:other"), Map.of());
            assertThatThrownBy(() -> CloudEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("evidence:cloud-infrastructure");
        }

        @Test
        void rejectsNullDescriptor() {
            assertThatThrownBy(() -> CloudEvidenceSpecification.requireConformant(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class CollectionConformance {

        @Test
        void collectsBoundedExternalSummariesForEachFamily() {
            for (CloudEvidenceFamily family : CloudEvidenceFamily.values()) {
                var adapter = new StubCloudEvidenceAdapter(CloudEvidenceProvider.AWS, family);
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
                    "config:DescribeSecurityGroups",
                    false,
                    Map.of(
                            "family", "cloud-security-group-config",
                            "accessKeySecret", "raw-secret-value",
                            "sessionToken", "raw-token-value"));
            assertThat(error.detail())
                    .containsEntry("family", "cloud-security-group-config")
                    .doesNotContainKeys("accessKeySecret", "sessionToken");
        }

        @Test
        void partialCollectionReportedViaStatusNotEmptySuccess() {
            var error = new EvidenceCollectionError(
                    "rate_limited", "Provider throttled", "config:DescribeComplianceByConfigRule", true, Map.of());
            var result = new EvidenceCollectionResult(
                    "aws-cloud-evidence",
                    "1.0.0",
                    EvidenceCollectionStatus.RATE_LIMITED,
                    CloudEvidenceSpecification.outputSchema(CloudEvidenceFamily.COMPLIANCE_SCAN),
                    NOW,
                    List.of(),
                    List.of(),
                    List.of(error),
                    RATE_LIMIT);
            assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.RATE_LIMITED);
            assertThat(result.errors()).isNotEmpty();
        }
    }

    private static EvidenceCollectionRequest request(CloudEvidenceFamily family) {
        var connection = new EvidenceConnectionConfig(
                "aws-prod-readonly",
                URI.create("https://config.us-east-1.amazonaws.com"),
                "secret://aws/prod-readonly",
                Map.of("region", "us-east-1"));
        var scope = new EvidenceCollectionScope(
                family.scopeType(), Map.of("includeDisabled", true), NOW.minus(Duration.ofDays(90)), NOW, 100);
        return new EvidenceCollectionRequest(PROJECT_ID, connection, scope, RATE_LIMIT, Map.of("dryRun", true));
    }
}

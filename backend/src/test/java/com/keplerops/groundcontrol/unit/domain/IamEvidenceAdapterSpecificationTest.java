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
import com.keplerops.groundcontrol.domain.evidence.collection.iam.IamEvidenceFamily;
import com.keplerops.groundcontrol.domain.evidence.collection.iam.IamEvidenceProvider;
import com.keplerops.groundcontrol.domain.evidence.collection.iam.IamEvidenceSpecification;
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
 * Conformance specification for GC-S002 IAM evidence adapters. Verifies the normative
 * provider/family/schema/capability contract and that a stub adapter built directly on
 * the GC-S001 {@link EvidenceCollectionAdapter} port (no IAM sub-interface) conforms.
 */
class IamEvidenceAdapterSpecificationTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000211");
    private static final Instant NOW = Instant.parse("2026-06-25T08:00:00Z");
    private static final EvidenceCollectionRateLimit RATE_LIMIT =
            new EvidenceCollectionRateLimit(600, Duration.ofMinutes(1), 599, NOW.plusSeconds(60));

    /** Minimal conforming adapter built directly on the GC-S001 port (no IAM sub-interface). */
    static final class StubIamEvidenceAdapter implements EvidenceCollectionAdapter {

        private final IamEvidenceProvider provider;
        private final IamEvidenceFamily family;

        StubIamEvidenceAdapter(IamEvidenceProvider provider, IamEvidenceFamily family) {
            this.provider = provider;
            this.family = family;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    provider.key() + "-iam-evidence",
                    "1.0.0",
                    "Collects IAM evidence for " + provider.key(),
                    PluginType.EVIDENCE_COLLECTOR,
                    IamEvidenceSpecification.capabilitiesFor(provider),
                    Map.of("provider", provider.key()));
        }

        @Override
        public EvidenceCollectionOutputSchema outputSchema() {
            return IamEvidenceSpecification.outputSchema(family);
        }

        @Override
        public EvidenceCollectionRateLimit rateLimitPolicy() {
            return RATE_LIMIT;
        }

        @Override
        public EvidenceCollectionResult collect(EvidenceCollectionRequest request) {
            IamEvidenceFamily requested =
                    IamEvidenceFamily.fromScopeType(request.scope().scopeType());
            var command = new CreateEvidenceArtifactCommand(
                    request.projectId(),
                    "EVID-IAM-" + requested.name(),
                    "IAM " + requested.scopeType() + " summary",
                    "Bounded IAM summary; counts and external references only",
                    requested.evidenceType(),
                    descriptor().name() + "-v1",
                    NOW,
                    null,
                    "HIGH",
                    null,
                    List.of(new EvidenceSourceRef(
                            EvidenceSourceKind.EXTERNAL, null, provider.key() + ":campaign/q2", "primary")));
            return new EvidenceCollectionResult(
                    descriptor().name(),
                    "1.0.0",
                    EvidenceCollectionStatus.SUCCEEDED,
                    IamEvidenceSpecification.outputSchema(requested),
                    NOW,
                    List.of(command),
                    List.of(provider.key() + ":campaign/q2"),
                    List.of(),
                    RATE_LIMIT);
        }
    }

    @Nested
    class Providers {

        @Test
        void declaresOktaAzureAndAws() {
            assertThat(IamEvidenceSpecification.supportedProviders())
                    .containsExactlyInAnyOrder(
                            IamEvidenceProvider.OKTA, IamEvidenceProvider.AZURE_AD, IamEvidenceProvider.AWS_IAM);
            assertThat(IamEvidenceProvider.OKTA.key()).isEqualTo("okta");
            assertThat(IamEvidenceProvider.AZURE_AD.key()).isEqualTo("azure-ad");
            assertThat(IamEvidenceProvider.AWS_IAM.key()).isEqualTo("aws-iam");
            assertThat(IamEvidenceProvider.OKTA.capabilityToken()).isEqualTo("provider:okta");
        }

        @Test
        void resolvesByKeyCaseInsensitively() {
            assertThat(IamEvidenceProvider.fromKey("AWS-IAM")).isEqualTo(IamEvidenceProvider.AWS_IAM);
        }

        @Test
        void rejectsUnsupportedProvider() {
            assertThatThrownBy(() -> IamEvidenceProvider.fromKey("google"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported IAM evidence provider");
        }
    }

    @Nested
    class Families {

        @Test
        void declaresAllFiveFamiliesAsObservationSummaries() {
            assertThat(IamEvidenceSpecification.supportedFamilies())
                    .containsExactlyInAnyOrder(
                            IamEvidenceFamily.USER_ACCESS_REVIEW,
                            IamEvidenceFamily.PROVISIONING_EVENT,
                            IamEvidenceFamily.MFA_ENROLLMENT,
                            IamEvidenceFamily.PRIVILEGED_ACCESS,
                            IamEvidenceFamily.DORMANT_ACCOUNT);
            assertThat(IamEvidenceFamily.values()).allSatisfy(family -> {
                assertThat(family.evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
                assertThat(family.scopeType()).startsWith("iam-");
                assertThat(family.schemaId()).isEqualTo(family.scopeType());
                assertThat(family.summaryFields()).isNotEmpty();
                assertThat(family.capabilityToken()).isEqualTo("family:" + family.scopeType());
            });
        }

        @Test
        void scopeTypesAreDistinct() {
            long distinct = Arrays.stream(IamEvidenceFamily.values())
                    .map(IamEvidenceFamily::scopeType)
                    .distinct()
                    .count();
            assertThat(distinct).isEqualTo(IamEvidenceFamily.values().length);
        }

        @Test
        void resolvesByScopeType() {
            assertThat(IamEvidenceFamily.fromScopeType("iam-mfa-enrollment"))
                    .isEqualTo(IamEvidenceFamily.MFA_ENROLLMENT);
        }

        @Test
        void rejectsUnsupportedScope() {
            assertThatThrownBy(() -> IamEvidenceFamily.fromScopeType("iam-unknown"))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("Unsupported IAM evidence family");
        }
    }

    @Nested
    class OutputSchemas {

        @ParameterizedTest
        @EnumSource(IamEvidenceFamily.class)
        void buildsVersionedSchemaWithExactFieldsForEveryFamily(IamEvidenceFamily family) {
            var schema = IamEvidenceSpecification.outputSchema(family);
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
        void exposesAllFiveSchemasInOrder() {
            assertThat(IamEvidenceSpecification.outputSchemas())
                    .hasSize(5)
                    .extracting(EvidenceCollectionOutputSchema::schemaId)
                    .containsExactly(
                            "iam-user-access-review",
                            "iam-provisioning-event",
                            "iam-mfa-enrollment",
                            "iam-privileged-access",
                            "iam-dormant-account");
        }

        @Test
        void rejectsNullFamily() {
            assertThatThrownBy(() -> IamEvidenceSpecification.outputSchema(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Capabilities {

        @Test
        void includesIamProviderAndFamilyTokens() {
            var capabilities = IamEvidenceSpecification.capabilitiesFor(IamEvidenceProvider.OKTA);
            assertThat(capabilities)
                    .contains(
                            "evidence:iam",
                            "provider:okta",
                            "family:iam-user-access-review",
                            "family:iam-provisioning-event",
                            "family:iam-mfa-enrollment",
                            "family:iam-privileged-access",
                            "family:iam-dormant-account");
        }

        @Test
        void rejectsNullProvider() {
            assertThatThrownBy(() -> IamEvidenceSpecification.capabilitiesFor(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class Conformance {

        @Test
        void acceptsConformantAdapterDescriptor() {
            var adapter = new StubIamEvidenceAdapter(IamEvidenceProvider.AWS_IAM, IamEvidenceFamily.USER_ACCESS_REVIEW);
            assertThat(adapter.descriptor().type()).isEqualTo(PluginType.EVIDENCE_COLLECTOR);
            assertThat(adapter.descriptor().capabilities()).contains(IamEvidenceSpecification.CAPABILITY_IAM);
            IamEvidenceSpecification.requireConformant(adapter.descriptor());
        }

        @Test
        void rejectsNonEvidenceCollector() {
            var bad = new PluginDescriptor("x", "1.0.0", "", PluginType.VALIDATOR, Set.of("evidence:iam"), Map.of());
            assertThatThrownBy(() -> IamEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("EVIDENCE_COLLECTOR");
        }

        @Test
        void rejectsMissingIamCapability() {
            var bad = new PluginDescriptor(
                    "x", "1.0.0", "", PluginType.EVIDENCE_COLLECTOR, Set.of("scope:other"), Map.of());
            assertThatThrownBy(() -> IamEvidenceSpecification.requireConformant(bad))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("evidence:iam");
        }

        @Test
        void rejectsNullDescriptor() {
            assertThatThrownBy(() -> IamEvidenceSpecification.requireConformant(null))
                    .isInstanceOf(DomainValidationException.class);
        }
    }

    @Nested
    class CollectionConformance {

        @Test
        void collectsBoundedExternalSummariesForEachFamily() {
            for (IamEvidenceFamily family : IamEvidenceFamily.values()) {
                var adapter = new StubIamEvidenceAdapter(IamEvidenceProvider.AWS_IAM, family);
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
                    "iam:ListUsers",
                    false,
                    Map.of(
                            "category", "iam-privileged-access",
                            "token", "raw-token-value",
                            "accessKeySecret", "raw-secret-value"));
            assertThat(error.detail())
                    .containsEntry("category", "iam-privileged-access")
                    .doesNotContainKeys("token", "accessKeySecret");
        }

        @Test
        void partialCollectionReportedViaStatusNotEmptySuccess() {
            var error =
                    new EvidenceCollectionError("rate_limited", "Provider throttled", "okta:listUsers", true, Map.of());
            var result = new EvidenceCollectionResult(
                    "okta-iam-evidence",
                    "1.0.0",
                    EvidenceCollectionStatus.RATE_LIMITED,
                    IamEvidenceSpecification.outputSchema(IamEvidenceFamily.USER_ACCESS_REVIEW),
                    NOW,
                    List.of(),
                    List.of(),
                    List.of(error),
                    RATE_LIMIT);
            assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.RATE_LIMITED);
            assertThat(result.errors()).isNotEmpty();
        }
    }

    private static EvidenceCollectionRequest request(IamEvidenceFamily family) {
        var connection = new EvidenceConnectionConfig(
                "aws-prod-readonly",
                URI.create("https://iam.amazonaws.com"),
                "secret://aws/prod-readonly",
                Map.of("region", "us-east-1"));
        var scope = new EvidenceCollectionScope(
                family.scopeType(), Map.of("includeGroups", true), NOW.minus(Duration.ofDays(90)), NOW, 100);
        return new EvidenceCollectionRequest(PROJECT_ID, connection, scope, RATE_LIMIT, Map.of("dryRun", true));
    }
}

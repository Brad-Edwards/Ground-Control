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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EvidenceCollectionAdapterContractTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000210");
    private static final Instant NOW = Instant.parse("2026-06-07T08:00:00Z");

    private static final EvidenceCollectionOutputSchema IAM_SCHEMA = new EvidenceCollectionOutputSchema(
            "iam-access-observation", "1.0.0", EvidenceType.OBSERVATION_SUMMARY, Map.of("provider", "aws-iam"));

    private static final EvidenceCollectionRateLimit RATE_LIMIT =
            new EvidenceCollectionRateLimit(100, Duration.ofMinutes(1), 99, NOW.plusSeconds(60));

    static class StubEvidenceAdapter implements EvidenceCollectionAdapter {

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    "aws-iam-evidence",
                    "1.0.0",
                    "Collects IAM evidence",
                    PluginType.EVIDENCE_COLLECTOR,
                    Set.of("evidence:iam", "scope:identity"),
                    Map.of("schema", IAM_SCHEMA.schemaId()));
        }

        @Override
        public EvidenceCollectionOutputSchema outputSchema() {
            return IAM_SCHEMA;
        }

        @Override
        public EvidenceCollectionRateLimit rateLimitPolicy() {
            return RATE_LIMIT;
        }

        @Override
        public EvidenceCollectionResult collect(EvidenceCollectionRequest request) {
            var command = new CreateEvidenceArtifactCommand(
                    request.projectId(),
                    "EVID-IAM-001",
                    "IAM user access evidence",
                    "Summarized IAM access evidence for user alice",
                    EvidenceType.OBSERVATION_SUMMARY,
                    "aws-iam-evidence-v1",
                    NOW,
                    null,
                    "HIGH",
                    null,
                    List.of(new EvidenceSourceRef(EvidenceSourceKind.EXTERNAL, null, "aws:iam:user/alice", "primary")));
            return new EvidenceCollectionResult(
                    "aws-iam-evidence",
                    "1.0.0",
                    EvidenceCollectionStatus.SUCCEEDED,
                    IAM_SCHEMA,
                    NOW,
                    List.of(command),
                    List.of("aws:iam:user/alice"),
                    List.of(),
                    RATE_LIMIT);
        }
    }

    @Nested
    class AdapterDescriptor {

        @Test
        void usesEvidenceCollectorPluginType() {
            var adapter = new StubEvidenceAdapter();

            assertThat(adapter.descriptor().type()).isEqualTo(PluginType.EVIDENCE_COLLECTOR);
            assertThat(adapter.descriptor().capabilities()).contains("evidence:iam", "scope:identity");
            assertThat(adapter.outputSchema().evidenceType()).isEqualTo(EvidenceType.OBSERVATION_SUMMARY);
            assertThat(adapter.rateLimitPolicy().remaining()).isEqualTo(99);
        }
    }

    @Nested
    class RequestAndResultShape {

        @Test
        void carriesConnectionScopeSchemaErrorsAndRateLimit() {
            var adapter = new StubEvidenceAdapter();
            var request = request();

            var result = adapter.collect(request);

            assertThat(request.connection().credentialRef()).isEqualTo("secret://aws/prod-readonly");
            assertThat(request.connection().settings()).containsEntry("region", "us-east-1");
            assertThat(request.scope().scopeType()).isEqualTo("iam-user-access");
            assertThat(request.scope().criteria()).containsEntry("user", "alice");
            assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
            assertThat(result.schema().schemaVersion()).isEqualTo("1.0.0");
            assertThat(result.artifacts()).hasSize(1);
            assertThat(result.artifacts().get(0).sources().get(0).sourceKind()).isEqualTo(EvidenceSourceKind.EXTERNAL);
            assertThat(result.externalReferences()).contains("aws:iam:user/alice");
            assertThat(result.rateLimit().resetAt()).isEqualTo(NOW.plusSeconds(60));
        }

        @Test
        void normalizesProviderErrorsWithoutSecretFields() {
            var error = new EvidenceCollectionError(
                    "provider_timeout",
                    "Provider timed out while collecting IAM evidence",
                    "iam:GetUser",
                    true,
                    Map.of(
                            "timeout_ms",
                            1000,
                            "token",
                            "raw-token-value",
                            "secret",
                            "raw-secret-value",
                            "password",
                            "raw-password-value",
                            "credentialRef",
                            "secret://aws/prod-readonly"));

            var result = new EvidenceCollectionResult(
                    "aws-iam-evidence",
                    "1.0.0",
                    EvidenceCollectionStatus.FAILED,
                    IAM_SCHEMA,
                    NOW,
                    List.of(),
                    List.of(),
                    List.of(error),
                    RATE_LIMIT);

            assertThat(result.errors()).singleElement().satisfies(e -> {
                assertThat(e.errorCode()).isEqualTo("provider_timeout");
                assertThat(e.retryable()).isTrue();
                assertThat(e.detail()).containsEntry("timeout_ms", 1000);
                assertThat(e.detail()).doesNotContainKeys("token", "secret", "password", "credentialRef");
            });
        }
    }

    @Nested
    class ValidationGuards {

        @Test
        void rejectsInvalidConnectionConfig() {
            assertThatThrownBy(() -> new EvidenceConnectionConfig(
                            "", URI.create("https://iam.amazonaws.com"), "secret://aws", Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("profileId");
            assertThatThrownBy(() -> new EvidenceConnectionConfig("aws", null, "secret://aws", Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("endpoint");
            assertThatThrownBy(() ->
                            new EvidenceConnectionConfig("aws", URI.create("https://iam.amazonaws.com"), " ", Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("credentialRef");
        }

        @Test
        void rejectsInvalidCollectionScope() {
            assertThatThrownBy(() -> new EvidenceCollectionScope("", Map.of(), null, null, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("scopeType");
            assertThatThrownBy(() -> new EvidenceCollectionScope("iam", Map.of(), NOW, NOW.minusSeconds(1), null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("from");
            assertThatThrownBy(() -> new EvidenceCollectionScope("iam", Map.of(), null, null, 0))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("itemLimit");
        }

        @Test
        void rejectsInvalidOutputSchema() {
            assertThatThrownBy(() ->
                            new EvidenceCollectionOutputSchema("", "1.0.0", EvidenceType.OBSERVATION_SUMMARY, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("schemaId");
            assertThatThrownBy(() -> new EvidenceCollectionOutputSchema(
                            "iam-access-observation", "", EvidenceType.OBSERVATION_SUMMARY, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("schemaVersion");
            assertThatThrownBy(
                            () -> new EvidenceCollectionOutputSchema("iam-access-observation", "1.0.0", null, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("evidenceType");
        }

        @Test
        void rejectsInvalidRateLimitPolicy() {
            assertThatThrownBy(() -> new EvidenceCollectionRateLimit(0, Duration.ofMinutes(1), 0, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("capacity");
            assertThatThrownBy(() -> new EvidenceCollectionRateLimit(10, Duration.ZERO, 0, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("window");
            assertThatThrownBy(() -> new EvidenceCollectionRateLimit(10, Duration.ofMinutes(1), 11, null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("remaining");
        }

        @Test
        void rejectsInvalidCollectionRequest() {
            var connection = connection();
            var scope = scope();

            assertThatThrownBy(() -> new EvidenceCollectionRequest(null, connection, scope, RATE_LIMIT, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("projectId");
            assertThatThrownBy(() -> new EvidenceCollectionRequest(PROJECT_ID, null, scope, RATE_LIMIT, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("connection");
            assertThatThrownBy(() -> new EvidenceCollectionRequest(PROJECT_ID, connection, null, RATE_LIMIT, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("scope");
        }

        @Test
        void rejectsInvalidCollectionResult() {
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "",
                            "1.0.0",
                            EvidenceCollectionStatus.SUCCEEDED,
                            IAM_SCHEMA,
                            NOW,
                            List.of(),
                            List.of(),
                            List.of(),
                            RATE_LIMIT))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("adapterName");
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "aws-iam-evidence",
                            "",
                            EvidenceCollectionStatus.SUCCEEDED,
                            IAM_SCHEMA,
                            NOW,
                            List.of(),
                            List.of(),
                            List.of(),
                            RATE_LIMIT))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("adapterVersion");
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "aws-iam-evidence",
                            "1.0.0",
                            null,
                            IAM_SCHEMA,
                            NOW,
                            List.of(),
                            List.of(),
                            List.of(),
                            RATE_LIMIT))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("status");
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "aws-iam-evidence",
                            "1.0.0",
                            EvidenceCollectionStatus.SUCCEEDED,
                            null,
                            NOW,
                            List.of(),
                            List.of(),
                            List.of(),
                            RATE_LIMIT))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("schema");
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "aws-iam-evidence",
                            "1.0.0",
                            EvidenceCollectionStatus.SUCCEEDED,
                            IAM_SCHEMA,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            RATE_LIMIT))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("collectedAt");
            assertThatThrownBy(() -> new EvidenceCollectionResult(
                            "aws-iam-evidence",
                            "1.0.0",
                            EvidenceCollectionStatus.SUCCEEDED,
                            IAM_SCHEMA,
                            NOW,
                            List.of(),
                            List.of(),
                            List.of(),
                            null))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("rateLimit");
        }

        @Test
        void rejectsInvalidCollectionError() {
            assertThatThrownBy(() -> new EvidenceCollectionError("", "message", "target", true, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("errorCode");
            assertThatThrownBy(() -> new EvidenceCollectionError("provider_timeout", "", "target", true, Map.of()))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessageContaining("message");
        }
    }

    private static EvidenceCollectionRequest request() {
        return new EvidenceCollectionRequest(PROJECT_ID, connection(), scope(), RATE_LIMIT, Map.of("dryRun", false));
    }

    private static EvidenceConnectionConfig connection() {
        return new EvidenceConnectionConfig(
                "aws-prod-readonly",
                URI.create("https://iam.amazonaws.com"),
                "secret://aws/prod-readonly",
                Map.of("region", "us-east-1"));
    }

    private static EvidenceCollectionScope scope() {
        return new EvidenceCollectionScope(
                "iam-user-access",
                Map.of("user", "alice", "includeGroups", true),
                NOW.minus(Duration.ofHours(1)),
                NOW,
                100);
    }
}

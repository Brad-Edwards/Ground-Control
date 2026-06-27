package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerComposeNormalizerTest {

    private static final String SURFACE = "docker-compose";
    private static final String PATH = "docker-compose.yml";
    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final String COMMIT = "abc123";
    private static final String RULESET_VERSION = "1.0.0";
    private static final Instant NOW = Instant.now();

    private List<DerivedSystemModelFact> normalize(String content) {
        return new DockerComposeNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, COMMIT, RULESET_VERSION, NOW);
    }

    @Test
    void serviceEmitsComponentFact() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "compose-service");
            assertThat(f.payload()).containsEntry("serviceName", "app");
        });
    }

    @Test
    void externalRegistryImageEmitsExternalInteraction() {
        var content =
                """
                services:
                  app:
                    image: ghcr.io/org/app:latest
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "image-registry");
            assertThat(f.payload()).containsEntry("registryTarget", "ghcr.io");
        });
    }

    @Test
    void privilegedContainerEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    privileged: true
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "privileged-container");
        });
    }

    @Test
    void dockerSocketMountEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "docker-socket-mount");
        });
    }

    @Test
    void composeSeccretEmitsSecretUsage() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    secrets:
                      - db_password
                secrets:
                  db_password:
                    external: true
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "db_password");
            assertThat(f.payload()).containsEntry("secretScope", "compose-secret");
        });
    }

    @Test
    void secretLikeEnvironmentKeyEmitsSecretUsageWithoutValue() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    environment:
                      DB_PASSWORD: actual-secret-value
                      APP_NAME: myapp
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "DB_PASSWORD");
            assertThat(f.payload()).containsEntry("secretScope", "environment");
            // Value must not appear
            assertThat(f.payload().toString()).doesNotContain("actual-secret-value");
        });
        // Non-secret env var must not appear as a SECRET_USAGE fact
        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "APP_NAME");
        });
    }

    @Test
    void publishedPortsEmitExternalInteraction() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    ports:
                      - "8080:8080"
                      - "443:443"
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "published-port");
        });
    }

    @Test
    void hostNetworkModeEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    network_mode: host
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "host-network");
        });
    }

    // ── Finding 1: fact-key stability across commits ──────────────────────────

    @Test
    void factKeyIsStableAcrossDifferentCommitShas() {
        var content =
                """
                services:
                  app:
                    image: nginx:latest
                    privileged: true
                """;
        var factsA = new DockerComposeNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-aaaa", RULESET_VERSION, NOW);
        var factsB = new DockerComposeNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-bbbb", RULESET_VERSION, NOW);

        assertThat(factsA).hasSameSizeAs(factsB);
        var keysA = factsA.stream().map(DerivedSystemModelFact::factKey).toList();
        var keysB = factsB.stream().map(DerivedSystemModelFact::factKey).toList();
        assertThat(keysA).containsExactlyInAnyOrderElementsOf(keysB);
    }

    // ── Finding 2b: parse failures propagate as exceptions (not silent empty) ─

    @Test
    void malformedYamlThrowsIllegalStateException() {
        var content = "{ this is: [not: valid: yaml\n";
        var normalizer = new DockerComposeNormalizer();

        assertThatThrownBy(() -> normalizer.normalize(SURFACE, PATH, content, ADAPTER_ID, COMMIT, RULESET_VERSION, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker-compose YAML")
                .hasMessageNotContaining(content.trim());
    }
}

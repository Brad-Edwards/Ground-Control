package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @ParameterizedTest
    @MethodSource("trustBoundaryServiceConfigs")
    void serviceConfigEmitsTrustBoundary(String yaml, String expectedPrivilegedOperation) {
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", expectedPrivilegedOperation);
        });
    }

    static Stream<Arguments> trustBoundaryServiceConfigs() {
        return Stream.of(
                Arguments.of(
                        """
                        services:
                          app:
                            image: myapp:latest
                            privileged: true
                        """,
                        "privileged-container"),
                Arguments.of(
                        """
                        services:
                          app:
                            image: myapp:latest
                            volumes:
                              - /var/run/docker.sock:/var/run/docker.sock
                        """,
                        "docker-socket-mount"),
                Arguments.of(
                        """
                        services:
                          app:
                            image: myapp:latest
                            network_mode: host
                        """,
                        "host-network"));
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

        assertThat(facts)
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
                    assertThat(f.payload()).containsEntry("secretRef", "DB_PASSWORD");
                    assertThat(f.payload()).containsEntry("secretScope", "environment");
                    // Value must not appear
                    assertThat(f.payload().toString()).doesNotContain("actual-secret-value");
                })
                // Non-secret env var must not appear as a SECRET_USAGE fact
                .noneSatisfy(f -> {
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

    // ── Privileged operation branches ─────────────────────────────────────────

    @Test
    void capAddEmitsTrustBoundaryWithCapabilities() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    cap_add:
                      - NET_ADMIN
                      - SYS_PTRACE
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "capability-add");
            assertThat(f.payload()).containsKey("securitySignals");
        });
    }

    @Test
    void hostPidNamespaceEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    pid: host
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "host-pid");
        });
    }

    @Test
    void hostIpcNamespaceEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    ipc: host
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "host-ipc");
        });
    }

    @Test
    void userRootEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    user: root
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "root-user");
        });
    }

    @Test
    void userZeroEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    user: "0"
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "root-user");
        });
    }

    // ── Volume / bind-mount branches ──────────────────────────────────────────

    @Test
    void sensitiveBindMountEtcEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    volumes:
                      - /etc/ssl:/etc/ssl:ro
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "sensitive-bind-mount");
        });
    }

    @Test
    void sensitiveBindMountProcSubpathEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    volumes:
                      - /proc/sys:/proc/sys:ro
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "sensitive-bind-mount");
        });
    }

    @Test
    void volumeObjectFormatWithDockerSockEmitsTrustBoundary() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    volumes:
                      - type: bind
                        source: /var/run/docker.sock
                        target: /var/run/docker.sock
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("privilegedOperation", "docker-socket-mount");
        });
    }

    @Test
    void volumeStringWithoutColonProducesNoVolumeFact() {
        // A named volume (no host path / colon) should produce no TRUST_BOUNDARY from volumes
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    volumes:
                      - mydata
                """;
        var facts = normalize(content);

        assertThat(facts)
                .noneSatisfy(f -> assertThat(f.payload()).containsEntry("artifactKind", "container-daemon-boundary"));
    }

    // ── env_file branches ─────────────────────────────────────────────────────

    @Test
    void envFileStringEmitsSecretUsageFact() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    env_file: .env
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "env-file");
        });
    }

    @Test
    void envFileArrayEmitsSecretUsageFact() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    env_file:
                      - .env
                      - .env.prod
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "env-file");
        });
    }

    // ── environment array branches ────────────────────────────────────────────

    @Test
    void environmentArrayWithSecretLikeKeyEmitsSecretUsage() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    environment:
                      - DB_PASSWORD=supersecret
                      - APP_NAME=myapp
                """;
        var facts = normalize(content);

        assertThat(facts)
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
                    assertThat(f.payload()).containsEntry("secretRef", "DB_PASSWORD");
                    assertThat(f.payload().toString()).doesNotContain("supersecret");
                })
                .noneSatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
                    assertThat(f.payload()).containsEntry("secretRef", "APP_NAME");
                });
    }

    @Test
    void environmentArrayEntryWithoutEqualsSignUsesWholeName() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    environment:
                      - MY_TOKEN
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "MY_TOKEN");
        });
    }

    // ── secret object-form branches ───────────────────────────────────────────

    @Test
    void secretObjectWithSourceFieldEmitsSecretUsage() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    secrets:
                      - source: db_creds
                secrets:
                  db_creds:
                    external: true
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "db_creds");
            assertThat(f.payload()).containsEntry("secretScope", "compose-secret");
        });
    }

    @Test
    void secretObjectWithoutSourceUsesFirstFieldAsName() {
        var content =
                """
                services:
                  app:
                    image: myapp:latest
                    secrets:
                      - my_secret: {}
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "my_secret");
        });
    }

    // ── image without external registry ──────────────────────────────────────

    @Test
    void libraryImageDoesNotEmitExternalInteractionFact() {
        // "nginx:latest" has no slash → no registry hostname → no EXTERNAL_INTERACTION for registry
        var content =
                """
                services:
                  app:
                    image: nginx:latest
                """;
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "image-registry");
        });
    }

    // ── empty / missing structures ────────────────────────────────────────────

    @Test
    void missingServicesKeyReturnsEmptyList() {
        var content =
                """
                version: "3.8"
                networks:
                  default:
                    driver: bridge
                """;
        var facts = normalize(content);

        assertThat(facts).isEmpty();
    }

    @Test
    void nullYamlContentReturnsEmptyList() {
        // "~" is YAML null
        var facts = normalize("~");

        assertThat(facts).isEmpty();
    }

    @Test
    void emptyYamlContentReturnsEmptyList() {
        var facts = normalize("");

        assertThat(facts).isEmpty();
    }
}

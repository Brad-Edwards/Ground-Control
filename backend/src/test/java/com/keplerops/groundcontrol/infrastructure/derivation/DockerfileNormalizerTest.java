package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerfileNormalizerTest {

    private static final String SURFACE = "dockerfile";
    private static final String PATH = "Dockerfile";
    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final String COMMIT = "abc123";
    private static final String RULESET_VERSION = "1.0.0";
    private static final Instant NOW = Instant.now();

    private List<DerivedSystemModelFact> normalize(String content) {
        return new DockerfileNormalizer().normalize(SURFACE, PATH, content, ADAPTER_ID, COMMIT, RULESET_VERSION, NOW);
    }

    @Test
    void fromInstructionEmitsComponentFact() {
        var content = "FROM ubuntu:22.04\nRUN apt-get update\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "docker-image");
        });
    }

    @Test
    void fromWithExternalRegistryEmitsExternalInteraction() {
        var content = "FROM ghcr.io/org/image:latest\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "image-registry");
            assertThat(f.payload()).containsEntry("registryTarget", "ghcr.io");
        });
    }

    @Test
    void secretLikeArgEmitsSecretUsageWithoutValue() {
        var content = "FROM ubuntu:22.04\nARG MY_PASSWORD=changeme\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "MY_PASSWORD");
            assertThat(f.payload()).containsEntry("secretScope", "build-arg");
            // Value must not appear in payload
            assertThat(f.payload().toString()).doesNotContain("changeme");
        });
    }

    @Test
    void nonSecretArgDoesNotEmitSecretUsage() {
        var content = "FROM ubuntu:22.04\nARG BUILD_DATE\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE));
    }

    @Test
    void secretLikeEnvEmitsSecretUsageWithoutValue() {
        var content = "FROM ubuntu:22.04\nENV DB_PASSWORD=secret123\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "DB_PASSWORD");
            assertThat(f.payload()).containsEntry("secretScope", "build-env");
            assertThat(f.payload().toString()).doesNotContain("secret123");
        });
    }

    @Test
    void runMountSecretEmitsSecretUsage() {
        var content = "FROM ubuntu:22.04\nRUN --mount=type=secret,id=my_secret cat /run/secrets/my_secret\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "my_secret");
            assertThat(f.payload()).containsEntry("secretScope", "build-secret");
        });
    }

    @Test
    void userRootEmitsComponent() {
        var content = "FROM ubuntu:22.04\nUSER root\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("privilegedOperation", "user-root");
        });
    }

    @Test
    void addWithHttpUrlEmitsExternalInteraction() {
        var content = "FROM ubuntu:22.04\nADD https://example.com/script.sh /usr/local/bin/\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-fetch");
            assertThat(f.payload()).containsEntry("registryTarget", "https://example.com/script.sh");
        });
    }

    // ── Finding 1: fact-key stability across commits ──────────────────────────

    @Test
    void factKeyIsStableAcrossDifferentCommitShas() {
        var content = "FROM ubuntu:22.04\nARG MY_PASSWORD\nADD https://example.com/file.tar /tmp/\n";
        var factsA = new DockerfileNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-aaaa", RULESET_VERSION, NOW);
        var factsB = new DockerfileNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-bbbb", RULESET_VERSION, NOW);

        assertThat(factsA).hasSameSizeAs(factsB);
        var keysA = factsA.stream().map(DerivedSystemModelFact::factKey).toList();
        var keysB = factsB.stream().map(DerivedSystemModelFact::factKey).toList();
        assertThat(keysA).containsExactlyInAnyOrderElementsOf(keysB);
    }

    // ── Finding 3: URL credential sanitization ────────────────────────────────

    @Test
    void addWithCredentialUrlStripsUserinfoAndQueryFromPayloadLabelSummary() {
        var content = "FROM ubuntu:22.04\nADD https://user:secret@host.example.com/path?token=abc#frag /tmp/\n";
        var facts = normalize(content);

        var addFact = facts.stream()
                .filter(f -> f.factKind() == SystemModelFactKind.EXTERNAL_INTERACTION)
                .filter(f -> "remote-fetch".equals(f.payload().get("artifactKind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a remote-fetch EXTERNAL_INTERACTION fact"));

        // userinfo, query, and fragment must not appear anywhere in the stored fact
        assertThat(addFact.payload().toString()).doesNotContain("secret");
        assertThat(addFact.payload().toString()).doesNotContain("token=abc");
        assertThat(addFact.payload().toString()).doesNotContain("user:secret");
        assertThat(addFact.label()).doesNotContain("secret");
        assertThat(addFact.label()).doesNotContain("token=abc");
        assertThat(addFact.summary()).doesNotContain("secret");
        assertThat(addFact.summary()).doesNotContain("token=abc");
        // scheme + host + path must be retained
        assertThat(addFact.payload().get("registryTarget").toString()).contains("host.example.com");
        assertThat(addFact.payload().get("registryTarget").toString()).startsWith("https://");
    }
}

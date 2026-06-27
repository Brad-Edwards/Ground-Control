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

    // ── Multi-stage builds ────────────────────────────────────────────────────

    @Test
    void fromWithMultiStageAliasIncludesBuildStageInPayload() {
        var content = "FROM ubuntu:22.04 AS builder\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "docker-image");
            assertThat(f.payload()).containsKey("buildStage");
            assertThat(f.payload().get("buildStage")).isEqualTo("builder");
        });
    }

    @Test
    void fromWithPlatformFlagStillEmitsComponentFact() {
        var content = "FROM --platform=linux/amd64 ubuntu:22.04\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "docker-image");
            // platform flag must not appear as the image name
            assertThat(f.label()).doesNotContain("--platform");
        });
    }

    @Test
    void fromWithExternalRegistryAndStageEmitsBothComponentAndRegistryFacts() {
        var content = "FROM ghcr.io/org/app:latest AS final\n";
        var facts = normalize(content);

        assertThat(facts)
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
                    assertThat(f.payload()).containsEntry("buildStage", "final");
                })
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
                    assertThat(f.payload()).containsEntry("registryTarget", "ghcr.io");
                });
    }

    // ── ENV space-separator format ────────────────────────────────────────────

    @Test
    void envWithSpaceSeparatorEmitsSecretUsage() {
        // "ENV NAME VALUE" format (space, not =)
        var content = "FROM ubuntu:22.04\nENV DB_PASSWORD secret_value\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "DB_PASSWORD");
            assertThat(f.payload()).containsEntry("secretScope", "build-env");
            assertThat(f.payload().toString()).doesNotContain("secret_value");
        });
    }

    // ── USER 0 variant ────────────────────────────────────────────────────────

    @Test
    void userZeroEmitsRootComponent() {
        var content = "FROM ubuntu:22.04\nUSER 0\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("privilegedOperation", "user-root");
        });
    }

    // ── ADD with local file → no fact ────────────────────────────────────────

    @Test
    void addWithLocalFileDoesNotEmitExternalInteraction() {
        var content = "FROM ubuntu:22.04\nADD localfile.tar.gz /tmp/\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-fetch");
        });
    }

    // ── RUN --mount=type=secret without id= → no fact ────────────────────────

    @Test
    void runWithSecretMountButNoIdDoesNotEmitFact() {
        // --mount=type=secret is present but no id= attribute
        var content = "FROM ubuntu:22.04\nRUN --mount=type=secret cat /run/secrets/myfile\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "build-secret");
        });
    }

    // ── FROM registry-hostname extraction edge cases ──────────────────────────

    @Test
    void fromImageWithNoSlashDoesNotEmitRegistryFact() {
        // "alpine" has no slash → extractRegistryHostname returns null → no EXTERNAL_INTERACTION
        var content = "FROM alpine\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "image-registry");
        });
    }

    @Test
    void fromImageWithOrgPrefixButNoDotOrColonDoesNotEmitRegistryFact() {
        // "library/nginx" — prefix "library" has no dot or colon → not an external registry
        var content = "FROM library/nginx:latest\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "image-registry");
        });
    }

    // ── Line continuation ─────────────────────────────────────────────────────

    @Test
    void lineContinuationJoinsRunInstruction() {
        var content = "FROM ubuntu:22.04\n"
                + "RUN --mount=type=secret,id=my_secret \\\n"
                + "    cat /run/secrets/my_secret\n";
        var facts = normalize(content);

        // The joined logical line should still match the secret mount pattern
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "my_secret");
            assertThat(f.payload()).containsEntry("secretScope", "build-secret");
        });
    }

    // ── ENV with no value (name only) ────────────────────────────────────────

    @Test
    void envWithNameOnlyAndNonSecretDoesNotEmitFact() {
        // "ENV PLAIN" — no = and no space after name — uses the else branch (name=rest.trim())
        // PLAIN doesn't match SECRET_LIKE → no fact
        var content = "FROM ubuntu:22.04\nENV PLAIN\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE));
    }

    @Test
    void envWithSecretNameAndNoValueEmitsFact() {
        // "ENV MY_TOKEN" — no = and no space — name matches SECRET_LIKE → fact emitted
        var content = "FROM ubuntu:22.04\nENV MY_TOKEN\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "MY_TOKEN");
        });
    }

    // ── Non-root USER does not emit COMPONENT ─────────────────────────────────

    @Test
    void nonRootUserDoesNotEmitRootComponent() {
        var content = "FROM ubuntu:22.04\nUSER appuser\n";
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("privilegedOperation", "user-root");
        });
    }

    // ── CRLF line endings handled ─────────────────────────────────────────────

    @Test
    void windowsCrlfLineEndingsAreParsedCorrectly() {
        // Lines ending with \r\n should be handled — the \r is stripped before parsing
        var content = "FROM ubuntu:22.04\r\nARG MY_SECRET=x\r\n";
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "MY_SECRET");
        });
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

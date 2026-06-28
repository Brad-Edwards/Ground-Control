package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubActionsNormalizerTest {

    private static final String SURFACE = "github-actions";
    private static final String PATH = ".github/workflows/ci.yml";
    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final String COMMIT = "abc123";
    private static final String RULESET_VERSION = "1.0.0";
    private static final Instant NOW = Instant.now();

    private List<DerivedSystemModelFact> normalize(String yaml) {
        return new GitHubActionsNormalizer().normalize(SURFACE, PATH, yaml, ADAPTER_ID, COMMIT, RULESET_VERSION, NOW);
    }

    @Test
    void pushTriggerEmitsEntryPointWithTrustedTrust() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(f.payload()).containsEntry("triggerKind", "push");
            assertThat(f.payload()).containsEntry("triggerTrust", "trusted");
        });
    }

    @Test
    void pullRequestTargetTriggerEmitsUntrustedEntry() {
        var yaml =
                """
                on: pull_request_target
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(f.payload()).containsEntry("triggerKind", "pull_request_target");
            assertThat(f.payload()).containsEntry("triggerTrust", "untrusted");
        });
    }

    @Test
    void multipleTriggerObjectEmitsMultipleEntryPoints() {
        var yaml =
                """
                on:
                  push:
                    branches: [main]
                  pull_request:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        var entryPoints = facts.stream()
                .filter(f -> f.factKind() == SystemModelFactKind.ENTRY_POINT)
                .toList();
        assertThat(entryPoints).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void selfHostedRunnerEmitsTrustBoundary() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: self-hosted
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("runnerTrustLevel", "untrusted");
        });
    }

    @Test
    void githubHostedRunnerEmitsComponentWithoutTrustBoundary() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts)
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
                    assertThat(f.payload()).containsEntry("runnerKind", "github-hosted");
                })
                // No trust boundary for github-hosted runners
                .noneSatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY));
    }

    @Test
    void secretsReferenceExtractedWithoutValue() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Run tests
                        env:
                          MY_SECRET: ${{ secrets.MY_API_KEY }}
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "MY_API_KEY");
            // The raw GitHub Actions secret expression must never be materialized in the payload.
            assertThat(f.payload().toString()).doesNotContain("${{ secrets.MY_API_KEY }}");
            assertThat(f.payload().toString()).doesNotContain("secrets.");
        });
    }

    @Test
    void thirdPartyActionEmitsExternalInteraction() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: some-org/some-action@v1
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "third-party-action");
        });
    }

    @Test
    void trustedActionDoesNotEmitExternalInteraction() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: github/codeql-action/analyze@v3
                """;
        var facts = normalize(yaml);

        // Neither "actions" nor "github" should be flagged as third-party
        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload().getOrDefault("artifactKind", "")).isEqualTo("third-party-action");
        });
    }

    @Test
    void oidcIdTokenWriteEmitsSecretUsage() {
        var yaml =
                """
                on: push
                permissions:
                  id-token: write
                  contents: read
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "oidc");
            assertThat(f.payload()).containsEntry("secretRef", "id-token");
        });
    }

    @Test
    void deployStepNameEmitsDataFlow() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Deploy to production
                        run: echo "deploying"
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.DATA_FLOW);
            assertThat(f.payload()).containsEntry("artifactKind", "deploy-step");
        });
    }

    // ── Additional trigger formats ────────────────────────────────────────────

    @Test
    void scheduleTriggerEmitsUntrustedEntryPoint() {
        var yaml =
                """
                on: schedule
                jobs:
                  run:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(f.payload()).containsEntry("triggerKind", "schedule");
            assertThat(f.payload()).containsEntry("triggerTrust", "untrusted");
        });
    }

    @Test
    void workflowDispatchTriggerEmitsUntrustedEntryPoint() {
        var yaml =
                """
                on: workflow_dispatch
                jobs:
                  run:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(f.payload()).containsEntry("triggerKind", "workflow_dispatch");
            assertThat(f.payload()).containsEntry("triggerTrust", "untrusted");
        });
    }

    @Test
    void pullRequestTriggerEmitsTrustedEntryPoint() {
        var yaml =
                """
                on: pull_request
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT);
            assertThat(f.payload()).containsEntry("triggerKind", "pull_request");
            assertThat(f.payload()).containsEntry("triggerTrust", "trusted");
        });
    }

    @Test
    void triggerAsArrayEmitsEntryPointForEachTrigger() {
        // on: [push, pull_request_target] — array format
        var yaml =
                """
                on: [push, pull_request_target]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        var entryPoints = facts.stream()
                .filter(f -> f.factKind() == SystemModelFactKind.ENTRY_POINT)
                .toList();
        assertThat(entryPoints)
                .hasSize(2)
                .anySatisfy(f -> assertThat(f.payload()).containsEntry("triggerTrust", "trusted"))
                .anySatisfy(f -> assertThat(f.payload()).containsEntry("triggerTrust", "untrusted"));
    }

    // ── Self-hosted runner as array ───────────────────────────────────────────

    @Test
    void selfHostedRunnerAsArrayEmitsTrustBoundary() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: [self-hosted, linux, x64]
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("runnerTrustLevel", "untrusted");
        });
    }

    // ── Job-level OIDC and secrets:inherit ────────────────────────────────────

    @Test
    void jobLevelOidcIdTokenWriteEmitsSecretUsage() {
        var yaml =
                """
                on: push
                jobs:
                  deploy:
                    runs-on: ubuntu-latest
                    permissions:
                      id-token: write
                      contents: read
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "oidc");
            assertThat(f.payload()).containsEntry("secretRef", "id-token");
        });
    }

    @Test
    void secretsInheritInJobEmitsSecretUsage() {
        var yaml =
                """
                on: workflow_call
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    secrets: inherit
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "inherit");
        });
    }

    // ── Deploy keyword in uses field ──────────────────────────────────────────

    @Test
    void deployActionInUsesFieldEmitsDataFlow() {
        var yaml =
                """
                on: push
                jobs:
                  release:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: docker/login-action@v3
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.DATA_FLOW);
            assertThat(f.payload()).containsEntry("artifactKind", "deploy-step");
        });
    }

    // ── Secret reference in step 'with' field ─────────────────────────────────

    @Test
    void secretRefInWithFieldEmitsSecretUsage() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: some-org/some-action@v1
                        with:
                          token: ${{ secrets.GITHUB_TOKEN }}
                """;
        var facts = normalize(yaml);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "GITHUB_TOKEN");
            assertThat(f.payload()).containsEntry("exposurePath", "step.with");
        });
    }

    // ── Permissions without id-token:write produces no OIDC fact ─────────────

    @Test
    void topLevelPermissionsWithoutIdTokenWriteDoesNotEmitOidcFact() {
        var yaml =
                """
                on: push
                permissions:
                  contents: read
                  packages: write
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """;
        var facts = normalize(yaml);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretScope", "oidc");
        });
    }

    // ── Empty workflow ────────────────────────────────────────────────────────

    @Test
    void emptyWorkflowYamlReturnsEmptyList() {
        var facts = normalize("");

        assertThat(facts).isEmpty();
    }

    // ── Finding 1: fact-key stability across commits ──────────────────────────

    @Test
    void factKeyIsStableAcrossDifferentCommitShas() {
        var yaml =
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: some-third-party/action@v1
                        with:
                          token: ${{ secrets.MY_TOKEN }}
                """;
        var factsA = new GitHubActionsNormalizer()
                .normalize(SURFACE, PATH, yaml, ADAPTER_ID, "sha-aaaa", RULESET_VERSION, NOW);
        var factsB = new GitHubActionsNormalizer()
                .normalize(SURFACE, PATH, yaml, ADAPTER_ID, "sha-bbbb", RULESET_VERSION, NOW);

        assertThat(factsA).hasSameSizeAs(factsB);
        var keysA = factsA.stream().map(DerivedSystemModelFact::factKey).toList();
        var keysB = factsB.stream().map(DerivedSystemModelFact::factKey).toList();
        assertThat(keysA).containsExactlyInAnyOrderElementsOf(keysB);
    }
}

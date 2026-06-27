package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.CaptureLimitReason;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IacPipelineDerivationAdapterTest {

    private static final String COMMIT = "abc123def456abc123def456abc123def456abc1";

    @TempDir
    Path repoRoot;

    @Test
    void descriptorDeclaresIacDerivationShape() {
        var adapter = adapter(props());

        var descriptor = adapter.descriptor();

        assertThat(descriptor.adapterId()).isEqualTo("iac-pipeline-derivation");
        assertThat(descriptor.toolName()).isEqualTo("iac-pipeline");
        assertThat(descriptor.languages()).containsExactlyInAnyOrder("yaml", "dockerfile", "hcl");
        assertThat(descriptor.surfaces())
                .containsExactlyInAnyOrder("github-actions", "dockerfile", "docker-compose", "terraform");
        assertThat(descriptor.scopeModes())
                .containsExactlyInAnyOrder(
                        DerivationScopeMode.FULL_REPO, DerivationScopeMode.DIFF, DerivationScopeMode.PATH_SET);
        assertThat(descriptor.factKinds())
                .containsExactlyInAnyOrder(
                        SystemModelFactKind.COMPONENT,
                        SystemModelFactKind.TRUST_BOUNDARY,
                        SystemModelFactKind.DATA_FLOW,
                        SystemModelFactKind.ENTRY_POINT,
                        SystemModelFactKind.SECRET_USAGE,
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        SystemModelFactKind.DATA_CLASSIFICATION_HINT);
    }

    @Test
    void availableWhenEnabledAndRepoRootExists() {
        var props = props();
        props.setRepositoryRoot(repoRoot);
        var adapter = new IacPipelineDerivationAdapter(props);

        assertThat(adapter.isAvailable()).isTrue();
    }

    @Test
    void notAvailableWhenDisabled() {
        var props = props();
        props.setEnabled(false);
        props.setRepositoryRoot(repoRoot);
        var adapter = new IacPipelineDerivationAdapter(props);

        assertThat(adapter.isAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenRepoRootMissing() {
        var props = props();
        props.setRepositoryRoot(repoRoot.resolve("does-not-exist"));
        var adapter = new IacPipelineDerivationAdapter(props);

        assertThat(adapter.isAvailable()).isFalse();
    }

    @Test
    void fullRepoModeDiscoversGitHubActionsFiles() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflowDir.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);
        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        assertThat(result.facts()).anySatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.ENTRY_POINT));
    }

    @Test
    void fullRepoModeExcludesNodeModulesDir() throws Exception {
        var nodeModulesWorkflows = repoRoot.resolve("node_modules/.github/workflows");
        Files.createDirectories(nodeModulesWorkflows);
        Files.writeString(
                nodeModulesWorkflows.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);
        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void fullRepoModeExcludesClaudeWorktreesDir() throws Exception {
        var worktreeWorkflows = repoRoot.resolve(".claude/worktrees/agent-abc/.github/workflows");
        Files.createDirectories(worktreeWorkflows);
        Files.writeString(
                worktreeWorkflows.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);
        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void oversizedFileEmitsCaptureLimitNotFacts() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflowDir.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);

        var props = props();
        props.setMaxFileBytes(4); // extremely small limit
        var adapter = adapter(props);
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        assertThat(result.facts()).isEmpty();
        assertThat(result.captureLimits())
                .anySatisfy(l -> assertThat(l.reason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED));
    }

    @Test
    void unsupportedSurfaceEmitsCaptureLimitNotFacts() {
        var props = props();
        var adapter = adapter(props);
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("kubernetes")));

        assertThat(result.facts()).isEmpty();
        assertThat(result.captureLimits())
                .anySatisfy(l -> assertThat(l.reason()).isEqualTo(CaptureLimitReason.UNSUPPORTED_SURFACE));
    }

    @Test
    void pathSetModeOnlyProcessesRequestedPaths() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflowDir.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);
        Files.writeString(
                workflowDir.resolve("deploy.yml"),
                """
                on: push
                jobs:
                  deploy:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                """);

        var adapter = adapter(props());
        // Only request the ci.yml path
        var result = adapter.derive(
                request(DerivationScopeMode.PATH_SET, List.of(".github/workflows/ci.yml"), Set.of("github-actions")));

        // Should only see facts from ci.yml, not deploy.yml
        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).isEqualTo(".github/workflows/ci.yml"));
    }

    @Test
    void parserFailureEmitsSanitizedCaptureLimitNotException() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        // Invalid YAML that will cause parser failure
        Files.writeString(workflowDir.resolve("bad.yml"), "{ this is: [not: valid: yaml\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        // No exception should propagate; capture limit should be emitted
        assertThat(result.captureLimits())
                .anySatisfy(l -> assertThat(l.reason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED));
    }

    @Test
    void sanitizationTestSecretValueNeverAppearsInPayload() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflowDir.resolve("ci.yml"),
                """
                on: push
                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Run tests
                        env:
                          MY_SECRET: ${{ secrets.MY_API_KEY }}
                          PLAIN_VALUE: actual-secret-value-12345
                """);

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        // The literal value "actual-secret-value-12345" must never appear in any payload
        for (var fact : result.facts()) {
            assertThat(fact.payload().toString()).doesNotContain("actual-secret-value-12345");
        }
        for (var limit : result.captureLimits()) {
            if (limit.detail() != null) {
                assertThat(limit.detail()).doesNotContain("actual-secret-value-12345");
            }
        }
    }

    // ── Finding 2a: maxFiles cap emits capture limit ──────────────────────────

    @Test
    void maxFilesCapEmitsCaptureLimitWhenMoreFilesRemain() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        for (int i = 0; i < 5; i++) {
            Files.writeString(
                    workflowDir.resolve("ci" + i + ".yml"),
                    """
                    on: push
                    jobs:
                      build:
                        runs-on: ubuntu-latest
                        steps:
                          - uses: actions/checkout@v4
                    """);
        }

        var props = props();
        props.setMaxFiles(2); // cap at 2, but 5 files exist
        var adapter = adapter(props);
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("github-actions")));

        assertThat(result.captureLimits()).anySatisfy(l -> {
            assertThat(l.reason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED);
            assertThat(l.surface()).isNull();
            assertThat(l.detail()).contains("maxFiles=2");
        });
    }

    // ── Finding 2b: malformed Compose YAML emits capture limit ───────────────

    @Test
    void malformedComposeFileEmitsCaptureLimitNotSilentEmpty() throws Exception {
        Files.writeString(repoRoot.resolve("docker-compose.yml"), "{ this is: [not: valid: yaml\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("docker-compose")));

        assertThat(result.facts()).isEmpty();
        assertThat(result.captureLimits()).anySatisfy(l -> {
            assertThat(l.reason()).isEqualTo(CaptureLimitReason.TOOL_EXECUTION_FAILED);
            // Raw YAML content must not leak into the limit detail
            assertThat(l.detail()).doesNotContain("this is:");
            assertThat(l.detail()).doesNotContain("not: valid");
        });
    }

    private IacPipelineDerivationAdapter adapter(IacPipelineDerivationProperties props) {
        props.setRepositoryRoot(repoRoot);
        return new IacPipelineDerivationAdapter(props);
    }

    private IacPipelineDerivationProperties props() {
        return new IacPipelineDerivationProperties();
    }

    private static DerivationAdapterRequest request(
            DerivationScopeMode mode, List<String> paths, Set<String> surfaces) {
        return new DerivationAdapterRequest(
                UUID.randomUUID(),
                "test-project",
                new DerivationScope(mode, COMMIT, null, paths, Set.of("yaml", "dockerfile", "hcl"), surfaces));
    }
}

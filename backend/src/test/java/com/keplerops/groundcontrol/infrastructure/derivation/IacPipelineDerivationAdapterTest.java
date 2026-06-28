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

    // ── Finding 2 (cycle 2): excluded directory pruning ──────────────────────

    @Test
    void excludedDirectoryIsPrunedNotJustFiltered() throws Exception {
        // A Dockerfile nested inside an excluded directory (node_modules/sub)
        var excludedSubDir = repoRoot.resolve("node_modules/sub");
        Files.createDirectories(excludedSubDir);
        Files.writeString(excludedSubDir.resolve("Dockerfile"), "FROM ubuntu:22.04\n");

        // A legitimate Dockerfile at the repo root
        Files.writeString(repoRoot.resolve("Dockerfile"), "FROM alpine:3\n");

        // With maxFiles=1, the excluded subtree must not consume the cap.
        // If node_modules were entered and its Dockerfile counted before exclusion,
        // the root-level Dockerfile would be dropped.  With directory pruning the
        // excluded subtree is never entered, so the root Dockerfile is processed.
        var props = props();
        props.setMaxFiles(1);
        var adapter = adapter(props);
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("dockerfile")));

        // The excluded Dockerfile must produce no facts
        assertThat(result.facts()).noneMatch(f -> f.sourcePath().contains("node_modules"));
        // The root Dockerfile must still be derived (maxFiles cap not exhausted by exclusion)
        assertThat(result.facts()).isNotEmpty();
        // No maxFiles capture limit should be emitted (the cap was not actually hit)
        assertThat(result.captureLimits())
                .noneMatch(l -> l.detail() != null && l.detail().contains("maxFiles=1"));
    }

    // ── Finding 3 (cycle 2): PATH_SET/DIFF scope path hardening ─────────────

    @Test
    void pathSetModeDotDotInRequestedPathMatchesNothing() throws Exception {
        // A Terraform file that would match if ".." were not rejected
        var tfDir = repoRoot.resolve("terraform");
        Files.createDirectories(tfDir);
        Files.writeString(tfDir.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {}\n");

        var adapter = adapter(props());
        // Requesting ".." or a path with ".." components must match nothing (fail closed)
        var result = adapter.derive(request(DerivationScopeMode.PATH_SET, List.of(".."), Set.of("terraform")));

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void pathSetModeSiblingDirNotMatchedByPrefixPath() throws Exception {
        // terraform-modules/ is a sibling of terraform/ — a prefix request for "terraform"
        // must not pull in files from terraform-modules/
        var tfModulesDir = repoRoot.resolve("terraform-modules");
        Files.createDirectories(tfModulesDir);
        Files.writeString(tfModulesDir.resolve("x.tf"), "resource \"aws_s3_bucket\" \"b\" {}\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.PATH_SET, List.of("terraform"), Set.of("terraform")));

        assertThat(result.facts()).noneMatch(f -> f.sourcePath().startsWith("terraform-modules"));
        assertThat(result.facts()).isEmpty();
    }

    @Test
    void pathSetModePrefixPathMatchesNestedFile() throws Exception {
        // Requesting "terraform" as a scope path must include terraform/main.tf
        var tfDir = repoRoot.resolve("terraform");
        Files.createDirectories(tfDir);
        // Multi-line block so the line-by-line HCL parser sees a block header on its own line
        Files.writeString(
                tfDir.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"my-bucket\"\n}\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.PATH_SET, List.of("terraform"), Set.of("terraform")));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).startsWith("terraform/"));
    }

    // ── Finding (cycle 3): adapter honors requested language scope ───────────

    @Test
    void languageScopedRunDoesNotDeriveOtherGrammars() throws Exception {
        // A Terraform file (hcl) and a Dockerfile (dockerfile) both present.
        var tfDir = repoRoot.resolve("terraform");
        Files.createDirectories(tfDir);
        Files.writeString(
                tfDir.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"my-bucket\"\n}\n");
        Files.writeString(repoRoot.resolve("Dockerfile"), "FROM alpine:3\n");

        var adapter = adapter(props());
        // Scope declares only "hcl" with no explicit surfaces — the Dockerfile must be excluded.
        var request = new DerivationAdapterRequest(
                UUID.randomUUID(),
                "test-project",
                new DerivationScope(DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("hcl"), Set.of()));
        var result = adapter.derive(request);

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).startsWith("terraform/"));
        assertThat(result.facts()).noneMatch(f -> f.sourcePath().endsWith("Dockerfile"));
    }

    // ── DIFF scope mode ───────────────────────────────────────────────────────

    @Test
    void diffModeWithMatchingPathsEmitsFacts() throws Exception {
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
        var result = adapter.derive(
                request(DerivationScopeMode.DIFF, List.of(".github/workflows/ci.yml"), Set.of("github-actions")));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).isEqualTo(".github/workflows/ci.yml"));
    }

    @Test
    void diffModeWithEmptyPathsSkipsAllFiles() throws Exception {
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
        var result = adapter.derive(request(DerivationScopeMode.DIFF, List.of(), Set.of("github-actions")));

        assertThat(result.facts()).isEmpty();
    }

    // ── normalizeScopePath edge cases ─────────────────────────────────────────

    @Test
    void nullEntryInRequestedPathsIsSkipped() throws Exception {
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
        // null path entry must be rejected (fail-closed) without throwing
        List<String> pathsWithNull = new java.util.ArrayList<>();
        pathsWithNull.add(null);
        pathsWithNull.add(".github/workflows/ci.yml");
        var result = adapter.derive(new DerivationAdapterRequest(
                UUID.randomUUID(),
                "test-project",
                new DerivationScope(
                        DerivationScopeMode.PATH_SET,
                        COMMIT,
                        null,
                        pathsWithNull,
                        Set.of("yaml"),
                        Set.of("github-actions"))));

        // null entry is skipped; valid entry still works
        assertThat(result.facts()).isNotEmpty();
    }

    @Test
    void absolutePathInRequestedPathsIsRejected() throws Exception {
        var tfDir = repoRoot.resolve("terraform");
        Files.createDirectories(tfDir);
        Files.writeString(tfDir.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"x\"\n}\n");

        var adapter = adapter(props());
        // An absolute path must be rejected fail-closed (treated as no match)
        var result = adapter.derive(
                request(DerivationScopeMode.PATH_SET, List.of("/absolute/path/terraform"), Set.of("terraform")));

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void emptyStringAfterDotSlashStrippingIsRejected() throws Exception {
        // "./" stripped becomes "" → normalizeScopePath returns empty → no files matched
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
        var result = adapter.derive(request(DerivationScopeMode.PATH_SET, List.of("./"), Set.of("github-actions")));

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void dotSlashPrefixInRequestedPathIsNormalized() throws Exception {
        var tfDir = repoRoot.resolve("terraform");
        Files.createDirectories(tfDir);
        Files.writeString(tfDir.resolve("main.tf"), "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"x\"\n}\n");

        var adapter = adapter(props());
        // "./terraform" must be normalized to "terraform" and match terraform/main.tf
        var result = adapter.derive(request(DerivationScopeMode.PATH_SET, List.of("./terraform"), Set.of("terraform")));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).startsWith("terraform/"));
    }

    // ── classifySurface edge cases ────────────────────────────────────────────

    @Test
    void gitHubActionsWorkflowWithYamlExtensionIsDiscovered() throws Exception {
        var workflowDir = repoRoot.resolve(".github/workflows");
        Files.createDirectories(workflowDir);
        Files.writeString(
                workflowDir.resolve("ci.yaml"), // .yaml not .yml
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
    void dockerfileWithDotSuffixIsDiscovered() throws Exception {
        // "Dockerfile.prod" matches filenameLower.startsWith("dockerfile.")
        Files.writeString(repoRoot.resolve("Dockerfile.prod"), "FROM alpine:3\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("dockerfile")));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).contains("Dockerfile.prod"));
    }

    @Test
    void dockerfileWithDotPrefixIsDiscovered() throws Exception {
        // "prod.dockerfile" matches filenameLower.endsWith(".dockerfile")
        Files.writeString(repoRoot.resolve("prod.dockerfile"), "FROM alpine:3\n");

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("dockerfile")));

        assertThat(result.facts()).isNotEmpty();
        assertThat(result.facts()).allSatisfy(f -> assertThat(f.sourcePath()).contains("prod.dockerfile"));
    }

    @Test
    void composeFileWithYamlExtensionIsDiscovered() throws Exception {
        // "compose.yaml" matches docker-compose surface
        Files.writeString(
                repoRoot.resolve("compose.yaml"),
                """
                services:
                  app:
                    image: myapp:latest
                """);

        var adapter = adapter(props());
        var result = adapter.derive(request(DerivationScopeMode.FULL_REPO, List.of(), Set.of("docker-compose")));

        assertThat(result.facts()).anySatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT));
    }

    // ── IacPipelineDerivationProperties null-setter coverage ─────────────────

    @Test
    void setExcludedPathsWithNullResultsInEmptyList() {
        var props = new IacPipelineDerivationProperties();
        props.setExcludedPaths(null);
        assertThat(props.getExcludedPaths()).isEmpty();
    }

    @Test
    void setExcludedPathsWithListPreservesEntries() {
        var props = new IacPipelineDerivationProperties();
        props.setExcludedPaths(List.of("vendor", "dist"));
        assertThat(props.getExcludedPaths()).containsExactly("vendor", "dist");
    }

    @Test
    void setEnabledSurfacesWithNullResultsInEmptyList() {
        var props = new IacPipelineDerivationProperties();
        props.setEnabledSurfaces(null);
        assertThat(props.getEnabledSurfaces()).isEmpty();
    }

    @Test
    void setEnabledSurfacesWithListPreservesEntries() {
        var props = new IacPipelineDerivationProperties();
        props.setEnabledSurfaces(List.of("dockerfile", "terraform"));
        assertThat(props.getEnabledSurfaces()).containsExactly("dockerfile", "terraform");
    }

    @Test
    void setRulesetVersionIsReflectedByGetter() {
        var props = new IacPipelineDerivationProperties();
        props.setRulesetVersion("2.0.0");
        assertThat(props.getRulesetVersion()).isEqualTo("2.0.0");
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

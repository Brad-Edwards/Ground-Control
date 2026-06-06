#!/usr/bin/env node
import { createHash } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "../..");
const packsRoot = join(repoRoot, "workflow/packs");
const catalogPath = join(repoRoot, "workflow/gate-catalog.json");
const version = "1.0.0";
const compatibleEngine = ">=1.0.0 <2.0.0";

const capabilities = [
  "format",
  "lint",
  "build",
  "type_safety",
  "unit_tests",
  "integration_tests",
  "contract_boundary",
  "property_verification",
  "architecture",
  "complexity",
  "mutation",
  "diff_coverage",
  "sast",
  "secret_scan",
  "dependency_policy",
  "accessibility",
  "docs_policy",
  "traceability",
  "policy",
  "remote_status",
];

function provided(provider, command, paths, options = {}) {
  return {
    status: "provided",
    provider,
    command,
    blocking: options.blocking ?? true,
    scope: options.scope ?? "changed",
    applies_when: { paths },
    thresholds: options.thresholds ?? {
      platform_minimum: { metric: "exit_code", max: 0 },
      recommendation: { metric: "exit_code", max: 0 },
    },
    ...(options.timeout_seconds ? { timeout_seconds: options.timeout_seconds } : {}),
    ...(options.required_statuses ? { required_statuses: options.required_statuses } : {}),
  };
}

function missing(reason = "No deterministic default provider is safe across all repositories for this capability.") {
  return {
    status: "provider_missing",
    provider_missing: "reviewer_fallback",
    blocking: false,
    reason,
    thresholds: {
      platform_minimum: { metric: "provider_declared", policy: "reviewer_fallback" },
      recommendation: { metric: "provider_declared", policy: "deterministic_provider_when_adopted" },
    },
  };
}

function notApplicable(reason = "Capability is not applicable for this pack by default.") {
  return {
    status: "not_applicable",
    provider_missing: "not_applicable",
    blocking: false,
    reason,
    thresholds: {
      platform_minimum: { metric: "applicability", policy: "not_applicable" },
      recommendation: { metric: "applicability", policy: "not_applicable" },
    },
  };
}

function remoteStatus() {
  return {
    status: "provided",
    provider: "required-statuses",
    blocking: true,
    scope: "repo",
    required_statuses: ["ci"],
    thresholds: {
      platform_minimum: { metric: "required_statuses", policy: "all_success" },
      recommendation: { metric: "required_statuses", policy: "all_success" },
    },
  };
}

function withAll(bindings) {
  const out = {};
  for (const cap of capabilities) out[cap] = bindings[cap] ?? missing();
  return out;
}

const commonCodePaths = ["**/*"];
const docsPaths = ["*.md", "**/*.md", "*.markdown", "**/*.markdown", "docs/**", "architecture/adrs/**", ".ground-control.yaml"];

const packs = [
  {
    id: "rust-cargo",
    name: "Rust Cargo gate pack",
    description: "Cargo workspace gates grounded in capcom and aphelion kernel workflows.",
    anchors: [
      "capcom: cargo fmt --all --check, cargo clippy --workspace --all-targets -- -D warnings, cargo build --workspace --all-targets, cargo test --workspace --all-targets",
      "aphelion kernel: Rust kernel under kernel/ chained after Gradle",
    ],
    profiles: [
      { id: "default", description: "Cargo workspace or single crate." },
      { id: "kernel", description: "Rust kernel scoped below a mixed-language repository." },
    ],
    install: {
      templates: [
        { source: "templates/deny.toml", target: ".gc/gate-packs/rust-cargo/deny.toml" },
      ],
      dev_dependencies: [],
    },
    selftestTools: [{ name: "cargo", command: "cargo", args: ["--version"] }],
    selftest: {
      passCapability: "unit_tests",
      failCapability: "unit_tests",
      missingCapability: "contract_boundary",
      changedFiles: ["src/lib.rs", "tests/smoke.rs"],
      files: {
        "Cargo.toml": "[package]\nname = \"gc-rust-pack-selftest\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[lib]\npath = \"src/lib.rs\"\n",
        "src/lib.rs": "pub fn add(left: i32, right: i32) -> i32 {\n    left + right\n}\n",
        "tests/smoke.rs": "use gc_rust_pack_selftest::add;\n\n#[test]\nfn adds_numbers() {\n    assert_eq!(add(2, 2), 4);\n}\n",
      },
      failFiles: {
        "tests/smoke.rs": "use gc_rust_pack_selftest::add;\n\n#[test]\nfn adds_numbers() {\n    assert_eq!(add(2, 2), 5);\n}\n",
      },
    },
    bindings: withAll({
      format: provided("cargo-fmt", "cargo fmt --all --check", ["**/*.rs", "Cargo.toml", "Cargo.lock"]),
      build: provided("cargo-build", "if [ -f Cargo.lock ]; then cargo build --workspace --all-targets --locked; else cargo build --workspace --all-targets; fi", ["**/*.rs", "Cargo.toml", "Cargo.lock"], { timeout_seconds: 1200 }),
      type_safety: provided("cargo-check", "cargo check --workspace --all-targets", ["**/*.rs", "Cargo.toml", "Cargo.lock"]),
      lint: provided("cargo-clippy", "cargo clippy --workspace --all-targets -- -D warnings", ["**/*.rs", "Cargo.toml", "Cargo.lock"], {
        thresholds: {
          platform_minimum: { metric: "warning_count", max: 0 },
          recommendation: { metric: "clippy_warnings", max: 0 },
        },
      }),
      unit_tests: provided("cargo-test", "cargo test --workspace --all-targets", ["**/*.rs", "Cargo.toml", "Cargo.lock"], { timeout_seconds: 1200 }),
      integration_tests: provided("cargo-test-integration", "cargo test --workspace --tests", ["**/*.rs", "tests/**", "Cargo.toml", "Cargo.lock"], { timeout_seconds: 1200 }),
      contract_boundary: missing("Rust boundary contracts are repository-specific: type-level validation, Result boundaries, runtime guards, and tests."),
      property_verification: provided("proptest-or-quickcheck", "cargo test --workspace --all-targets --features property-tests", ["**/*.rs", "Cargo.toml", "Cargo.lock"], { blocking: false }),
      architecture: provided("cargo-workspace-policy", "cargo test --workspace --all-targets arch", ["**/*.rs", "Cargo.toml", "Cargo.lock"], { blocking: false }),
      complexity: provided("cargo-clippy-complexity", "cargo clippy --workspace --all-targets -- -D warnings", ["**/*.rs", "Cargo.toml", "Cargo.lock"]),
      mutation: provided("cargo-mutants", "cargo mutants --in-place --timeout 60", ["**/*.rs", "Cargo.toml", "Cargo.lock"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 60 },
          recommendation: { metric: "mutation_score", min: 80 },
        },
      }),
      diff_coverage: provided("cargo-llvm-cov-diff", "cargo llvm-cov --workspace --lcov --output-path .gc/reports/rust/lcov.info && diff-cover .gc/reports/rust/lcov.info --fail-under=90", ["**/*.rs"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("semgrep-rust", "semgrep --config p/rust --error", ["**/*.rs", "Cargo.toml"]),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("cargo-audit-deny", "if command -v cargo-deny >/dev/null 2>&1; then cargo deny check; else cargo audit; fi", ["Cargo.toml", "Cargo.lock", "**/Cargo.toml"], {
        thresholds: {
          platform_minimum: { metric: "vulnerability_severity", severity: "high" },
          recommendation: { metric: "vulnerability_severity", severity: "medium" },
        },
      }),
      accessibility: notApplicable("Rust Cargo projects do not expose a rendered UI accessibility surface by default."),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else echo 'No repo policy command found'; exit 1; fi", commonCodePaths, { scope: "repo" }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/deny.toml": "[advisories]\nyanked = \"warn\"\nignore = []\n\n[licenses]\nunlicensed = \"deny\"\nallow = [\"Apache-2.0\", \"MIT\", \"BSD-2-Clause\", \"BSD-3-Clause\", \"ISC\"]\nconfidence-threshold = 0.8\n",
    },
  },
  {
    id: "python",
    name: "Python gate pack",
    description: "Python package gates grounded in aptl and aces-sdl verification graphs.",
    anchors: [
      "aptl: pytest, pre-commit, Ruff, Hypothesis, Pydantic, icontract",
      "aces-sdl: nox verification graph through uv",
    ],
    profiles: [
      { id: "default", description: "Python application or package." },
      { id: "uv", description: "uv-managed project." },
      { id: "nox", description: "nox-owned verification graph." },
    ],
    install: {
      templates: [
        { source: "templates/pyproject.gc.toml", target: ".gc/gate-packs/python/pyproject.gc.toml" },
        { source: "templates/importlinter.ini", target: ".gc/gate-packs/python/importlinter.ini" },
      ],
      dev_dependencies: ["ruff", "pytest", "pytest-cov", "hypothesis", "diff-cover", "import-linter", "bandit", "pip-audit"],
    },
    selftestTools: [
      { name: "python3", command: "python3", args: ["--version"] },
      { name: "pytest", command: "python3", args: ["-m", "pytest", "--version"] },
    ],
    selftest: {
      passCapability: "unit_tests",
      failCapability: "unit_tests",
      missingCapability: "contract_boundary",
      changedFiles: ["src/gc_pack_selftest/__init__.py", "tests/test_sample.py"],
      files: {
        "pyproject.toml": "[project]\nname = \"gc-pack-python-selftest\"\nversion = \"0.1.0\"\nrequires-python = \">=3.10\"\n\n[tool.pytest.ini_options]\npythonpath = [\"src\"]\n",
        "src/gc_pack_selftest/__init__.py": "def add(left: int, right: int) -> int:\n    return left + right\n",
        "tests/test_sample.py": "from gc_pack_selftest import add\n\n\ndef test_adds_numbers():\n    assert add(2, 2) == 4\n",
      },
      failFiles: {
        "tests/test_sample.py": "from gc_pack_selftest import add\n\n\ndef test_adds_numbers():\n    assert add(2, 2) == 5\n",
      },
    },
    bindings: withAll({
      format: provided("ruff-format", "ruff format --check .", ["**/*.py", "pyproject.toml"]),
      lint: provided("ruff-check", "ruff check .", ["**/*.py", "pyproject.toml"], {
        thresholds: {
          platform_minimum: { metric: "ruff_errors", max: 0 },
          recommendation: { metric: "ruff_errors", max: 0 },
        },
      }),
      build: provided("python-build", "python -m build", ["pyproject.toml", "setup.cfg", "setup.py", "**/*.py"], { blocking: false }),
      type_safety: provided("pyright-or-mypy", "if command -v pyright >/dev/null 2>&1; then pyright; else mypy --strict .; fi", ["**/*.py", "pyproject.toml"], {
        thresholds: {
          platform_minimum: { metric: "type_errors", max: 0 },
          recommendation: { metric: "type_errors", max: 0 },
        },
      }),
      unit_tests: provided("pytest", "pytest", ["**/*.py", "tests/**", "pyproject.toml"], { timeout_seconds: 1200 }),
      integration_tests: provided("pytest-integration", "pytest -m integration", ["**/*.py", "tests/**", "pyproject.toml"], { blocking: false, timeout_seconds: 1800 }),
      contract_boundary: missing("Python boundary contracts depend on the project's Pydantic/icontract/deal/runtime-guard choices."),
      property_verification: provided("hypothesis", "pytest -m fuzz", ["**/*.py", "tests/**", "pyproject.toml"], { blocking: false, timeout_seconds: 1800 }),
      architecture: provided("import-linter", "lint-imports --config .gc/gate-packs/python/importlinter.ini", ["**/*.py", "pyproject.toml"], { blocking: false }),
      complexity: provided("ruff-complexity", "ruff check --select C901 .", ["**/*.py", "pyproject.toml"], {
        thresholds: {
          platform_minimum: { metric: "mccabe_complexity", max: 15 },
          recommendation: { metric: "mccabe_complexity", max: 10 },
        },
      }),
      mutation: provided("mutmut", "mutmut run", ["**/*.py", "tests/**", "pyproject.toml"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 60 },
          recommendation: { metric: "mutation_score", min: 80 },
        },
      }),
      diff_coverage: provided("coverage-diff-cover", "coverage run -m pytest && coverage xml -o .gc/reports/python/coverage.xml && diff-cover .gc/reports/python/coverage.xml --fail-under=90", ["**/*.py", "tests/**"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("bandit", "bandit -r . -ll", ["**/*.py", "pyproject.toml"], {
        thresholds: {
          platform_minimum: { metric: "sast_severity", severity: "high" },
          recommendation: { metric: "sast_severity", severity: "medium" },
        },
      }),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("pip-audit-or-uv-audit", "if command -v uv >/dev/null 2>&1 && [ -f uv.lock ]; then uv audit; else pip-audit; fi", ["pyproject.toml", "requirements*.txt", "uv.lock", "poetry.lock"], {
        thresholds: {
          platform_minimum: { metric: "vulnerability_severity", severity: "high" },
          recommendation: { metric: "vulnerability_severity", severity: "medium" },
        },
      }),
      accessibility: notApplicable("Python projects do not expose a rendered UI accessibility surface by default."),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "if [ -f noxfile.py ]; then nox -s verify; elif [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else pre-commit run --all-files; fi", commonCodePaths, { scope: "repo", timeout_seconds: 1800 }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/pyproject.gc.toml": "[tool.coverage.run]\nbranch = true\nsource = [\"src\"]\n\n[tool.coverage.report]\nshow_missing = true\n\n[tool.ruff.lint]\nselect = [\"E\", \"F\", \"I\", \"B\", \"C901\", \"S\"]\n",
      "templates/importlinter.ini": "[importlinter]\nroot_package = src\n\n[importlinter:contract:layers]\nname = Layered architecture\nlayers = api; domain; infrastructure\n",
    },
  },
  {
    id: "jvm-gradle",
    name: "JVM Gradle gate pack",
    description: "Gradle JVM gates grounded in Ground-Control, gc, and aphelion.",
    anchors: [
      "Ground-Control/gc: Gradle Kotlin DSL, Spotless, Checkstyle, JaCoCo, PIT, jqwik, ArchUnit",
      "aphelion: Gradle with Spotless, SpotBugs, Error Prone, jqwik, and ArchUnit",
    ],
    profiles: [
      { id: "default", description: "Generic JVM Gradle project." },
      { id: "spring", description: "Spring or server-side JVM service." },
    ],
    install: {
      templates: [
        { source: "templates/gc-quality.gradle.kts", target: ".gc/gate-packs/jvm-gradle/gc-quality.gradle.kts" },
        { source: "templates/checkstyle.xml", target: ".gc/gate-packs/jvm-gradle/checkstyle.xml" },
        { source: "templates/pmd-ruleset.xml", target: ".gc/gate-packs/jvm-gradle/pmd-ruleset.xml" },
      ],
      dev_dependencies: [],
    },
    selftestTools: [
      { name: "java", command: "java", args: ["-version"] },
      { name: "gradle", command: "gradle", args: ["--version"] },
    ],
    selftest: {
      passCapability: "build",
      failCapability: "build",
      missingCapability: "contract_boundary",
      changedFiles: ["src/main/java/example/App.java"],
      files: {
        "settings.gradle": "pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }\nrootProject.name = 'gc-gradle-pack-selftest'\n",
        "build.gradle": "plugins { id 'java' }\n\njava { toolchain { languageVersion = JavaLanguageVersion.of(17) } }\n",
        "src/main/java/example/App.java": "package example;\n\npublic final class App {\n  private App() {}\n  public static int add(int left, int right) { return left + right; }\n}\n",
      },
      failFiles: {
        "src/main/java/example/App.java": "package example;\n\npublic final class App {\n  public static int add(int left, int right) { return left + ; }\n}\n",
      },
    },
    bindings: withAll({
      format: provided("gradle-spotless", "if [ -x ./gradlew ]; then ./gradlew spotlessCheck; else gradle spotlessCheck; fi", ["**/*.java", "**/*.kt", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"]),
      build: provided("gradle-build", "if [ -x ./gradlew ]; then ./gradlew build; else gradle build; fi", ["src/**", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"], { timeout_seconds: 1800 }),
      type_safety: provided("gradle-compile-static", "if [ -x ./gradlew ]; then ./gradlew compileJava compileKotlin; else gradle compileJava compileKotlin; fi", ["src/main/**", "build.gradle", "build.gradle.kts"], {
        thresholds: {
          platform_minimum: { metric: "compile_errors", max: 0 },
          recommendation: { metric: "nullness_errors", max: 0 },
        },
      }),
      lint: provided("gradle-checkstyle-pmd", "if [ -x ./gradlew ]; then ./gradlew checkstyleMain pmdMain; else gradle checkstyleMain pmdMain; fi", ["src/main/**", "config/**", "build.gradle", "build.gradle.kts"]),
      unit_tests: provided("gradle-test", "if [ -x ./gradlew ]; then ./gradlew test; else gradle test; fi", ["src/main/**", "src/test/**", "build.gradle", "build.gradle.kts"], { timeout_seconds: 1800 }),
      integration_tests: provided("gradle-integration", "if [ -x ./gradlew ]; then ./gradlew integrationTest; else gradle integrationTest; fi", ["src/main/**", "src/integrationTest/**", "build.gradle", "build.gradle.kts"], { blocking: false, timeout_seconds: 2400 }),
      contract_boundary: missing("JVM boundary contracts depend on repository-selected Bean Validation, JML, guard, or schema patterns."),
      property_verification: provided("jqwik", "if [ -x ./gradlew ]; then ./gradlew test --tests '*Properties'; else gradle test --tests '*Properties'; fi", ["src/main/**", "src/test/**", "build.gradle", "build.gradle.kts"], { blocking: false, timeout_seconds: 1800 }),
      architecture: provided("archunit", "if [ -x ./gradlew ]; then ./gradlew test --tests '*Architecture*'; else gradle test --tests '*Architecture*'; fi", ["src/main/**", "src/test/**", "build.gradle", "build.gradle.kts"], { blocking: false }),
      complexity: provided("checkstyle-pmd-complexity", "if [ -x ./gradlew ]; then ./gradlew checkstyleMain pmdMain; else gradle checkstyleMain pmdMain; fi", ["src/main/**", "config/**"], {
        thresholds: {
          platform_minimum: { metric: "cyclomatic_complexity", max: 15 },
          recommendation: { metric: "cyclomatic_complexity", max: 10 },
        },
      }),
      mutation: provided("pitest", "if [ -x ./gradlew ]; then ./gradlew pitest; else gradle pitest; fi", ["src/main/**", "src/test/**", "build.gradle", "build.gradle.kts"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 60 },
          recommendation: { metric: "mutation_score", min: 80 },
        },
      }),
      diff_coverage: provided("jacoco-diff-cover", "if [ -x ./gradlew ]; then ./gradlew test jacocoTestReport; else gradle test jacocoTestReport; fi && diff-cover build/reports/jacoco/test/jacocoTestReport.xml --fail-under=90", ["src/main/**", "src/test/**"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("spotbugs-semgrep", "if [ -x ./gradlew ]; then ./gradlew spotbugsMain; else gradle spotbugsMain; fi", ["src/main/**", "build.gradle", "build.gradle.kts"], {
        thresholds: {
          platform_minimum: { metric: "sast_severity", severity: "high" },
          recommendation: { metric: "sast_severity", severity: "medium" },
        },
      }),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("osv-gradle", "osv-scanner --lockfile=gradle.lockfile .", ["gradle.lockfile", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"], { blocking: false }),
      accessibility: notApplicable("JVM Gradle pack does not expose a rendered UI accessibility surface by default."),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else echo 'No repo policy command found'; exit 1; fi", commonCodePaths, { scope: "repo", timeout_seconds: 1800 }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/gc-quality.gradle.kts": "plugins {\n    java\n    checkstyle\n    pmd\n    jacoco\n}\n\ncheckstyle {\n    toolVersion = \"10.21.1\"\n    configFile = rootProject.file(\".gc/gate-packs/jvm-gradle/checkstyle.xml\")\n}\n\npmd {\n    toolVersion = \"7.10.0\"\n    ruleSetFiles = files(rootProject.file(\".gc/gate-packs/jvm-gradle/pmd-ruleset.xml\"))\n    ruleSets = emptyList()\n}\n",
      "templates/checkstyle.xml": "<?xml version=\"1.0\"?>\n<!DOCTYPE module PUBLIC \"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\" \"https://checkstyle.org/dtds/configuration_1_3.dtd\">\n<module name=\"Checker\">\n  <module name=\"TreeWalker\">\n    <module name=\"CyclomaticComplexity\"><property name=\"max\" value=\"15\"/></module>\n    <module name=\"MethodLength\"><property name=\"max\" value=\"60\"/></module>\n    <module name=\"ParameterNumber\"><property name=\"max\" value=\"7\"/></module>\n  </module>\n</module>\n",
      "templates/pmd-ruleset.xml": "<?xml version=\"1.0\"?>\n<ruleset name=\"Ground Control JVM rules\" xmlns=\"http://pmd.sourceforge.net/ruleset/2.0.0\">\n  <description>Pack-managed PMD design rules.</description>\n  <rule ref=\"category/java/design.xml/CyclomaticComplexity\"><properties><property name=\"methodReportLevel\" value=\"15\"/></properties></rule>\n  <rule ref=\"category/java/design.xml/ExcessiveMethodLength\" />\n</ruleset>\n",
    },
  },
  {
    id: "jvm-maven",
    name: "JVM Maven gate pack",
    description: "Maven JVM gates for platform completeness where no local consumer is currently present.",
    anchors: ["No local .ground-control.yaml Maven consumer was found; Maven support is required for portable JVM coverage."],
    profiles: [
      { id: "default", description: "Generic Maven JVM project." },
      { id: "multi-module", description: "Parent POM with child modules." },
    ],
    install: {
      templates: [
        { source: "templates/gc-quality-profile.xml", target: ".gc/gate-packs/jvm-maven/gc-quality-profile.xml" },
        { source: "templates/checkstyle.xml", target: ".gc/gate-packs/jvm-maven/checkstyle.xml" },
        { source: "templates/pmd-ruleset.xml", target: ".gc/gate-packs/jvm-maven/pmd-ruleset.xml" },
      ],
      dev_dependencies: [],
    },
    selftestTools: [
      { name: "java", command: "java", args: ["-version"] },
      { name: "mvn", command: "mvn", args: ["--version"] },
    ],
    selftest: {
      passCapability: "build",
      failCapability: "build",
      missingCapability: "contract_boundary",
      changedFiles: ["src/main/java/example/App.java"],
      files: {
        "pom.xml": "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>example</groupId>\n  <artifactId>gc-maven-pack-selftest</artifactId>\n  <version>0.1.0</version>\n  <properties><maven.compiler.release>17</maven.compiler.release></properties>\n</project>\n",
        "src/main/java/example/App.java": "package example;\n\npublic final class App {\n  private App() {}\n  public static int add(int left, int right) { return left + right; }\n}\n",
      },
      failFiles: {
        "src/main/java/example/App.java": "package example;\n\npublic final class App {\n  public static int add(int left, int right) { return left + ; }\n}\n",
      },
    },
    bindings: withAll({
      format: provided("maven-spotless", "mvn -B spotless:check", ["**/*.java", "pom.xml", "**/pom.xml"]),
      build: provided("maven-verify", "mvn -B verify", ["src/**", "pom.xml", "**/pom.xml"], { timeout_seconds: 1800 }),
      type_safety: provided("maven-compiler-static", "mvn -B -DskipTests compile", ["src/main/**", "pom.xml", "**/pom.xml"], {
        thresholds: {
          platform_minimum: { metric: "compile_errors", max: 0 },
          recommendation: { metric: "nullness_errors", max: 0 },
        },
      }),
      lint: provided("maven-checkstyle-pmd", "mvn -B checkstyle:check pmd:check", ["src/main/**", "pom.xml", "**/pom.xml"]),
      unit_tests: provided("maven-surefire", "mvn -B test", ["src/main/**", "src/test/**", "pom.xml", "**/pom.xml"], { timeout_seconds: 1800 }),
      integration_tests: provided("maven-failsafe", "mvn -B verify -DskipUnitTests", ["src/main/**", "src/it/**", "pom.xml", "**/pom.xml"], { blocking: false, timeout_seconds: 2400 }),
      contract_boundary: missing("JVM boundary contracts depend on repository-selected Bean Validation, JML, guard, or schema patterns."),
      property_verification: provided("jqwik", "mvn -B test -Dtest='*Properties'", ["src/main/**", "src/test/**", "pom.xml", "**/pom.xml"], { blocking: false }),
      architecture: provided("archunit", "mvn -B test -Dtest='*Architecture*'", ["src/main/**", "src/test/**", "pom.xml", "**/pom.xml"], { blocking: false }),
      complexity: provided("maven-checkstyle-pmd-complexity", "mvn -B checkstyle:check pmd:check", ["src/main/**", "pom.xml", "**/pom.xml"], {
        thresholds: {
          platform_minimum: { metric: "cyclomatic_complexity", max: 15 },
          recommendation: { metric: "cyclomatic_complexity", max: 10 },
        },
      }),
      mutation: provided("pitest-maven", "mvn -B org.pitest:pitest-maven:mutationCoverage", ["src/main/**", "src/test/**", "pom.xml", "**/pom.xml"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 60 },
          recommendation: { metric: "mutation_score", min: 80 },
        },
      }),
      diff_coverage: provided("jacoco-maven-diff-cover", "mvn -B test jacoco:report && diff-cover target/site/jacoco/jacoco.xml --fail-under=90", ["src/main/**", "src/test/**"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("spotbugs-maven", "mvn -B spotbugs:check", ["src/main/**", "pom.xml", "**/pom.xml"], {
        thresholds: {
          platform_minimum: { metric: "sast_severity", severity: "high" },
          recommendation: { metric: "sast_severity", severity: "medium" },
        },
      }),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("dependency-check-maven", "mvn -B org.owasp:dependency-check-maven:check", ["pom.xml", "**/pom.xml"], { blocking: false }),
      accessibility: notApplicable("JVM Maven pack does not expose a rendered UI accessibility surface by default."),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else echo 'No repo policy command found'; exit 1; fi", commonCodePaths, { scope: "repo", timeout_seconds: 1800 }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/gc-quality-profile.xml": "<profile>\n  <id>gc-quality</id>\n  <build><plugins><!-- Merge into pluginManagement when adopting this pack. --></plugins></build>\n</profile>\n",
      "templates/checkstyle.xml": "<?xml version=\"1.0\"?>\n<!DOCTYPE module PUBLIC \"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\" \"https://checkstyle.org/dtds/configuration_1_3.dtd\">\n<module name=\"Checker\"><module name=\"TreeWalker\"><module name=\"CyclomaticComplexity\"><property name=\"max\" value=\"15\"/></module></module></module>\n",
      "templates/pmd-ruleset.xml": "<?xml version=\"1.0\"?>\n<ruleset name=\"Ground Control Maven rules\" xmlns=\"http://pmd.sourceforge.net/ruleset/2.0.0\"><description>Pack-managed PMD rules.</description><rule ref=\"category/java/design.xml/CyclomaticComplexity\"/></ruleset>\n",
    },
  },
  {
    id: "node-ts",
    name: "Node TypeScript gate pack",
    description: "TypeScript and JavaScript gates grounded in pulsar, Ground-Control frontend, and aptl MCP/web workspaces.",
    anchors: [
      "pulsar: pnpm lint, pnpm typecheck, pnpm test, pnpm policy, Playwright checks, OSV",
      "Ground-Control frontend: Vite, TypeScript, Biome, Vitest",
      "aptl: multiple TypeScript MCP packages and a web frontend",
    ],
    profiles: [
      { id: "default", description: "Node package with package-manager scripts." },
      { id: "react-vite", description: "Vite/React frontend with accessibility profile." },
      { id: "node-library", description: "Node library or MCP package." },
    ],
    install: {
      templates: [
        { source: "templates/dependency-cruiser.cjs", target: ".gc/gate-packs/node-ts/dependency-cruiser.cjs" },
        { source: "templates/a11y.config.mjs", target: ".gc/gate-packs/node-ts/a11y.config.mjs" },
      ],
      dev_dependencies: ["typescript", "vitest", "fast-check", "dependency-cruiser", "@axe-core/playwright", "eslint-plugin-jsx-a11y", "stryker-cli"],
    },
    selftestTools: [
      { name: "node", command: "node", args: ["--version"] },
      { name: "npm", command: "npm", args: ["--version"] },
    ],
    selftest: {
      passCapability: "unit_tests",
      failCapability: "unit_tests",
      missingCapability: "contract_boundary",
      changedFiles: ["src/add.test.js", "src/add.js"],
      files: {
        "package.json": "{\n  \"name\": \"gc-node-pack-selftest\",\n  \"version\": \"0.1.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"test\": \"node --test\",\n    \"build\": \"node --check src/add.js\",\n    \"typecheck\": \"node --check src/add.js\"\n  }\n}\n",
        "src/add.js": "export function add(left, right) {\n  return left + right;\n}\n",
        "src/add.test.js": "import test from 'node:test';\nimport assert from 'node:assert/strict';\nimport { add } from './add.js';\n\ntest('adds numbers', () => {\n  assert.equal(add(2, 2), 4);\n});\n",
      },
      failFiles: {
        "src/add.test.js": "import test from 'node:test';\nimport assert from 'node:assert/strict';\nimport { add } from './add.js';\n\ntest('adds numbers', () => {\n  assert.equal(add(2, 2), 5);\n});\n",
      },
    },
    bindings: withAll({
      format: provided("biome-prettier-script", "npm run format:check --if-present || npm run format --if-present", ["**/*.ts", "**/*.tsx", "**/*.js", "**/*.jsx", "package.json", "biome.json", ".prettierrc*"]),
      lint: provided("biome-eslint", "npm run lint", ["**/*.ts", "**/*.tsx", "**/*.js", "**/*.jsx", "package.json", "biome.json", "eslint.config.*"], {
        thresholds: {
          platform_minimum: { metric: "lint_errors", max: 0 },
          recommendation: { metric: "lint_errors", max: 0 },
        },
      }),
      build: provided("package-build", "npm run build --if-present", ["src/**", "package.json", "tsconfig*.json", "vite.config.*"], { timeout_seconds: 1200 }),
      type_safety: provided("typescript", "npm run typecheck --if-present || npx tsc --noEmit", ["**/*.ts", "**/*.tsx", "tsconfig*.json", "package.json"], {
        thresholds: {
          platform_minimum: { metric: "type_errors", max: 0 },
          recommendation: { metric: "type_errors", max: 0 },
        },
      }),
      unit_tests: provided("node-test-runner", "npm test", ["src/**", "test/**", "tests/**", "package.json"], { timeout_seconds: 1200 }),
      integration_tests: provided("playwright-or-script", "npm run test:integration --if-present || npm run test:e2e --if-present", ["src/**", "tests/**", "playwright.config.*", "package.json"], { blocking: false, timeout_seconds: 1800 }),
      contract_boundary: missing("TypeScript boundary contracts depend on project-selected Zod, Valibot, assertion, or generated-contract conventions."),
      property_verification: provided("fast-check", "npm run test:property --if-present", ["src/**", "test/**", "tests/**", "package.json"], { blocking: false }),
      architecture: provided("dependency-cruiser", "npx dependency-cruiser --config .gc/gate-packs/node-ts/dependency-cruiser.cjs src", ["src/**", "package.json"], { blocking: false }),
      complexity: provided("biome-eslint-complexity", "npm run lint", ["**/*.ts", "**/*.tsx", "**/*.js", "**/*.jsx", "package.json"], {
        thresholds: {
          platform_minimum: { metric: "cyclomatic_complexity", max: 15 },
          recommendation: { metric: "cyclomatic_complexity", max: 10 },
        },
      }),
      mutation: provided("stryker-js", "npx stryker run", ["src/**", "test/**", "tests/**", "package.json"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 50 },
          recommendation: { metric: "mutation_score", min: 80 },
        },
      }),
      diff_coverage: provided("vitest-diff-cover", "npm run coverage --if-present && diff-cover coverage/lcov.info --fail-under=90", ["src/**", "test/**", "tests/**"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("semgrep-ts-js", "semgrep --config p/typescript --config p/javascript --error", ["**/*.ts", "**/*.tsx", "**/*.js", "**/*.jsx", "package.json"], {
        thresholds: {
          platform_minimum: { metric: "sast_severity", severity: "high" },
          recommendation: { metric: "sast_severity", severity: "medium" },
        },
      }),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("npm-audit-osv", "npm audit --audit-level=high", ["package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock"], {
        thresholds: {
          platform_minimum: { metric: "vulnerability_severity", severity: "high" },
          recommendation: { metric: "vulnerability_severity", severity: "medium" },
        },
      }),
      accessibility: provided("jsx-a11y-playwright-axe", "npm run test:a11y --if-present", ["src/**/*.tsx", "src/**/*.jsx", "playwright.config.*", "package.json"], {
        blocking: false,
        thresholds: {
          platform_minimum: { metric: "wcag_violations", max: 0 },
          recommendation: { metric: "wcag_2_1_aa_violations", max: 0 },
        },
      }),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "npm run policy --if-present || (if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; else echo 'No repo policy command found'; exit 1; fi)", commonCodePaths, { scope: "repo", timeout_seconds: 1800 }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/dependency-cruiser.cjs": "module.exports = {\n  forbidden: [\n    { name: 'no-circular', severity: 'error', from: {}, to: { circular: true } },\n    { name: 'no-orphans', severity: 'warn', from: { orphan: true }, to: {} }\n  ],\n  options: { doNotFollow: { path: 'node_modules' } }\n};\n",
      "templates/a11y.config.mjs": "export default {\n  standard: 'WCAG2AA',\n  include: ['src'],\n  exclude: ['node_modules']\n};\n",
    },
  },
  {
    id: "cpp-cmake",
    name: "C/C++ CMake gate pack",
    description: "C and C++ CMake gates grounded in kuzu's CMake, CTest, clang-tidy, sanitizer, WERROR, and coverage workflows.",
    anchors: [
      "kuzu: Makefile frontend to CMake, CTest, clang-tidy, sanitizer switches, ENABLE_WERROR, LCOV targets",
    ],
    profiles: [
      { id: "default", description: "CMake project with CTest." },
      { id: "werror", description: "Warnings-as-errors build profile." },
    ],
    install: {
      templates: [
        { source: "templates/clang-tidy", target: ".gc/gate-packs/cpp-cmake/clang-tidy" },
        { source: "templates/clang-format", target: ".gc/gate-packs/cpp-cmake/clang-format" },
        { source: "templates/gc-quality.cmake", target: ".gc/gate-packs/cpp-cmake/gc-quality.cmake" },
      ],
      dev_dependencies: [],
    },
    selftestTools: [
      { name: "cmake", command: "cmake", args: ["--version"] },
      { name: "ctest", command: "ctest", args: ["--version"] },
      { name: "c++", command: "c++", args: ["--version"] },
    ],
    selftest: {
      passCapability: "unit_tests",
      failCapability: "unit_tests",
      missingCapability: "contract_boundary",
      changedFiles: ["src/add.cpp", "tests/add_test.cpp", "CMakeLists.txt"],
      files: {
        "CMakeLists.txt": "cmake_minimum_required(VERSION 3.16)\nproject(gc_cpp_pack_selftest LANGUAGES CXX)\nset(CMAKE_CXX_STANDARD 17)\nset(CMAKE_CXX_STANDARD_REQUIRED ON)\nenable_testing()\nadd_library(add src/add.cpp)\ntarget_include_directories(add PUBLIC include)\nadd_executable(add_test tests/add_test.cpp)\ntarget_link_libraries(add_test PRIVATE add)\nadd_test(NAME add_test COMMAND add_test)\n",
        "include/add.hpp": "#pragma once\nint add(int left, int right);\n",
        "src/add.cpp": "#include \"add.hpp\"\nint add(int left, int right) { return left + right; }\n",
        "tests/add_test.cpp": "#include \"add.hpp\"\nint main() { return add(2, 2) == 4 ? 0 : 1; }\n",
      },
      failFiles: {
        "tests/add_test.cpp": "#include \"add.hpp\"\nint main() { return add(2, 2) == 5 ? 0 : 1; }\n",
      },
    },
    bindings: withAll({
      format: provided("clang-format", "find . -type f \\( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \\) -print0 | xargs -0 clang-format --dry-run --Werror", ["**/*.c", "**/*.cc", "**/*.cpp", "**/*.h", "**/*.hpp", ".clang-format"]),
      build: provided("cmake-build", "cmake -S . -B build/gc-quality -DCMAKE_BUILD_TYPE=RelWithDebInfo -DENABLE_WERROR=TRUE && cmake --build build/gc-quality", ["CMakeLists.txt", "cmake/**", "src/**", "include/**"], { timeout_seconds: 1800 }),
      type_safety: provided("compiler-werror-clang-tidy", "cmake -S . -B build/gc-quality -DCMAKE_BUILD_TYPE=RelWithDebInfo -DENABLE_WERROR=TRUE && cmake --build build/gc-quality", ["CMakeLists.txt", "cmake/**", "src/**", "include/**"], {
        timeout_seconds: 1800,
        thresholds: {
          platform_minimum: { metric: "warning_count", max: 0 },
          recommendation: { metric: "warning_count", max: 0 },
        },
      }),
      lint: provided("clang-tidy", "run-clang-tidy -p build/gc-quality -quiet", ["**/*.c", "**/*.cc", "**/*.cpp", "**/*.h", "**/*.hpp", ".clang-tidy"], { blocking: false, timeout_seconds: 1800 }),
      unit_tests: provided("ctest", "cmake -S . -B build/gc-quality -DCMAKE_BUILD_TYPE=RelWithDebInfo -DBUILD_TESTING=ON && cmake --build build/gc-quality && ctest --test-dir build/gc-quality --output-on-failure", ["CMakeLists.txt", "src/**", "include/**", "tests/**"], { timeout_seconds: 1800 }),
      integration_tests: provided("ctest-integration", "ctest --test-dir build/gc-quality --output-on-failure -L integration", ["CMakeLists.txt", "src/**", "include/**", "tests/**"], { blocking: false, timeout_seconds: 2400 }),
      contract_boundary: missing("C/C++ boundary contracts depend on project-selected assertions, GSL Expects/Ensures, or explicit error-return conventions."),
      property_verification: provided("fuzz-property-harness", "ctest --test-dir build/gc-quality --output-on-failure -L property", ["src/**", "include/**", "tests/**"], { blocking: false }),
      architecture: provided("include-dependency-policy", "cmake --graphviz=.gc/reports/cpp/deps.dot build/gc-quality", ["CMakeLists.txt", "cmake/**", "src/**", "include/**"], { blocking: false }),
      complexity: provided("lizard", "lizard src include -C 15", ["src/**", "include/**"], {
        blocking: false,
        thresholds: {
          platform_minimum: { metric: "cyclomatic_complexity", max: 15 },
          recommendation: { metric: "cyclomatic_complexity", max: 10 },
        },
      }),
      mutation: provided("mull", "mull-runner --reporters Elements", ["src/**", "include/**", "tests/**"], {
        blocking: false,
        timeout_seconds: 3600,
        thresholds: {
          platform_minimum: { metric: "mutation_score", min: 50 },
          recommendation: { metric: "mutation_score", min: 70 },
        },
      }),
      diff_coverage: provided("gcovr-diff-cover", "gcovr --xml-pretty --output .gc/reports/cpp/coverage.xml && diff-cover .gc/reports/cpp/coverage.xml --fail-under=80", ["src/**", "include/**", "tests/**"], {
        thresholds: {
          platform_minimum: { metric: "changed_line_coverage", min: 80 },
          recommendation: { metric: "changed_line_coverage", min: 90 },
        },
      }),
      sast: provided("clang-static-analyzer", "scan-build cmake --build build/gc-quality", ["src/**", "include/**", "CMakeLists.txt"], { blocking: false }),
      secret_scan: provided("gitleaks", "gitleaks detect --source . --no-git --redact --exit-code 1", commonCodePaths),
      dependency_policy: provided("sbom-scan", "if command -v osv-scanner >/dev/null 2>&1; then osv-scanner --recursive .; else echo 'No C/C++ dependency scanner configured'; exit 1; fi", ["vcpkg.json", "conanfile.*", "CMakeLists.txt", "cmake/**"], { blocking: false }),
      accessibility: notApplicable("C/C++ CMake pack does not expose a rendered UI accessibility surface by default."),
      docs_policy: provided("docs-profile", "if command -v vale >/dev/null 2>&1; then vale .; else echo 'vale not installed; docs policy provider unavailable'; exit 1; fi", docsPaths, { blocking: false }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy", "if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else echo 'No repo policy command found'; exit 1; fi", commonCodePaths, { scope: "repo", timeout_seconds: 1800 }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/clang-tidy": "Checks: '-*,bugprone-*,clang-analyzer-*,cppcoreguidelines-*,modernize-*,performance-*,readability-*'\nWarningsAsErrors: '*'\nHeaderFilterRegex: '.*'\n",
      "templates/clang-format": "BasedOnStyle: LLVM\nColumnLimit: 100\nIndentWidth: 2\n",
      "templates/gc-quality.cmake": "option(ENABLE_WERROR \"Treat warnings as errors for Ground Control gates\" ON)\nif(ENABLE_WERROR)\n  add_compile_options(-Wall -Wextra -Wpedantic -Werror)\nendif()\n",
    },
  },
  {
    id: "docs-generic",
    name: "Generic documentation gate pack",
    description: "Documentation, policy, and code-light repository gates grounded in aiops and keplerops-platform pre-commit workflows.",
    anchors: [
      "aiops and keplerops-platform: pre-commit run --all-files as test/lint/completion",
      "Code-light repositories in the scan must not receive build or mutation gates",
    ],
    profiles: [
      { id: "default", description: "Markdown, policy, and code-light repository." },
      { id: "docs", description: "Documentation-focused repository." },
    ],
    install: {
      templates: [
        { source: "templates/check-docs.mjs", target: ".gc/gate-packs/docs-generic/check-docs.mjs" },
        { source: "templates/vale.ini", target: ".gc/gate-packs/docs-generic/vale.ini" },
        { source: "templates/pre-commit-config.yaml", target: ".gc/gate-packs/docs-generic/pre-commit-config.yaml" },
      ],
      dev_dependencies: [],
    },
    selftestTools: [{ name: "node", command: "node", args: ["--version"] }],
    selftest: {
      passCapability: "docs_policy",
      failCapability: "docs_policy",
      missingCapability: "build",
      changedFiles: ["README.md"],
      files: {
        "README.md": "# Self-test\n\nThis document has a title and useful content.\n",
      },
      failFiles: {
        "README.md": "# Self-test\n\nTODO\n",
      },
    },
    bindings: withAll({
      format: provided("pre-commit-docs-format", "if command -v pre-commit >/dev/null 2>&1; then pre-commit run --all-files; else node .gc/gate-packs/docs-generic/check-docs.mjs; fi", docsPaths, { scope: "repo" }),
      lint: provided("markdown-policy", "node .gc/gate-packs/docs-generic/check-docs.mjs", docsPaths, {
        thresholds: {
          platform_minimum: { metric: "doc_errors", max: 0 },
          recommendation: { metric: "doc_errors", max: 0 },
        },
      }),
      build: notApplicable("docs-generic does not compile product code."),
      type_safety: notApplicable("docs-generic does not type-check product code."),
      unit_tests: notApplicable("docs-generic does not run product unit tests."),
      integration_tests: notApplicable("docs-generic does not run product integration tests."),
      contract_boundary: notApplicable("docs-generic assurance classification no-ops on Markdown/YAML-only diffs."),
      property_verification: notApplicable("docs-generic has no property-test surface by default."),
      architecture: notApplicable("docs-generic has no code architecture surface by default."),
      complexity: notApplicable("docs-generic has no code complexity surface by default."),
      mutation: notApplicable("docs-generic has no code mutation-testing surface by default."),
      diff_coverage: notApplicable("docs-generic has no code coverage surface by default."),
      sast: provided("semgrep-config", "semgrep --config p/ci --error", ["**/*.yaml", "**/*.yml", "**/*.json", "**/*.md"], { blocking: false }),
      secret_scan: provided("gitleaks-detect-private-key", "if command -v gitleaks >/dev/null 2>&1; then gitleaks detect --source . --no-git --redact --exit-code 1; else node .gc/gate-packs/docs-generic/check-docs.mjs --secrets-only; fi", commonCodePaths),
      dependency_policy: provided("workflow-policy", "node .gc/gate-packs/docs-generic/check-docs.mjs --workflows", [".github/workflows/**", ".pre-commit-config.yaml", "**/*.yaml", "**/*.yml"], { blocking: false }),
      accessibility: notApplicable("docs-generic has no rendered UI accessibility surface by default."),
      docs_policy: provided("vale-markdown-policy", "node .gc/gate-packs/docs-generic/check-docs.mjs", docsPaths, {
        thresholds: {
          platform_minimum: { metric: "doc_errors", max: 0 },
          recommendation: { metric: "doc_errors", max: 0 },
        },
      }),
      traceability: missing("Ground Control traceability requires a configured project and live server context."),
      policy: provided("repo-policy-or-doc-policy", "if [ -f Makefile ] && grep -q '^policy:' Makefile; then make policy; elif [ -x ./bin/policy ]; then ./bin/policy --skip-pr-body; else node .gc/gate-packs/docs-generic/check-docs.mjs; fi", commonCodePaths, { scope: "repo" }),
      remote_status: remoteStatus(),
    }),
    templates: {
      "templates/check-docs.mjs": "#!/usr/bin/env node\nimport { readdirSync, readFileSync, statSync } from 'node:fs';\nimport { join } from 'node:path';\n\nconst mode = new Set(process.argv.slice(2));\nconst errors = [];\nfunction walk(dir) {\n  for (const entry of readdirSync(dir)) {\n    if (['.git', '.gc', 'node_modules', 'build', 'dist'].includes(entry)) continue;\n    const p = join(dir, entry);\n    const st = statSync(p);\n    if (st.isDirectory()) walk(p);\n    else check(p);\n  }\n}\nfunction check(path) {\n  const text = readFileSync(path, 'utf8');\n  if (!mode.has('--workflows') && /(^|\\n)TODO(\\b|:)/.test(text)) errors.push(`${path}: TODO marker`);\n  if (/-----BEGIN (?:RSA |OPENSSH |EC |DSA |PGP )?PRIVATE KEY-----/.test(text)) errors.push(`${path}: private key marker`);\n  if (mode.has('--workflows') && /uses:\\s*[^@\\s]+\\s*$/m.test(text)) errors.push(`${path}: unpinned workflow action`);\n}\nwalk(process.cwd());\nif (errors.length) {\n  console.error(errors.join('\\n'));\n  process.exit(1);\n}\nconsole.log('docs policy passed');\n",
      "templates/vale.ini": "StylesPath = .vale/styles\nMinAlertLevel = suggestion\nPackages = Google\n[*]\nBasedOnStyles = Vale, Google\n",
      "templates/pre-commit-config.yaml": "repos:\n  - repo: https://github.com/pre-commit/pre-commit-hooks\n    rev: v5.0.0\n    hooks:\n      - id: check-yaml\n      - id: detect-private-key\n      - id: trailing-whitespace\n",
    },
  },
];

function yamlScalar(value) {
  if (value === null) return "null";
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (typeof value !== "string") return JSON.stringify(value);
  if (value === "") return '""';
  if (/^[A-Za-z0-9_./:+-]+$/.test(value) && !["true", "false", "null"].includes(value)) return value;
  return JSON.stringify(value);
}

function toYaml(value, indent = 0) {
  const pad = " ".repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) return "[]";
    return value.map((entry) => {
      if (entry && typeof entry === "object") {
        const rendered = toYaml(entry, indent + 2);
        return `${pad}- ${rendered.trimStart()}`;
      }
      return `${pad}- ${yamlScalar(entry)}`;
    }).join("\n");
  }
  if (value && typeof value === "object") {
    const entries = Object.entries(value).filter(([, v]) => v !== undefined);
    if (entries.length === 0) return "{}";
    return entries.map(([key, v]) => {
      if (v && typeof v === "object") {
        const rendered = toYaml(v, indent + 2);
        if (rendered === "[]" || rendered === "{}") return `${pad}${key}: ${rendered}`;
        return `${pad}${key}:\n${rendered}`;
      }
      return `${pad}${key}: ${yamlScalar(v)}`;
    }).join("\n");
  }
  return yamlScalar(value);
}

function providedCaps(bindings) {
  return capabilities.filter((cap) => bindings[cap].status === "provided");
}

function groupedMissing(bindings, status) {
  return capabilities.filter((cap) => bindings[cap].status === status);
}

function writePack(pack) {
  const dir = join(packsRoot, pack.id);
  const existingClassifierPath = join(dir, "classifier.yaml");
  const existingClassifier = statOrNull(existingClassifierPath)?.isFile()
    ? readFileSync(existingClassifierPath, "utf8")
    : null;
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const packYaml = {
    schema_version: 1,
    id: pack.id,
    version,
    name: pack.name,
    description: pack.description,
    compatible_engine: compatibleEngine,
    profiles: pack.profiles,
    grounded_local_anchors: pack.anchors,
    capabilities_provided: providedCaps(pack.bindings),
    capabilities_not_provided: {
      provider_missing: groupedMissing(pack.bindings, "provider_missing"),
      not_applicable: groupedMissing(pack.bindings, "not_applicable"),
    },
    threshold_tiers: {
      platform_minimums: "encoded per capability under capabilities.yaml",
      pack_recommendations: "encoded per capability under capabilities.yaml",
      consumer_ratchets: "supported by workflow.gate_overrides in the engine",
    },
    install: pack.install,
    selftest: {
      runner: "selftest/run.mjs",
      required_tools: pack.selftestTools.map((tool) => tool.name),
    },
    signing: {
      checksum_algorithm: "sha256",
      provenance: "TODO: signed release provenance before broad rollout",
    },
  };
  writeFileSync(join(dir, "pack.yaml"), `${toYaml(packYaml)}\n`);
  writeFileSync(join(dir, "capabilities.yaml"), `${toYaml({
    schema_version: 1,
    pack: pack.id,
    version,
    bindings: pack.bindings,
  })}\n`);
  writeFileSync(join(dir, "installer.mjs"), `#!/usr/bin/env node
import { runInstallWorkflowAssetsCli } from "../../tools/install-workflow-assets.mjs";

await runInstallWorkflowAssetsCli({ defaultPackId: ${JSON.stringify(pack.id)} });
`);
  mkdirSync(join(dir, "selftest"), { recursive: true });
  writeFileSync(join(dir, "selftest/config.json"), `${JSON.stringify({
    pack_id: pack.id,
    required_tools: pack.selftestTools,
    fixture: pack.selftest,
  }, null, 2)}\n`);
  writeFileSync(join(dir, "selftest/run.mjs"), `#!/usr/bin/env node
import { runPackSelftestCli } from "../../../tools/selftest-pack.mjs";

await runPackSelftestCli({ defaultPackId: ${JSON.stringify(pack.id)} });
`);
  for (const [rel, content] of Object.entries(pack.templates)) {
    const target = join(dir, rel);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
  if (typeof existingClassifier === "string") {
    writeFileSync(join(dir, "classifier.yaml"), existingClassifier);
  }
}

function statOrNull(path) {
  try {
    return statSync(path);
  } catch {
    return null;
  }
}

function listFiles(dir) {
  const out = [];
  function walk(current) {
    for (const entry of readdirSync(current).sort()) {
      const abs = join(current, entry);
      const st = statSync(abs);
      if (st.isDirectory()) walk(abs);
      else out.push(abs);
    }
  }
  walk(dir);
  return out;
}

function directoryChecksum(dir) {
  const h = createHash("sha256");
  for (const abs of listFiles(dir)) {
    const rel = relative(dir, abs).replace(/\\/g, "/");
    h.update(rel);
    h.update("\0");
    h.update(readFileSync(abs));
    h.update("\0");
  }
  return h.digest("hex");
}

mkdirSync(packsRoot, { recursive: true });
for (const pack of packs) writePack(pack);

const catalog = {
  schema_version: 1,
  kind: "ground-control-gate-catalog",
  engine: {
    version,
    compatible: compatibleEngine,
    source_url: "workflow/packs",
    checksum: "TODO: engine release checksum is recorded when engine artifacts are cut",
  },
  packs: packs.map((pack) => {
    const sourcePath = `workflow/packs/${pack.id}`;
    return {
      id: pack.id,
      version,
      source_url: sourcePath,
      artifact: sourcePath,
      sha256: directoryChecksum(join(repoRoot, sourcePath)),
      compatible_engine: compatibleEngine,
      signer: "TODO: release signer",
      trust_policy: "checksum-only-development",
    };
  }),
};
writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);
console.log(`materialized ${packs.length} gate packs and ${catalogPath}`);

import net.ltgt.gradle.errorprone.errorprone
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.27"
    id("net.ltgt.errorprone") version "4.1.0"
    id("org.sonarqube") version "6.0.1.5171"
    // Pitest mutation testing (#931). The `make test-quality` Makefile target
    // runs Pitest against the unit-test surface; the threshold is intentionally
    // loose initially (60% on changed classes) and tightens after the first
    // five PRs of data. Mutation testing directly measures whether the tests
    // detect breakage, which is the gap `gc_test_quality_review` has been
    // trying to close with an LLM pass.
    id("info.solidsoft.pitest") version "1.15.0"
    checkstyle
    jacoco
}

group = "com.keplerops"
version = "1.1.0" // x-release-please-version

// Security patch overrides on top of the Spring Boot BOM. The managed versions
// in Boot 3.5.14 still carry fixable CRITICAL/HIGH CVEs, and the CI trivy gate
// blocks on those, so each is raised to the patch release that fixes them.
// These are patch bumps inside the BOM's own minor line, not upgrades.
//   tomcat     10.1.55  CVE-2026-41293 (CRITICAL), -41284, -42498, -43512, -43513, -43515
//   jackson    2.21.4   CVE-2026-54512, -54513, GHSA-r7wm-3cxj-wff9
//   postgresql 42.7.12  CVE-2026-42198, CVE-2026-54291
// Drop an entry once the Boot BOM manages a version at or above it.
extra["tomcat.version"] = "10.1.55"
extra["jackson-bom.version"] = "2.21.4"
extra["postgresql.version"] = "42.7.12"

sonar {
    properties {
        property("sonar.projectKey", "autarchy-ai_Ground-Control")
        property("sonar.organization", "autarchy-ai")
        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.exclusions", "**/node_modules/**,**/.gradle/**,**/build/**,**/dist/**,**/coverage/**,**/*.min.js,bin/**,backend/bin/**,../workflow/releases/**,workflow/releases/**")
        property("sonar.java.binaries", "build/classes")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}

// -Pquick: disable slow static analysis for fast dev loops
val quick = providers.gradleProperty("quick").isPresent

fun csvGradleProperty(name: String) =
    providers.gradleProperty(name).map { value ->
        value.split(",").map(String::trim).filter(String::isNotEmpty).toSet()
    }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Gradle dependency locking. Produces backend/gradle.lockfile covering every
// resolvable configuration, which OSV-scanner consumes in CI. Subsequent
// `gradle build` (without --write-locks) fails if the resolved graph drifts
// from the lockfile, which keeps the scanned input honest.
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Audit trail
    implementation("org.springframework.data:spring-data-envers")

    // Database — implementation (not runtimeOnly) because AgeGraphService binds AGE's
    // agtype pseudotype via org.postgresql.util.PGobject at compile time.
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Logging (JSON output in production)
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // API docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")

    // Export: Excel (.xlsx)
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // Export: PDF
    implementation("com.github.librepdf:openpdf:2.0.3")

    // Gherkin parser (TC-004 / ADR-042). Pure parser library — no Cucumber
    // runtime, no glue execution, no remote fetch. Pulls io.cucumber:messages
    // transitively. Version pinned via a property so a future bump can be
    // co-located with the rationale comment instead of inlined into the
    // dependency string.
    val gherkinVersion = "39.1.0"
    implementation("io.cucumber:gherkin:$gherkinVersion")

    // Error Prone
    errorprone("com.google.errorprone:error_prone_core:2.36.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // jqwik property-based testing
    testImplementation("net.jqwik:jqwik:1.9.2")

    // ArchUnit
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    // Instancio test data generation
    testImplementation("org.instancio:instancio-junit:5.2.1")

    // Testcontainers for integration tests
    testImplementation("org.testcontainers:testcontainers:1.21.1")
    testImplementation("org.testcontainers:junit-jupiter:1.21.1")
    testImplementation("org.testcontainers:postgresql:1.21.1")

}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.isEnabled.set(!quick)
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.disable("MissingSummary")
}

if (quick) {
    tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach { enabled = false }
    tasks.named("checkstyleMain") { enabled = false }
}

// Generate META-INF/build-info.properties so BuildProperties / actuator info can read the
// build version. NOTE: build-info is a BuildProperties bean, not a Spring Environment
// property source, so logback's <springProperty> cannot read build.version directly.
// logback resolves the product version from the filtered `info.app.version` property
// (see processResources below); this generation still feeds /actuator/info.
springBoot {
    buildInfo()
}

// Filter application.yml so `info.app.version` resolves to the Release Please-managed
// product version at build time (GC-P027 / #1399). The `@projectVersion@` token avoids
// clashing with the many Spring `${...}` placeholders in the same file; logback-spring.xml
// reads this property as SERVICE_VERSION (else it falls back to the literal "unknown").
tasks.processResources {
    filesMatching("application.yml") {
        filter(mapOf("tokens" to mapOf("projectVersion" to project.version.toString())), ReplaceTokens::class.java)
    }
    // The ADR-090 station catalogue is a published contract, and the backend validates emitted
    // station ids against it (issue #1355). It is copied from contracts/ at build time rather than
    // committed a second time under resources/: a mirrored catalogue is a catalogue that can drift,
    // and a validator disagreeing with the contract it enforces is worse than no validator.
    from(rootProject.layout.projectDirectory.dir("../contracts/measurement")) {
        include("gc-station-catalogue-v2.json")
        into("measurement")
    }
}

tasks.register("rapid") {
    description = "Fast dev loop: format + compile (no tests, no static analysis)"
    group = "development"
    dependsOn("spotlessApply", "compileJava")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

// Unit tests only (fast, no DB)
tasks.test {
    useJUnitPlatform { excludeTags("integration", "age") }
    finalizedBy(tasks.jacocoTestReport)
}

// Integration tests (Testcontainers, slow)
tasks.register<Test>("integrationTest") {
    description = "Runs integration tests with Testcontainers PostgreSQL"
    group = "verification"
    useJUnitPlatform { includeTags("integration"); excludeTags("age") }
    shouldRunAfter(tasks.test)
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    finalizedBy(tasks.jacocoTestReport)
}

// AGE integration tests (requires Apache AGE Docker image)
tasks.register<Test>("ageTest") {
    description = "Runs Apache AGE integration tests"
    group = "verification"
    useJUnitPlatform { includeTags("age") }
    shouldRunAfter(tasks.test)
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

// MCP–backend contract spec capture (issue #1106, ADR-034).
// Runs only McpOpenApiContractSpecTest (tagged "integration") via Testcontainers,
// writes backend/build/contract/openapi.json for the Node contract test.
tasks.register<Test>("generateContractOpenApi") {
    description = "Generates backend/build/contract/openapi.json for the MCP contract test"
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
        filter { includeTestsMatching("*McpOpenApiContractSpecTest") }
    }
    shouldRunAfter(tasks.test)
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // Merge coverage from integration tests when available
    val integrationTest = tasks.findByName("integrationTest")
    if (integrationTest != null) {
        executionData(integrationTest)
    }
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Pitest mutation testing configuration (#931). Wired but NOT in the default
// `check` chain — invoked via `make test-quality`. The initial pass is
// genuinely advisory: thresholds are 0 so any mutation score "passes" the
// task and the score lands in build/reports/pitest as HTML + XML. After ~5
// PRs of mutation-score data we tighten via the threshold knobs.
//
// Codex cycle-1 finding F2 (#931): mutationThreshold is a build-failing
// threshold in Pitest — setting it to 60 was a hard gate before the
// repository had calibration data, contradicting the "advisory" intent.
// The fix sets BOTH thresholds to 0 so this run is truly score-reporting
// only. targetClasses scope stays project-wide; the changed-class scoping
// is a follow-on knob once we have a stable cadence for the score.
pitest {
    junit5PluginVersion.set("1.2.1")
    pitestVersion.set("1.17.0")
    targetClasses.set(csvGradleProperty("mutationTargetClasses").orElse(setOf("com.keplerops.groundcontrol.*")))
    targetTests.set(csvGradleProperty("mutationTargetTests").orElse(emptySet()))
    // Mutators: default set is good enough for the initial calibration.
    mutators.set(listOf("DEFAULTS"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    reportDir.set(
            providers.gradleProperty("mutationReportDir")
                    .map { layout.projectDirectory.dir(it) }
                    .orElse(layout.projectDirectory.dir("build/reports/pitest")))
    // Advisory-only thresholds (#931 codex F2). Score is in the report;
    // build does not fail on low mutation/coverage during the calibration
    // window. Tighten after the first ~5 PRs of evidence.
    mutationThreshold.set(providers.gradleProperty("mutationThreshold").map(String::toInt).orElse(0))
    coverageThreshold.set(0)
    failWhenNoMutations.set(providers.gradleProperty("mutationFailWhenNoMutations").map(String::toBoolean).orElse(false))
}

// SpotBugs
spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    excludeFilter = file("config/spotbugs/exclusions.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") { required = true }
    // XML is what the ADR-090 measurement projection reads (issue #1355): the completion command
    // already runs SpotBugs, so its own report is the structured source. Parsing the combined
    // Gradle console output instead would guess at per-gate boundaries, and re-running SpotBugs
    // to measure it would execute a canonical gate twice.
    reports.create("xml") { required = true }
}

// Checkstyle
checkstyle {
    toolVersion = "10.21.1"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

// Exclude checkstyle on test sources — focus on production code
tasks.checkstyleTest {
    enabled = false
}

apply(from = "gradle/openjml.gradle.kts")

spotless {
    java {
        importOrder()
        removeUnusedImports()
        cleanthat()
        palantirJavaFormat()
        formatAnnotations()
    }
}

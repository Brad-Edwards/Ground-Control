import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.api.plugins.quality.Pmd

plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.27"
    id("net.ltgt.errorprone") version "4.1.0"
    id("net.ltgt.nullaway") version "3.1.0"
    id("org.sonarqube") version "6.0.1.5171"
    // Pitest mutation testing (#931). The `make test-quality` Makefile target
    // runs Pitest against the unit-test surface; the threshold is intentionally
    // loose initially (60% on changed classes) and tightens after the first
    // five PRs of data. Mutation testing directly measures whether the tests
    // detect breakage, which is the gap `gc_test_quality_review` has been
    // trying to close with an LLM pass.
    id("info.solidsoft.pitest") version "1.15.0"
    checkstyle
    pmd
    jacoco
}

group = "com.keplerops"
version = "0.20.1"

sonar {
    properties {
        property("sonar.projectKey", "KeplerOps_Ground-Control")
        property("sonar.organization", "keplerops")
    }
}

// -Pquick: disable slow static analysis for fast dev loops
val quick = providers.gradleProperty("quick").isPresent

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
    errorprone("com.uber.nullaway:nullaway:0.12.10")
    compileOnly("org.jspecify:jspecify:1.0.0")

    // SpotBugs security rules
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")

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

nullaway {
    onlyNullMarked = true
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.isEnabled.set(!quick)
    options.errorprone.disableWarningsInGeneratedCode = true
    options.errorprone.disable("MissingSummary")
    options.errorprone.nullaway {
        error()
    }
}

if (quick) {
    tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach { enabled = false }
    tasks.named("checkstyleMain") { enabled = false }
    tasks.named("pmdMain") { enabled = false }
}

// Generate META-INF/build-info.properties so logback can read the version dynamically
springBoot {
    buildInfo()
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
    val changedClassTargets = providers
            .gradleProperty("pitestTargetClasses")
            .map { raw -> raw.split(",").map(String::trim).filter(String::isNotEmpty) }
    targetClasses.set(changedClassTargets.orElse(listOf("com.keplerops.groundcontrol.*")))
    // Mutators: default set is good enough for the initial calibration.
    mutators.set(listOf("DEFAULTS"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    // Whole-repo PIT stays advisory for calibration; changed-class PIT is a
    // blocking ratchet with a 60% floor when the gate supplies target classes.
    mutationThreshold.set(changedClassTargets.map { 60 }.orElse(0))
    coverageThreshold.set(0)
    failWhenNoMutations.set(false)
}

// SpotBugs
spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    excludeFilter = file("config/spotbugs/exclusions.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") { required = true }
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

pmd {
    toolVersion = "7.10.0"
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
    isIgnoreFailures = true
}

tasks.withType<Pmd>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}

val pmdMain by tasks.existing(Pmd::class)
val pmdRatchet by tasks.registering {
    group = "verification"
    description = "Fails when PMD reports a finding not present in config/pmd/baseline.txt."
    dependsOn(pmdMain)

    val baselineFile = layout.projectDirectory.file("config/pmd/baseline.txt")
    val reportFile = layout.buildDirectory.file("reports/pmd/main.xml")
    inputs.file(baselineFile)
    inputs.file(reportFile)

    doLast {
        val baseline = baselineFile.asFile
                .readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        val report = reportFile.get().asFile
        if (!report.exists()) {
            return@doLast
        }
        val document = javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(report)
        val violations = document.getElementsByTagName("violation")
        val findings = (0 until violations.length)
                .map { violations.item(it) as org.w3c.dom.Element }
                .map { violation ->
                    val file = violation.parentNode as org.w3c.dom.Element
                    listOf(
                            file.getAttribute("name").removePrefix(projectDir.parentFile.absolutePath + "/"),
                            violation.getAttribute("rule"),
                            violation.getAttribute("beginline"),
                            violation.textContent.trim().replace(Regex("\\s+"), " "))
                            .joinToString("|")
                }
                .toSortedSet()
        val newFindings = findings - baseline
        if (newFindings.isNotEmpty()) {
            throw GradleException(
                    "PMD found ${newFindings.size} finding(s) outside config/pmd/baseline.txt:\n"
                            + newFindings.joinToString("\n"))
        }
    }
}

tasks.check {
    dependsOn(pmdRatchet)
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

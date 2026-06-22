# ADR-013: Java/Spring Boot Backend Rewrite

## Status

Accepted

## Date

2026-03-08

## Context

Ground Control's Python 3.12/Django backend (ADR-001) hit a fundamental tooling gap in its formal methods chain (ADR-003, ADR-012). The project requires Specification-Driven Development (SDD) with runtime contracts, static verification, property-based testing, and formal proofs at increasing assurance levels. The Python ecosystem cannot deliver this:

1. **icontract-hypothesis is broken** - the bridge between icontract and Hypothesis depends on `extract_lambda_source`, removed in Hypothesis 6.x. No fix upstream.
2. **CrossHair cannot import Django models** - Django's app registry initialization triggers database connections during symbolic execution, making CrossHair unusable for any code that touches `django.db.models`.
3. **Rocq/Coq has no Python extraction bridge** - there is no toolchain to extract verified Rocq specifications into Python implementations. The L3 (Formally Specified) assurance level was aspirational, not achievable.

These are not configuration issues - they are fundamental incompatibilities between the Python tools. Approximately 12 hours were invested in the Python codebase (scaffold, domain model, tests, CI). The architecture decisions (state machine design, requirements model, DAG structure, exception hierarchy, clean architecture layers, assurance levels, SDD workflow) are the real asset and transfer completely to Java.

The developer's professional experience is in Java, and the Java ecosystem provides a tightly integrated formal methods chain where the tools actually work together.

### Why Java + JML + OpenJML + KeY

| Python tool | Problem | Java equivalent | Advantage |
|-------------|---------|-----------------|-----------|
| icontract | Runtime-only, no static bridge | JML + OpenJML RAC | Runtime AND static checking from same annotations |
| CrossHair | Cannot import Django models | OpenJML ESC | Operates on Java source + JML directly, no framework issues |
| Hypothesis | No icontract bridge | jqwik | JUnit 5 native, stateful testing, actively maintained |
| icontract-hypothesis | Broken with modern Hypothesis | JML → jqwik (manual) | No bridge needed - same language |
| Rocq/Coq | No Python extraction | KeY | Java-native JML prover, reads source directly |
| mypy strict | Type checking only | `javac` + sealed classes + records | Compile-time type safety, pattern matching |

The critical advantage: in Python, L2 and L3 assurance levels were unreachable. In Java, the JML → OpenJML ESC → KeY pipeline makes them genuinely achievable.

## Decision

Rewrite the Ground Control backend from Python 3.12/Django to Java 21/Spring Boot with the following stack:

### Core Stack

| Component | Choice | Version | Rationale |
|-----------|--------|---------|-----------|
| Language | Java 21 LTS | 21.0.x | Sealed classes, records, pattern matching. LTS stability. OpenJML tracks LTS. |
| Framework | Spring Boot | 3.4.x | Latest stable. Spring Framework 6.2.x. Hibernate 6.6.x. |
| Build tool | Gradle (Kotlin DSL) | 8.12+ | Custom task model for OpenJML/KeY pipeline. Better than Maven for exec tasks. |
| Package | `com.keplerops.groundcontrol` | - | Standard reverse-domain. |

### Formal Methods Chain

| Level | Name | Tools | Replaces |
|-------|------|-------|----------|
| L0 | Standard | `javac` + JUnit 5 | mypy strict + pytest |
| L1 | Contracted | + JML `requires`/`ensures`/`invariant` via OpenJML RAC | + icontract |
| L2 | Property-Verified | + jqwik + OpenJML ESC (static checking) | + Hypothesis + CrossHair |
| L3 | Formally Specified | + KeY proofs (Isabelle/HOL future secondary) | + Rocq/Coq (was unreachable) |

### Supporting Libraries

| Concern | Choice | Version | Replaces |
|---------|--------|---------|----------|
| Contracts | JML via OpenJML | 0.17.x | icontract |
| Property testing | jqwik | 1.9.x | Hypothesis (via icontract-hypothesis, broken) |
| Formal prover | KeY (primary) | 2.12.x | Rocq/Coq (no Python extraction) |
| DB migrations | Flyway | 10.x | Django migrations |
| Audit trail | Spring Data Envers | 3.4.x | django-auditlog |
| Arch enforcement | ArchUnit | 1.3.x | import-linter |
| Logging | SLF4J + Logback + Logstash Encoder | 2.x / 1.5.x / 8.x | structlog |
| API docs | Springdoc-OpenAPI | 2.8.x | django-ninja OpenAPI |
| Testing | JUnit 5 + Mockito + AssertJ + Testcontainers | 5.11 / 5.14 / 3.27 / 1.20 | pytest + factory-boy |
| Test data | Instancio | 5.2.x | factory-boy |
| Code quality | Checkstyle + SpotBugs + Error Prone + JaCoCo + Spotless | Various | ruff + mypy + pytest-cov |
| Graph DB | Apache AGE via JDBC | PG16 + AGE 1.6.0 | Apache AGE via psycopg (same DB) |
| Task scheduling | Spring `@Scheduled` | Built-in | django-q2 |
| Caching | Spring Cache + Redis | Built-in | Django cache + Redis |

### Key Tool Choices

- **jqwik instead of JUnit-Quickcheck** - JUnit-Quickcheck's last release was 2021, JUnit 4 only. jqwik is actively maintained, JUnit 5 native, has stateful testing (`ActionSequence`) ideal for state machines.
- **KeY instead of Isabelle/PVS as primary prover** - KeY natively understands JML + Java source. No model extraction step. Isabelle planned as secondary for abstract security proofs (future).
- **Gradle instead of Maven** - OpenJML integration needs custom exec tasks. Gradle's task graph model handles the L0-L3 verification pipeline cleanly.

### Package Structure

```
src/main/java/com/keplerops/groundcontrol/
    GroundControlApplication.java

    domain/                                  # Business logic (no Spring imports except JPA)
        requirements/
            model/
                Requirement.java             # @Entity + JML contracts
                RequirementRelation.java
            state/
                Status.java                  # Enum + EnumMap transition table
                RequirementType.java
                Priority.java
                RelationType.java
            service/
                RequirementService.java
            repository/
                RequirementRepository.java
                RequirementRelationRepository.java
            exception/
                GroundControlException.java
                NotFoundException.java
                DomainValidationException.java
                AuthenticationException.java
                AuthorizationException.java
                ConflictException.java

    api/                                     # REST controllers (thin handlers)
        requirements/
            RequirementController.java
            RequirementRequest.java          # Request DTO (record)
            RequirementResponse.java         # Response DTO (record)
        GlobalExceptionHandler.java          # @ControllerAdvice
        ErrorResponse.java                   # {"error": {"code", "message", "detail"}}

    infrastructure/                          # External adapters
        age/
            AgeGraphService.java             # AGE Cypher via JdbcTemplate
        config/
            JpaConfig.java
            CacheConfig.java
            SchedulingConfig.java
            LoggingConfig.java

    shared/
        logging/
            RequestLoggingFilter.java        # MDC: request_id, tenant_id, actor_id
```

### What Transfers from Python

All domain knowledge transfers without loss:

- **State machine** - `VALID_TRANSITIONS` dict becomes `EnumMap<Status, Set<Status>>`
- **Requirements model** - Django model fields become JPA `@Entity` fields
- **DAG structure** - Same Apache AGE queries via JDBC instead of psycopg
- **Exception hierarchy** - Same 6 exception types, Java class hierarchy
- **Clean architecture** - Same `api/ → domain/ ← infrastructure/` layers
- **Assurance levels** - Same L0-L3 ladder, now with working tools
- **SDD workflow** - Same spec-first loop (ADR-012), Java tool names
- **ADR process** - Continues unchanged

### What Is Lost

- ~1000 lines of Python code (scaffold, models, tests, CI config)
- ~12 hours of implementation time
- Familiarity with Django ORM patterns (offset by Java/Spring experience)

## Consequences

### Positive

- **L2 and L3 assurance levels become achievable** - OpenJML ESC operates on Java source with JML annotations, no framework import issues. KeY reads Java source directly.
- **Single annotation language (JML) spans L1-L3** - Runtime checking (RAC), static checking (ESC), and formal proofs (KeY) all consume the same JML annotations. No tool-boundary translation.
- **Professional alignment** - Developer's primary experience is Java. Faster development velocity.
- **Mature ecosystem** - Spring Boot, Hibernate, JUnit 5 are industry-standard with extensive documentation and tooling.
- **Type safety at compile time** - Java's type system with sealed classes and records catches errors that mypy only detects at lint time.

### Negative

- **12 hours of Python work discarded** - Scaffold, domain model, tests, CI configuration. Architecture decisions and domain knowledge are preserved.
- **Heavier runtime** - JVM vs Python interpreter. Offset by better tooling and type safety.
- **Spring Boot complexity** - More configuration than Django for simple cases. Offset by familiarity and ecosystem maturity.

### Risks

| Risk | Mitigation |
|------|-----------|
| OpenJML doesn't support Java 21 features (sealed, records) | Check OpenJML release notes. JML annotations are in comments - worst case they're ignored for sealed types, contracts still work on methods. |
| KeY learning curve is steep | Defer L3 proofs. Start with L1 (JML RAC) and L2 (jqwik + OpenJML ESC). |
| AGE has no Java JDBC driver | Use raw SQL via Spring JdbcTemplate (same approach as Python psycopg). |
| Hibernate Envers + JML invariants interaction | Test carefully. Envers uses proxies - JML RAC may need `@SuppressWarnings` on Envers-generated methods. |
| Instancio can't generate valid JPA entities for integration tests | Fall back to manual builders for integration tests. Instancio for unit tests. |
| jqwik + Testcontainers DB tests are slow | Separate fast/slow test suites via JUnit tags (same pattern as `@pytest.mark.slow`). |

## Supersedes

- **ADR-001** (Python 3.12+ with Django and django-ninja for Backend) - Language, framework, and runtime replaced.
- **ADR-003** (Design by Contract with icontract) - Contract system replaced by JML/OpenJML. DbC principle retained.
- **ADR-004** (Code Quality Toolchain) - Python toolchain (ruff, mypy, pytest-cov) replaced by Java toolchain (Checkstyle, SpotBugs, Error Prone, JaCoCo).

## Related ADRs

- **ADR-002** (PostgreSQL as Primary Database) - Remains valid. Same database, accessed via Hibernate + Spring Data JPA.
- **ADR-005** (Apache AGE for Graph Database Capabilities) - Remains valid. Same AGE extension, accessed via JdbcTemplate instead of psycopg.
- **ADR-011** (Requirements Data Model) - Remains valid. Same data model, implemented as JPA entities instead of Django models.
- **ADR-012** (Formal Methods Development Process) - Remains valid. SDD workflow and assurance levels are language-agnostic. Tool names updated for Java.

package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationAdapterRequest;
import com.keplerops.groundcontrol.domain.derivation.service.DerivationScope;
import com.keplerops.groundcontrol.domain.derivation.state.DerivationScopeMode;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodeQlSarifNormalizerTest {

    private static final String COMMIT = "25c991231cf2a1464792846b083d1bd885299b3c";
    private static final Instant DERIVED_AT = Instant.parse("2026-06-13T12:00:00Z");
    private static final String QUERY_PACK = "codeql/java-queries@1.10.1";

    private final CodeQlSarifNormalizer normalizer = new CodeQlSarifNormalizer(new ObjectMapper());

    @Test
    void normalizesEntryPointAndTaintPathFactsWithoutRawSourcePayload() {
        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithEntryPointAndTaintFlow(), request(fullScope()), "2.23.9", DERIVED_AT);

        assertThat(result.facts()).hasSize(2);
        assertThat(result.captureLimits()).isEmpty();
        assertThat(result.facts())
                .extracting("factKind")
                .containsExactly(SystemModelFactKind.ENTRY_POINT, SystemModelFactKind.TAINT_PATH);
        assertThat(result.facts()).allSatisfy(fact -> {
            assertThat(fact.provenance().adapterId()).isEqualTo("codeql-derivation");
            assertThat(fact.provenance().toolName()).isEqualTo("CodeQL");
            assertThat(fact.provenance().toolVersion()).isEqualTo("2.23.9");
            assertThat(fact.provenance().rulesetVersion()).isEqualTo(QUERY_PACK);
            assertThat(fact.provenance().commitSha()).isEqualTo(COMMIT);
            assertThat(fact.payload()).doesNotContainKeys("raw_output", "stderr", "source_content", "raw_diff");
        });
        assertThat(result.facts().get(1).payload())
                .containsEntry("boundaryCrossing", true)
                .containsEntry("boundaries", List.of("frontend", "backend"));
    }

    @Test
    void diffScopeKeepsOnlyFactsTouchingRequestedPathsAndPreservesFlowContext() {
        var scope = new DerivationScope(
                DerivationScopeMode.DIFF,
                COMMIT,
                "16792466cf2a1464792846b083d1bd885299b3c",
                List.of("backend/src/main/java/com/example/Service.java"),
                Set.of("java"),
                Set.of("application"));

        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithEntryPointAndTaintFlow(), request(scope), "2.23.9", DERIVED_AT);

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.factKind()).isEqualTo(SystemModelFactKind.TAINT_PATH);
            assertThat(fact.sourcePath()).isEqualTo("backend/src/main/java/com/example/Service.java");
            assertThat(fact.payload()).containsEntry("boundaryCrossing", true);
            assertThat(fact.payload()).containsEntry("boundaries", List.of("frontend", "backend"));
        });
    }

    @Test
    void factKeysAreDeterministicForTheSameCommitAndQueryPackPins() {
        var first = normalizer.normalize(
                "java", QUERY_PACK, sarifWithEntryPointAndTaintFlow(), request(fullScope()), "2.23.9", DERIVED_AT);
        var second = normalizer.normalize(
                "java", QUERY_PACK, sarifWithEntryPointAndTaintFlow(), request(fullScope()), "2.23.9", DERIVED_AT);
        var changedPin = normalizer.normalize(
                "java",
                "codeql/java-queries@1.10.2",
                sarifWithEntryPointAndTaintFlow(),
                request(fullScope()),
                "2.23.9",
                DERIVED_AT);

        assertThat(second.facts())
                .extracting("factKey")
                .containsExactlyElementsOf(
                        first.facts().stream().map(fact -> fact.factKey()).toList());
        assertThat(changedPin.facts().getFirst().factKey())
                .isNotEqualTo(first.facts().getFirst().factKey());
    }

    @Test
    void factKeysDistinguishRepeatedFindingsInTheSameFile() {
        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithRepeatedFindingsInSameFile(), request(fullScope()), "2.23.9", DERIVED_AT);

        assertThat(result.facts()).hasSize(2);
        assertThat(result.facts()).extracting("factKey").doesNotHaveDuplicates();
    }

    @Test
    void classifiesSecretExternalReachabilityAndFallbackFindings() {
        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithClassificationKinds(), request(fullScope()), "2.23.9", DERIVED_AT);

        assertThat(result.facts())
                .extracting("factKind")
                .containsExactly(
                        SystemModelFactKind.SECRET_USAGE,
                        SystemModelFactKind.DATA_FLOW,
                        SystemModelFactKind.EXTERNAL_INTERACTION,
                        SystemModelFactKind.DATA_FLOW);
        assertThat(result.facts().getFirst().payload())
                .extractingByKey("locations")
                .asList()
                .first()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("endLine", 12)
                .containsEntry("endColumn", 24);
    }

    @Test
    void skipsResultsWithoutPrimaryLocations() {
        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithoutLocations(), request(fullScope()), "2.23.9", DERIVED_AT);

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void pathScopedDerivationRequiresRequestedPaths() {
        var scope = new DerivationScope(
                DerivationScopeMode.PATH_SET, COMMIT, null, List.of(), Set.of("java"), Set.of("application"));

        var result = normalizer.normalize(
                "java", QUERY_PACK, sarifWithEntryPointAndTaintFlow(), request(scope), "2.23.9", DERIVED_AT);

        assertThat(result.facts()).isEmpty();
    }

    @Test
    void invalidSarifFailsWithoutPersistingRawOutput() {
        assertThatThrownBy(
                        () -> normalizer.normalize("java", QUERY_PACK, "{", request(fullScope()), "2.23.9", DERIVED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to parse CodeQL SARIF output");
    }

    private static DerivationScope fullScope() {
        return new DerivationScope(
                DerivationScopeMode.FULL_REPO, COMMIT, null, List.of(), Set.of("java"), Set.of("application"));
    }

    private static DerivationAdapterRequest request(DerivationScope scope) {
        return new DerivationAdapterRequest(UUID.randomUUID(), "ground-control", scope);
    }

    private static String sarifWithEntryPointAndTaintFlow() {
        return """
                {
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "CodeQL",
                          "rules": [
                            {
                              "id": "java/spring-controller-entry-point",
                              "name": "Spring MVC entry point",
                              "shortDescription": { "text": "HTTP route entry point" },
                              "properties": {
                                "tags": ["entry-point", "spring", "route"],
                                "security-severity": "5.0"
                              }
                            },
                            {
                              "id": "java/tainted-path",
                              "name": "Tainted data flow",
                              "shortDescription": { "text": "User input reaches sink" },
                              "properties": {
                                "tags": ["security", "taint", "data-flow"],
                                "security-severity": "8.1"
                              }
                            }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "java/spring-controller-entry-point",
                          "message": { "text": "HTTP route entry point detected." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/Controller.java" },
                                "region": { "startLine": 42, "startColumn": 5 }
                              }
                            }
                          ]
                        },
                        {
                          "ruleId": "java/tainted-path",
                          "message": { "text": "User-controlled data reaches a sink." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/Service.java" },
                                "region": { "startLine": 77, "startColumn": 9 }
                              }
                            }
                          ],
                          "codeFlows": [
                            {
                              "threadFlows": [
                                {
                                  "locations": [
                                    {
                                      "location": {
                                        "physicalLocation": {
                                          "artifactLocation": { "uri": "frontend/src/pages/analysis.tsx" },
                                          "region": { "startLine": 12, "startColumn": 3 }
                                        }
                                      }
                                    },
                                    {
                                      "location": {
                                        "physicalLocation": {
                                          "artifactLocation": { "uri": "backend/src/main/java/com/example/Service.java" },
                                          "region": { "startLine": 77, "startColumn": 9 }
                                        }
                                      }
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private static String sarifWithRepeatedFindingsInSameFile() {
        return """
                {
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "CodeQL",
                          "rules": [
                            {
                              "id": "java/tainted-path",
                              "name": "Tainted data flow",
                              "shortDescription": { "text": "User input reaches sink" },
                              "properties": { "tags": ["security", "taint", "data-flow"] }
                            }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "java/tainted-path",
                          "message": { "text": "User-controlled data reaches a sink." },
                          "partialFingerprints": { "primaryLocationLineHash": "hash-one" },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/Service.java" },
                                "region": { "startLine": 77, "startColumn": 9 }
                              }
                            }
                          ]
                        },
                        {
                          "ruleId": "java/tainted-path",
                          "message": { "text": "User-controlled data reaches a sink." },
                          "partialFingerprints": { "primaryLocationLineHash": "hash-two" },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/Service.java" },
                                "region": { "startLine": 88, "startColumn": 9 }
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private static String sarifWithClassificationKinds() {
        return """
                {
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "CodeQL",
                          "rules": [
                            {
                              "id": "java/hardcoded-secret",
                              "name": "Hard-coded credential",
                              "shortDescription": { "text": "Credential material is embedded" },
                              "properties": { "tags": ["security", "secret"] }
                            },
                            {
                              "id": "java/reachable-call",
                              "name": "Call graph reachability",
                              "shortDescription": { "text": "Reachability path" },
                              "properties": { "tags": ["reachability"] }
                            },
                            {
                              "id": "java/network-client",
                              "name": "Network client",
                              "shortDescription": { "text": "HTTP call leaves the service" },
                              "properties": { "tags": ["external", "http"] }
                            },
                            {
                              "id": "java/unknown-finding",
                              "name": "Unclassified finding",
                              "shortDescription": { "text": "General CodeQL finding" },
                              "properties": { "tags": ["quality"] }
                            }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "java/hardcoded-secret",
                          "message": { "text": "Credential material is embedded." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "./backend\\\\src\\\\main\\\\java\\\\com\\\\example\\\\Secrets.java" },
                                "region": {
                                  "startLine": 10,
                                  "startColumn": 5,
                                  "endLine": 12,
                                  "endColumn": 24
                                }
                              }
                            }
                          ]
                        },
                        {
                          "ruleId": "java/reachable-call",
                          "message": { "text": "Reachability path detected." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/Reachable.java" },
                                "region": { "startLine": 22 }
                              }
                            }
                          ]
                        },
                        {
                          "ruleId": "java/network-client",
                          "message": { "text": "HTTP call leaves the service." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/HttpClient.java" },
                                "region": { "startLine": 33 }
                              }
                            }
                          ]
                        },
                        {
                          "ruleId": "java/unknown-finding",
                          "message": { "text": "General CodeQL finding." },
                          "locations": [
                            {
                              "physicalLocation": {
                                "artifactLocation": { "uri": "backend/src/main/java/com/example/General.java" },
                                "region": { "startLine": 44 }
                              }
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;
    }

    private static String sarifWithoutLocations() {
        return """
                {
                  "version": "2.1.0",
                  "runs": [
                    {
                      "tool": {
                        "driver": {
                          "name": "CodeQL",
                          "rules": [
                            {
                              "id": "java/locationless",
                              "name": "Locationless finding",
                              "shortDescription": { "text": "No primary location" },
                              "properties": { "tags": ["quality"] }
                            }
                          ]
                        }
                      },
                      "results": [
                        {
                          "ruleId": "java/locationless",
                          "message": { "text": "No primary location." }
                        }
                      ]
                    }
                  ]
                }
                """;
    }
}

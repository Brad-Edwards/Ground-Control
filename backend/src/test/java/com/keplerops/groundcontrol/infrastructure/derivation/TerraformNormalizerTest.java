package com.keplerops.groundcontrol.infrastructure.derivation;

import static org.assertj.core.api.Assertions.assertThat;

import com.keplerops.groundcontrol.domain.derivation.service.DerivedSystemModelFact;
import com.keplerops.groundcontrol.domain.derivation.state.SystemModelFactKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerraformNormalizerTest {

    private static final String SURFACE = "terraform";
    private static final String PATH = "main.tf";
    private static final String ADAPTER_ID = "iac-pipeline-derivation";
    private static final String COMMIT = "abc123";
    private static final String RULESET_VERSION = "1.0.0";
    private static final Instant NOW = Instant.now();

    private List<DerivedSystemModelFact> normalize(String content) {
        return new TerraformNormalizer().normalize(SURFACE, PATH, content, ADAPTER_ID, COMMIT, RULESET_VERSION, NOW);
    }

    @Test
    void resourceBlockEmitsComponent() {
        var content =
                """
                resource "aws_s3_bucket" "my_bucket" {
                  bucket = "my-tf-test-bucket"
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-resource");
            assertThat(f.payload()).containsEntry("deployTarget", "aws_s3_bucket");
        });
    }

    @Test
    void providerBlockEmitsComponentAndExternalInteraction() {
        var content =
                """
                provider "aws" {
                  region = "us-east-1"
                }
                """;
        var facts = normalize(content);

        assertThat(facts)
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
                    assertThat(f.payload()).containsEntry("artifactKind", "terraform-provider");
                })
                .anySatisfy(f -> {
                    assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
                    assertThat(f.payload()).containsEntry("artifactKind", "provider-registry");
                });
    }

    @Test
    void moduleBlockWithRemoteSourceEmitsExternalInteraction() {
        var content =
                """
                module "vpc" {
                  source  = "terraform-aws-modules/vpc/aws"
                  version = "5.1.0"
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-module");
        });
    }

    @Test
    void sensitiveVariableEmitsDataClassificationHint() {
        var content =
                """
                variable "db_password" {
                  type      = string
                  sensitive = true
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.DATA_CLASSIFICATION_HINT);
            assertThat(f.payload()).containsEntry("artifactKind", "sensitive-variable");
        });
    }

    @Test
    void secretLikeVariableNameEmitsSecretUsage() {
        var content =
                """
                variable "api_key" {
                  type = string
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE);
            assertThat(f.payload()).containsEntry("secretRef", "api_key");
            assertThat(f.payload()).containsEntry("secretScope", "variable");
        });
    }

    @Test
    void terraformBackendBlockEmitsTrustBoundary() {
        var content =
                """
                terraform {
                  backend "s3" {
                    bucket = "my-tf-state"
                    key    = "state.tfstate"
                    region = "us-east-1"
                  }
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-state-boundary");
            assertThat(f.payload()).containsEntry("deployTarget", "s3");
        });
    }

    @Test
    void sensitiveOutputEmitsDataClassificationHint() {
        var content =
                """
                output "db_endpoint" {
                  value     = aws_db_instance.main.endpoint
                  sensitive = true
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.DATA_CLASSIFICATION_HINT);
            assertThat(f.payload()).containsEntry("artifactKind", "sensitive-output");
        });
    }

    @Test
    void commentLinesAreIgnored() {
        var content =
                """
                # This is a comment
                // Another comment
                resource "aws_s3_bucket" "main" {
                  # bucket name
                  bucket = "my-bucket"
                }
                """;
        var facts = normalize(content);

        // Should still parse the resource block and emit exactly one fact
        assertThat(facts).hasSize(1).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-resource");
        });
    }

    // ── Provisioner block ─────────────────────────────────────────────────────

    @Test
    void provisionerInsideResourceEmitsComponent() {
        var content =
                """
                resource "null_resource" "setup" {
                  provisioner "remote-exec" {
                    inline = ["echo hello"]
                  }
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-provisioner");
            assertThat(f.payload()).containsEntry("privilegedOperation", "remote-exec");
        });
    }

    @Test
    void provisionerLabelIsExtractedFromBlockHeader() {
        var content =
                """
                resource "null_resource" "infra" {
                  provisioner "local-exec" {
                    command = "echo done"
                  }
                }
                """;
        var facts = normalize(content);

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.label()).contains("local-exec");
        });
    }

    // ── Module source without path/URL → no external interaction ─────────────

    @Test
    void moduleWithLocalOnlySourceDoesNotEmitExternalInteraction() {
        // source = "." has no "/" and no "://" → not treated as remote
        var content =
                """
                module "local_mod" {
                  source = "."
                }
                """;
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.EXTERNAL_INTERACTION);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-module");
        });
    }

    // ── sensitive = true in resource body is a no-op ─────────────────────────

    @Test
    void sensitiveMarkerInResourceBodyIsIgnored() {
        var content =
                """
                resource "aws_secretsmanager_secret" "db" {
                  sensitive = true
                }
                """;
        var facts = normalize(content);

        // sensitive = true inside a resource block must not emit DATA_CLASSIFICATION_HINT
        assertThat(facts)
                .noneSatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.DATA_CLASSIFICATION_HINT));
        // But the resource itself should still be emitted
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-resource");
        });
    }

    // ── Variable with non-secret name → no SECRET_USAGE ─────────────────────

    @Test
    void variableWithNonSecretLikeNameDoesNotEmitSecretUsage() {
        var content =
                """
                variable "region" {
                  type    = string
                  default = "us-east-1"
                }
                """;
        var facts = normalize(content);

        assertThat(facts).noneSatisfy(f -> assertThat(f.factKind()).isEqualTo(SystemModelFactKind.SECRET_USAGE));
    }

    // ── Backend with no label falls back to "unknown" ────────────────────────

    @Test
    void backendWithEmptyLabelUsesUnknownFallback() {
        // Unusual but syntactically valid: backend block with no label
        // The block header is "backend {" — labels list will be empty
        var content =
                """
                terraform {
                  backend {
                    bucket = "state"
                  }
                }
                """;
        var facts = normalize(content);

        // A TRUST_BOUNDARY should still be emitted even without a backend type label
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.TRUST_BOUNDARY);
            assertThat(f.payload()).containsEntry("artifactKind", "remote-state-boundary");
        });
    }

    // ── Double-slash comment is ignored ──────────────────────────────────────

    @Test
    void doubleSlashCommentLinesAreIgnored() {
        var content =
                """
                // This is a double-slash comment
                resource "aws_s3_bucket" "main" {
                  bucket = "my-bucket"
                }
                """;
        var facts = normalize(content);

        assertThat(facts).hasSize(1);
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-resource");
        });
    }

    // ── Unrecognised top-level block type → no facts ─────────────────────────

    @Test
    void unrecognisedTopLevelBlockEmitsNoFacts() {
        // "data" blocks are not handled by the switch → default returns empty
        var content =
                """
                data "aws_ami" "ubuntu" {
                  filter {
                    name   = "name"
                    values = ["ubuntu*"]
                  }
                }
                """;
        var facts = normalize(content);

        assertThat(facts).isEmpty();
    }

    // ── Finding 1: fact-key stability across commits ──────────────────────────

    @Test
    void factKeyIsStableAcrossDifferentCommitShas() {
        var content =
                """
                resource "aws_s3_bucket" "my_bucket" {
                  bucket = "my-tf-test-bucket"
                }
                module "vpc" {
                  source = "git::https://example.com/vpc.git"
                }
                """;
        var factsA = new TerraformNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-aaaa", RULESET_VERSION, NOW);
        var factsB = new TerraformNormalizer()
                .normalize(SURFACE, PATH, content, ADAPTER_ID, "sha-bbbb", RULESET_VERSION, NOW);

        assertThat(factsA).hasSameSizeAs(factsB);
        var keysA = factsA.stream().map(DerivedSystemModelFact::factKey).toList();
        var keysB = factsB.stream().map(DerivedSystemModelFact::factKey).toList();
        assertThat(keysA).containsExactlyInAnyOrderElementsOf(keysB);
    }

    // ── Finding 3: URL credential sanitization ────────────────────────────────

    @Test
    void moduleSourceWithCredentialUrlStripsUserinfoAndQueryFromPayloadLabelSummary() {
        var content =
                """
                module "my_module" {
                  source = "git::https://user:secret@host.example.com/module.git?token=abc#v1"
                }
                """;
        var facts = normalize(content);

        var moduleFact = facts.stream()
                .filter(f -> f.factKind() == SystemModelFactKind.EXTERNAL_INTERACTION)
                .filter(f -> "remote-module".equals(f.payload().get("artifactKind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a remote-module EXTERNAL_INTERACTION fact"));

        // userinfo, query, and fragment must not appear anywhere
        assertThat(moduleFact.payload().toString()).doesNotContain("secret");
        assertThat(moduleFact.payload().toString()).doesNotContain("token=abc");
        assertThat(moduleFact.label()).doesNotContain("secret");
        assertThat(moduleFact.label()).doesNotContain("token=abc");
        assertThat(moduleFact.summary()).doesNotContain("secret");
        assertThat(moduleFact.summary()).doesNotContain("token=abc");
        // scheme + host + path must be retained
        assertThat(moduleFact.payload().get("registryTarget").toString()).contains("host.example.com");
        assertThat(moduleFact.payload().get("registryTarget").toString()).startsWith("git::");
    }
}

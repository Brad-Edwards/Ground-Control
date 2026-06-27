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

        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-provider");
        });
        assertThat(facts).anySatisfy(f -> {
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

        // Should still parse the resource block
        assertThat(facts).anySatisfy(f -> {
            assertThat(f.factKind()).isEqualTo(SystemModelFactKind.COMPONENT);
            assertThat(f.payload()).containsEntry("artifactKind", "terraform-resource");
        });
        // Should not emit anything for comment lines
        assertThat(facts).hasSize(1);
    }
}

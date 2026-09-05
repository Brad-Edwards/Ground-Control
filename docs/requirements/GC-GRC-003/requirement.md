---
id: GC-GRC-003
title: "IaC and Pipeline Derivation Adapter"
status: DEPRECATED
type: FUNCTIONAL
priority: MUST
wave: 7
created_at: 2026-06-12T07:24:51.648430Z
updated_at: 2026-07-11T23:43:44.555523Z
---

# GC-GRC-003 — IaC and Pipeline Derivation Adapter

## Statement

The system shall provide a derivation adapter for infrastructure-as-code and CI/CD pipeline artifacts.

(a) Supported surfaces shall include, at minimum: GitHub Actions workflows, Dockerfiles, docker-compose, and Terraform.

(b) Derived facts shall capture the deployment surface: secret scopes and exposure paths, runner/executor trust levels, artifact build and publish flows, registry and deploy targets, and privileged operations.

(c) Facts shall normalize into the GC-GRC-001 schema as components, flows, and boundary crossings with full provenance.

(d) Pipeline changes shall be classifiable as security-relevant from these facts alone, without agent judgment.

## Rationale

Deployment pipelines are a high-consequence security surface that path-pattern heuristics and LLM judgment routinely under-classify (the motivating incident: a major pipeline change screened with no security verdict). Parsing the pipeline yields its trust and secret topology deterministically.

## Traceability

- IMPLEMENTS → GITHUB_ISSUE `1116` (Issue #1116: GC-GRC-003 IaC and pipeline derivation adapter)

## Historical traceability

Links below name artifacts that are not in the tree, almost all of them removed by the
#1500 re-platform. They are kept for provenance and sit outside the parsed
`## Traceability` section, so no tool reads them as live evidence. Do not infer current
implementation from them.

- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/IacPipelineDerivationAdapter.java` (IacPipelineDerivationAdapter — IaC/pipeline derivation adapter)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/IacPipelineDerivationProperties.java` (IacPipelineDerivationProperties — adapter config POJO)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/GitHubActionsNormalizer.java` (GitHubActionsNormalizer — clause (a) GitHub Actions surface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/DockerfileNormalizer.java` (DockerfileNormalizer — clause (a) Dockerfile surface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/DockerComposeNormalizer.java` (DockerComposeNormalizer — clause (a) docker-compose surface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/TerraformNormalizer.java` (TerraformNormalizer — clause (a) Terraform surface)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/RemoteRefSanitizer.java` (RemoteRefSanitizer — clause (b) secret/URL credential redaction)
- IMPLEMENTS → CODE_FILE `backend/src/main/java/com/keplerops/groundcontrol/infrastructure/derivation/IacFactKeys.java` (IacFactKeys — clause (c) deterministic provenance fact keys)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/IacPipelineDerivationAdapterTest.java` (IacPipelineDerivationAdapterTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/GitHubActionsNormalizerTest.java` (GitHubActionsNormalizerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/DockerfileNormalizerTest.java` (DockerfileNormalizerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/DockerComposeNormalizerTest.java` (DockerComposeNormalizerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/TerraformNormalizerTest.java` (TerraformNormalizerTest)
- TESTS → TEST `backend/src/test/java/com/keplerops/groundcontrol/infrastructure/derivation/RemoteRefSanitizerTest.java` (RemoteRefSanitizerTest)

package com.keplerops.groundcontrol.infrastructure.derivation;

/**
 * Package-private string-constant holder for IaC/pipeline derivation payload keys and surface
 * identifier tokens. Centralising these literals removes duplicated-literal findings (S1192) that
 * would otherwise appear across all normalizers.
 */
final class IacFactKeys {

    // ── Payload key constants (shared across all normalizers) ──────────────────

    /** Payload key whose value identifies the IaC surface the fact was derived from. */
    static final String SURFACE = "surface";

    /** Payload key whose value classifies the artifact kind within the surface. */
    static final String ARTIFACT_KIND = "artifactKind";

    /** Payload key whose value is the repository-relative path of the source file. */
    static final String SOURCE_PATH = "sourcePath";

    /** Payload key whose value is the secret name or identifier referenced by the fact. */
    static final String SECRET_REF = "secretRef";

    /** Payload key whose value describes the scope in which the secret is accessible. */
    static final String SECRET_SCOPE = "secretScope";

    /** Payload key whose value names the privileged operation for a trust-boundary fact. */
    static final String PRIVILEGED_OPERATION = "privilegedOperation";

    /** Payload key whose value identifies the exposure or deployment pathway. */
    static final String EXPOSURE_PATH = "exposurePath";

    /** Payload key whose value identifies the deployment target (provider, backend, resource type, etc.). */
    static final String DEPLOY_TARGET = "deployTarget";

    // ── Surface identifier tokens ──────────────────────────────────────────────

    static final String SURFACE_DOCKERFILE = "dockerfile";
    static final String SURFACE_DOCKER_COMPOSE = "docker-compose";
    static final String SURFACE_GITHUB_ACTIONS = "github-actions";
    static final String SURFACE_TERRAFORM = "terraform";

    private IacFactKeys() {}
}

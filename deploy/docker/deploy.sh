#!/bin/bash
# Deploy script for the red-dragon docker-compose stack (ADR-030).
#
# Invoked as the SSH forced command for the `gc-deploy` user — the deploy
# path SSHes to gc-deploy@red-dragon and the authorized_keys entry forces
# /opt/gc/deploy.sh to run with no argv. SSH exit code is this script's
# exit code, so the caller's pass/fail reflects the deploy outcome. The
# operator-facing wrapper is scripts/deploy.sh (it syncs the canonical
# artifacts into /opt/gc/ and publishes the deploy outcome to GitHub
# Deployments); this script is the on-host rollout itself.
#
# Contract (ADR-030 + GC-P022 + GC-P023):
#   - `/opt/gc/.env` pins `GC_IMAGE` to an immutable versioned release tag
#     (`...:X.Y.Z`, ADR-063), not a floating branch tag. Promotion is the
#     deliberate act of bumping that pin to a cut release; a digest pin
#     (`@sha256:` with `GC_ALLOW_IMAGE_PIN=1`) is the rollback form. The
#     deploy-time env validator (validate-env.sh, RELEASE_PIN) rejects a
#     floating tag like `...:main` so prod cannot silently follow a moving tag.
#   - `/opt/gc/docker-compose.yml` is the production compose file (canonical
#     copy: `deploy/docker/docker-compose.prod.yml`).
#   - Health check runs INSIDE the backend container via `docker compose exec`
#     because the host port-binding is restricted to the tailnet IP (#828 /
#     ADR-026). The JRE Alpine base ships `wget`, not `curl`.
#   - Drift guard (GC-P023): before rollout, the live /opt/gc mirrors are
#     checksum-verified against the synced MANIFEST.sha256. A mismatch fails
#     loudly — the manual-file-sync drift this issue (#855) exists to kill.
#   - Env validation (GC-P023): /opt/gc/.env is validated against env.schema
#     before restart (validate-env.sh). Reports variable NAMES only.
#   - Staleness guard (#953 / GC-P022): the deploy FAILS LOUDLY if the freshly
#     pulled backend image carries no `org.opencontainers.image.revision`
#     label, or if that revision has not advanced past the running container's
#     revision. Set `GC_ALLOW_SAME_REVISION=1` for an intentional same-image
#     restart/rollback (loud warning, never a silent bypass).
#   - Rollback (GC-P023): if the freshly pulled candidate fails its health
#     window, the deploy automatically restores the previous image and never
#     leaves a newly unhealthy backend as the steady state.
#   - Deploy state (GC-P023): the rolled-out digest + source commit SHA are
#     written to /opt/gc/deploy-state.json and echoed as a DEPLOY_STATE_JSON
#     marker line (no secrets) for the wrapper to publish to GitHub Deployments.
set -euo pipefail

# GC_DIR defaults to the production mount; overridable so the rollback /
# health / deploy-state orchestration can be exercised against a throwaway
# stack in an integration test (tools/tests/deploy-rollback-integration.sh).
GC_DIR="${GC_DIR:-/opt/gc}"
cd "${GC_DIR}"

# Map a canonical repo artifact basename to its /opt/gc runtime path (the
# compose file is renamed on the host).
host_path_for() {
  case "$1" in
    docker-compose.prod.yml) echo "${GC_DIR}/docker-compose.yml" ;;
    *) echo "${GC_DIR}/$1" ;;
  esac
}

# --- Drift guard (GC-P023) -------------------------------------------------
# Verify the live /opt/gc mirrors match the synced MANIFEST.sha256. If the
# manifest is absent (host not yet re-synced for #855) we warn and continue so
# an in-flight rollout is not bricked; once present, a mismatch is fatal.
if [ -f "${GC_DIR}/MANIFEST.sha256" ]; then
  drift=()
  while IFS= read -r mline || [ -n "${mline}" ]; do
    case "${mline}" in ''|'#'*) continue ;; esac
    expected_sha="${mline%% *}"
    name="${mline##*  }"
    hp="$(host_path_for "${name}")"
    if [ ! -f "${hp}" ]; then
      drift+=("missing runtime file ${hp} (manifest entry ${name})")
      continue
    fi
    actual_sha="$(sha256sum "${hp}" | cut -d' ' -f1)"
    if [ "${actual_sha}" != "${expected_sha}" ]; then
      drift+=("${hp} drifted from canonical ${name}")
    fi
  done < "${GC_DIR}/MANIFEST.sha256"
  if [ "${#drift[@]}" -gt 0 ]; then
    echo "ERROR: deploy-host artifacts drifted from the canonical repo copies (GC-P023):"
    for d in "${drift[@]}"; do echo "  - ${d}"; done
    echo "       Re-run 'make deploy' from a current checkout to re-sync /opt/gc, or"
    echo "       reconcile the out-of-band edit. Refusing to roll out drifted artifacts."
    exit 1
  fi
  echo "Drift guard: /opt/gc mirrors match canonical artifacts."
else
  echo "WARNING: ${GC_DIR}/MANIFEST.sha256 absent; skipping drift guard (host not yet" \
       "re-synced for GC-P023). Run 'make deploy' from a checkout to install it."
fi

# --- Env validation (GC-P023) ----------------------------------------------
# Validate /opt/gc/.env against the canonical schema before touching the stack.
if [ -x "${GC_DIR}/validate-env.sh" ]; then
  "${GC_DIR}/validate-env.sh" "${GC_DIR}/.env" "${GC_DIR}/env.schema"
elif [ -f "${GC_DIR}/validate-env.sh" ]; then
  bash "${GC_DIR}/validate-env.sh" "${GC_DIR}/.env" "${GC_DIR}/env.schema"
else
  echo "WARNING: ${GC_DIR}/validate-env.sh absent; skipping env validation" \
       "(host not yet re-synced for GC-P023)."
fi

# Resolve the backend image ref from .env so we can inspect its OCI revision
# label after pulling (the compose file references it as ${GC_IMAGE}).
GC_IMAGE="$(grep -E '^GC_IMAGE=' .env | head -n1 | cut -d= -f2- || true)"

# Capture the revision AND pullable digest of the running backend container
# BEFORE pulling: the revision drives the staleness guard, the digest is the
# rollback target if the candidate fails its health check.
running_rev=""
previous_digest=""
running_cid="$(docker compose --env-file .env ps -q backend 2>/dev/null || true)"
if [ -n "${running_cid}" ]; then
  running_img="$(docker inspect --format '{{ .Image }}' "${running_cid}" 2>/dev/null || true)"
  if [ -n "${running_img}" ]; then
    running_rev="$(docker image inspect \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
      "${running_img}" 2>/dev/null || true)"
    previous_digest="$(docker image inspect \
      --format '{{ if .RepoDigests }}{{ index .RepoDigests 0 }}{{ end }}' \
      "${running_img}" 2>/dev/null || true)"
  fi
fi

docker compose --env-file .env pull

# --- Staleness guard (#953 / GC-P022) --------------------------------------
pulled_rev=""
pulled_digest=""
if [ -n "${GC_IMAGE}" ]; then
  pulled_rev="$(docker image inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "${GC_IMAGE}" 2>/dev/null || true)"
  pulled_digest="$(docker image inspect \
    --format '{{ if .RepoDigests }}{{ index .RepoDigests 0 }}{{ end }}' \
    "${GC_IMAGE}" 2>/dev/null || true)"
fi

if [ -z "${pulled_rev}" ]; then
  echo "ERROR: pulled image '${GC_IMAGE:-<unresolved>}' has no" \
       "org.opencontainers.image.revision label."
  echo "       Cannot confirm the deploy is fresh; refusing to roll out."
  echo "       image ref/digest: ${GC_IMAGE:-<unresolved>} ${pulled_digest}"
  exit 1
fi

echo "Image revision: running='${running_rev:-<none>}' pulled='${pulled_rev}'"
echo "Image ref/digest: ${GC_IMAGE} ${pulled_digest}"

if [ -n "${running_rev}" ] && [ "${pulled_rev}" = "${running_rev}" ]; then
  if [ "${GC_ALLOW_SAME_REVISION:-}" = "1" ]; then
    echo "WARNING: pulled revision == running revision (${pulled_rev});" \
         "proceeding because GC_ALLOW_SAME_REVISION=1 (intentional restart/rollback)."
  else
    echo "ERROR: pulled image revision (${pulled_rev}) has not advanced past the"
    echo "       running container's revision. 'docker compose pull' resolved a"
    echo "       frozen image — the silent-stale-deploy failure mode (#953)."
    echo "       Verify GC_IMAGE's namespace/tag match CI's publish target."
    echo "       For an intentional same-image restart/rollback, re-run with"
    echo "       GC_ALLOW_SAME_REVISION=1."
    exit 1
  fi
fi
# ---------------------------------------------------------------------------

backend_health_ok() {
  docker compose --env-file .env exec -T backend \
    wget -q -O - http://localhost:8000/actuator/health 2>/dev/null \
    | grep -q '"UP"'
}

# Poll health from INSIDE the container (host binds are tailnet-restricted,
# #828). Returns 0 once the backend is healthy, 1 after the window (default
# 30 tries x 2s = ~60s; GC_HEALTH_RETRIES / GC_HEALTH_INTERVAL let an
# integration test shrink the window).
health_ok() {
  local i
  for i in $(seq 1 "${GC_HEALTH_RETRIES:-30}"); do
    if backend_health_ok; then
      return 0
    fi
    sleep "${GC_HEALTH_INTERVAL:-2}"
  done
  return 1
}

# Write the deploy-state record (no secrets) and emit the one-line marker the
# wrapper captures from SSH output to publish a GitHub Deployment. `revision`
# is the commit SHA of the image ACTUALLY SERVING after this run (the candidate
# on a clean deploy, the restored previous image after a rollback) so the
# queryable surface answers "what is deployed?" correctly; `candidate_revision`
# records what this run attempted, which differs from `revision` on a rollback.
write_deploy_state() {
  local outcome="$1" active_digest="$2" active_revision="$3" now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cat > "${GC_DIR}/deploy-state.json" <<EOF
{
  "outcome": "${outcome}",
  "image_ref": "${GC_IMAGE}",
  "active_digest": "${active_digest}",
  "revision": "${active_revision}",
  "candidate_digest": "${pulled_digest}",
  "candidate_revision": "${pulled_rev}",
  "previous_digest": "${previous_digest}",
  "timestamp": "${now}",
  "host": "$(hostname)"
}
EOF
  chmod 0644 "${GC_DIR}/deploy-state.json" 2>/dev/null || true
  echo "DEPLOY_STATE_JSON={\"outcome\":\"${outcome}\",\"image_ref\":\"${GC_IMAGE}\",\"active_digest\":\"${active_digest}\",\"revision\":\"${active_revision}\",\"candidate_revision\":\"${pulled_rev}\",\"timestamp\":\"${now}\"}"
}

docker compose --env-file .env up -d
echo "Waiting for health check..."
if health_ok; then
  write_deploy_state deployed "${pulled_digest}" "${pulled_rev}"
  echo "Deploy complete - application is UP"
  exit 0
fi

# --- Rollback (GC-P023) ----------------------------------------------------
echo "ERROR: candidate image failed health check within 60s."
docker compose --env-file .env logs --tail=50 backend || true

if [ -n "${previous_digest}" ]; then
  echo "Rolling back to previous image: ${previous_digest}"
  if GC_IMAGE="${previous_digest}" docker compose --env-file .env up -d && health_ok; then
    # Active image is now the restored previous one, so the deploy-state
    # revision must be the previous image's revision, NOT the failed candidate.
    write_deploy_state rolled_back "${previous_digest}" "${running_rev}"
    echo "ERROR: deploy FAILED; rolled back to previous image and service is UP."
    echo "       The candidate (${pulled_rev}) did not pass health; no new code shipped."
    exit 1
  fi
  echo "CRITICAL: rollback to ${previous_digest} did not become healthy."
else
  echo "CRITICAL: no previous image digest captured; cannot auto-roll-back."
fi

write_deploy_state failed "${previous_digest}" "${running_rev}"
echo "ERROR: deploy failed and the stack is not healthy. Operator intervention required."
exit 1

#!/bin/bash
# Integration test for deploy/docker/deploy.sh (GC-P023).
#
# Exercises the orchestration that cannot be unit-tested without a container
# runtime — staleness guard, automatic rollback, drift guard, and the
# deploy-state record — against REAL containers in a throwaway compose project
# backed by a local registry. It never touches /opt/gc or red-dragon: it points
# deploy.sh at a temp GC_DIR via the GC_DIR override and uses a private compose
# project + local registry, all torn down on exit.
#
# Requires Docker. Skips (exit 0) when Docker is unavailable so it is safe to
# run anywhere; it is intentionally NOT named test_*.py so the stdlib-only,
# Docker-free `tools/tests` unittest sweep does not pick it up.
#
# Usage: bash tools/tests/deploy-rollback-integration.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CANON="${REPO_ROOT}/deploy/docker"
REG_PORT="${GC_TEST_REGISTRY_PORT:-5959}"
REG="localhost:${REG_PORT}"
REG_NAME="gc-deploytest-registry"
PROJECT="gcdeploytest"
export COMPOSE_PROJECT_NAME="${PROJECT}"
GCDIR=""
PASS=0
FAIL=0

if ! docker info >/dev/null 2>&1; then
  echo "SKIP: Docker is not available; skipping deploy rollback integration test."
  exit 0
fi

cleanup() {
  if [ -n "${GCDIR}" ] && [ -f "${GCDIR}/docker-compose.yml" ]; then
    ( cd "${GCDIR}" && docker compose --env-file .env -p "${PROJECT}" down -v >/dev/null 2>&1 || true )
  fi
  docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
  docker rmi -f "${REG}/gctest:1.0.0" "${REG}/gctest:1.0.1" "${REG}/gctest:1.0.2" >/dev/null 2>&1 || true
  [ -n "${GCDIR}" ] && rm -rf "${GCDIR}"
}
trap cleanup EXIT

ok()   { echo "  PASS: $1"; PASS=$((PASS + 1)); }
bad()  { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

# --- Local registry --------------------------------------------------------
docker rm -f "${REG_NAME}" >/dev/null 2>&1 || true
docker run -d --rm -p "127.0.0.1:${REG_PORT}:5000" --name "${REG_NAME}" registry:2 >/dev/null
for _ in $(seq 1 30); do
  curl -sf "http://${REG}/v2/" >/dev/null 2>&1 && break
  sleep 1
done

# --- Build + push a healthy and a broken backend image ---------------------
# busybox httpd serves /actuator/health; wget inside the container is what
# deploy.sh's health check uses. Each image carries an OCI revision label so
# the staleness guard and deploy-state revision tracking have real input.
build_img() {
  local tag="$1" status="$2" rev="$3"
  docker build -t "${REG}/gctest:${tag}" - >/dev/null 2>&1 <<EOF
FROM alpine:3
RUN apk add --no-cache busybox-extras \
 && mkdir -p /www/actuator && printf '{"status":"${status}"}' > /www/actuator/health
LABEL org.opencontainers.image.revision=${rev}
EXPOSE 8000
CMD ["/usr/sbin/httpd","-f","-p","8000","-h","/www"]
EOF
  docker push "${REG}/gctest:${tag}" >/dev/null 2>&1
}
# Tags are semver release coordinates (ADR-063): validate-env.sh now rejects a
# floating tag, so the throwaway images use immutable version tags. The health /
# rollback semantics are driven by image content + OCI revision label, not the
# tag string. 1.0.0 = healthy (running), 1.0.1 = broken candidate.
build_img 1.0.0 UP  revhealthyAAA
build_img 1.0.1 DOWN revbrokenBBB

# --- Temp GC_DIR mirroring /opt/gc -----------------------------------------
GCDIR="$(mktemp -d "${TMPDIR:-/tmp}/gc-deploytest.XXXXXX")"
cp "${CANON}/deploy.sh" "${CANON}/validate-env.sh" "${CANON}/env.schema" "${GCDIR}/"
chmod +x "${GCDIR}/deploy.sh" "${GCDIR}/validate-env.sh"
cat > "${GCDIR}/docker-compose.yml" <<'EOF'
services:
  backend:
    image: ${GC_IMAGE}
    restart: "no"
EOF
cat > "${GCDIR}/.env" <<EOF
GC_IMAGE=${REG}/gctest:1.0.0
GC_DATABASE_URL=jdbc:postgresql://db:5432/x
GC_DATABASE_USER=x
GC_DATABASE_PASSWORD=x
JAVA_TOOL_OPTIONS=-Xmx64m
POSTGRES_DB=x
POSTGRES_USER=x
POSTGRES_PASSWORD=x
GC_SECURITY_ENABLED=false
EOF
# Manifest the on-host deploy.sh drift-guards against. The compose entry name is
# the canonical basename (docker-compose.prod.yml); deploy.sh maps it to the
# host's docker-compose.yml.
{
  printf '%s  deploy.sh\n'               "$(sha256sum "${GCDIR}/deploy.sh"          | cut -d' ' -f1)"
  printf '%s  docker-compose.prod.yml\n' "$(sha256sum "${GCDIR}/docker-compose.yml" | cut -d' ' -f1)"
  printf '%s  validate-env.sh\n'         "$(sha256sum "${GCDIR}/validate-env.sh"    | cut -d' ' -f1)"
  printf '%s  env.schema\n'              "$(sha256sum "${GCDIR}/env.schema"         | cut -d' ' -f1)"
} > "${GCDIR}/MANIFEST.sha256"

run_deploy() {  # extra env assignments passed as args
  env GC_DIR="${GCDIR}" GC_HEALTH_RETRIES=8 GC_HEALTH_INTERVAL=1 "$@" \
    bash "${GCDIR}/deploy.sh"
}
set_image() { sed -i "s|^GC_IMAGE=.*|GC_IMAGE=${REG}/gctest:$1|" "${GCDIR}/.env"; }
state() { cat "${GCDIR}/deploy-state.json" 2>/dev/null; }

echo "== Scenario 1: clean deploy of a healthy image =="
if run_deploy >/tmp/gcdt.1 2>&1; then
  grep -q '"outcome": "deployed"' "$(echo "${GCDIR}/deploy-state.json")" && ok "outcome=deployed" || bad "outcome not deployed"
  state | grep -q '"revision": "revhealthyAAA"' && ok "revision=healthy" || bad "wrong revision"
else
  bad "clean deploy exited non-zero"; cat /tmp/gcdt.1
fi

echo "== Scenario 2: staleness guard blocks a same-revision re-deploy =="
if run_deploy >/tmp/gcdt.2 2>&1; then
  bad "same-revision re-deploy should have failed"
else
  grep -q "has not advanced" /tmp/gcdt.2 && ok "staleness guard fired" || { bad "no staleness message"; cat /tmp/gcdt.2; }
fi
echo "== Scenario 2b: GC_ALLOW_SAME_REVISION=1 overrides the staleness guard =="
if run_deploy GC_ALLOW_SAME_REVISION=1 >/tmp/gcdt.2b 2>&1; then
  ok "override allowed same-revision restart"
else
  bad "override did not allow same-revision restart"; cat /tmp/gcdt.2b
fi

echo "== Scenario 3: a broken candidate auto-rolls-back to the previous image =="
set_image 1.0.1
if run_deploy >/tmp/gcdt.3 2>&1; then
  bad "broken deploy should exit non-zero"
else
  grep -q '"outcome": "rolled_back"' "${GCDIR}/deploy-state.json" && ok "outcome=rolled_back" || bad "not rolled_back"
  # The codex review fix: the queryable record must report the RESTORED image's
  # revision, not the failed candidate's.
  state | grep -q '"revision": "revhealthyAAA"' && ok "active revision = restored healthy (not candidate)" || bad "revision is wrong after rollback"
  state | grep -q '"candidate_revision": "revbrokenBBB"' && ok "candidate_revision = broken" || bad "candidate_revision wrong"
  # Service is actually serving the healthy image again.
  if docker compose --env-file "${GCDIR}/.env" -p "${PROJECT}" -f "${GCDIR}/docker-compose.yml" exec -T backend wget -q -O - http://localhost:8000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    ok "backend healthy after rollback"
  else
    bad "backend not healthy after rollback"
  fi
fi

echo "== Scenario 4: drift guard refuses a tampered mirror =="
echo "# out-of-band edit" >> "${GCDIR}/docker-compose.yml"
if run_deploy >/tmp/gcdt.4 2>&1; then
  bad "drift guard should have refused the tampered mirror"
else
  grep -q "drifted from the canonical" /tmp/gcdt.4 && ok "drift guard refused rollout" || { bad "no drift message"; cat /tmp/gcdt.4; }
fi

echo "== Scenario 5: one-command rollback to a prior healthy version =="
# Build a third healthy image at 1.0.2 (revhealthyCCC) and promote it so the
# running container is CCC, then use scripts/rollback.sh to roll back to the
# original healthy 1.0.0 image (revhealthyAAA) in a single command.
build_img 1.0.2 UP  revhealthyCCC
# Restore docker-compose.yml that was corrupted by the drift-guard scenario.
# Restore the same minimal test compose (no host port-binding, no db service)
# used throughout this suite — NOT the full production compose, which has host
# port bindings and a db service that would conflict here.
cat > "${GCDIR}/docker-compose.yml" <<'COMPOSE'
services:
  backend:
    image: ${GC_IMAGE}
    restart: "no"
COMPOSE
{
  printf '%s  deploy.sh\n'               "$(sha256sum "${GCDIR}/deploy.sh"          | cut -d' ' -f1)"
  printf '%s  docker-compose.prod.yml\n' "$(sha256sum "${GCDIR}/docker-compose.yml" | cut -d' ' -f1)"
  printf '%s  validate-env.sh\n'         "$(sha256sum "${GCDIR}/validate-env.sh"    | cut -d' ' -f1)"
  printf '%s  env.schema\n'              "$(sha256sum "${GCDIR}/env.schema"         | cut -d' ' -f1)"
} > "${GCDIR}/MANIFEST.sha256"
# Deploy 1.0.2 so the running image is revhealthyCCC.
set_image 1.0.2
if run_deploy >/tmp/gcdt.5a 2>&1; then
  state | grep -q '"revision": "revhealthyCCC"' && ok "pre-rollback running revision=CCC" || { bad "pre-rollback revision is not CCC"; cat /tmp/gcdt.5a; }
else
  bad "deploy of 1.0.2 failed before rollback test"; cat /tmp/gcdt.5a
fi
# Invoke the one-command rollback: scripts/rollback.sh 1.0.0 with test seam.
# GC_ROLLBACK_LOCAL=1 patches $GC_DIR/.env directly (no SSH/sudo) and runs
# bash "$GC_DIR/deploy.sh" — the same canonical path the other scenarios use.
if env GC_DIR="${GCDIR}" GC_ROLLBACK_LOCAL=1 GC_HEALTH_RETRIES=8 GC_HEALTH_INTERVAL=1 \
    bash "${REPO_ROOT}/scripts/rollback.sh" 1.0.0 >/tmp/gcdt.5b 2>&1; then
  grep -q '"outcome": "deployed"' "${GCDIR}/deploy-state.json" && ok "outcome=deployed after rollback" || bad "outcome is not deployed after rollback"
  state | grep -q '"revision": "revhealthyAAA"' && ok "revision=revhealthyAAA (rolled-back image)" || bad "revision is not revhealthyAAA after rollback"
  # Staleness guard must NOT have blocked the older-revision rollback.
  if ! grep -q "has not advanced" /tmp/gcdt.5b; then
    ok "staleness guard did not block the older-revision rollback"
  else
    bad "staleness guard incorrectly blocked the rollback"; cat /tmp/gcdt.5b
  fi
  # Backend must serve the healthy 1.0.0 image.
  if docker compose --env-file "${GCDIR}/.env" -p "${PROJECT}" -f "${GCDIR}/docker-compose.yml" \
       exec -T backend wget -q -O - http://localhost:8000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    ok "backend healthy (UP) after one-command rollback"
  else
    bad "backend not healthy after one-command rollback"
  fi
else
  bad "scripts/rollback.sh exited non-zero"; cat /tmp/gcdt.5b
fi

echo
echo "deploy rollback integration: ${PASS} passed, ${FAIL} failed"
[ "${FAIL}" -eq 0 ]

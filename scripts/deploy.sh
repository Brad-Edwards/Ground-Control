#!/bin/bash
# Ground Control operator deploy wrapper (ADR-030, GC-P023).
#
# Invoked by `make deploy`. The host-aware entry point that does the three
# things the on-host forced-command script (/opt/gc/deploy.sh, canonical copy
# deploy/docker/deploy.sh) cannot do for itself:
#
#   1. SYNC the canonical deploy artifacts from this repo checkout into
#      /opt/gc/ before rolling out — eliminating the manual scp step that was
#      the root cause of deploy-host drift (#855). /opt/gc/.env is host-local
#      and carries secrets; it is NEVER synced or read here.
#   2. INVOKE the rollout: on red-dragon itself via `sudo -u gc-deploy`, or
#      from any other tailnet host via the `gc-deploy@red-dragon` forced
#      command (ssh argv is ignored by design — argv-driven rollbacks are not
#      supported; pin GC_IMAGE in /opt/gc/.env and re-run instead).
#   3. PUBLISH the deploy outcome (rolled-out digest + source commit SHA, no
#      secrets) to GitHub Deployments so "what is deployed?" is answerable
#      without SSHing to the box (`make deploy-status`).
#
# The on-host deploy.sh re-verifies the synced artifacts against
# MANIFEST.sha256 and refuses to roll out drifted files, so an imperfect or
# skipped sync fails safe (it never deploys mismatched artifacts).
#
# Deploy-target configuration (extensibility seam — a second target is config,
# not a forked script): override via environment.
set -euo pipefail

if [ "$#" -gt 0 ]; then
  echo "usage: $0   (no arguments)" >&2
  echo "for rollbacks: edit GC_IMAGE in /opt/gc/.env on red-dragon and re-run" >&2
  exit 2
fi

# Shared checkout-to-owner/repo resolver (GC-P026) — same github.com remote-URL
# contract as the other shell entry points and the MCP server.
# shellcheck source=scripts/lib/gh-repo-slug.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/gh-repo-slug.sh"

DEPLOY_HOST="${GC_DEPLOY_HOST:-red-dragon}"
DEPLOY_SSH_USER="${GC_DEPLOY_SSH_USER:-gc-deploy}"
DEPLOY_REMOTE_DIR="${GC_DEPLOY_REMOTE_DIR:-/opt/gc}"
DEPLOY_REMOTE_OWNER="${GC_DEPLOY_REMOTE_OWNER:-gc-deploy:gc-deploy}"
DEPLOY_ENVIRONMENT="${GC_DEPLOY_ENVIRONMENT:-production}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CANON_DIR="${REPO_ROOT}/deploy/docker"

# Set by sync_artifacts on the off-box path (remote mktemp -d); cleaned by the
# EXIT trap so a failed copy/install never leaves the staging dir behind.
REMOTE_STAGE=""

# Canonical artifacts to sync: "<repo-basename>:<mode>[:<remote-basename>]".
ARTIFACTS=(
  "deploy.sh:0755"
  "docker-compose.prod.yml:0644:docker-compose.yml"
  "validate-env.sh:0755"
  "env.schema:0644"
  "MANIFEST.sha256:0644"
)

on_box() {
  [ "$(hostname -s 2>/dev/null || hostname)" = "${DEPLOY_HOST}" ]
}

sync_artifacts() {
  echo "Syncing canonical deploy artifacts into ${DEPLOY_REMOTE_DIR} (GC-P023)..."
  local entry src mode dst
  if on_box; then
    for entry in "${ARTIFACTS[@]}"; do
      IFS=':' read -r src mode dst <<<"${entry}"
      dst="${dst:-${src}}"
      sudo install -o "${DEPLOY_REMOTE_OWNER%:*}" -g "${DEPLOY_REMOTE_OWNER#*:}" \
        -m "${mode}" "${CANON_DIR}/${src}" "${DEPLOY_REMOTE_DIR}/${dst}"
    done
  else
    # Stage under a private, unpredictable remote dir: `mktemp -d` creates it
    # atomically with mode 0700 owned by the SSH user, so a local attacker on
    # the deploy host cannot pre-create or race a predictable path and swap an
    # artifact before the privileged `install` (codex review, #855). REMOTE_STAGE
    # is script-scoped so the EXIT trap removes it even if a copy/install fails.
    REMOTE_STAGE="$(ssh "${DEPLOY_HOST}" 'mktemp -d "${TMPDIR:-/tmp}/gc-deploy-sync.XXXXXXXX"')"
    [ -n "${REMOTE_STAGE}" ] || { echo "ERROR: could not create remote staging dir on ${DEPLOY_HOST}" >&2; exit 1; }
    for entry in "${ARTIFACTS[@]}"; do
      IFS=':' read -r src mode dst <<<"${entry}"
      dst="${dst:-${src}}"
      scp -q "${CANON_DIR}/${src}" "${DEPLOY_HOST}:${REMOTE_STAGE}/${dst}"
      ssh "${DEPLOY_HOST}" "sudo install -o ${DEPLOY_REMOTE_OWNER%:*} -g ${DEPLOY_REMOTE_OWNER#*:} -m ${mode} '${REMOTE_STAGE}/${dst}' '${DEPLOY_REMOTE_DIR}/${dst}'"
    done
  fi
}

run_deploy() {
  if on_box; then
    sudo -u "${DEPLOY_SSH_USER}" "${DEPLOY_REMOTE_DIR}/deploy.sh"
  else
    ssh "${DEPLOY_SSH_USER}@${DEPLOY_HOST}"
  fi
}

# Publish the deploy outcome to GitHub Deployments. Best-effort: a missing or
# unauthenticated gh, or a publish error, warns but never fails the deploy
# (the rollout already happened). Carries digest/SHA/outcome only — no secrets.
publish_deployment() {
  local state_json="$1"
  command -v gh >/dev/null 2>&1 || { echo "NOTE: gh not found; skipping GitHub Deployment publish."; return 0; }
  gh auth status >/dev/null 2>&1 || { echo "NOTE: gh not authenticated; skipping GitHub Deployment publish."; return 0; }
  [ -n "${state_json}" ] || { echo "NOTE: no DEPLOY_STATE_JSON marker captured; skipping publish."; return 0; }

  local revision outcome digest repo state
  revision="$(printf '%s' "${state_json}" | sed -n 's/.*"revision":"\([^"]*\)".*/\1/p')"
  outcome="$(printf '%s' "${state_json}" | sed -n 's/.*"outcome":"\([^"]*\)".*/\1/p')"
  digest="$(printf '%s' "${state_json}" | sed -n 's/.*"active_digest":"\([^"]*\)".*/\1/p')"
  [ -n "${revision}" ] || { echo "NOTE: deploy state carried no revision SHA; skipping publish."; return 0; }
  # Derive identity from the checkout's origin remote (GC-P026): git ignores
  # GH_REPO, so this cannot be redirected at the wrong repo the way
  # `gh repo view` (which honors GH_REPO) can. Shared resolver keeps the
  # supported remote-URL forms consistent across all shell entry points.
  repo="$(resolve_repo_slug "${REPO_ROOT}")"
  [ -n "${repo}" ] || { echo "NOTE: could not resolve GitHub repo from origin remote; skipping publish."; return 0; }
  case "${outcome}" in
    deployed) state="success" ;;
    rolled_back|failed) state="failure" ;;
    *) state="error" ;;
  esac

  local dep_id
  dep_id="$(printf '{"ref":"%s","environment":"%s","auto_merge":false,"required_contexts":[],"production_environment":true,"description":"operator deploy via make deploy"}' \
    "${revision}" "${DEPLOY_ENVIRONMENT}" \
    | gh api -X POST "repos/${repo}/deployments" --input - --jq '.id' 2>/dev/null || true)"
  if [ -z "${dep_id}" ]; then
    echo "NOTE: GitHub Deployment create did not return an id (ref ${revision} may be unknown to GitHub); skipping status."
    return 0
  fi
  printf '{"state":"%s","environment":"%s","description":"image %s (%s)"}' \
    "${state}" "${DEPLOY_ENVIRONMENT}" "${digest}" "${outcome}" \
    | gh api -X POST "repos/${repo}/deployments/${dep_id}/statuses" --input - >/dev/null 2>&1 \
    && echo "Published GitHub Deployment ${dep_id} (${state}) for ${revision}." \
    || echo "NOTE: GitHub Deployment status publish failed (non-fatal)."
}

tmp_out="$(mktemp)"
cleanup() {
  rm -f "${tmp_out}"
  if [ -n "${REMOTE_STAGE}" ]; then
    ssh "${DEPLOY_HOST}" "rm -rf -- '${REMOTE_STAGE}'" 2>/dev/null || true
  fi
}
trap cleanup EXIT

sync_artifacts

set +e
run_deploy 2>&1 | tee "${tmp_out}"
rc=${PIPESTATUS[0]}
set -e

state_json="$(grep -m1 '^DEPLOY_STATE_JSON=' "${tmp_out}" | sed 's/^DEPLOY_STATE_JSON=//' || true)"
publish_deployment "${state_json}"

exit "${rc}"

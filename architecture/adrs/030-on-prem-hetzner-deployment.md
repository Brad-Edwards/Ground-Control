# ADR-030: On-prem Hetzner Deployment

## Status

Accepted

## Date

2026-05-03

## Context

[ADR-018](./018-aws-ec2-deployment.md) put Ground Control on a `t3a.small` EC2 instance in the `catalyst-dev` AWS account, behind Tailscale, with EBS for data and S3 for backups. That decision served its purpose - it got the workload off the developer's laptop and onto durable infrastructure - but several pressures accumulated:

- **Capacity ceiling.** `t3a.small` is 2 vCPU / 1.9 GB RAM. The JVM was capped at `-Xmx512m`, leaving no room for Lucene/AGE workload growth or the embedding-pipeline experiments that ADR-033 anticipates. Vertical scale on EC2 means money and reboot.
- **Single-account AWS dependency.** The deployment pulled in EC2, EBS, S3, DLM, IAM (OIDC role + policies), VPC defaults, plus the cron-driven backup script writing to S3. Every move added more AWS surface and more "now we also need…."
- **No ergonomic fit with the rest of the fleet.** `aurora` (Proxmox VE 9, fabricator runner host) and the Hetzner box (`red-dragon`, 8c/16t / 128 GB / 2× 1 TB NVMe) are both already in the operator's tailnet. Running one workload in AWS while every other workload is on-prem fragments the operational model. ADR-018's "single-tenant Tailnet-only" intent fit better with the rest of the on-prem fleet than with EC2.
- **Cost trajectory.** EC2+EBS+S3 was ~$17/mo on-demand. Marginal in absolute terms, but it scaled with disk and snapshot retention, and it was AWS spend the rest of the workload didn't have.

The Hetzner dedicated server was already provisioned for other purposes and joined to the same tailnet. Migrating Ground Control to it costs nothing additional and unwinds the AWS dependencies entirely.

## Decision

Run Ground Control on `red-dragon` (Hetzner dedicated, AMD Ryzen 7 3700X / 128 GB ECC / 2× 1 TB NVMe), reachable only via the operator's Tailscale tailnet. Same Docker Compose stack as ADR-018; image pulled from GHCR; no AWS account involvement.

### Architecture

- **Compute** - Single host (`red-dragon`), Ubuntu, Docker Engine 29.x, Docker Compose v5.x. PostgreSQL+AGE container + Spring Boot backend container managed by `/opt/gc/docker-compose.yml`.
- **Access** - Tailscale-only. `red-dragon`'s sshd binds to the tailnet IP (`100.98.28.66:22`) only; no public SSH. Application reachable on tailnet at `http://red-dragon:8000`.
- **Storage** - `/data/postgres` bind-mounted into the database container (Postgres data); `/data/backups` for `pg_dump` artifacts. Both on the host's main NVMe array.
- **Image registry** - GHCR (`ghcr.io/autarchy-ai/ground-control`). The CI workflow's previous dual-push to GHCR + ECR is replaced by GHCR-only.
- **Deploy path** - push to `main` → CI (`.github/workflows/ci.yml`) → `deploy` job runs on a fabricator-managed runner that joined the tailnet at first boot (per [`KeplerOps/fabricator` PR #14](https://github.com/KeplerOps/fabricator/pull/14)) → SSH to `gc-deploy@red-dragon` → forced command `/opt/gc/deploy.sh` does `docker compose pull && docker compose up -d` and verifies `/actuator/health`. No public ingress is required at any point.
- **Backups** - `pg_dump -Fc` cron 3×/day to `/data/backups/`, retention 30 days local. Off-box copy via rsync over the tailnet to `aurora` (or another tailnet target - see [ADR-025](./025-backup-policy.md) amendment for current mechanism). EBS DLM snapshots and the S3 bucket from ADR-018 are removed.

### CI runner asymmetry

CI's `policy`, `build`, `test`, `integration`, `sonar`, `verify`, `docker`, and `smoke` jobs run on **github-hosted runners** (`ubuntu-latest`). They have no tailnet dependency and don't justify the operational cost of keeping the fabricator self-hosted pool healthy at all times.

Two jobs are the **deliberate exceptions** and stay on the fabricator-managed self-hosted runner pool because they need to reach the tailnet:

- `deploy` - SSHes to `gc-deploy@red-dragon` (see "Runner → red-dragon network path" below).
- `policy-live` - when `vars.GC_BASE_URL` is set, it talks to the live Ground Control instance, which is the tailnet-only `red-dragon` deployment. The var is currently unset so the job is skipped, but the runner choice has to match the intended target rather than the empty-default behavior, otherwise enabling the var silently breaks the live policy gate.

If/when this repo adds the `tailscale/github-action` and a Tailscale OAuth secret, both exception jobs can move to `ubuntu-latest` too and the asymmetry disappears.

### Runner → red-dragon network path

GitHub Actions runs the `deploy` job on a fabricator-provisioned ephemeral VM. Fabricator's cloud-init now installs Tailscale and brings each VM up on the tailnet at first boot (`tailscale up --authkey=… --hostname=fab-<job> --ephemeral --advertise-tags=tag:fabricator-runner --ssh=false`). Devices auto-deauth at shutdown.

The tailnet ACL contains:

```hujson
{
    "src": ["tag:fabricator-runner"],
    "dst": ["tag:gc-host"],
    "ip":  ["tcp:22"],
}
```

`tag:gc-host` is on `red-dragon`. The tag/ACL pair scopes the runner's tailnet reach to exactly the deploy target, on exactly TCP/22.

### SSH authorization model

`red-dragon` carries an unprivileged `gc-deploy` user, member of the `docker` group. Its `~/.ssh/authorized_keys` is a single line:

```
command="/opt/gc/deploy.sh",restrict <ed25519-pubkey>
```

The forced command + `restrict` (which disables port-forwarding, X11, agent-forwarding, PTY, user-rc) means the deploy key cannot do anything except invoke the deploy script - no shell access, no lateral pivot. The corresponding private key lives in two places only: the operator's local file (created during the migration) and the `RED_DRAGON_DEPLOY_KEY` GitHub repo secret on `KeplerOps/Ground-Control`. `RED_DRAGON_KNOWN_HOSTS` carries the host-key fingerprints so the runner verifies it's talking to the real `red-dragon`.

### What ADR-018 retains

- Single-tenant, Tailscale-only access model.
- Apache AGE-on-Postgres (ADR-005) - same image (`apache/age:release_PG16_1.6.0`).
- ADR-026 access-control model (Bearer-token + IP allowlist) is unchanged.
- Backup policy *intent* (GC-P021): three dump cadence, off-box durability, retention floor. The mechanism changes (rsync-to-aurora replaces S3); the contract does not.

### What ADR-018 sheds

- AWS account, EC2 / EBS / S3 / DLM resources.
- AWS-OIDC GitHub Actions role.
- ECR push from the `docker` job. GHCR remains.
- Terraform module under `deploy/terraform/`. Deleted in this PR after `terraform destroy` ran successfully and AWS resources were torn down. Git history preserves the prior layout for anyone needing to reconstruct it.
- SSM `SendCommand` deploy path. Replaced by SSH over tailnet.
- Cron-watchdog restart-on-health-failure. Docker Compose's own `restart: unless-stopped` covers the container-crash case; if backend health degrades without the container exiting, the deploy job's health check on the next deploy will catch it and the operator can intervene. A revived watchdog can land in a follow-up if the situation warrants.

### Amendment 2026-06-23: CI/CD hardening for issue #855

The red-dragon topology remains single-host Docker Compose for this work. Issue
#855 should tighten the existing deploy path instead of introducing Watchtower,
k3s/Argo/Flux, or a managed-platform migration. Those options stay available
only if the workload or operator model outgrows the current host.

The hardened deploy contract is:

- `deploy/docker/deploy.sh`, `deploy/docker/docker-compose.prod.yml`, and the
  committed `.env` template/example are the repo source of truth. `/opt/gc/*`
  files are runtime mirrors; `/opt/gc/.env` is the only host-local secret
  boundary and must stay mode `0600`.
- CI must fail before rollout when the red-dragon mirrors drift from the repo
  artifacts, and repo-native invariants must live in `make policy` /
  `bin/policy` or a tested helper invoked by that gate. A workflow-only check is
  not enough for constraints that can be evaluated from the repo.
- Each deploy must resolve an immutable image identity before restart and publish
  the rolled-out digest plus source commit SHA to a queryable surface that does
  not require SSHing to red-dragon. GitHub Deployments or a durable PR/issue
  record are acceptable workflow records; this is not backend domain state.
- Durable host config must not be used as a silent release pointer. A per-deploy
  digest is acceptable as the candidate and rollback identity, but CI must not
  depend on a stale digest pinned in `/opt/gc/.env` advancing by itself.
- The deploy must validate `/opt/gc/.env` against the committed template and
  Spring configuration binding constraints before restarting the backend. ADR-026
  indexed credential and IP-allowlist slots must keep the inherit-only Compose
  form documented in `deploy/docker/docker-compose.prod.yml` so unset optional
  slots are not injected as blank values.
- Failed rollout must either leave the old backend serving or automatically
  restore the previous image/container after the candidate fails readiness. It
  must never exit after leaving a newly unhealthy backend as the steady state.
- Health checks stay inside the backend container unless the host check targets
  the tailnet bind address from `GC_BIND_IP`; `localhost:8000` on red-dragon is
  not a valid host-side readiness check after the ADR-026 tailnet-only bind.
- Deploy secrets remain in GitHub protected-main secrets or host-local files.
  Tokens, database passwords, session cookies, and registry credentials must not
  be printed, embedded in argv, written to workflow summaries, or copied into
  deploy status records.
- Database migration safety uses the existing Flyway path and migration
  immutability policy. A deploy can inspect and report pending migrations or take
  a pre-rollout backup, but it must not introduce a second migration engine or
  edit already-applied migrations.

The extension point for future targets is deploy-target configuration: host,
SSH identity, remote directory, compose project, image package, and status
publisher. Do not push target-specific branching into backend code or duplicate
the deploy contract across unrelated scripts.

### Implementation (issue #855, GC-P023)

The hardened contract above shipped as:

- One canonical on-host script `deploy/docker/deploy.sh` (forced-command
  target). The dead divergent duplicate `deploy/scripts/deploy.sh` (a broken
  host-side `curl` health check) was removed; `scripts/deploy.sh` is the single
  operator wrapper, which syncs the canonical artifacts into `/opt/gc/` before
  every rollout (no manual scp) and publishes the outcome to GitHub Deployments.
- `deploy/docker/env.schema` as the single env contract, consumed by both the
  deploy-time validator `deploy/docker/validate-env.sh` and the `make policy`
  gate `run_deploy_artifact_consistency` in `tools/policy/checks.py`.
- `deploy/docker/MANIFEST.sha256` (regenerated by `make deploy-manifest`), which
  `deploy.sh` checksum-verifies the `/opt/gc` mirrors against, refusing to roll
  out drifted artifacts.
- Rollback in `deploy.sh`: a candidate that fails its health window is replaced
  by the previously running image, never left unhealthy as the steady state.
- `/opt/gc/deploy-state.json` + a GitHub Deployment carrying the rolled-out
  digest and source commit SHA (no secrets), queryable via `make deploy-status`.
- The two contradictory `deploy/docker/.env.*` templates were consolidated to a
  single canonical `.env.example` (floating-tag policy); `.env.template` was
  removed. A deliberate digest pin for a controlled cutover/rollback requires
  `GC_ALLOW_IMAGE_PIN=1`, mirroring the `GC_ALLOW_SAME_REVISION` override.

Anchored on requirement GC-P023, sibling to GC-P022. GC-GRC-003 (deterministic
IaC/pipeline screening) is the future automated-classification home and is out
of scope here.

## Consequences

### Positive

- Fleet consolidation. One operational mental model (Tailscale-only on-prem hosts) instead of "everything on aurora/red-dragon except this one EC2 thing."
- Capacity headroom. 128 GB RAM ceiling vs 2 GB. The JVM heap cap can lift; embedding workloads can run without paging.
- AWS dependency removed. No IAM management, no S3 lifecycle, no DLM, no OIDC role to keep in sync.
- Slightly cheaper. ~$17/mo to $0 marginal (red-dragon is paid for unrelated reasons).
- Build pipeline simpler. GHCR-only image push; no `aws-actions/configure-aws-credentials` step.

### Negative

- Single-host failure domain is now physical (Hetzner DC) rather than AWS-AZ. Recovery requires rebuilding on a different host from the off-box backup.
- Off-box backups depend on `aurora` (or whatever tailnet target is chosen) being reachable; an aurora outage doesn't lose data but does delay the off-box copy.
- No managed snapshot service. EBS DLM was zero-effort; rsync-to-aurora needs the operator to verify it's running and that retention is honored. ADR-025 amendment captures the new defaults; the GC-P021 pre-commit assertion in the repo enforces the policy floor on input.

### Risks

- The Tailscale auth key for fabricator runners is reusable + ephemeral; if leaked it could be used to register devices into the tailnet under `tag:fabricator-runner`. Mitigation: ACL constrains that tag to `tag:gc-host:tcp:22`, and the deploy key only invokes the forced command. The blast radius of a leaked key is "an attacker can SSH-attempt with no shell." Rotate the key if leaked; ephemeral devices auto-deauth.
- The deploy SSH key is repo-scoped to `KeplerOps/Ground-Control` secrets. A compromised PR with workflow modifications could exfil it (standard GH-Actions concern). Mitigation: deploy job only runs on `push` to `main`, not on PRs, and required reviews on `main` pushes are the gate.
- Tailscale outage blocks the deploy path (CI runner can't reach red-dragon). Manual deploy from the operator's machine over tailnet is the fallback (`ssh red-dragon "cd /opt/gc && docker compose pull && docker compose up -d"`).

## Non-Goals

- Multi-region / DR. Single-host single-region is intentional for a single-user dev system.
- Public ingress. Tailnet-only is the explicit access model.
- Move off Tailscale. The decision rides on the existing tailnet; replacing it is a separate ADR.
- Zero-downtime deploys. The deploy briefly bounces the backend container; under one minute typical, acceptable for this workload.

## Migration

Performed 2026-05-03:

1. Stood up the compose stack on red-dragon at `/opt/gc/` from the existing `deploy/docker/docker-compose.prod.yml`, with `GC_IMAGE` flipped from ECR to GHCR.
2. `pg_dump -Fc` on gc-dev → scp to red-dragon → `pg_restore`. ~100 MB; row counts verified equal on both ends (1907 requirements, 14 projects, 3180 traceability links).
3. Fleet sweep: every `.mcp.json` referencing `http://gc-dev:8000` updated to `http://red-dragon:8000`.
4. fabricator runner-side Tailscale integration shipped (KeplerOps/fabricator PR #14) and deployed to aurora.
5. AWS resources destroyed (`terraform destroy` in `environments/dev/` and direct CLI cleanup of bootstrap): EC2 instance + EBS data volume + ECR repo + S3 backup bucket + DLM snapshot policy + IAM roles/policies + SSM parameters + S3 terraform-state bucket + DynamoDB lock table + GitHub Actions OIDC provider. catalyst-dev (516608939870) now has no Ground-Control resources.
6. This PR: replace the AWS deploy job with the SSH path; rewrite `docs/deployment/DEPLOYMENT.md`'s production section; delete `deploy/terraform/` (no longer needed after destroy).

Pending after this PR merges:

7. Untag `gc-dev` in Tailscale admin.

## Related Requirements

- GC-P021 Backup Policy

## Related ADRs

- ADR-018 - superseded by this ADR.
- ADR-005 - Apache AGE on Postgres (preserved, same image).
- ADR-025 - Backup Policy (mechanism amendment).
- ADR-026 - REST API Access Control (preserved, unchanged).

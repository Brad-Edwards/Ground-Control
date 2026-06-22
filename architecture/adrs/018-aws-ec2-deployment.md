# ADR-018: AWS EC2 Deployment

## Status

**Superseded by [ADR-030](./030-on-prem-hetzner-deployment.md).** Ground Control no longer runs on AWS EC2; the workload moved to an on-prem Hetzner dedicated server (`red-dragon`) on the user's Tailscale tailnet. The decommission removed the EC2 instance, the EBS data volume, the S3 backup bucket, the DLM snapshot policy, and the AWS-OIDC GitHub Actions role. ADR-018 is preserved for historical context (memory budget, backup policy intent, deploy contract); the AWS-specific implementation details no longer reflect production.

## Original status

Accepted (superseded 2026-05-03).

## Date

2026-03-18

## Context

Ground Control runs on the developer's local machine via Docker Compose. This creates two problems:

1. **Dev box load** - running the full stack (PostgreSQL+AGE, Spring Boot, frontend dev server) taxes the development machine.
2. **No off-machine durability** - data lives in a Docker volume on a single machine. A disk failure, machine rebuild, or accidental `docker compose down -v` destroys the source of truth.

ADR-015 (withdrawn) attempted to solve durability with AWS RDS, but RDS does not support the Apache AGE extension required by ADR-005. Self-managed PostgreSQL is the only viable cloud path.

## Decision

Deploy Ground Control on a single EC2 instance (`t3a.small`, 2 vCPU / 2 GB RAM) in the `catalyst-dev` AWS account (516608939870, us-east-2), running the same Docker Compose stack. All access via Tailscale - zero public ingress.

### Architecture

- **Compute**: Single `t3a.small` instance running Docker Compose (PostgreSQL+AGE + Spring Boot backend)
- **Access**: Tailscale mesh network only. Security group has zero ingress rules. SSH via Tailscale SSH (no SSH keys). Application at `http://gc-dev:8000`.
- **Storage**: Root volume (8 GB gp3) for OS/Docker. Separate EBS data volume (20 GB gp3, encrypted) mounted at `/data` for PostgreSQL data and local backup staging.
- **Backup Layer 1 - EBS Snapshots**: AWS Data Lifecycle Manager creates daily snapshots of the data volume with 7-day retention (~$1/mo).
- **Backup Layer 2 - pg_dump to S3**: Cron at 03:00 UTC runs `pg_dump -Fc` → S3 bucket with 30-day lifecycle expiration.
- **Monitoring**: Docker health checks (`pg_isready`, `/actuator/health`). Cron watchdog every 5 minutes restarts backend on health failure. No CloudWatch (overkill for single-user).
- **Deployment**: Automated. Push to `main` → CI builds/tests/pushes GHCR image → SSM `SendCommand` triggers `deploy.sh` on EC2. Manual deploy also available via `ssh gc-dev /opt/gc/deploy.sh`.

### Memory Budget (2 GB)

| Process | Estimate |
|---------|----------|
| JVM (Spring Boot) | ~700 MB |
| PostgreSQL + AGE | ~350 MB |
| OS + Docker | ~300 MB |
| Headroom | ~650 MB |

### Cost: ~$17/mo on-demand

| Component | Monthly |
|-----------|---------|
| EC2 t3a.small | $13.50 |
| EBS root 8 GB gp3 | $0.64 |
| EBS data 20 GB gp3 | $1.60 |
| EBS snapshots (7-day) | ~$1.00 |
| S3 backups (~500 MB) | ~$0.12 |
| **Total** | **~$17** |

### Why not Lightsail ($11/mo)

No IAM instance profiles (credential management headache), separate networking from VPC, less mature Terraform support, no EBS snapshot lifecycle. The ~$6/mo premium buys operational simplicity with native AWS tooling.

### Why not ECS/Fargate

Overkill for a single-user tool. Fargate cannot run docker-in-docker for the AGE container. ECS adds orchestration complexity without benefit at this scale.

## Consequences

### Positive

- Data durability via two independent backup layers (EBS snapshots + S3 dumps)
- Dev machine freed from running the full stack
- AGE extension available in all environments (no feature gap)
- Zero public exposure (Tailscale only)
- Same Docker Compose model as local development
- Infrastructure as code via Terraform

### Negative

- ~$17/mo ongoing cost
- Self-managed PostgreSQL upgrades (no managed service automation)
- Single point of failure (acceptable for single-user dev tool)

### When to revisit

- If the application needs multi-user access or HA
- If AGE becomes available on RDS or Aurora
- If costs need further reduction (Reserved Instance drops to ~$12/mo)

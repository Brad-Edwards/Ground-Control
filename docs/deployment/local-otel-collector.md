# Local OpenTelemetry collector for Claude Code telemetry

Claude Code is wired (at the user level, via `~/.claude/settings.json`) to
export tool-detail logs over OTLP to `http://localhost:4317`:

```jsonc
{
  "env": {
    "OTEL_LOGS_EXPORTER": "otlp",
    "OTEL_LOG_TOOL_DETAILS": "1",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:4317"
  }
}
```

If nothing is listening on that port, Claude Code silently drops every
tool call, rejection reason, and MCP event - the observability the
setting was meant to give you is gone. When this was first investigated,
the collector had been dead for 11 days without anyone noticing, because
the failure mode is exactly "no telemetry" rather than an error.

## Why a native daemon, not `docker compose`

The previous setup ran the collector via `docker compose` in
`~/.claude/telemetry/`. On WSL + Docker Desktop, that setup suffers from
the bind-mount translation layer:

- The `config.yaml` mount goes through
  `/run/desktop/mnt/host/wsl/docker-desktop-bind-mounts/...`, whose
  paths go stale on Docker Desktop restart or WSL path drift.
- Stale bind-mount paths surface as `OCI runtime create failed ...
  error mounting ... not a directory`, the container exits with code
  127, and Docker does not retry.
- Claude Code does not surface the missing endpoint; exports vanish.

`otelcol-contrib` is a single static Go binary (~60 MB) with zero
runtime dependencies, so running it as a `systemd --user` unit is
simpler, faster to start, and avoids the entire bind-mount failure
mode. The native setup is also lighter - a native daemon uses tens of
MB resident versus Docker Desktop's hundreds.

## Installing / re-installing

```sh
scripts/setup-local-otel-collector.sh
```

The script is idempotent. Re-running:

- Verifies / downloads / upgrades `~/.local/bin/otelcol-contrib` to
  the pinned version (`OTEL_VERSION`, default `0.149.0`).
- Writes `~/.claude/telemetry/otel-collector-config.yaml` (OTLP
  gRPC on 127.0.0.1:4317, OTLP HTTP on 127.0.0.1:4318, file exporter
  to `~/.claude/telemetry/data/claude-code.jsonl` with size/age/count
  rotation).
- Writes `~/.config/systemd/user/claude-otel-collector.service`.
- `systemctl --user daemon-reload` → `enable --now` (first install)
  or `restart` (subsequent runs, so config changes take effect).
- Verifies `127.0.0.1:4317` is listening before exiting.

Environment variables:

- `OTEL_VERSION` - pin the collector version. Bump this and re-run
  the script to upgrade.
- `SKIP_START=1` - install files but do not enable / start the unit.

## Verifying

Collector state:

```sh
systemctl --user is-active claude-otel-collector.service
ss -tln | grep -E '4317|4318'
```

End-to-end over OTLP/HTTP (no gRPC client needed):

```sh
NOW_NS=$(date +%s%N)
curl -sSf -X POST http://127.0.0.1:4318/v1/logs \
  -H 'Content-Type: application/json' \
  -d "{
    \"resourceLogs\": [{
      \"resource\": {\"attributes\": [{\"key\": \"service.name\",
        \"value\": {\"stringValue\": \"smoke-test\"}}]},
      \"scopeLogs\": [{
        \"scope\": {\"name\": \"manual\"},
        \"logRecords\": [{
          \"timeUnixNano\": \"${NOW_NS}\",
          \"severityText\": \"INFO\",
          \"body\": {\"stringValue\": \"hello\"}
        }]
      }]
    }]
  }"
tail -c 1000 ~/.claude/telemetry/data/claude-code.jsonl
```

Claude Code tool telemetry:

```sh
tail -F ~/.claude/telemetry/data/claude-code.jsonl
```

Then run any Claude Code tool and watch new lines appear.

## Important: long-running `claude` sessions

OpenTelemetry SDKs attempt to connect at process start and retry with
backoff when the endpoint is down. After enough retries they can mark
the exporter as unavailable and stop attempting for the remainder of
the process lifetime.

**If the collector was down when your current `claude` session
started, that session will not export telemetry even after the
collector comes back up.** The fix is to restart `claude`. Fresh
sessions started after the collector is live will export normally.

## Rolling back

The old `docker-compose.yml` at `~/.claude/telemetry/docker-compose.yml`
is left in place. To stop the native daemon and revert:

```sh
systemctl --user disable --now claude-otel-collector.service
rm ~/.config/systemd/user/claude-otel-collector.service
systemctl --user daemon-reload
# then, if desired:
docker compose -f ~/.claude/telemetry/docker-compose.yml up -d
```

Be aware that the docker-compose route has the bind-mount failure
mode described above and may need its config path refreshed on every
Docker Desktop restart.

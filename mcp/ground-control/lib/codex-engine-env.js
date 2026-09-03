// Minimal environment for the `codex` child (issue #1518). The spawn sites
// previously forwarded `{...process.env}` in full, exposing every unrelated
// GitHub, Sonar, cloud, and Claude credential on the host to a sandboxed
// process whose reads are not confined by `--sandbox`. Allowlist only what
// `codex exec` needs to run and authenticate.
const CODEX_ENV_ALLOWLIST = ["HOME", "PATH", "OPENAI_API_KEY", "CODEX_HOME"];

export function codexEngineEnv(baseEnv = process.env) {
  const env = {};
  for (const key of CODEX_ENV_ALLOWLIST) {
    if (baseEnv[key] !== undefined) env[key] = baseEnv[key];
  }
  env.NO_COLOR = "1";
  return env;
}

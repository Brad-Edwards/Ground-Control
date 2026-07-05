const listFromEnv = (name) => {
  const raw = process.env[name];
  if (!raw) {
    throw new Error(`${name} is required for scoped mutation runs`);
  }
  const values = raw
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  if (values.length === 0) {
    throw new Error(`${name} must contain at least one path`);
  }
  return values;
};

const integerFromEnv = (name, fallback) => {
  const raw = process.env[name] ?? String(fallback);
  const value = Number.parseInt(raw, 10);
  if (!Number.isInteger(value) || value < 0 || value > 100) {
    throw new Error(`${name} must be an integer in [0, 100]`);
  }
  return value;
};

const threshold = integerFromEnv("STRYKER_THRESHOLD", 0);

export default {
  testRunner: "vitest",
  mutate: listFromEnv("STRYKER_MUTATE"),
  testFiles: listFromEnv("STRYKER_TEST_FILES"),
  thresholds: {
    high: threshold,
    low: threshold,
    break: threshold,
  },
  reporters: ["clear-text", "html", "json"],
  jsonReporter: {
    fileName: process.env.STRYKER_JSON_REPORT ?? "build/reports/stryker/mutation.json",
  },
  htmlReporter: {
    fileName: process.env.STRYKER_HTML_REPORT ?? "build/reports/stryker/html/index.html",
  },
  vitest: {
    configFile: "vitest.config.ts",
    related: false,
  },
  coverageAnalysis: "off",
  tempDirName: "build/stryker-tmp",
  concurrency: integerFromEnv("STRYKER_CONCURRENCY", 2),
};

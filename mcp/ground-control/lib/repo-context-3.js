// Extracted from repo-context.js to keep it under the 500-line limit (docs/CODING_STANDARDS.md, Sonar S104).
// review_disposition config normalizers; re-exported through repo-context.js so the import surface is unchanged.

export const REVIEW_DISPOSITION_MODES = ["shadow", "authoritative"];
export const REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN = 0;
export const REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX = 5;
function emptyReviewDispositionConfig() {
  return { enabled: false, mode: "shadow", max_auto_overrides: 1, judge: { enabled: false, model: null } };
}
function normalizeReviewDispositionEnabled(rawBlock, value, errors) {
  if (rawBlock.enabled == null) return;
  if (typeof rawBlock.enabled !== "boolean") {
    errors.push("workflow.review_disposition.enabled must be a boolean when set");
    return;
  }
  value.enabled = rawBlock.enabled;
}
function normalizeReviewDispositionMode(rawBlock, value, errors) {
  if (rawBlock.mode == null) return;
  if (REVIEW_DISPOSITION_MODES.includes(rawBlock.mode)) {
    value.mode = rawBlock.mode;
    return;
  }
  errors.push(`workflow.review_disposition.mode must be one of: ${REVIEW_DISPOSITION_MODES.join(", ")}`);
}
function normalizeReviewDispositionMaxAutoOverrides(rawBlock, value, errors) {
  if (rawBlock.max_auto_overrides == null) return;
  const v = rawBlock.max_auto_overrides;
  if (typeof v !== "number" || !Number.isInteger(v)) {
    errors.push("workflow.review_disposition.max_auto_overrides must be an integer");
    return;
  }
  if (
    v < REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN ||
    v > REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX
  ) {
    errors.push(
      `workflow.review_disposition.max_auto_overrides must be between ${REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MIN} and ${REVIEW_DISPOSITION_MAX_AUTO_OVERRIDES_MAX} inclusive`,
    );
    return;
  }
  value.max_auto_overrides = v;
}
function normalizeReviewDispositionJudgeEnabled(judge, value, errors) {
  if (judge.enabled == null) return;
  if (typeof judge.enabled !== "boolean") {
    errors.push("workflow.review_disposition.judge.enabled must be a boolean when set");
    return;
  }
  value.judge.enabled = judge.enabled;
}
function normalizeReviewDispositionJudgeModel(judge, value, errors) {
  if (judge.model == null) return;
  if (typeof judge.model !== "string" || judge.model.trim() === "") {
    errors.push("workflow.review_disposition.judge.model must be a non-empty string when set");
    return;
  }
  value.judge.model = judge.model.trim();
}
function normalizeReviewDispositionJudge(rawBlock, value, errors) {
  if (rawBlock.judge == null) return;
  if (typeof rawBlock.judge !== "object" || Array.isArray(rawBlock.judge)) {
    errors.push("workflow.review_disposition.judge must be a mapping when set");
    return;
  }
  const judgeAllowed = new Set(["enabled", "model"]);
  for (const key of Object.keys(rawBlock.judge)) {
    if (!judgeAllowed.has(key)) {
      errors.push(`workflow.review_disposition.judge has unknown key '${key}'`);
    }
  }
  normalizeReviewDispositionJudgeEnabled(rawBlock.judge, value, errors);
  normalizeReviewDispositionJudgeModel(rawBlock.judge, value, errors);
}
export function normalizeReviewDispositionConfig(rawBlock) {
  if (rawBlock == null) {
    return { ok: true, value: emptyReviewDispositionConfig() };
  }
  if (typeof rawBlock !== "object" || Array.isArray(rawBlock)) {
    return { ok: false, errors: ["workflow.review_disposition must be a mapping when set"] };
  }
  const allowed = new Set(["enabled", "mode", "max_auto_overrides", "judge"]);
  const errors = [];
  for (const key of Object.keys(rawBlock)) {
    if (!allowed.has(key)) {
      errors.push(`workflow.review_disposition has unknown key '${key}'`);
    }
  }
  const value = emptyReviewDispositionConfig();
  normalizeReviewDispositionEnabled(rawBlock, value, errors);
  normalizeReviewDispositionMode(rawBlock, value, errors);
  normalizeReviewDispositionMaxAutoOverrides(rawBlock, value, errors);
  normalizeReviewDispositionJudge(rawBlock, value, errors);
  if (errors.length) return { ok: false, errors };
  return { ok: true, value };
}

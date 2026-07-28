// Review-station emission (issue #1355).
//
// Its own module because the split placed this beside unrelated neighbours: the map lived in
// knowledge-capture and the emitter in the PR-body renderer, neither of which has anything to do
// with recording a review as a station attempt.

import { execFile } from "./runtime-primitives.js";
import { getRepoGroundControlContext } from "./repo-vocabulary-2.js";

export const REVIEW_STATION_BY_REVIEWER = Object.freeze({
  codex: "codex_review",
  "test-quality": "test_quality_review",
});

export async function _emitReviewStationAttempt({
  repoPath,
  issueNumber,
  reviewer,
  stationResult,
  findings,
  findingsDropped,
}) {
  const stationId = REVIEW_STATION_BY_REVIEWER[reviewer];
  if (!stationId) return;
  try {
    const context = await getRepoGroundControlContext(repoPath);
    if (context?.status !== "ok" || !context.project) return;
    const { stdout } = await execFile("git", ["-C", repoPath, "branch", "--show-current"]);
    const branch = stdout.trim();
    if (branch === "") return;

    const { createWorkflowRunLifecycleEmitter } = await import("./workflow-run-lifecycle.js");
    const emitter = createWorkflowRunLifecycleEmitter({
      project: context.project,
      repo: context.github_repo,
      issueNumber,
      branch,
      workflowType: "IMPLEMENT",
    });
    emitter.ensureRun();
    emitter.recordStationAttempt({ stationId, stationResult, findings, findingsDropped });
    await emitter.flush();
  } catch {
    // Measurement never becomes a reason a review fails.
  }
}

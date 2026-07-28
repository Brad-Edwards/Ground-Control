"""Unit tests for the CI timing measurement tool (issue #1461, ADR-091)."""

import unittest

from tools.ci.measure_ci_timings import (
    build_run_list_args,
    parse_args,
    percentile,
    render_markdown,
    summarize,
)


def job(name, started, completed, conclusion="success"):
    """Build a jobs-API record. Minutes past a fixed epoch keep cases readable."""
    return {
        "name": name,
        "conclusion": conclusion,
        "started_at": f"2026-07-28T10:{started:02d}:00Z",
        "completed_at": f"2026-07-28T10:{completed:02d}:00Z",
    }


def run(run_id, jobs, conclusion="success", branch="feature"):
    return {
        "databaseId": run_id,
        "conclusion": conclusion,
        "headBranch": branch,
        "jobs": jobs,
    }


class PercentileTest(unittest.TestCase):
    def test_interpolates_between_neighbours(self):
        self.assertEqual(percentile([0, 10], 0.5), 5)

    def test_p95_interpolates_rather_than_snapping_to_the_top_value(self):
        self.assertEqual(percentile(list(range(20)), 0.95), 19 * 0.95)

    def test_single_value_sample_returns_that_value(self):
        self.assertEqual(percentile([7], 0.95), 7)

    def test_empty_sample_has_no_percentile(self):
        self.assertIsNone(percentile([], 0.5))

    def test_unsorted_input_does_not_change_the_result(self):
        self.assertEqual(percentile([10, 0, 5], 0.5), percentile([0, 5, 10], 0.5))


class SummarizeTest(unittest.TestCase):
    def test_wall_clock_spans_first_job_start_to_last_job_completion(self):
        summary = summarize([run(1, [job("a", 0, 5), job("b", 2, 12)])])

        self.assertEqual(summary["total_seconds"]["median"], 12 * 60)

    def test_skipped_jobs_do_not_extend_or_shorten_the_window(self):
        """A skipped job reports timestamps but did no work."""
        jobs = [
            job("a", 0, 5),
            job("skipped-lane", 0, 30, conclusion="skipped"),
        ]

        summary = summarize([run(1, jobs)])

        self.assertEqual(summary["total_seconds"]["median"], 5 * 60)

    def test_time_to_first_failure_is_the_earliest_failing_completion(self):
        jobs = [
            job("slow-fail", 0, 20, conclusion="failure"),
            job("fast-fail", 0, 3, conclusion="failure"),
            job("ok", 0, 6),
        ]

        summary = summarize([run(1, jobs, conclusion="failure")])

        self.assertEqual(summary["first_failure_seconds"]["median"], 3 * 60)

    def test_runs_without_a_failure_are_excluded_from_the_failure_sample(self):
        runs = [
            run(1, [job("a", 0, 4, conclusion="failure")], conclusion="failure"),
            run(2, [job("a", 0, 30)]),
        ]

        summary = summarize(runs)

        self.assertEqual(summary["first_failure_seconds"]["n"], 1)
        self.assertEqual(summary["first_failure_seconds"]["median"], 4 * 60)

    def test_per_job_stats_carry_duration_and_start_offset(self):
        """Start offset is what exposes a serialized graph."""
        summary = summarize([run(1, [job("policy", 0, 2), job("sonar", 11, 18)])])

        self.assertEqual(summary["jobs"]["sonar"]["median_seconds"], 7 * 60)
        self.assertEqual(summary["jobs"]["sonar"]["median_start_offset_seconds"], 11 * 60)
        self.assertEqual(summary["jobs"]["policy"]["median_start_offset_seconds"], 0)

    def test_jobs_missing_timestamps_are_ignored(self):
        incomplete = {
            "name": "queued",
            "conclusion": None,
            "started_at": None,
            "completed_at": None,
        }

        summary = summarize([run(1, [job("a", 0, 5), incomplete])])

        self.assertEqual(summary["total_seconds"]["median"], 5 * 60)
        self.assertNotIn("queued", summary["jobs"])

    def test_jobs_that_never_ran_are_excluded(self):
        """Cancelled jobs report completed_at fractionally before started_at.

        Left in, they add phantom rows to the per-job table with a negative
        median duration, which is how `docker` and `smoke` appeared in the
        pull_request sample despite never running on a PR.
        """
        never_ran = {
            "name": "docker",
            "conclusion": "cancelled",
            "started_at": "2026-07-28T10:02:05Z",
            "completed_at": "2026-07-28T10:02:04Z",
        }

        summary = summarize([run(1, [job("test", 0, 5), never_ran], conclusion="cancelled")])

        self.assertNotIn("docker", summary["jobs"])
        self.assertEqual(summary["total_seconds"]["median"], 5 * 60)

    def test_zero_length_jobs_are_excluded(self):
        summary = summarize([run(1, [job("test", 0, 5), job("instant", 2, 2)])])

        self.assertNotIn("instant", summary["jobs"])

    def test_zero_length_runs_are_excluded(self):
        """A cancelled run whose jobs never advanced is not a timing sample."""
        runs = [run(1, [job("a", 0, 0)], conclusion="cancelled"), run(2, [job("a", 0, 6)])]

        summary = summarize(runs)

        self.assertEqual(summary["total_seconds"]["n"], 1)
        self.assertEqual(summary["total_seconds"]["median"], 6 * 60)

    def test_runs_with_no_usable_jobs_are_excluded(self):
        summary = summarize([run(1, [])])

        self.assertEqual(summary["total_seconds"]["n"], 0)
        self.assertIsNone(summary["total_seconds"]["median"])

    def test_medians_aggregate_across_runs(self):
        runs = [
            run(1, [job("test", 0, 4)]),
            run(2, [job("test", 0, 6)]),
            run(3, [job("test", 0, 20)]),
        ]

        summary = summarize(runs)

        self.assertEqual(summary["jobs"]["test"]["n"], 3)
        self.assertEqual(summary["jobs"]["test"]["median_seconds"], 6 * 60)


class RunListArgsTest(unittest.TestCase):
    """A branch filter is what makes an after-measurement honest.

    Without it the sample mixes the branch under test with historical runs of
    the topology it replaced, so the aggregate understates the change.
    """

    def test_branch_filter_is_passed_through_when_requested(self):
        args = build_run_list_args("o/r", "ci.yml", 5, "pull_request", "my-branch")

        self.assertIn("--branch", args)
        self.assertEqual(args[args.index("--branch") + 1], "my-branch")

    def test_branch_filter_is_omitted_when_not_requested(self):
        self.assertNotIn(
            "--branch", build_run_list_args("o/r", "ci.yml", 5, "pull_request", None)
        )

    def test_scalar_options_reach_the_gh_invocation(self):
        args = build_run_list_args("owner/repo", "other.yml", 7, "push", None)

        self.assertEqual(args[args.index("--repo") + 1], "owner/repo")
        self.assertEqual(args[args.index("--workflow") + 1], "other.yml")
        self.assertEqual(args[args.index("--limit") + 1], "7")
        self.assertEqual(args[args.index("--event") + 1], "push")

    def test_branch_defaults_to_unset(self):
        self.assertIsNone(parse_args([]).branch)


class RenderMarkdownTest(unittest.TestCase):
    def test_report_states_the_headline_metrics_and_per_job_rows(self):
        summary = summarize([run(1, [job("policy", 0, 2), job("sonar", 0, 7)])])

        report = render_markdown(summary)

        self.assertIn("Whole-run wall clock", report)
        self.assertIn("Time to first failing check", report)
        self.assertIn("sonar", report)
        self.assertIn("7.0m", report)

    def test_empty_sample_renders_without_crashing(self):
        report = render_markdown(summarize([]))

        self.assertIn("Whole-run wall clock", report)
        self.assertIn("n/a", report)


if __name__ == "__main__":
    unittest.main()

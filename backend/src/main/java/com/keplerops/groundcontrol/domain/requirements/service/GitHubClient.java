package com.keplerops.groundcontrol.domain.requirements.service;

import java.util.List;

public interface GitHubClient {

    List<GitHubIssueData> fetchAllIssues(String owner, String repo);

    List<GitHubPullRequestData> fetchAllPullRequests(String owner, String repo);

    /**
     * Fetch a single pull request's current state (GC-O009 (b) merge-gate observation). Returns the
     * bounded, redacted {@link GitHubPullRequestData} — in particular {@code merged} and {@code state}
     * — for the authoritative GitHub merge fact the {@code /implement} workflow polls. Throws when the
     * PR does not exist or the coordinates are invalid.
     */
    GitHubPullRequestData fetchPullRequest(String owner, String repo, int number);

    GitHubIssueData createIssue(String repo, String title, String body, List<String> labels);
}

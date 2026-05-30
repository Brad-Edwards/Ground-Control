### Changed
- `gc_render_pr_body` now accepts an optional `additional_closes` array of GitHub issue numbers; the renderer emits one `Closes #<n>` line per entry under `## Related Issues` so multi-issue PRs auto-close every linked issue on merge without hand-appended body lines that vanish on re-render. (#1058 follow-up)

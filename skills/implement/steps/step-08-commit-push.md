---
stage_id: git_publish
step: "Step 8"
tier: low
---

# Step 8: Commit & Push

1. Craft a concise commit message in imperative mood (per coding standards). Example: "Add risk scoring engine for requirement prioritization"
2. NEVER include Co-Authored-By, "Generated with Claude Code", or any agent attribution in commit messages.
3. `git commit -m "<message>"`
4. `git push -u origin <branch>`

## Return contract

```json
{
  "status": "ok",
  "cached_for_next_step": {
    "commit_sha": "<sha>",
    "pushed_branch": "<branch name>"
  }
}
```

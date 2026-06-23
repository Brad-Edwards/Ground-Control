Exempt the `dev` to `main` release PR from the per-PR body contract in `make
policy`. That PR aggregates feature PRs that each already satisfied the contract
on the way into `dev`, so re-checking requirement UID, the Ground Control
checklist, and the documentation outcome on the aggregate failed every release
PR on policy. The check now detects the release PR by base/head and skips only
the body-content checks; all changed-file checks (changelog, migration, parity)
still run.

Repoint the production container image from `ghcr.io/brad-edwards/ground-control`
to `ghcr.io/autarchy-ai/ground-control` across CI, deploy scripts, and docs. After
the repository moved into the `autarchy-ai` org the CI `GITHUB_TOKEN` could no
longer push to the old user namespace (`permission_denied: The requested
installation does not exist`), breaking every `main`/`dev` image build. The image
now lives under the owning org so the workflow token's `packages: write` scope
applies.

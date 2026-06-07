# Python Gate Pack

Python gates execute through `.gc/gate-packs/python/gc-python-run` rather than bare global Python tools.

The launcher resolves the project environment in this order:

1. `uv run -- <tool>` when `uv` is available.
2. `poetry run -- <tool>` when `poetry` is available and `pyproject.toml` has `[tool.poetry]`.
3. `hatch run -- <tool>` when `hatch` is available and `pyproject.toml` has `[tool.hatch]`.
4. An existing project virtual environment from `$VIRTUAL_ENV`, `.venv/`, or `venv/`.
5. A generated `.venv` with the pack's pinned fallback tool set.

Each invocation writes one stderr line naming the selected runner. The fallback `.venv` path installs exact tool pins from the pack; it does not install global latest Python tools.

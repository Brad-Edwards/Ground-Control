"""git-merge-guard.py -- package barrel.

Implementation lives in this package, split under issue #1467 for the
500-LOC limit (docs/CODING_STANDARDS.md). Modules are packed from the
declaration dependency graph in topological order, so the package graph is
acyclic by construction. Every top-level name the module exposed before is
re-exported here -- including the underscore-prefixed helpers a star import
would drop -- so existing callers are unaffected.
"""

from .protected_branches import PROTECTED_BRANCHES, INTEGRATION_SOURCE_REF, GIT_QUERY_TIMEOUT_SECONDS, SHELL_OPERATORS, SHELL_OPERATOR_SET, SHELL_EXPANSION_CHARS, COMMAND_WRAPPERS, GIT_GLOBAL_OPTS_WITH_VALUE, GH_GLOBAL_OPTS_WITH_VALUE, PUSH_OPTS_WITH_VALUE, MERGE_ALLOWED_BOOL_OPTS, MERGE_ALLOWED_VALUE_OPTS, UNPARSEABLE_FALLBACK_DENY, deny, normalize_operators, split_segments, strip_wrappers, strip_global_opts, looks_protected, has_short_force_flag, git_query, current_branch, branch_merge_options_configured, merge_source, check_git_merge, check_gh, check_git_push  # noqa: F401
from .check_git import check_git, main  # noqa: F401

__all__ = [
    "PROTECTED_BRANCHES",
    "INTEGRATION_SOURCE_REF",
    "GIT_QUERY_TIMEOUT_SECONDS",
    "SHELL_OPERATORS",
    "SHELL_OPERATOR_SET",
    "SHELL_EXPANSION_CHARS",
    "COMMAND_WRAPPERS",
    "GIT_GLOBAL_OPTS_WITH_VALUE",
    "GH_GLOBAL_OPTS_WITH_VALUE",
    "PUSH_OPTS_WITH_VALUE",
    "MERGE_ALLOWED_BOOL_OPTS",
    "MERGE_ALLOWED_VALUE_OPTS",
    "UNPARSEABLE_FALLBACK_DENY",
    "deny",
    "normalize_operators",
    "split_segments",
    "strip_wrappers",
    "strip_global_opts",
    "looks_protected",
    "has_short_force_flag",
    "git_query",
    "current_branch",
    "branch_merge_options_configured",
    "merge_source",
    "check_git_merge",
    "check_gh",
    "check_git",
    "check_git_push",
    "main",
]

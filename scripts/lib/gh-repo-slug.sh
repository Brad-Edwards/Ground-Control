#!/usr/bin/env bash
# Shared checkout-to-owner/repo resolver (GC-P026, issue #1383).
#
# One consistent repository-identity vocabulary for every shell entry point,
# mirroring the MCP server's `parseOwnerRepoFromRemoteUrl` / `getOwnerRepo`
# coverage so the same valid checkout resolves identically regardless of which
# script runs: scp-style SSH (`git@github.com:owner/name`), `ssh://` and HTTPS
# (including token-embedded `https://user:tok@github.com/...`) github.com
# remotes. git ignores GH_REPO, so deriving from the `origin` remote is immune
# to the env-hijack class this issue closes.
#
# Usage: source this file, then `slug="$(resolve_repo_slug [repo_dir])"`.
# Echoes `owner/name` on success, an empty string otherwise (the caller decides
# whether an unresolved slug is fatal). No global state; safe under `set -u`.

resolve_repo_slug() {
    local repo_dir="${1:-}" url
    if [ -n "${repo_dir}" ]; then
        url="$(git -C "${repo_dir}" remote get-url origin 2>/dev/null || true)"
    else
        url="$(git remote get-url origin 2>/dev/null || true)"
    fi
    [ -n "${url}" ] || { echo ""; return 0; }
    url="${url%.git}"
    url="${url%/}"
    case "${url}" in
        git@github.com:*)       echo "${url#git@github.com:}" ;;
        ssh://git@github.com/*) echo "${url#ssh://git@github.com/}" ;;
        ssh://github.com/*)     echo "${url#ssh://github.com/}" ;;
        https://*@github.com/*) echo "${url#https://*@github.com/}" ;;
        https://github.com/*)   echo "${url#https://github.com/}" ;;
        http://*@github.com/*)  echo "${url#http://*@github.com/}" ;;
        http://github.com/*)    echo "${url#http://github.com/}" ;;
        *)                      echo "" ;;
    esac
}

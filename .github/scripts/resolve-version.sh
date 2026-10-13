#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
    app_version="${GITHUB_REF_NAME:-}"
    app_version="${app_version#v}"
else
    app_version="$(sed -n 's/^starlingVersion=//p' gradle.properties | tail -n 1)"
fi

if [[ ! "$app_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "Version '$app_version' must use semantic version X.Y.Z[-prerelease]." >&2
    exit 1
fi

{
    echo "app_version=$app_version"
    echo "package_version=${app_version%%-*}"
} >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT must be set}"

#!/usr/bin/env bash

set -euo pipefail

repository="${1:-.}"

git -C "$repository" rev-parse --git-dir >/dev/null

while IFS=$'\t' read -r release_tag object_type target_type; do
  [[ -n "$release_tag" ]] || continue
  [[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
    echo "Malformed semantic release tag: $release_tag" >&2
    exit 1
  }
  [[ "$object_type" == tag && "$target_type" == commit ]] || {
    echo "Release tags must be annotated and point directly to commits: $release_tag" >&2
    exit 1
  }
  printf '%s\n' "$release_tag"
done < <(
  git -C "$repository" for-each-ref \
    --sort=version:refname \
    --format='%(refname:short)%09%(objecttype)%09%(*objecttype)' \
    'refs/tags/v*'
)

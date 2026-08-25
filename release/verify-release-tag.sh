#!/usr/bin/env bash

set -euo pipefail

release_tag="${1:-}"
expected_commit="${2:-}"
expected_object="${3:-}"

[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'A canonical release tag is required.' >&2
  exit 1
}
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ -z "$expected_object" || "$expected_object" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected tag object must be empty or a full lowercase Git SHA.' >&2
  exit 1
}
[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo 'GITHUB_REPOSITORY is required.' >&2; exit 1; }
[[ -n "${GH_TOKEN:-}" ]] || { echo 'GH_TOKEN is required.' >&2; exit 1; }

git fetch --force origin \
  '+refs/heads/master:refs/remotes/origin/master' \
  "+refs/tags/$release_tag:refs/tags/$release_tag"

[[ "$(git cat-file -t "refs/tags/$release_tag")" == "tag" ]] || {
  echo 'The release tag must be annotated and signed.' >&2
  exit 1
}
tag_object="$(git rev-parse "refs/tags/$release_tag")"
tag_commit="$(git rev-parse --verify "refs/tags/$release_tag^{commit}")"
[[ "$tag_commit" == "$expected_commit" ]] || {
  echo 'The release tag does not point to the expected commit.' >&2
  exit 1
}
[[ -z "$expected_object" || "$tag_object" == "$expected_object" ]] || {
  echo 'The release tag object changed after preflight.' >&2
  exit 1
}

tag_record="$(gh api "repos/$GITHUB_REPOSITORY/git/tags/$tag_object" --jq '[.verification.verified, .object.type, .object.sha] | @tsv')"
IFS=$'\t' read -r verified target_type target_sha <<< "$tag_record"
[[ "$verified" == "true" ]] || { echo 'GitHub did not verify the release tag signature.' >&2; exit 1; }
[[ "$target_type" == "commit" && "$target_sha" == "$tag_commit" ]] || {
  echo 'The signed GitHub tag object does not directly target the expected commit.' >&2
  exit 1
}
remote_object="$(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$release_tag" --jq '.object.sha')"
[[ "$remote_object" == "$tag_object" ]] || {
  echo 'The remote release tag changed while it was being verified.' >&2
  exit 1
}

printf '%s %s\n' "$tag_object" "$tag_commit"

#!/usr/bin/env bash

set -euo pipefail

controller_commit="${1:-}"
release_tag="${2:-}"
expected_release_commit="${3:-}"
repository="${4:-.}"

fail() {
  echo "$1" >&2
  exit 1
}

[[ "$controller_commit" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || \
  fail 'The Pages controller commit must be an exact object ID.'
[[ "$release_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || \
  fail 'Pages release deployments require a canonical stable semantic tag.'
[[ "$expected_release_commit" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || \
  fail 'The expected Pages release commit must be an exact object ID.'

repository_root="$(git -C "$repository" rev-parse --show-toplevel)"
[[ -d "$repository_root" && ! -L "$repository_root" ]] || fail 'The Pages source repository is not a regular directory.'
[[ "$(git -C "$repository_root" --no-replace-objects cat-file -t "$controller_commit")" == 'commit' ]] || \
  fail 'The Pages controller object is not a commit.'
[[ "$(git -C "$repository_root" rev-parse --verify refs/remotes/origin/master)" == "$controller_commit" ]] || \
  fail 'The Pages controller is not the exact origin/master head.'

controller_directory="$(mktemp -d)"
cleanup() {
  chmod -R u+w -- "$controller_directory" >/dev/null 2>&1 || true
  rm -rf -- "$controller_directory"
}
trap cleanup EXIT INT TERM

materialize_controller_file() {
  local source_path="$1"
  local destination_name="$2"
  local expected_mode="$3"
  local record=""
  local mode=""
  local object_type=""
  local blob=""
  local verified_path=""
  local destination="$controller_directory/$destination_name"

  record="$(git -C "$repository_root" --no-replace-objects ls-tree --full-tree "$controller_commit" -- "$source_path")"
  [[ -n "$record" && "$record" != *$'\n'* ]] || fail "Pages controller source is missing or ambiguous: $source_path"
  read -r mode object_type blob verified_path <<< "$record"
  [[ "$mode" == "$expected_mode" && "$object_type" == 'blob' && \
    "$blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_path" == "$source_path" ]] || \
    fail "Pages controller source is not the expected regular Git blob: $source_path"
  git -C "$repository_root" --no-replace-objects cat-file blob "$blob" > "$destination"
  [[ -f "$destination" && ! -L "$destination" ]] || fail "Pages controller material is not a regular file: $destination_name"
  [[ "$(git -C "$repository_root" hash-object --no-filters -- "$destination")" == "$blob" ]] || \
    fail "Pages controller material differs from its exact Git blob: $source_path"
}

materialize_controller_file release/current-controller.json current-controller.json 100644
materialize_controller_file release/list-release-tags.sh list-release-tags.sh 100755
materialize_controller_file \
  release/verify-current-controller-release-order.sh verify-current-controller-release-order.sh 100755
bash -n "$controller_directory/list-release-tags.sh" "$controller_directory/verify-current-controller-release-order.sh"
find "$controller_directory" -mindepth 1 -maxdepth 1 -type f -exec chmod a-w -- {} +
chmod a-w -- "$controller_directory"

metadata="$controller_directory/current-controller.json"
current_tag="$(jq -er '.current.tag' "$metadata")"
current_commit="$(jq -er '.current.commit' "$metadata")"
current_object="$(jq -er '.current.tagObject' "$metadata")"
[[ "$current_tag" == "$release_tag" && "$current_commit" == "$expected_release_commit" ]] || \
  fail 'The Pages release source differs from current-controller metadata.'

tag_record="$(git -C "$repository_root" --no-replace-objects for-each-ref \
  --format='%(objecttype)%09%(*objecttype)%09%(objectname)' "refs/tags/$release_tag")"
[[ -n "$tag_record" && "$tag_record" != *$'\n'* ]] || fail 'The Pages release tag is missing or ambiguous.'
IFS=$'\t' read -r tag_type target_type tag_object <<< "$tag_record"
[[ "$tag_type" == 'tag' && "$target_type" == 'commit' ]] || \
  fail 'The Pages release tag must be annotated and point directly to a commit.'
tag_commit="$(git -C "$repository_root" --no-replace-objects rev-parse --verify "refs/tags/$release_tag^{commit}")"
[[ "$tag_object" == "$current_object" && "$tag_commit" == "$expected_release_commit" ]] || \
  fail 'The Pages release tag identity differs from current-controller metadata.'

bash "$controller_directory/verify-current-controller-release-order.sh" "$metadata" "$repository_root" >/dev/null
git -C "$repository_root" --no-replace-objects merge-base --is-ancestor "$expected_release_commit" "$controller_commit" || \
  fail 'The current Pages release is not contained in controller master.'
[[ "$(git -C "$repository_root" rev-parse --verify refs/remotes/origin/master)" == "$controller_commit" ]] || \
  fail 'The Pages controller changed during release-source verification.'

printf '%s\t%s\t%s\n' "$controller_commit" "$tag_object" "$tag_commit"

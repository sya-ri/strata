#!/usr/bin/env bash

set -euo pipefail

metadata="${1:-}"
repository="${2:-.}"

fail() {
  echo "$1" >&2
  exit 1
}

script_path="$(realpath "${BASH_SOURCE[0]}")"
controller_directory="$(dirname "$script_path")"
metadata_path="$(realpath "$metadata")"
[[ "$script_path" == "$controller_directory/verify-current-controller-release-order.sh" && \
  "$metadata_path" == "$controller_directory/current-controller.json" && \
  -f "$controller_directory/list-release-tags.sh" && \
  ! -L "$controller_directory/list-release-tags.sh" ]] || \
  fail 'Release-order verification must use one immutable controller bundle.'

current_tag="$(jq -er '.current.tag' "$metadata_path")"
predecessor_tag="$(jq -er '.predecessor.tag' "$metadata_path")"
mapfile -t stable_release_tags < <(
  bash "$controller_directory/list-release-tags.sh" "$repository" | \
    grep --extended-regexp '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
)
(( 2 <= ${#stable_release_tags[@]} )) || fail 'At least two stable annotated releases are required.'
[[ "${stable_release_tags[-2]}" == "$predecessor_tag" && "${stable_release_tags[-1]}" == "$current_tag" ]] || \
  fail 'Controller metadata must name the latest two stable annotated release tags.'

printf '%s\t%s\n' "$predecessor_tag" "$current_tag"

#!/usr/bin/env bash

set -euo pipefail

strata_jq_path="$(type -P jq || true)"
strata_od_path="$(type -P od || true)"
strata_tr_path="$(type -P tr || true)"
if [[ "$strata_jq_path" == /* && -x "$strata_jq_path" && \
  "$strata_od_path" == /* && -x "$strata_od_path" && \
  "$strata_tr_path" == /* && -x "$strata_tr_path" ]]; then
  :
else
  echo 'Portable jq initialization requires absolute executable jq, od, and tr paths.' >&2
  exit 1
fi
readonly strata_jq_path strata_od_path strata_tr_path

strata_jq_binary_options=()
if "$strata_jq_path" --binary -n 'null' >/dev/null 2>&1; then
  strata_jq_binary_options=(--binary)
fi
readonly -a strata_jq_binary_options

strata_jq_probe_hex=''
if strata_jq_probe_hex="$(
  "$strata_jq_path" "${strata_jq_binary_options[@]}" -nr --arg x x "\$x" |
    "$strata_od_path" -An -tx1 |
    "$strata_tr_path" -d '[:space:]'
)"; then
  :
else
  echo 'jq output-mode byte probing failed.' >&2
  exit 1
fi
if [[ "$strata_jq_probe_hex" == '780a' ]]; then
  unset strata_jq_probe_hex
else
  echo 'jq output mode does not produce exact LF-delimited bytes.' >&2
  exit 1
fi

portable_jq() {
  "$strata_jq_path" "${strata_jq_binary_options[@]}" "$@"
}
readonly -f portable_jq

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

current_tag="$(portable_jq -er '.current.tag' "$metadata_path")"
predecessor_tag="$(portable_jq -er '.predecessor.tag' "$metadata_path")"
stable_release_inventory="$(
  bash "$controller_directory/list-release-tags.sh" "$repository" | \
    grep --extended-regexp '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
)"
mapfile -t stable_release_tags <<< "$stable_release_inventory"
(( 2 <= ${#stable_release_tags[@]} )) || fail 'At least two stable annotated releases are required.'
[[ "${stable_release_tags[-2]}" == "$predecessor_tag" && "${stable_release_tags[-1]}" == "$current_tag" ]] || \
  fail 'Controller metadata must name the latest two stable annotated release tags.'

printf '%s\t%s\n' "$predecessor_tag" "$current_tag"

#!/usr/bin/env bash

# Read-only GitHub release requests. Never interpret an inaccessible or incomplete
# inventory as absence, and never retry an external mutation.
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN is required.}"
: "${GITHUB_API_URL:?GITHUB_API_URL is required.}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required.}"
[[ "$GITHUB_API_URL" == https://* && "$GITHUB_API_URL" != *'?'* && "$GITHUB_API_URL" != *'#'* ]] || exit 64
[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || exit 64

read_request() {
  local path="$1" output="$2" accept="$3"
  curl --fail --silent --show-error --location --retry 3 --retry-all-errors --retry-delay 1 \
    --connect-timeout 10 --max-time 60 --retry-max-time 180 \
    --proto '=https' --proto-redir '=https' \
    --header "Accept: $accept" --header "Authorization: Bearer $GH_TOKEN" \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "$GITHUB_API_URL/repos/$GITHUB_REPOSITORY/$path" --output "$output"
}

case "${1:-}" in
  download)
    [[ $# == 3 && "$2" =~ ^[1-9][0-9]*$ && -n "$3" && ! -e "$3" && ! -L "$3" ]] || exit 64
    if ! read_request "releases/assets/$2" "$3" application/octet-stream; then
      rm -f -- "$3"
      exit 1
    fi
    ;;
  find)
    [[ $# == 1 ]] || exit 64
    : "${RELEASE_TAG:?RELEASE_TAG is required.}"
    [[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || exit 64
    temporary="$(mktemp -d)"
    trap 'rm -rf -- "$temporary"' EXIT
    printf '[]\n' > "$temporary/releases.json"
    complete=false
    # A full page must be followed, even when it already contains the requested
    # tag: a later draft could make the identity ambiguous.
    for page in $(seq 1 100); do
      read_request "releases?per_page=100&page=$page" "$temporary/page.json" application/vnd.github+json
      jq -e 'type == "array" and length <= 100 and all(.[];
        type == "object" and (.id | type == "number" and . > 0 and . == floor) and
        (.tag_name | type == "string") and (.draft | type == "boolean"))' "$temporary/page.json" >/dev/null
      jq -s '.[0] + .[1] | if (map(.id) | length == (unique | length)) then .
        else error("GitHub release pagination repeated an identity") end' \
        "$temporary/releases.json" "$temporary/page.json" > "$temporary/next.json"
      mv -- "$temporary/next.json" "$temporary/releases.json"
      if [[ "$(jq 'length' "$temporary/page.json")" -lt 100 ]]; then
        complete=true
        break
      fi
    done
    [[ "$complete" == true ]] || { echo 'GitHub release pagination limit reached.' >&2; exit 1; }
    jq --arg tag "$RELEASE_TAG" '[.[] | select(.tag_name == $tag)] |
      if length == 0 then null elif length == 1 then .[0]
      else error("Multiple GitHub releases have the requested tag") end' "$temporary/releases.json"
    ;;
  *) exit 64 ;;
esac

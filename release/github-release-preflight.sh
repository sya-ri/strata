#!/usr/bin/env bash

set -euo pipefail

bundle_directory="${1:-build/release/github}"

: "${GH_TOKEN:?GH_TOKEN is required for the GitHub Release preflight.}"
: "${GITHUB_API_URL:?GITHUB_API_URL is required for the GitHub Release preflight.}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required for the GitHub Release preflight.}"
: "${RELEASE_TAG:?RELEASE_TAG is required for the GitHub Release preflight.}"
: "${CENTRAL_STATE:?CENTRAL_STATE is required for the GitHub Release preflight.}"

[[ "$GITHUB_API_URL" == https://* ]] || { echo 'The GitHub API URL must use HTTPS.' >&2; exit 1; }
[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'Invalid GitHub repository name.' >&2; exit 1; }
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'The GitHub Release preflight requires an exact semantic release tag.' >&2
  exit 1
}
[[ "$CENTRAL_STATE" == absent || "$CENTRAL_STATE" == exact ]] || { echo 'Unexpected Maven Central state.' >&2; exit 1; }

release_json="$(mktemp)"
release_body="$(mktemp)"
downloads="$(mktemp -d)"
expected_inventory="$(mktemp)"
actual_inventory="$(mktemp)"
trap 'rm -rf -- "$release_json" "$release_body" "$downloads" "$expected_inventory" "$actual_inventory"' EXIT

status="$(
  curl --silent --show-error --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' \
    --header 'Accept: application/vnd.github+json' \
    --header "Authorization: Bearer $GH_TOKEN" \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    --output "$release_json" \
    --write-out '%{http_code}' \
    "$GITHUB_API_URL/repos/$GITHUB_REPOSITORY/releases/tags/$RELEASE_TAG"
)"

case "$status" in
  404)
    echo "No GitHub Release exists for $RELEASE_TAG."
    exit 0
    ;;
  200)
    ;;
  *)
    echo "GitHub Release preflight failed with HTTP $status." >&2
    exit 1
    ;;
esac

[[ "$CENTRAL_STATE" == exact ]] || {
  echo 'A GitHub Release already exists while Maven Central is absent; refusing any external write.' >&2
  exit 1
}

jq --exit-status 'type == "object"' "$release_json" >/dev/null
expected_title="Strata ${RELEASE_TAG#v}"
[[ "$(jq -r '.tag_name' "$release_json")" == "$RELEASE_TAG" ]] || { echo 'Existing GitHub Release tag differs.' >&2; exit 1; }
[[ "$(jq -r '.name' "$release_json")" == "$expected_title" ]] || { echo 'Existing GitHub Release title differs.' >&2; exit 1; }
jq --raw-output --join-output '(.body // "") | gsub("\r\n"; "\n") | if contains("\r") then error("release body contains a lone carriage return") else @base64 end' "$release_json" |
  base64 --decode > "$release_body"
cmp --silent "docs/releases/$RELEASE_TAG.md" "$release_body" || { echo 'Existing GitHub Release body differs.' >&2; exit 1; }
[[ "$(jq -r '.prerelease' "$release_json")" == false ]] || { echo 'The stable GitHub Release must not be a prerelease.' >&2; exit 1; }

[[ -d "$bundle_directory" ]] || { echo 'The canonical GitHub bundle is missing for an existing release.' >&2; exit 1; }
shopt -s nullglob
assets=("$bundle_directory"/*)
runtime_jars=("$bundle_directory"/*.jar)
expected_asset_count=$(( ${#runtime_jars[@]} * 2 + 1 ))
[[ "${#runtime_jars[@]}" == 20 || "${#runtime_jars[@]}" == 21 ]] || {
  echo "Expected a supported 20- or 21-runtime GitHub bundle, found ${#runtime_jars[@]} JARs." >&2
  exit 1
}
[[ "${#assets[@]}" == "$expected_asset_count" ]] || {
  echo "Expected $expected_asset_count canonical GitHub assets, found ${#assets[@]}." >&2
  exit 1
}
(cd "$bundle_directory" && sha256sum --check SHA256SUMS)

printf '%s\n' "${assets[@]##*/}" | LC_ALL=C sort > "$expected_inventory"
jq -r '.assets[].name' "$release_json" | LC_ALL=C sort > "$actual_inventory"
unexpected="$(comm -13 "$expected_inventory" "$actual_inventory")"
[[ -z "$unexpected" ]] || { echo "GitHub Release contains unexpected assets: $unexpected" >&2; exit 1; }

if [[ "$(jq -r '.draft' "$release_json")" == false ]]; then
  cmp --silent "$expected_inventory" "$actual_inventory" || { echo 'Published GitHub Release has an incomplete immutable asset inventory.' >&2; exit 1; }
fi

while IFS=$'\t' read -r name asset_id; do
  [[ -n "$name" && -n "$asset_id" ]] || { echo 'GitHub returned incomplete release-asset metadata.' >&2; exit 1; }
  grep --fixed-strings --line-regexp "$name" "$expected_inventory" >/dev/null || { echo "Unsafe GitHub Release asset name: $name" >&2; exit 1; }
  curl --fail --silent --show-error --location --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' --proto-redir '=https' \
    --header 'Accept: application/octet-stream' \
    --header "Authorization: Bearer $GH_TOKEN" \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "$GITHUB_API_URL/repos/$GITHUB_REPOSITORY/releases/assets/$asset_id" \
    --output "$downloads/$name"
  cmp --silent "$bundle_directory/$name" "$downloads/$name" || { echo "Existing GitHub Release asset differs: $name" >&2; exit 1; }
done < <(jq -r '.assets[] | [.name, (.id | tostring)] | @tsv' "$release_json")

echo 'Existing GitHub Release metadata and uploaded assets are conflict-free.'

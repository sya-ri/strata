#!/usr/bin/env bash

set -euo pipefail

release_tag="${1:-}"
expected_release_commit="${2:-}"
expected_controller_commit="${3:-}"
timeout_seconds="${4:-900}"
poll_interval_seconds="${5:-15}"

[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'A canonical release tag is required.' >&2
  exit 1
}
[[ "$expected_release_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ "$expected_controller_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected controller Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ && "$timeout_seconds" -le 1800 ]] || {
  echo 'The Pages receipt timeout must be between 1 and 1800 seconds.' >&2
  exit 1
}
[[ "$poll_interval_seconds" =~ ^[0-9]+$ && "$poll_interval_seconds" -le 30 ]] || {
  echo 'The Pages receipt poll interval must be between 0 and 30 seconds.' >&2
  exit 1
}

temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

receipt_is_current() {
  local url="$1"
  local expected_revision="$2"
  local expected_commit="$3"
  local label="$4"
  local remaining="$5"
  local receipt="$temporary_root/$label-receipt.json"
  local headers="$temporary_root/$label-headers.txt"
  local max_time=20
  local cache_age=""

  if (( remaining < max_time )); then
    max_time="$remaining"
  fi
  if ! curl --fail --silent --show-error --location \
    --connect-timeout 10 --max-time "$max_time" \
    --proto '=https' --proto-redir '=https' \
    --dump-header "$headers" \
    "$url" \
    --output "$receipt"; then
    return 1
  fi
  if ! jq --exit-status \
    --arg revision "$expected_revision" \
    --arg commit "$expected_commit" \
    'type == "object" and keys == ["commit", "revision"] and .revision == $revision and .commit == $commit' \
    "$receipt" >/dev/null; then
    return 1
  fi
  cache_age="$(
    awk '
      /^HTTP\// {
        age = ""
      }
      tolower($1) == "age:" {
        gsub(/\r/, "", $2)
        age = $2
      }
      END { print age }
    ' "$headers"
  )"
  [[ -z "$cache_age" || "$cache_age" =~ ^[0-9]+$ && "$cache_age" -le 5 ]]
}

pages_base='https://gh.s7a.dev/strata'
release_pages_base="$pages_base/releases/${release_tag#v}"
deadline=$((SECONDS + timeout_seconds))
attempt=0
controller_verified=false
release_verified=false
while (( SECONDS < deadline )); do
  attempt=$((attempt + 1))
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    break
  fi
  if [[ "$controller_verified" == false ]] && receipt_is_current \
    "$pages_base/source-receipt.json" master "$expected_controller_commit" controller "$remaining"; then
    controller_verified=true
  fi
  remaining=$((deadline - SECONDS))
  if (( 0 < remaining )) && [[ "$release_verified" == false ]] && receipt_is_current \
    "$release_pages_base/source-receipt.json" \
    "$release_tag" \
    "$expected_release_commit" \
    release \
    "$remaining"; then
    release_verified=true
  fi
  if [[ "$controller_verified" == true && "$release_verified" == true ]]; then
    echo "Verified current public controller and immutable release Pages receipts for $release_tag."
    exit 0
  fi
  remaining=$((deadline - SECONDS))
  if (( 0 < remaining && 0 < poll_interval_seconds )); then
    sleep_seconds="$poll_interval_seconds"
    if (( remaining < sleep_seconds )); then
      sleep_seconds="$remaining"
    fi
    sleep "$sleep_seconds"
  fi
done

echo "The public Pages receipts did not reach controller $expected_controller_commit and $release_tag at $expected_release_commit within ${timeout_seconds}s." >&2
exit 1

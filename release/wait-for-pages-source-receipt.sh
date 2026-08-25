#!/usr/bin/env bash

set -euo pipefail

release_tag="${1:-}"
expected_commit="${2:-}"
timeout_seconds="${3:-900}"
poll_interval_seconds="${4:-15}"

[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'A canonical release tag is required.' >&2
  exit 1
}
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected Pages commit must be a full lowercase Git SHA.' >&2
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

receipt="$(mktemp)"
headers="$(mktemp)"
trap 'rm -f -- "$receipt" "$headers"' EXIT
release_pages_base="https://gh.s7a.dev/strata/releases/${release_tag#v}"
deadline=$((SECONDS + timeout_seconds))
attempt=0
while (( SECONDS < deadline )); do
  attempt=$((attempt + 1))
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    break
  fi
  max_time=20
  if (( remaining < max_time )); then
    max_time="$remaining"
  fi
  if curl --fail --silent --show-error --location \
    --connect-timeout 10 --max-time "$max_time" \
    --proto '=https' --proto-redir '=https' \
    --dump-header "$headers" \
    "$release_pages_base/source-receipt.json" \
    --output "$receipt"; then
    if jq --exit-status \
      --arg revision "$release_tag" \
      --arg commit "$expected_commit" \
      'type == "object" and keys == ["commit", "revision"] and .revision == $revision and .commit == $commit' \
      "$receipt" >/dev/null; then
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
      if [[ -z "$cache_age" || "$cache_age" =~ ^[0-9]+$ && "$cache_age" -le 5 ]]; then
        echo "Verified current public Pages receipt for $release_tag at $expected_commit."
        exit 0
      fi
    fi
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

echo "The public Pages receipt did not reach $release_tag at $expected_commit within ${timeout_seconds}s." >&2
exit 1

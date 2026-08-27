#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
contract_path="${1:-$repository_root/release/github-tag-ruleset.json}"
receipt_path="${2:-$repository_root/release/github-tag-ruleset-receipt.json}"

[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo 'GITHUB_REPOSITORY is required.' >&2; exit 1; }
[[ -n "${GH_TOKEN:-}" ]] || { echo 'GH_TOKEN is required.' >&2; exit 1; }

timestamp_instant() {
  local timestamp="$1"
  [[ "$timestamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$ ]] || return 1
  date -u --date="$timestamp" '+%s.%N'
}

expected_ref="$(jq -er '.conditions.ref_name.include | select(length == 1) | .[0]' "$contract_path")" || {
  echo 'The tracked tag ruleset contract must include exactly one release tag.' >&2
  exit 1
}
[[ "$expected_ref" =~ ^refs/tags/v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'The tracked tag ruleset contract has an invalid release ref.' >&2
  exit 1
}
expected_tag="${expected_ref#refs/tags/}"
expected_name="Protect Strata $expected_tag"

jq -e --arg name "$expected_name" --arg ref "$expected_ref" '
  keys == ["bypass_actors", "conditions", "enforcement", "name", "rules", "target"] and
  .name == $name and
  .target == "tag" and
  .enforcement == "active" and
  .bypass_actors == [] and
  .conditions == {
    "ref_name": {
      "exclude": [],
      "include": [$ref]
    }
  } and
  (.rules | sort_by(.type)) == [
    {"type": "deletion"},
    {
      "parameters": {"update_allows_fetch_and_merge": false},
      "type": "update"
    }
  ]
' "$contract_path" >/dev/null || {
  echo "The tracked $expected_tag tag ruleset contract is not canonical." >&2
  exit 1
}

ruleset_id="$(jq -er '
  select(keys == ["bypassActorsAuditedAt", "rulesetId", "updatedAt"]) |
  .rulesetId |
  select(type == "number" and . > 0 and . == floor) |
  tostring
' "$receipt_path")" || {
  echo 'The tracked tag ruleset receipt must be populated after external creation and an administrator bypass audit.' >&2
  exit 1
}
updated_at="$(jq -er '.updatedAt | select(type == "string" and length > 0)' "$receipt_path")" || {
  echo 'The tracked tag ruleset receipt is missing its GitHub-controlled updatedAt value.' >&2
  exit 1
}
updated_at_instant="$(timestamp_instant "$updated_at")" || {
  echo 'The tracked tag ruleset receipt has a non-canonical updatedAt value.' >&2
  exit 1
}
bypass_audited_at="$(jq -er '.bypassActorsAuditedAt | select(type == "string" and length > 0)' "$receipt_path")" || {
  echo 'The tracked tag ruleset receipt is missing its administrator bypass audit timestamp.' >&2
  exit 1
}
[[ "$bypass_audited_at" == "$updated_at" ]] || {
  echo 'The bypass audit does not cover the currently tracked ruleset revision.' >&2
  exit 1
}

remote_response="$(mktemp)"
cleanup() {
  rm -f -- "$remote_response"
}
trap cleanup EXIT

gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2026-03-10' \
  "repos/$GITHUB_REPOSITORY/rulesets/$ruleset_id" > "$remote_response"

remote_updated_at="$(jq -er '.updated_at | select(type == "string" and length > 0)' "$remote_response")" || {
  echo 'The active GitHub tag ruleset has no updated_at revision.' >&2
  exit 1
}
remote_updated_at_instant="$(timestamp_instant "$remote_updated_at")" || {
  echo 'The active GitHub tag ruleset has a non-canonical updated_at revision.' >&2
  exit 1
}
[[ "$remote_updated_at_instant" == "$updated_at_instant" ]] || {
  echo 'The active GitHub tag ruleset changed after its administrator bypass audit.' >&2
  exit 1
}

jq -e \
  --arg repository "$GITHUB_REPOSITORY" \
  --arg name "$(jq -r '.name' "$contract_path")" \
  --arg ref "$expected_ref" \
  --argjson rulesetId "$ruleset_id" '
    .id == $rulesetId and
    .name == $name and
    .target == "tag" and
    .enforcement == "active" and
    .source_type == "Repository" and
    .source == $repository and
    .conditions.ref_name == {
      "exclude": [],
      "include": [$ref]
    } and
    (.rules | length) == 2 and
    ([.rules[].type] | sort) == ["deletion", "update"] and
    all(
      .rules[];
      if .type == "deletion" then
        keys == ["type"]
      else
        (keys == ["type"]) or
        (
          keys == ["parameters", "type"] and
          .parameters == {"update_allows_fetch_and_merge": false}
        )
      end
    ) and
    (
      (has("bypass_actors") | not) or
      (.bypass_actors == null) or
      (.bypass_actors == [])
    )
  ' "$remote_response" >/dev/null || {
  echo "The active GitHub tag ruleset differs from its tracked $expected_tag contract or audited revision." >&2
  exit 1
}

printf '%s %s\n' "$ruleset_id" "$updated_at"

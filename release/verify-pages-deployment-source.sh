#!/usr/bin/env bash

set -euo pipefail

run_id="${1:-}"
release_tag="${2:-}"
expected_commit="${3:-}"

[[ "$run_id" =~ ^[1-9][0-9]*$ ]] || { echo 'A positive Documentation workflow run ID is required.' >&2; exit 1; }
[[ "$release_tag" == "v0.1.0" ]] || { echo 'Pages deployment verification is pinned to v0.1.0.' >&2; exit 1; }
[[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected Pages deployment commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo 'GITHUB_REPOSITORY is required.' >&2; exit 1; }
[[ -n "${GH_TOKEN:-}" ]] || { echo 'GH_TOKEN is required.' >&2; exit 1; }

temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

api_header=(-H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2026-03-10')
run_response="$temporary_root/run.json"
jobs_response="$temporary_root/jobs.json"
artifacts_response="$temporary_root/artifacts.json"
deployments_response="$temporary_root/deployments.json"
statuses_response="$temporary_root/statuses.json"

gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id" > "$run_response"
jq -e \
  --argjson runId "$run_id" \
  --arg tag "$release_tag" \
  --arg commit "$expected_commit" '
    .id == $runId and
    .path == ".github/workflows/pages.yml" and
    .event == "push" and
    .head_branch == $tag and
    .head_sha == $commit and
    .status == "completed" and
    .conclusion == "success"
  ' "$run_response" >/dev/null || {
  echo 'The Documentation workflow run is not the successful exact tag and commit.' >&2
  exit 1
}

gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/jobs?per_page=100" > "$jobs_response"
jq -e '
  ([.jobs[] | select(.name == "build" and .status == "completed" and .conclusion == "success")] | length) == 1 and
  ([.jobs[] | select(.name == "deploy" and .status == "completed" and .conclusion == "success")] | length) == 1
' "$jobs_response" >/dev/null || {
  echo 'The exact Documentation run does not contain one successful build and deploy job.' >&2
  exit 1
}

gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/artifacts?per_page=100" > "$artifacts_response"
artifact_record="$(
  jq -er \
    --argjson runId "$run_id" \
    --arg tag "$release_tag" \
    --arg commit "$expected_commit" '
      [
        .artifacts[] |
        select(
          .name == "github-pages" and
          .expired == false and
          .size_in_bytes > 0 and
          ((.digest // "") | test("^sha256:[0-9a-f]{64}$")) and
          .workflow_run.id == $runId and
          .workflow_run.head_branch == $tag and
          .workflow_run.head_sha == $commit
        )
      ] |
      select(length == 1) |
      .[0] |
      select(.id | type == "number" and . > 0 and . == floor) |
      [.id, .size_in_bytes, .digest] |
      @tsv
    ' "$artifacts_response"
)" || {
  echo 'The exact Documentation run does not have one current commit-bound github-pages artifact.' >&2
  exit 1
}
IFS=$'\t' read -r artifact_id artifact_size artifact_digest <<< "$artifact_record"

artifact_zip="$temporary_root/github-pages.zip"
gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$artifact_zip"
[[ "$(stat -c '%s' "$artifact_zip")" == "$artifact_size" ]] || {
  echo 'The downloaded github-pages ZIP size differs from immutable artifact metadata.' >&2
  exit 1
}
[[ "sha256:$(sha256sum "$artifact_zip" | cut -d ' ' -f 1)" == "$artifact_digest" ]] || {
  echo 'The downloaded github-pages ZIP digest differs from immutable artifact metadata.' >&2
  exit 1
}
mapfile -t zip_entries < <(unzip -Z1 "$artifact_zip")
[[ "${#zip_entries[@]}" == "1" && "${zip_entries[0]}" == "artifact.tar" ]] || {
  echo 'The downloaded github-pages ZIP must contain only artifact.tar.' >&2
  exit 1
}
artifact_tar="$temporary_root/artifact.tar"
unzip -p "$artifact_zip" artifact.tar > "$artifact_tar"

printf '{"commit":"%s","revision":"%s"}\n' "$expected_commit" "$release_tag" > "$temporary_root/expected-receipt.json"
verify_artifact_receipt() {
  local receipt_target="$1"
  local receipt_output="$2"
  local receipt_label="$3"
  local receipt_entries=()
  mapfile -t receipt_entries < <(
    tar -tf "$artifact_tar" |
      while IFS= read -r entry; do
        normalized="${entry#./}"
        if [[ "$normalized" == "$receipt_target" ]]; then
          printf '%s\n' "$entry"
        fi
      done
  )
  [[ "${#receipt_entries[@]}" == "1" ]] || {
    echo "The Pages artifact must contain exactly one $receipt_label source receipt." >&2
    exit 1
  }
  tar -xOf "$artifact_tar" "${receipt_entries[0]}" > "$receipt_output"
  cmp --silent "$temporary_root/expected-receipt.json" "$receipt_output" || {
    echo "The $receipt_label Pages artifact source receipt differs from the exact tag and commit." >&2
    exit 1
  }
}
verify_artifact_receipt 'source-receipt.json' "$temporary_root/root-source-receipt.json" 'root'
verify_artifact_receipt \
  "releases/${release_tag#v}/source-receipt.json" \
  "$temporary_root/release-source-receipt.json" \
  'immutable release'

gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/deployments?environment=github-pages&per_page=100" > "$deployments_response"
deployment_id="$(
  jq -er \
    --arg tag "$release_tag" \
    --arg commit "$expected_commit" '
      .[0] |
      select(
        .ref == $tag and
        .sha == $commit and
        .environment == "github-pages" and
        .task == "deploy"
      ) |
      .id |
      select(type == "number" and . > 0 and . == floor) |
      tostring
    ' "$deployments_response"
)" || {
  echo 'The globally newest github-pages deployment is not bound to the exact tag and commit.' >&2
  exit 1
}
gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/deployments/$deployment_id/statuses?per_page=100" > "$statuses_response"
jq -e \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$run_id" '
  length > 0 and
  .[0].state == "success" and
  .[0].environment == "github-pages" and
  ([.[0].log_url, .[0].target_url] | any(. == $runUrl or startswith("\($runUrl)/")))
' "$statuses_response" >/dev/null || {
  echo 'The exact github-pages deployment does not have a latest successful status.' >&2
  exit 1
}

printf '%s %s %s\n' "$run_id" "$artifact_id" "$deployment_id"

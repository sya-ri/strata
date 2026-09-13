#!/usr/bin/env bash

# Select an existing successful Pages producer for an explicit release. This is
# discovery only; the deployment verifier still checks every artifact and byte.
set -euo pipefail
tag="${1:-}" source_commit="${2:-}" controller_commit="${3:-}"
[[ $# == 3 && "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ &&
  "$source_commit" =~ ^[0-9a-f]{40}$ && "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || exit 64
runs="$(gh run list --workflow pages.yml --event push --branch master --status success --limit 100 \
  --json conclusion,databaseId,event,headBranch,headSha)"
jq -e 'type == "array" and all(.[];
  (.databaseId | type == "number" and . > 0 and . == floor) and
  (.headSha | type == "string" and test("^[0-9a-f]{40}$"))) and
  (map(.databaseId) | length == (unique | length))' <<< "$runs" >/dev/null
while IFS=$'\t' read -r run_id producer_commit; do
  # Ignore subsequent builds and commits removed from the reviewed history.
  if ! git --no-replace-objects merge-base --is-ancestor "$producer_commit" "$controller_commit" 2>/dev/null; then
    continue
  fi
  if ! metadata="$(git --no-replace-objects show "$producer_commit:release/current-controller.json" 2>/dev/null)"; then
    continue
  fi
  if jq -e --arg tag "$tag" --arg commit "$source_commit" \
    '.current.tag == $tag and .current.commit == $commit' <<< "$metadata" >/dev/null; then
    printf '%s %s\n' "$run_id" "$producer_commit"
    exit 0
  fi
done < <(jq -r '.[] | select(.conclusion == "success" and .event == "push" and .headBranch == "master") |
  [.databaseId, .headSha] | @tsv' <<< "$runs")
echo 'No retained successful Pages producer for the selected release was found among the latest 100 master runs.' >&2
exit 1

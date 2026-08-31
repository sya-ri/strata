#!/usr/bin/env bash

set -euo pipefail

operation="${1:-}"
contract="${2:-}"
project_id="${3:-}"
release_tag="${4:-}"
source_commit="${5:-}"
controller_commit="${6:-}"
pages_record="${7:-}"

fail() {
  echo "$1" >&2
  exit 1
}

runner_path="$(realpath "${BASH_SOURCE[0]}")"
controller_directory="$(dirname "$runner_path")"
contract_path="$(realpath "$contract")"
[[ "$runner_path" == "$controller_directory/run-publish-controller-recovery.sh" && \
  "$contract_path" == "$controller_directory/backlog-recovery.json" ]] || \
  fail 'Recovery must use the generic files from one immutable controller bundle.'
[[ -f "$controller_directory/backlog-recovery-runner" && \
  -f "$controller_directory/backlog-artifact-evidence.json" && \
  ! -L "$controller_directory/backlog-recovery-runner" && \
  ! -L "$controller_directory/backlog-artifact-evidence.json" ]] || \
  fail 'No identity-bound recovery is available in this controller bundle.'

current_tag="$(jq -er '.releaseSource.tag' "$contract_path")"
baseline_tag="$(jq -er '.baselineReleases[0].tag' "$contract_path")"
[[ "$current_tag" == "$release_tag" ]] || fail 'Recovery contract does not bind the requested release tag.'
[[ "$current_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ && \
  "$baseline_tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || \
  fail 'Recovery contract tags are not canonical stable semantic versions.'

temporary="$(mktemp -d "${RUNNER_TEMP:-/tmp}/strata-controller-recovery.XXXXXX")"
cleanup() {
  chmod -R u+w -- "$temporary" >/dev/null 2>&1 || true
  rm -rf -- "$temporary"
}
trap cleanup EXIT INT TERM

required_bundle_files=(
  run-publish-controller-recovery.sh
  backlog-recovery-runner
  backlog-recovery.json
  backlog-artifact-evidence.json
)
for bundle_file in "${required_bundle_files[@]}"; do
  [[ -f "$controller_directory/$bundle_file" && ! -L "$controller_directory/$bundle_file" ]] || \
    fail "Controller recovery bundle is missing $bundle_file."
  cp "$controller_directory/$bundle_file" "$temporary/$bundle_file"
done
optional_bundle_files=(
  current-controller.json
  verify-release-tag.sh
  list-release-tags.sh
  verify-current-controller-release-order.sh
  verify-github-tag-ruleset.sh
  github-release-tag-ruleset.json
  github-release-tag-ruleset-receipt.json
  verify-pages-deployment-source.sh
  wait-for-pages-source-receipt.sh
  list-java-toolchains.sh
)
for bundle_file in "${optional_bundle_files[@]}"; do
  if [[ -e "$controller_directory/$bundle_file" || -L "$controller_directory/$bundle_file" ]]; then
    [[ -f "$controller_directory/$bundle_file" && ! -L "$controller_directory/$bundle_file" ]] || \
      fail "Controller recovery bundle contains an invalid $bundle_file."
    cp "$controller_directory/$bundle_file" "$temporary/$bundle_file"
  fi
done
incident_contract="$temporary/modrinth-${current_tag}-backlog-recovery.json"
incident_runner="$temporary/run-modrinth-${current_tag}-backlog-recovery.sh"
incident_evidence="$temporary/modrinth-${baseline_tag}-artifacts.json"
cp "$temporary/backlog-recovery.json" "$incident_contract"
cp "$temporary/backlog-recovery-runner" "$incident_runner"
cp "$temporary/backlog-artifact-evidence.json" "$incident_evidence"
chmod a-w -- "$temporary" "$temporary"/*

bash "$incident_runner" "$operation" "$incident_contract" "$project_id" "$release_tag" \
  "$source_commit" "$controller_commit" "$pages_record"

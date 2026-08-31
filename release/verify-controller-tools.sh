#!/usr/bin/env bash

set -euo pipefail

operation="${1:-}"
controller_commit="${2:-}"
tool_directory="${3:-}"

fail() {
  echo "$1" >&2
  exit 1
}

[[ "$operation" == "materialize" || "$operation" == "verify" ]] || fail 'Controller tool operation must be materialize or verify.'
[[ "$controller_commit" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]] || fail 'Controller commit is not an exact object ID.'
[[ -d "$tool_directory" && ! -L "$tool_directory" ]] || fail 'Controller tool directory is not a regular directory.'
[[ "$(git --no-replace-objects cat-file -t "$controller_commit")" == "commit" ]] || fail 'Controller object is not a commit.'
if [[ "$operation" == "verify" ]]; then
  [[ "$(stat -c '%A' -- "$tool_directory")" != *w* ]] || fail 'Controller tool directory regained write access.'
fi

verify_controller_tool() {
  local source_path="$1"
  local destination_name="$2"
  local validation="$3"
  local destination="$tool_directory/$destination_name"
  local record=""
  local mode=""
  local object_type=""
  local blob=""
  local verified_path=""

  record="$(git --no-replace-objects ls-tree --full-tree "$controller_commit" -- "$source_path")"
  [[ "$record" != *$'\n'* ]] || fail "Controller tree contains an ambiguous tool entry: $source_path"
  read -r mode object_type blob verified_path <<< "$record"
  [[ "$mode" == "100644" && "$object_type" == "blob" && \
    "$blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_path" == "$source_path" ]] || \
    fail "Controller tool is not the expected regular Git blob: $source_path"

  if [[ "$operation" == "materialize" ]]; then
    [[ ! -e "$destination" && ! -L "$destination" ]] || fail "Controller tool destination already exists: $destination"
    git --no-replace-objects cat-file blob "$blob" > "$destination"
  fi

  [[ -f "$destination" && ! -L "$destination" ]] || fail "Controller tool is not a regular file: $destination"
  if [[ "$operation" == "verify" ]]; then
    [[ "$(stat -c '%A' -- "$destination")" != *w* ]] || fail "Controller tool regained write access: $destination"
  fi
  [[ "$(git hash-object --no-filters -- "$destination")" == "$blob" ]] || fail "Controller tool differs from its exact Git blob: $source_path"
  case "$validation" in
    bash) bash -n "$destination" ;;
    json) jq -e 'type == "object"' "$destination" >/dev/null ;;
    *) fail "Unsupported controller tool validation: $validation" ;;
  esac
}

verify_controller_tool release/verify-github-tag-ruleset.sh verify-github-tag-ruleset.sh bash
verify_controller_tool release/github-release-tag-ruleset.json github-release-tag-ruleset.json json
verify_controller_tool release/github-release-tag-ruleset-receipt.json github-release-tag-ruleset-receipt.json json
verify_controller_tool release/verify-pages-deployment-source.sh verify-pages-deployment-source.sh bash
verify_controller_tool release/wait-for-pages-source-receipt.sh wait-for-pages-source-receipt.sh bash
verify_controller_tool release/run-modrinth-v0.1.2-backlog-recovery.sh run-modrinth-v0.1.2-backlog-recovery.sh bash
verify_controller_tool release/modrinth-v0.1.2-backlog-recovery.json modrinth-v0.1.2-backlog-recovery.json json
verify_controller_tool release/modrinth-v0.1.0-artifacts.json modrinth-v0.1.0-artifacts.json json

if [[ "$operation" == "materialize" ]]; then
  chmod a-w "$tool_directory" \
    "$tool_directory/verify-github-tag-ruleset.sh" \
    "$tool_directory/github-release-tag-ruleset.json" \
    "$tool_directory/github-release-tag-ruleset-receipt.json" \
    "$tool_directory/verify-pages-deployment-source.sh" \
    "$tool_directory/wait-for-pages-source-receipt.sh" \
    "$tool_directory/run-modrinth-v0.1.2-backlog-recovery.sh" \
    "$tool_directory/modrinth-v0.1.2-backlog-recovery.json" \
    "$tool_directory/modrinth-v0.1.0-artifacts.json"
fi

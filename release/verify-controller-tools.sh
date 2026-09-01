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
  echo 'Controller verification requires absolute executable jq, od, and tr paths.' >&2
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
  echo 'Controller jq output-mode byte probing failed.' >&2
  exit 1
fi
if [[ "$strata_jq_probe_hex" == '780a' ]]; then
  unset strata_jq_probe_hex
else
  echo 'Controller jq output mode does not produce exact LF-delimited bytes.' >&2
  exit 1
fi

portable_jq() {
  "$strata_jq_path" "${strata_jq_binary_options[@]}" "$@"
}
readonly -f portable_jq

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

controller_tree_record() {
  local source_path="$1"
  local expected_mode="$2"
  local record=""
  local mode=""
  local object_type=""
  local blob=""
  local verified_path=""

  [[ "$expected_mode" == "100644" || "$expected_mode" == "100755" ]] || \
    fail "Controller source mapping declares an unsupported Git mode: $source_path: $expected_mode"
  record="$(git --no-replace-objects ls-tree --full-tree "$controller_commit" -- "$source_path")"
  [[ -n "$record" && "$record" != *$'\n'* ]] || fail "Controller tree contains a missing or ambiguous entry: $source_path"
  read -r mode object_type blob verified_path <<< "$record"
  [[ "$mode" == "$expected_mode" && "$object_type" == "blob" && \
    "$blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_path" == "$source_path" ]] || \
    fail "Controller source is not the expected regular Git blob and mode: $source_path: $expected_mode"
  printf '%s\n' "$blob"
}

verify_controller_tool() {
  local source_path="$1"
  local destination_name="$2"
  local validation="$3"
  local expected_mode="$4"
  local destination="$tool_directory/$destination_name"
  local blob=""

  blob="$(controller_tree_record "$source_path" "$expected_mode")"
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
    json) portable_jq -e 'type == "object"' "$destination" >/dev/null ;;
    controller)
      portable_jq -e '
        type == "object" and
        keys == ["current", "predecessor", "schemaVersion"] and
        .schemaVersion == 1 and
        (.current | type == "object" and
          keys == ["commit", "representativeMinecraftVersions", "tag", "tagObject"] and
          (.tag | type == "string" and test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
          (.commit | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
          (.tagObject | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
          (.representativeMinecraftVersions | type == "array" and length > 0) and
          (.representativeMinecraftVersions | length == (unique | length)) and
          all(.representativeMinecraftVersions[];
            type == "string" and test("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*$"))
        ) and
        (.predecessor | type == "object" and
          keys == ["commit", "tag", "tagObject"] and
          (.tag | type == "string" and test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
          (.commit | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
          (.tagObject | type == "string" and test("^([0-9a-f]{40}|[0-9a-f]{64})$"))
        ) and
        .current.tag != .predecessor.tag and
        .current.commit != .predecessor.commit and
        .current.tagObject != .predecessor.tagObject
      ' "$destination" >/dev/null || fail 'Current-controller metadata schema differs.'
      ;;
    *) fail "Unsupported controller tool validation: $validation" ;;
  esac
}

verify_controller_tool release/current-controller.json current-controller.json controller 100644
verify_controller_tool release/verify-release-tag.sh verify-release-tag.sh bash 100644
verify_controller_tool release/list-release-tags.sh list-release-tags.sh bash 100755
verify_controller_tool release/verify-current-controller-release-order.sh verify-current-controller-release-order.sh bash 100755
verify_controller_tool release/verify-github-tag-ruleset.sh verify-github-tag-ruleset.sh bash 100644
verify_controller_tool release/github-release-tag-ruleset.json github-release-tag-ruleset.json json 100644
verify_controller_tool release/github-release-tag-ruleset-receipt.json github-release-tag-ruleset-receipt.json json 100644
verify_controller_tool release/verify-pages-deployment-source.sh verify-pages-deployment-source.sh bash 100644
verify_controller_tool release/verify-pages-artifact-equivalence.sh verify-pages-artifact-equivalence.sh bash 100644
verify_controller_tool release/wait-for-pages-source-receipt.sh wait-for-pages-source-receipt.sh bash 100644
verify_controller_tool release/run-publish-controller-recovery.sh run-publish-controller-recovery.sh bash 100755
verify_controller_tool gradle/list-java-toolchains.sh list-java-toolchains.sh bash 100755

current_tag="$(portable_jq -er '.current.tag' "$tool_directory/current-controller.json")"
current_commit="$(portable_jq -er '.current.commit' "$tool_directory/current-controller.json")"
current_object="$(portable_jq -er '.current.tagObject' "$tool_directory/current-controller.json")"
predecessor_tag="$(portable_jq -er '.predecessor.tag' "$tool_directory/current-controller.json")"
predecessor_commit="$(portable_jq -er '.predecessor.commit' "$tool_directory/current-controller.json")"
predecessor_object="$(portable_jq -er '.predecessor.tagObject' "$tool_directory/current-controller.json")"

recovery_contracts=()
while IFS= read -r candidate; do
  blob="$(controller_tree_record "$candidate" 100644)"
  if git --no-replace-objects cat-file blob "$blob" | portable_jq -e \
    --arg current_tag "$current_tag" \
    --arg current_commit "$current_commit" \
    --arg current_object "$current_object" \
    --arg predecessor_tag "$predecessor_tag" \
    --arg predecessor_commit "$predecessor_commit" \
    --arg predecessor_object "$predecessor_object" '
      type == "object" and
      .releaseSource.tag == $current_tag and
      .releaseSource.commit == $current_commit and
      .releaseSource.tagObject == $current_object and
      (.baselineReleases | type == "array" and length > 0) and
      .baselineReleases[-1].tag == $predecessor_tag and
      .baselineReleases[-1].commit == $predecessor_commit and
      .baselineReleases[-1].tagObject == $predecessor_object
    ' >/dev/null 2>&1; then
    recovery_contracts+=("$candidate")
  fi
done < <(git --no-replace-objects ls-tree -r --name-only "$controller_commit" -- release | \
  grep --extended-regexp '^release/[^/]+-backlog-recovery\.json$' || true)
[[ "${#recovery_contracts[@]}" -le 1 ]] || fail 'Multiple recovery contracts bind the current-controller identities.'

if [[ "${#recovery_contracts[@]}" == 1 ]]; then
  recovery_contract="${recovery_contracts[0]}"
  recovery_stem="$(basename "$recovery_contract" .json)"
  recovery_runner="release/run-$recovery_stem.sh"
  controller_tree_record "$recovery_runner" 100644 >/dev/null
  recovery_blob="$(controller_tree_record "$recovery_contract" 100644)"
  baseline_tag="$(git --no-replace-objects cat-file blob "$recovery_blob" | portable_jq -er '.baselineReleases[0].tag')"
  baseline_commit="$(git --no-replace-objects cat-file blob "$recovery_blob" | portable_jq -er '.baselineReleases[0].commit')"
  evidence_candidates=()
  while IFS= read -r candidate; do
    blob="$(controller_tree_record "$candidate" 100644)"
    if git --no-replace-objects cat-file blob "$blob" | portable_jq -e \
      --arg tag "$baseline_tag" --arg commit "$baseline_commit" '
        type == "object" and .releaseTag == $tag and .releaseCommit == $commit
      ' >/dev/null 2>&1; then
      evidence_candidates+=("$candidate")
    fi
  done < <(git --no-replace-objects ls-tree -r --name-only "$controller_commit" -- release | \
    grep --extended-regexp '^release/[^/]+-artifacts\.json$' || true)
  [[ "${#evidence_candidates[@]}" == 1 ]] || fail 'Recovery baseline artifact evidence is missing or ambiguous.'
  verify_controller_tool "$recovery_runner" backlog-recovery-runner bash 100644
  verify_controller_tool "$recovery_contract" backlog-recovery.json json 100644
  verify_controller_tool "${evidence_candidates[0]}" backlog-artifact-evidence.json json 100644
else
  for unexpected in backlog-recovery-runner backlog-recovery.json backlog-artifact-evidence.json; do
    [[ ! -e "$tool_directory/$unexpected" && ! -L "$tool_directory/$unexpected" ]] || \
      fail "Recovery material was enabled without an identity-bound contract: $unexpected"
  done
fi

if [[ "$operation" == "materialize" ]]; then
  find "$tool_directory" -mindepth 1 -maxdepth 1 -type f -exec chmod a-w -- {} +
  chmod a-w -- "$tool_directory"
fi

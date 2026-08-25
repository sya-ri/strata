#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

runner="$repository_root/release/run-controller-overlay.sh"
manifest="$repository_root/release/controller-overlays/v0.1.0-modrinth-generic-draft.json"
patch="$repository_root/release/controller-overlays/v0.1.0-modrinth-generic-draft.patch"
base_commit="$(jq -er '.baseCommit' "$manifest")"
base_tag="$(jq -er '.baseTag' "$manifest")"
controller_commit="$(git -C "$repository_root" rev-parse HEAD)"
fixture="$temporary_root/fixture"
runner_temporary="$temporary_root/runner"
mkdir -p "$runner_temporary"

git -c core.autocrlf=false clone --quiet --no-local "$repository_root" "$fixture"
git -C "$fixture" checkout --quiet --detach "$base_commit"
git -C "$fixture" fetch --quiet origin master "$base_tag"

run_overlay() {
  local operation="$1"
  local selected_manifest="${2:-$manifest}"
  local selected_patch="${3:-$patch}"
  local selected_controller="${4:-$controller_commit}"
  (
    cd "$fixture"
    RUNNER_TEMP="$runner_temporary" \
      STRATA_CONTROLLER_OVERLAY_TEST_MODE=1 \
      bash "$runner" "$operation" "$selected_manifest" "$selected_patch" \
        test-project "$base_tag" "$base_commit" "$selected_controller"
  )
}

run_overlay build-logic-test >/dev/null
[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'The successful controller overlay test did not restore a clean tagged tree.'
for cache_path in build-logic/build build-logic/.gradle build-logic/.kotlin; do
  [[ ! -e "$fixture/$cache_path" ]] || fail "The controller overlay test retained generated cache state: $cache_path"
done

task_graph_output="$(run_overlay task-graph-test | tr -d '\r')"
mapfile -t planned_tasks < <(
  printf '%s\n' "$task_graph_output" |
    grep -E '^:[^ ]+ SKIPPED$'
)
[[ "${planned_tasks[*]}" == ':modrinthReleasePreflight SKIPPED :modrinthReleaseStage SKIPPED' ]] || \
  fail 'The controller overlay stage graph contains tasks beyond preflight followed by stage.'
[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'The controller overlay task-graph test did not restore a clean tagged tree.'

git -C "$fixture" update-ref refs/remotes/origin/master "$base_commit"
if run_overlay build-logic-test "$manifest" "$patch" "$base_commit" >/dev/null 2>&1; then
  fail 'A controller overlay was not bound to the reviewed controller source blobs.'
fi
git -C "$fixture" update-ref refs/remotes/origin/master "$controller_commit"

git -C "$fixture" checkout --quiet --detach "$controller_commit"
if run_overlay build-logic-test >/dev/null 2>&1; then
  fail 'A controller overlay ran against the wrong base commit.'
fi
git -C "$fixture" checkout --quiet --detach "$base_commit"

corrupt_patch="$temporary_root/v0.1.0-modrinth-generic-draft.patch"
cp "$patch" "$corrupt_patch"
printf '\n' >> "$corrupt_patch"
if run_overlay build-logic-test "$manifest" "$corrupt_patch" >/dev/null 2>&1; then
  fail 'A controller overlay accepted a patch with the wrong SHA-256.'
fi

extra_manifest="$temporary_root/extra-path.json"
jq '.paths += [{
  path: "README.md",
  baseGitBlob: "0000000000000000000000000000000000000000",
  baseSha256: "0000000000000000000000000000000000000000000000000000000000000000",
  patchedGitBlob: "0000000000000000000000000000000000000000",
  patchedSha256: "0000000000000000000000000000000000000000000000000000000000000000"
}]' "$manifest" > "$extra_manifest"
if run_overlay build-logic-test "$extra_manifest" "$patch" >/dev/null 2>&1; then
  fail 'A controller overlay accepted an expanded path allowlist.'
fi

outside_patch="$temporary_root/outside-allowlist.patch"
cp "$patch" "$outside_patch"
printf '\n' >> "$fixture/README.md"
git -C "$fixture" diff --binary --full-index -- README.md >> "$outside_patch"
git -C "$fixture" restore --source="$base_commit" --worktree -- README.md
outside_manifest="$temporary_root/outside-allowlist.json"
outside_sha256="$(sha256sum "$outside_patch" | cut -d ' ' -f 1)"
jq --arg file "$(basename "$outside_patch")" --arg sha256 "$outside_sha256" \
  '.patchFile = $file | .patchSha256 = $sha256' "$manifest" > "$outside_manifest"
if run_overlay build-logic-test "$outside_manifest" "$outside_patch" >/dev/null 2>&1; then
  fail 'A controller overlay patch changed a path outside its allowlist.'
fi

printf '\n' >> "$fixture/README.md"
if run_overlay build-logic-test >/dev/null 2>&1; then
  fail 'A controller overlay accepted a dirty tagged tree.'
fi
git -C "$fixture" restore --source="$base_commit" --worktree -- README.md

if run_overlay submit >/dev/null 2>&1; then
  fail 'The controller overlay accepted an external operation beyond preflight and stage.'
fi

[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'Controller overlay rejection tests did not leave the tagged tree clean.'

echo 'Controller overlay guards passed.'

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

runner="$repository_root/release/run-central-controller-overlay.sh"
manifest="$repository_root/release/controller-overlays/v0.1.0-central-signature-checksums.json"
patch="$repository_root/release/controller-overlays/v0.1.0-central-signature-checksums.patch"
base_commit="$(jq -er '.baseCommit' "$manifest")"
base_tag="$(jq -er '.baseTag' "$manifest")"
controller_commit="$(git -C "$repository_root" rev-parse HEAD)"
fixture="$temporary_root/fixture"
runner_temporary="$temporary_root/runner"
mkdir -p "$runner_temporary"

for input in "$runner" "$manifest" "$patch"; do
  if LC_ALL=C od -An -t x1 "$input" | grep -Eq '(^|[[:space:]])0d([[:space:]]|$)'; then
    fail "Central controller overlay input does not use LF line endings: $input"
  fi
done
[[ "$(sha256sum "$patch" | cut -d ' ' -f 1)" == "$(jq -er '.patchSha256' "$manifest")" ]] || \
  fail 'The tracked Central overlay patch differs from its manifest.'
mapfile -t tracked_paths < <(jq -er '.paths | map(.path) | .[]' "$manifest" | tr -d '\r')
git -C "$repository_root" diff --binary --full-index "$base_commit" "$controller_commit" -- "${tracked_paths[@]}" |
  cmp --silent - "$patch" || fail 'The Central overlay patch was not generated mechanically from its pinned blobs.'

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
      STRATA_CENTRAL_CONTROLLER_OVERLAY_TEST_MODE=1 \
      bash "$runner" "$operation" "$selected_manifest" "$selected_patch" \
        "$base_tag" "$base_commit" "$selected_controller"
  )
}

run_overlay build-logic-test >/dev/null
[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'The successful Central build-logic overlay test did not restore a clean tagged tree.'
for cache_path in build-logic/build build-logic/.gradle build-logic/.kotlin; do
  [[ ! -e "$fixture/$cache_path" ]] || fail "The Central overlay test retained generated cache state: $cache_path"
done
[[ -z "$(find "$runner_temporary" -mindepth 1 -print -quit)" ]] || \
  fail 'The successful Central overlay test retained its temporary build state.'

mkdir -p "$fixture/build-logic/.gradle"
printf 'preserved-cache\n' > "$fixture/build-logic/.gradle/overlay-marker"
task_graph_output="$(run_overlay task-graph-test | tr -d '\r')"
for expected_task in \
  mavenCentralReleasePreflight \
  mavenCentralReleaseVerify \
  githubReleaseBundle; do
  [[ "$(printf '%s\n' "$task_graph_output" | grep -Ec "^:$expected_task SKIPPED$")" == '1' ]] || \
    fail "The Central overlay task graph does not contain exactly one read-only target: $expected_task"
done
mapfile -t planned_tasks < <(printf '%s\n' "$task_graph_output" | grep -E '^:[^ ]+ SKIPPED$')
if printf '%s\n' "${planned_tasks[@]}" |
  grep -Eiq ':(publishAndReleaseToMavenCentral|publishToMavenCentral|publish[^ ]*ToMavenCentralRepository|drop[^ ]*MavenCentral|releaseRepository|enableAutomatic[^ ]*MavenCentral) SKIPPED$'; then
  fail 'The Central overlay task graph contains a remote Central mutation task.'
fi
[[ "$(cat "$fixture/build-logic/.gradle/overlay-marker")" == 'preserved-cache' ]] || \
  fail 'The Central overlay did not restore the pre-existing build-logic cache.'
[[ ! -e "$fixture/build-logic/build" && ! -e "$fixture/build-logic/.kotlin" ]] || \
  fail 'The Central overlay task-graph test retained generated cache state.'
[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'The Central overlay task-graph test did not restore a clean tagged tree.'
[[ -z "$(find "$runner_temporary" -mindepth 1 -print -quit)" ]] || \
  fail 'The Central task-graph test retained its temporary build state.'

git -C "$fixture" update-ref refs/remotes/origin/master "$base_commit"
if run_overlay build-logic-test "$manifest" "$patch" "$base_commit" >/dev/null 2>&1; then
  fail 'A Central controller overlay was not bound to the reviewed controller source blobs.'
fi
git -C "$fixture" update-ref refs/remotes/origin/master "$controller_commit"

git -C "$fixture" checkout --quiet --detach "$controller_commit"
if run_overlay build-logic-test >/dev/null 2>&1; then
  fail 'A Central controller overlay ran against the wrong base commit.'
fi
git -C "$fixture" checkout --quiet --detach "$base_commit"

corrupt_patch="$temporary_root/v0.1.0-central-signature-checksums.patch"
cp "$patch" "$corrupt_patch"
printf '\n' >> "$corrupt_patch"
if run_overlay build-logic-test "$manifest" "$corrupt_patch" >/dev/null 2>&1; then
  fail 'A Central controller overlay accepted a patch with the wrong SHA-256.'
fi

crlf_manifest="$temporary_root/crlf-central-signature-checksums.json"
awk '{ printf "%s%c\n", $0, 13 }' "$manifest" > "$crlf_manifest"
LC_ALL=C od -An -t x1 "$crlf_manifest" | grep -Eq '(^|[[:space:]])0d([[:space:]]|$)' || \
  fail 'The CRLF rejection fixture does not contain carriage returns.'
if run_overlay build-logic-test "$crlf_manifest" "$patch" >/dev/null 2>&1; then
  fail 'A Central controller overlay accepted a manifest with CRLF line endings.'
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
  fail 'A Central controller overlay accepted an expanded path allowlist.'
fi

expanded_operations_manifest="$temporary_root/expanded-operations.json"
jq '.allowedOperations += ["publish"]' "$manifest" > "$expanded_operations_manifest"
if run_overlay build-logic-test "$expanded_operations_manifest" "$patch" >/dev/null 2>&1; then
  fail 'A Central controller overlay accepted an expanded operation allowlist.'
fi

outside_patch="$temporary_root/outside-central-allowlist.patch"
cp "$patch" "$outside_patch"
printf '\n' >> "$fixture/README.md"
git -C "$fixture" diff --binary --full-index -- README.md >> "$outside_patch"
git -C "$fixture" restore --source="$base_commit" --worktree -- README.md
outside_manifest="$temporary_root/outside-central-allowlist.json"
outside_sha256="$(sha256sum "$outside_patch" | cut -d ' ' -f 1)"
jq --arg file "$(basename "$outside_patch")" --arg sha256 "$outside_sha256" \
  '.patchFile = $file | .patchSha256 = $sha256' "$manifest" > "$outside_manifest"
if run_overlay build-logic-test "$outside_manifest" "$outside_patch" >/dev/null 2>&1; then
  fail 'A Central controller overlay patch changed a path outside its allowlist.'
fi

printf '\n' >> "$fixture/README.md"
if run_overlay build-logic-test >/dev/null 2>&1; then
  fail 'A Central controller overlay accepted a dirty tagged tree.'
fi
git -C "$fixture" restore --source="$base_commit" --worktree -- README.md

for unsupported_operation in portal-preflight portal-verify upload publish drop; do
  if run_overlay "$unsupported_operation" >/dev/null 2>&1; then
    fail "The sealed Central controller overlay accepted an unsupported operation: $unsupported_operation"
  fi
done

if (
  cd "$fixture"
  RUNNER_TEMP="$runner_temporary" \
    bash "$runner" build-logic-test "$manifest" "$patch" \
      "$base_tag" "$base_commit" "$controller_commit"
) >/dev/null 2>&1; then
  fail 'The Central controller overlay exposed a test-only operation without its explicit test mode.'
fi

[[ "$(cat "$fixture/build-logic/.gradle/overlay-marker")" == 'preserved-cache' ]] || \
  fail 'Central overlay rejection tests changed the preserved build-logic cache.'
[[ -z "$(git -C "$fixture" status --porcelain --untracked-files=all)" ]] || \
  fail 'Central overlay rejection tests did not leave the tagged tree clean.'

echo 'Central controller overlay guards passed.'

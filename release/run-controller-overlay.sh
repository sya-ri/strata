#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "$1" >&2
  exit 1
}

operation="${1:-}"
manifest_file="${2:-}"
patch_file="${3:-}"
project_id="${4:-}"
release_tag="${5:-}"
source_commit="${6:-}"
controller_commit="${7:-}"

[[ -n "$operation" && -n "$manifest_file" && -n "$patch_file" && -n "$project_id" ]] || \
  fail 'Usage: run-controller-overlay.sh <operation> <manifest> <patch> <project-id> <tag> <source-commit> <controller-commit>'
[[ -n "$release_tag" && -n "$source_commit" && -n "$controller_commit" ]] || \
  fail 'The release tag, source commit, and controller commit are required.'
[[ -f "$manifest_file" && -f "$patch_file" ]] || fail 'The controller overlay manifest and patch must be regular files.'

repository_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
[[ "$(pwd -P)" == "$repository_root" ]] || fail 'The controller overlay must run from the release repository root.'

expected_paths=(
  build-logic/src/main/kotlin/dev/s7a/strata/gradle/release/ModrinthApiClient.kt
  build-logic/src/main/kotlin/dev/s7a/strata/gradle/release/ModrinthReleaseCoordinator.kt
)
cache_paths=(
  build-logic/build
  build-logic/.gradle
  build-logic/.kotlin
)

[[ "$(jq -er '.schemaVersion' "$manifest_file")" == '1' ]] || fail 'Unsupported controller overlay manifest schema.'
base_tag="$(jq -er '.baseTag' "$manifest_file")"
base_commit="$(jq -er '.baseCommit' "$manifest_file")"
reviewed_controller_commit="$(jq -er '.controllerCommit' "$manifest_file")"
expected_patch_name="$(jq -er '.patchFile' "$manifest_file")"
expected_patch_sha256="$(jq -er '.patchSha256' "$manifest_file")"
[[ "$base_tag" == 'v0.1.0' ]] || fail 'The controller overlay is not pinned to v0.1.0.'
[[ "$base_commit" =~ ^[0-9a-f]{40}$ && "$reviewed_controller_commit" =~ ^[0-9a-f]{40}$ && \
  "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || \
  fail 'Controller overlay commits must be full lowercase SHA-1 values.'
[[ "$release_tag" == "$base_tag" && "$source_commit" == "$base_commit" ]] || \
  fail 'The requested release source differs from the controller overlay base.'
[[ "$(basename "$patch_file")" == "$expected_patch_name" ]] || fail 'The controller overlay patch filename differs from its manifest.'
[[ "$(sha256sum "$patch_file" | cut -d ' ' -f 1)" == "$expected_patch_sha256" ]] || \
  fail 'The controller overlay patch SHA-256 differs from its manifest.'

mapfile -t manifest_paths < <(jq -er '.paths | map(.path) | .[]' "$manifest_file" | tr -d '\r')
[[ "${#manifest_paths[@]}" == "${#expected_paths[@]}" ]] || fail 'The controller overlay path count differs.'
for index in "${!expected_paths[@]}"; do
  [[ "${manifest_paths[$index]}" == "${expected_paths[$index]}" ]] || fail 'The controller overlay path allowlist differs.'
done
[[ "$(jq -c '.allowedOperations' "$manifest_file")" == '["preflight","stage"]' ]] || \
  fail 'The controller overlay release operation allowlist differs.'

release_operation=0
modrinth_task_graph=0
dry_run=0
case "$operation" in
  preflight)
    gradle_task=modrinthReleasePreflight
    release_operation=1
    modrinth_task_graph=1
    ;;
  stage)
    gradle_task=modrinthReleaseStage
    release_operation=1
    modrinth_task_graph=1
    ;;
  build-logic-test)
    [[ "${STRATA_CONTROLLER_OVERLAY_TEST_MODE:-}" == '1' ]] || fail 'The build-logic overlay test is test-only.'
    gradle_task=:build-logic:test
    ;;
  task-graph-test)
    [[ "${STRATA_CONTROLLER_OVERLAY_TEST_MODE:-}" == '1' ]] || fail 'The overlay task-graph test is test-only.'
    gradle_task=modrinthReleaseStage
    modrinth_task_graph=1
    dry_run=1
    ;;
  *)
    fail "Unsupported controller overlay operation: $operation"
    ;;
esac

[[ "$(git rev-parse HEAD)" == "$base_commit" ]] || fail 'The controller overlay base is not the checked-out product source.'
[[ "$(git rev-parse "refs/tags/$base_tag^{commit}")" == "$base_commit" ]] || fail 'The controller overlay tag does not resolve to its base commit.'
[[ "$(git rev-parse origin/master)" == "$controller_commit" ]] || \
  fail 'The active controller commit is not the current origin/master.'
git merge-base --is-ancestor "$base_commit" "$reviewed_controller_commit" || \
  fail 'The reviewed controller overlay does not descend from the product source.'
git merge-base --is-ancestor "$reviewed_controller_commit" "$controller_commit" || \
  fail 'The reviewed controller commit is not an ancestor of the current origin/master.'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail 'The product source must be clean before applying a controller overlay.'
git diff --cached --quiet -- . || fail 'The product index changed before applying a controller overlay.'

mapfile -t patch_paths < <(git apply --numstat "$patch_file" | awk '{print $3}' | tr -d '\r')
[[ "${#patch_paths[@]}" == "${#expected_paths[@]}" ]] || fail 'The controller overlay patch path count differs.'
for index in "${!expected_paths[@]}"; do
  [[ "${patch_paths[$index]}" == "${expected_paths[$index]}" ]] || fail 'The controller overlay patch changes a path outside its allowlist.'
done
git apply --check "$patch_file"

for index in "${!expected_paths[@]}"; do
  path="${expected_paths[$index]}"
  [[ -f "$path" && ! -L "$path" ]] || fail "Controller overlay input is not a regular file: $path"
  expected_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .baseGitBlob' "$manifest_file")"
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .baseSha256' "$manifest_file")"
  expected_patched_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedGitBlob' "$manifest_file")"
  expected_patched_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$(git hash-object "$path")" == "$expected_blob" ]] || fail "Controller overlay base Git blob differs: $path"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || fail "Controller overlay base SHA-256 differs: $path"
  [[ "$(git rev-parse "$reviewed_controller_commit:$path")" == "$expected_patched_blob" ]] || \
    fail "Controller source does not contain the reviewed overlay Git blob: $path"
  [[ "$(git show "$reviewed_controller_commit:$path" | sha256sum | cut -d ' ' -f 1)" == "$expected_patched_sha256" ]] || \
    fail "Controller source does not contain the reviewed overlay SHA-256: $path"
done

runner_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
[[ -d "$runner_parent" ]] || fail 'The controller overlay temporary parent does not exist.'
overlay_temporary="$(mktemp -d "$runner_parent/strata-controller-overlay.XXXXXX")"
cache_present=()

validate_cache_path() {
  local relative_path="$1"
  local resolved_root
  local resolved_path
  resolved_root="$(realpath -m "$repository_root/build-logic")"
  resolved_path="$(realpath -m "$repository_root/$relative_path")"
  [[ "$resolved_path" == "$resolved_root/"* ]] || fail "Controller overlay cache path escaped build-logic: $relative_path"
}

cleanup() {
  local status=$?
  local cleanup_failed=0
  trap - EXIT INT TERM
  set +e
  git restore --source="$base_commit" --worktree -- "${expected_paths[@]}" || cleanup_failed=1
  for index in "${!cache_paths[@]}"; do
    relative_path="${cache_paths[$index]}"
    validate_cache_path "$relative_path"
    rm -rf -- "$repository_root/$relative_path" || cleanup_failed=1
    if [[ "${cache_present[$index]:-0}" == '1' ]]; then
      mv "$overlay_temporary/cache-$index" "$repository_root/$relative_path" || cleanup_failed=1
    fi
  done
  if ! git diff --quiet -- . || ! git diff --cached --quiet -- . || [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
    echo 'The controller overlay did not restore a clean product source.' >&2
    status=1
  fi
  rm -rf -- "$overlay_temporary" || cleanup_failed=1
  if [[ "$cleanup_failed" != '0' ]]; then
    echo 'The controller overlay could not restore its isolated build state.' >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for index in "${!cache_paths[@]}"; do
  relative_path="${cache_paths[$index]}"
  validate_cache_path "$relative_path"
  cache_present[$index]=0
  if [[ -e "$repository_root/$relative_path" ]]; then
    mv "$repository_root/$relative_path" "$overlay_temporary/cache-$index"
    cache_present[$index]=1
  fi
done

release_manifest=build/release/modrinth/manifest.json
artifact_receipt_before="$overlay_temporary/artifacts-before.sha256"
release_manifest_sha256=''
if [[ "$release_operation" == '1' ]]; then
  [[ -f "$release_manifest" ]] || fail 'The immutable Modrinth release manifest is missing.'
  [[ "$(jq -er '.projectId' "$release_manifest")" == "$project_id" ]] || fail 'The Modrinth release manifest project ID differs.'
  [[ "$(jq -er '.artifacts | length' "$release_manifest")" == '20' ]] || fail 'The Modrinth release manifest must contain twenty artifacts.'
  release_manifest_sha256="$(sha256sum "$release_manifest" | cut -d ' ' -f 1)"
  while IFS=$'\t' read -r relative_path expected_sha256; do
    [[ "$relative_path" =~ ^artifacts/[^/]+\.jar$ ]] || fail "Unsafe Modrinth artifact path: $relative_path"
    artifact_path="$repository_root/build/release/modrinth/$relative_path"
    resolved_artifact="$(realpath "$artifact_path")"
    [[ "$resolved_artifact" == "$repository_root/build/release/modrinth/artifacts/"* ]] || \
      fail "Modrinth artifact escaped its bundle: $relative_path"
    actual_sha256="$(sha256sum "$artifact_path" | cut -d ' ' -f 1)"
    [[ "$actual_sha256" == "$expected_sha256" ]] || fail "Modrinth artifact differs from its manifest: $relative_path"
    printf '%s  %s\n' "$actual_sha256" "$relative_path" >> "$artifact_receipt_before"
  done < <(jq -er '.artifacts[] | [.relativePath, .sha256] | @tsv' "$release_manifest" | tr -d '\r')
  [[ "$(wc -l < "$artifact_receipt_before")" == '20' ]] || fail 'The Modrinth artifact receipt must contain twenty entries.'
fi

git apply "$patch_file"
mapfile -t changed_paths < <(git diff --name-only -- . | tr -d '\r')
[[ "${#changed_paths[@]}" == "${#expected_paths[@]}" ]] || fail 'The applied controller overlay changed an unexpected path count.'
for index in "${!expected_paths[@]}"; do
  [[ "${changed_paths[$index]}" == "${expected_paths[$index]}" ]] || fail 'The applied controller overlay changed a path outside its allowlist.'
  path="${expected_paths[$index]}"
  expected_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedGitBlob' "$manifest_file")"
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$(git hash-object "$path")" == "$expected_blob" ]] || fail "Controller overlay patched Git blob differs: $path"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || fail "Controller overlay patched SHA-256 differs: $path"
done
git diff --cached --quiet -- . || fail 'The controller overlay changed the product index.'

gradle_arguments=(
  --no-daemon
  --no-parallel
  --max-workers=2
  --no-build-cache
  --project-cache-dir "$overlay_temporary/project-cache"
  "$gradle_task"
)
if [[ "$dry_run" == '1' ]]; then
  gradle_arguments+=(--dry-run)
fi
if [[ "$modrinth_task_graph" == '1' ]]; then
  gradle_arguments+=(
    -x modrinthReleaseManifest
    -x verifyPublishedConsumer
    "-Pstrata.sourceRevision=$release_tag"
    "-Pstrata.sourceCommit=$source_commit"
    "-Pstrata.modrinthProjectId=$project_id"
  )
fi
if [[ "$release_operation" == '1' ]]; then
  [[ -n "${MODRINTH_TOKEN:-}" ]] || fail 'MODRINTH_TOKEN is required for a release overlay operation.'
fi
bash ./gradlew "${gradle_arguments[@]}"

mapfile -t changed_paths < <(git diff --name-only -- . | tr -d '\r')
[[ "${changed_paths[*]}" == "${expected_paths[*]}" ]] || fail 'The controller overlay task changed tracked product source.'
for path in "${expected_paths[@]}"; do
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || fail "The controller overlay task changed its source: $path"
done
git diff --cached --quiet -- . || fail 'The controller overlay task changed the product index.'

if [[ "$release_operation" == '1' ]]; then
  [[ "$(sha256sum "$release_manifest" | cut -d ' ' -f 1)" == "$release_manifest_sha256" ]] || \
    fail 'The controller overlay task changed the immutable Modrinth release manifest.'
  artifact_receipt_after="$overlay_temporary/artifacts-after.sha256"
  while IFS=$'\t' read -r relative_path expected_sha256; do
    actual_sha256="$(sha256sum "$repository_root/build/release/modrinth/$relative_path" | cut -d ' ' -f 1)"
    [[ "$actual_sha256" == "$expected_sha256" ]] || fail "The controller overlay task changed an artifact: $relative_path"
    printf '%s  %s\n' "$actual_sha256" "$relative_path" >> "$artifact_receipt_after"
  done < <(jq -er '.artifacts[] | [.relativePath, .sha256] | @tsv' "$release_manifest" | tr -d '\r')
  cmp --silent "$artifact_receipt_before" "$artifact_receipt_after" || fail 'The controller overlay changed the canonical artifact receipt.'
fi

echo "Controller overlay $expected_patch_sha256 completed $operation without changing tagged product artifacts."

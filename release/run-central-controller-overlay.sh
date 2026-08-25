#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "$1" >&2
  exit 1
}

operation="${1:-}"
manifest_file="${2:-}"
patch_file="${3:-}"
release_tag="${4:-}"
source_commit="${5:-}"
controller_commit="${6:-}"

[[ -n "$operation" && -n "$manifest_file" && -n "$patch_file" ]] || \
  fail 'Usage: run-central-controller-overlay.sh <operation> <manifest> <patch> <tag> <source-commit> <controller-commit>'
[[ -n "$release_tag" && -n "$source_commit" && -n "$controller_commit" ]] || \
  fail 'The release tag, source commit, and controller commit are required.'
[[ -f "$manifest_file" && ! -L "$manifest_file" && -f "$patch_file" && ! -L "$patch_file" ]] || \
  fail 'The Central controller overlay manifest and patch must be regular non-symlink files.'

repository_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
[[ "$(pwd -P)" == "$repository_root" ]] || fail 'The Central controller overlay must run from the release repository root.'

expected_paths=(
  build-logic/src/main/kotlin/dev/s7a/strata/gradle/release/MavenCentralReleaseVerifier.kt
  build-logic/src/test/kotlin/dev/s7a/strata/gradle/release/MavenCentralReleaseVerifierTest.kt
)
cache_paths=(
  build-logic/build
  build-logic/.gradle
  build-logic/.kotlin
)

contains_carriage_return() {
  LC_ALL=C od -An -t x1 "$1" | grep -Eq '(^|[[:space:]])0d([[:space:]]|$)'
}

if contains_carriage_return "$manifest_file" || contains_carriage_return "$patch_file"; then
  fail 'The Central controller overlay inputs must use LF line endings.'
fi
[[ "$(jq -er '.schemaVersion' "$manifest_file")" == '1' ]] || fail 'Unsupported Central controller overlay manifest schema.'
[[ "$(jq -er '.overlayId' "$manifest_file")" == 'v0.1.0-central-signature-checksums' ]] || \
  fail 'The Central controller overlay identity differs.'
base_tag="$(jq -er '.baseTag' "$manifest_file")"
base_commit="$(jq -er '.baseCommit' "$manifest_file")"
expected_patch_name="$(jq -er '.patchFile' "$manifest_file")"
expected_patch_sha256="$(jq -er '.patchSha256' "$manifest_file")"
[[ "$base_tag" == 'v0.1.0' ]] || fail 'The Central controller overlay is not pinned to v0.1.0.'
[[ "$base_commit" == 'd0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8' ]] || \
  fail 'The Central controller overlay base commit differs.'
[[ "$base_commit" =~ ^[0-9a-f]{40}$ && "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || \
  fail 'Central controller overlay commits must be full lowercase SHA-1 values.'
[[ "$release_tag" == "$base_tag" && "$source_commit" == "$base_commit" ]] || \
  fail 'The requested release source differs from the Central controller overlay base.'
[[ "$(basename "$patch_file")" == "$expected_patch_name" ]] || \
  fail 'The Central controller overlay patch filename differs from its manifest.'
[[ "$expected_patch_sha256" =~ ^[0-9a-f]{64}$ ]] || fail 'The Central controller overlay patch SHA-256 is malformed.'
[[ "$(sha256sum "$patch_file" | cut -d ' ' -f 1)" == "$expected_patch_sha256" ]] || \
  fail 'The Central controller overlay patch SHA-256 differs from its manifest.'

mapfile -t manifest_paths < <(jq -er '.paths | map(.path) | .[]' "$manifest_file" | tr -d '\r')
[[ "${#manifest_paths[@]}" == "${#expected_paths[@]}" ]] || fail 'The Central controller overlay path count differs.'
for index in "${!expected_paths[@]}"; do
  [[ "${manifest_paths[$index]}" == "${expected_paths[$index]}" ]] || \
    fail 'The Central controller overlay path allowlist differs.'
done
[[ "$(jq -c '.allowedOperations' "$manifest_file")" == \
  '["release-preflight","release-verify"]' ]] || \
  fail 'The Central controller overlay operation allowlist differs.'

public_operation=0
dry_run=0
gradle_tasks=()
gradle_extra_arguments=()
case "$operation" in
  release-preflight)
    gradle_tasks=(mavenCentralReleasePreflight)
    public_operation=1
    ;;
  release-verify)
    gradle_tasks=(mavenCentralReleaseVerify githubReleaseBundle)
    public_operation=1
    ;;
  build-logic-test)
    [[ "${STRATA_CENTRAL_CONTROLLER_OVERLAY_TEST_MODE:-}" == '1' ]] || \
      fail 'The Central build-logic overlay test is test-only.'
    gradle_tasks=(:build-logic:test)
    gradle_extra_arguments=(--tests dev.s7a.strata.gradle.release.MavenCentralReleaseVerifierTest)
    ;;
  task-graph-test)
    [[ "${STRATA_CENTRAL_CONTROLLER_OVERLAY_TEST_MODE:-}" == '1' ]] || \
      fail 'The Central overlay task-graph test is test-only.'
    gradle_tasks=(
      :mavenCentralReleasePreflight
      :mavenCentralReleaseVerify
      :githubReleaseBundle
    )
    dry_run=1
    ;;
  *)
    fail "Unsupported Central controller overlay operation: $operation"
    ;;
esac

if [[ "$public_operation" == '1' ]]; then
  jq -e --arg operation "$operation" '.allowedOperations | index($operation) != null' "$manifest_file" >/dev/null || \
    fail 'The requested operation is not allowed by the Central controller overlay manifest.'
fi

[[ "$(git rev-parse HEAD)" == "$base_commit" ]] || \
  fail 'The Central controller overlay base is not the checked-out product source.'
[[ "$(git rev-parse "refs/tags/$base_tag^{commit}")" == "$base_commit" ]] || \
  fail 'The Central controller overlay tag does not resolve to its base commit.'
[[ "$(git rev-parse origin/master)" == "$controller_commit" ]] || \
  fail 'The Central controller overlay commit is not the current origin/master.'
git merge-base --is-ancestor "$base_commit" "$controller_commit" || \
  fail 'The Central controller overlay does not descend from the product source.'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || \
  fail 'The product source must be clean before applying a Central controller overlay.'
git diff --cached --quiet -- . || fail 'The product index changed before applying a Central controller overlay.'

mapfile -t patch_paths < <(git apply --numstat "$patch_file" | awk '{print $3}' | tr -d '\r')
[[ "${#patch_paths[@]}" == "${#expected_paths[@]}" ]] || \
  fail 'The Central controller overlay patch path count differs.'
for index in "${!expected_paths[@]}"; do
  [[ "${patch_paths[$index]}" == "${expected_paths[$index]}" ]] || \
    fail 'The Central controller overlay patch changes a path outside its allowlist.'
done
git apply --check "$patch_file"

for path in "${expected_paths[@]}"; do
  [[ -f "$path" && ! -L "$path" ]] || fail "Central controller overlay input is not a regular file: $path"
  expected_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .baseGitBlob' "$manifest_file")"
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .baseSha256' "$manifest_file")"
  expected_patched_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedGitBlob' "$manifest_file")"
  expected_patched_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$expected_blob" =~ ^[0-9a-f]{40}$ && "$expected_patched_blob" =~ ^[0-9a-f]{40}$ ]] || \
    fail "Central controller overlay Git blob is malformed: $path"
  [[ "$expected_sha256" =~ ^[0-9a-f]{64}$ && "$expected_patched_sha256" =~ ^[0-9a-f]{64}$ ]] || \
    fail "Central controller overlay SHA-256 is malformed: $path"
  [[ "$(git hash-object "$path")" == "$expected_blob" ]] || fail "Central controller overlay base Git blob differs: $path"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || \
    fail "Central controller overlay base SHA-256 differs: $path"
  [[ "$(git rev-parse "$controller_commit:$path")" == "$expected_patched_blob" ]] || \
    fail "Controller source does not contain the reviewed Central overlay Git blob: $path"
  [[ "$(git show "$controller_commit:$path" | sha256sum | cut -d ' ' -f 1)" == "$expected_patched_sha256" ]] || \
    fail "Controller source does not contain the reviewed Central overlay SHA-256: $path"
done

runner_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
[[ -d "$runner_parent" && ! -L "$runner_parent" ]] || \
  fail 'The Central controller overlay temporary parent must be a regular directory.'
resolved_runner_parent="$(realpath "$runner_parent")"
case "$resolved_runner_parent" in
  "$repository_root"|"$repository_root"/*)
    fail 'The Central controller overlay temporary parent must be outside the product source.'
    ;;
esac
resolved_build_logic="$(realpath -m "$repository_root/build-logic")"
for relative_path in "${cache_paths[@]}"; do
  resolved_cache_path="$(realpath -m "$repository_root/$relative_path")"
  [[ "$resolved_cache_path" == "$resolved_build_logic/"* ]] || \
    fail "Central controller overlay cache path escaped build-logic: $relative_path"
  if [[ -e "$repository_root/$relative_path" ]]; then
    [[ -d "$repository_root/$relative_path" && ! -L "$repository_root/$relative_path" ]] || \
    fail "Central controller overlay cache path is not a regular directory: $relative_path"
  fi
done
overlay_temporary="$(mktemp -d "$resolved_runner_parent/strata-central-controller-overlay.XXXXXX")"
[[ -d "$overlay_temporary" && ! -L "$overlay_temporary" ]] || fail 'The Central controller overlay temporary directory is unsafe.'
cache_present=(0 0 0)

cleanup() {
  local status=$?
  local cleanup_failed=0
  trap - EXIT INT TERM
  set +e
  git restore --source="$base_commit" --worktree -- "${expected_paths[@]}" || cleanup_failed=1
  for index in "${!cache_paths[@]}"; do
    relative_path="${cache_paths[$index]}"
    rm -rf -- "$repository_root/$relative_path" || cleanup_failed=1
    if [[ "${cache_present[$index]}" == '1' ]]; then
      mv "$overlay_temporary/cache-$index" "$repository_root/$relative_path" || cleanup_failed=1
    fi
  done
  if ! git diff --quiet -- . || ! git diff --cached --quiet -- . || [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
    echo 'The Central controller overlay did not restore a clean product source.' >&2
    status=1
  fi
  rm -rf -- "$overlay_temporary" || cleanup_failed=1
  if [[ "$cleanup_failed" != '0' ]]; then
    echo 'The Central controller overlay could not restore its isolated build state.' >&2
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for index in "${!cache_paths[@]}"; do
  relative_path="${cache_paths[$index]}"
  if [[ -e "$repository_root/$relative_path" ]]; then
    mv "$repository_root/$relative_path" "$overlay_temporary/cache-$index"
    cache_present[$index]=1
  fi
done

git apply "$patch_file"
mapfile -t changed_paths < <(git diff --name-only -- . | tr -d '\r')
[[ "${#changed_paths[@]}" == "${#expected_paths[@]}" ]] || \
  fail 'The applied Central controller overlay changed an unexpected path count.'
for index in "${!expected_paths[@]}"; do
  [[ "${changed_paths[$index]}" == "${expected_paths[$index]}" ]] || \
    fail 'The applied Central controller overlay changed a path outside its allowlist.'
  path="${expected_paths[$index]}"
  expected_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedGitBlob' "$manifest_file")"
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$(git hash-object "$path")" == "$expected_blob" ]] || fail "Central controller overlay patched Git blob differs: $path"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || \
    fail "Central controller overlay patched SHA-256 differs: $path"
done
git diff --cached --quiet -- . || fail 'The Central controller overlay changed the product index.'

gradle_arguments=(
  --no-daemon
  --no-parallel
  --max-workers=2
  --no-build-cache
  --project-cache-dir "$overlay_temporary/project-cache"
  "${gradle_tasks[@]}"
  "${gradle_extra_arguments[@]}"
)
if [[ "$dry_run" == '1' ]]; then
  gradle_arguments+=(
    --dry-run
    --configure-on-demand
    -x verifyPublishedConsumer
    -x modrinthReleaseManifest
  )
fi
if [[ "$public_operation" == '1' || "$operation" == 'task-graph-test' ]]; then
  gradle_arguments+=(
    "-Pstrata.sourceRevision=$release_tag"
    "-Pstrata.sourceCommit=$source_commit"
  )
fi
bash ./gradlew "${gradle_arguments[@]}"

if [[ "$public_operation" == '1' ]]; then
  case "$operation" in
    release-preflight)
      central_receipt=build/release/maven-central/preflight.json
      ;;
    release-verify)
      central_receipt=build/release/maven-central/verify.json
      ;;
  esac
  [[ -f "$central_receipt" && ! -L "$central_receipt" ]] || \
    fail "The sealed Central operation did not produce its receipt: $central_receipt"
  jq -e \
    '.state == "exact" and .coordinateCount == 24 and .verifiedFileCount == 240 and .verifiedChecksumCount == 480' \
    "$central_receipt" >/dev/null || fail 'The sealed Central operation did not prove the exact public v0.1.0 release.'
fi

mapfile -t changed_paths < <(git diff --name-only -- . | tr -d '\r')
[[ "${changed_paths[*]}" == "${expected_paths[*]}" ]] || \
  fail 'The Central controller overlay task changed tracked product source.'
for path in "${expected_paths[@]}"; do
  expected_blob="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedGitBlob' "$manifest_file")"
  expected_sha256="$(jq -er --arg path "$path" '.paths[] | select(.path == $path) | .patchedSha256' "$manifest_file")"
  [[ "$(git hash-object "$path")" == "$expected_blob" ]] || fail "The Central controller overlay task changed its Git blob: $path"
  [[ "$(sha256sum "$path" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || \
    fail "The Central controller overlay task changed its source: $path"
done
git diff --cached --quiet -- . || fail 'The Central controller overlay task changed the product index.'

echo "Central controller overlay $expected_patch_sha256 completed $operation without changing tagged product source or Central remote state."

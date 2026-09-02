#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "$1" >&2
  exit 1
}

operation="${1:-}"
contract_file="${2:-}"
project_id="${3:-}"
release_tag="${4:-}"
source_commit="${5:-}"
controller_commit="${6:-}"
expected_pages_record="${7:-}"

[[ -n "$operation" && -n "$contract_file" && -n "$project_id" && -n "$release_tag" ]] || \
  fail 'Usage: run-modrinth-v0.1.3-backlog-recovery.sh <operation> <contract> <project-id> <tag> <source-commit> <controller-commit> <pages-record>'
[[ -n "$source_commit" && -n "$controller_commit" && -n "$expected_pages_record" ]] || \
  fail 'Exact source, controller, and Pages evidence are required.'
[[ -f "$contract_file" && ! -L "$contract_file" ]] || fail 'The Modrinth backlog contract must be a regular file.'
[[ -n "${MODRINTH_TOKEN:-}" ]] || fail 'MODRINTH_TOKEN is required for authenticated Modrinth reconciliation.'

repository_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
[[ "$(pwd -P)" == "$repository_root" ]] || fail 'The Modrinth backlog runner must execute from the release repository root.'
runner_path="$(realpath "${BASH_SOURCE[0]}")"
controller_tool_directory="$(dirname "$runner_path")"
contract_resolved="$(realpath "$contract_file")"
[[ "$runner_path" == "$controller_tool_directory/run-modrinth-v0.1.3-backlog-recovery.sh" && \
  "$contract_resolved" == "$controller_tool_directory/modrinth-v0.1.3-backlog-recovery.json" ]] || \
  fail 'The Modrinth backlog runner and contract are not the same immutable controller bundle.'
artifact_evidence="$controller_tool_directory/modrinth-v0.1.0-artifacts.json"
[[ -f "$artifact_evidence" && ! -L "$artifact_evidence" && \
  "$(realpath "$artifact_evidence")" == "$controller_tool_directory/modrinth-v0.1.0-artifacts.json" ]] || \
  fail 'The immutable v0.1.0 public artifact evidence is missing from the controller bundle.'
[[ "$source_commit" =~ ^[0-9a-f]{40}$ && "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || \
  fail 'Release controller commits must be full lowercase SHA-1 values.'

expected_source_commit='86bfe6a0d7a229b107538101b7ef4abee10e7fae'
expected_source_object='7bb84df1a4ae40be6699a866411e4c66b3bfe0bd'
expected_source_body_blob='a64da014ecc6befe6924d1210cb1039a51ea90ee'
backlog_body_sha256='d036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33'
predecessor_body_sha256='f214e060534509ae59024a4bd9295d39a07c29e7f1665d2a9e943a90fe7f34f9'
current_body_sha256='372543c21994b6a92637355817688ac32551405bcb07924edfa7e5dbb1434d9b'

jq -e \
  --arg backlog "$backlog_body_sha256" \
  --arg predecessor "$predecessor_body_sha256" \
  --arg current "$current_body_sha256" '
    .schemaVersion == 2 and
    (keys | sort) == [
      "allowedCompletionStatuses",
      "allowedOperations",
      "allowedProjectStatuses",
      "baselineReleases",
      "bodyLineage",
      "processingRequestedStatus",
      "projectId",
      "releaseSource",
      "schemaVersion",
      "slug"
    ] and
    .projectId == "CAdZ3jVr" and
    .slug == "strata-ui" and
    .releaseSource == {
      tag: "v0.1.3",
      commit: "86bfe6a0d7a229b107538101b7ef4abee10e7fae",
      tagObject: "7bb84df1a4ae40be6699a866411e4c66b3bfe0bd",
      bodyBlob: "a64da014ecc6befe6924d1210cb1039a51ea90ee",
      releaseVersion: "0.1.3",
      artifactCount: 21,
      manifestCanonicalSha256: "6087d67cf83f316e125ef8f2af538bb1fd30e709b33325271db2e6cbb4d82fdc"
    } and
    .bodyLineage == {
      backlogSha256: $backlog,
      predecessorSha256: $predecessor,
      currentSha256: $current
    } and
    .allowedOperations == ["preflight", "stage", "observe"] and
    .allowedProjectStatuses == ["processing", "approved"] and
    .allowedCompletionStatuses == ["processing", "approved"] and
    .processingRequestedStatus == "approved" and
    (.baselineReleases | length) == 3 and
    (.baselineReleases[0] | keys | sort) == [
      "artifactEvidenceCanonicalSha256", "bodyBlob", "bodySha256", "commit", "gameVersions", "manifestCanonicalSha256", "releaseVersion", "tag", "tagObject"
    ] and
    .baselineReleases[0].tag == "v0.1.0" and
    .baselineReleases[0].commit == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" and
    .baselineReleases[0].tagObject == "ccf221fe7f133fe5598fafc4ad01e6bc69ba2230" and
    .baselineReleases[0].bodyBlob == "6b68a2e42278c0e3c389137df25c2ac9d6637d0a" and
    .baselineReleases[0].releaseVersion == "0.1.0" and
    .baselineReleases[0].bodySha256 == $backlog and
    .baselineReleases[0].artifactEvidenceCanonicalSha256 == "29cda6910f37d01c98f2fc47412868b493cddbe5a3ff70869222757045d83b3a" and
    .baselineReleases[0].manifestCanonicalSha256 == "0392b28deb9e7838dbe9b6a25e9fd49cac510e4045bb1ebcff7682a4f7e83e08" and
    .baselineReleases[0].gameVersions == [
      "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
      "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
      "26.1", "26.2"
    ] and
    (.baselineReleases[1] | keys | sort) == [
      "bodyBlob", "bodySha256", "commit", "gameVersions", "manifestCanonicalSha256", "releaseVersion", "tag", "tagObject"
    ] and
    .baselineReleases[1].tag == "v0.1.1" and
    .baselineReleases[1].commit == "6e35f5984bbca06c18f8ca8080f45a70b09831bb" and
    .baselineReleases[1].tagObject == "013bb6b0c4835229402f5843b967151f5dfdc5b2" and
    .baselineReleases[1].bodyBlob == "b6b2229e2b2bc679d12b6b43137fa73e80147293" and
    .baselineReleases[1].releaseVersion == "0.1.1" and
    .baselineReleases[1].bodySha256 == "3e79db82a612df73b387d1bc5a4b8b1f495ff0ae9042d1a9bae7a01da14bda1b" and
    .baselineReleases[1].manifestCanonicalSha256 == "ba15a712879d2fd1191a34015262d236a7f28169bf9946af4b52534d98cd413c" and
    .baselineReleases[1].gameVersions == [
      "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
      "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
      "26.1", "26.2"
    ] and
    (.baselineReleases[2] | keys | sort) == [
      "bodyBlob", "bodySha256", "commit", "gameVersions", "manifestCanonicalSha256", "releaseVersion", "tag", "tagObject"
    ] and
    .baselineReleases[2].tag == "v0.1.2" and
    .baselineReleases[2].commit == "b541fc5492b798b6805c0c4d24e09f43ceff938a" and
    .baselineReleases[2].tagObject == "49195293b3e163abd0beefc9fc8e61a428b8eb24" and
    .baselineReleases[2].bodyBlob == "075d941895ed9b70bcb42d9814948fb1bdc4a05d" and
    .baselineReleases[2].releaseVersion == "0.1.2" and
    .baselineReleases[2].bodySha256 == $predecessor and
    .baselineReleases[2].manifestCanonicalSha256 == "5a26f471445f2e53d5c24e6431bf902d5d659e449a9d295d3c199fa132a757c6" and
    .baselineReleases[2].gameVersions == .baselineReleases[1].gameVersions
  ' "$contract_file" >/dev/null || fail 'The Modrinth backlog recovery contract differs from the reviewed v0.1.3 contract.'
expected_artifact_evidence_sha256="$(jq -er '.baselineReleases[0].artifactEvidenceCanonicalSha256' "$contract_file")"
[[ "$(jq -cS . "$artifact_evidence" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" == "$expected_artifact_evidence_sha256" ]] || \
  fail 'The immutable v0.1.0 public artifact evidence differs from its pinned canonical hash.'
jq -e '
    .schemaVersion == 1 and
    (keys | sort) == ["artifacts", "githubReleaseUrl", "releaseCommit", "releaseTag", "schemaVersion"] and
    .releaseTag == "v0.1.0" and
    .releaseCommit == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" and
    .githubReleaseUrl == "https://github.com/sya-ri/strata/releases/tag/v0.1.0" and
    (.artifacts | length) == 20 and
    (.artifacts | map(.gameVersion)) == [
      "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
      "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
      "26.1", "26.2"
    ] and
    ([.artifacts[].versionNumber] | length == (unique | length)) and
    ([.artifacts[].fileName] | length == (unique | length)) and
    all(.artifacts[];
      (keys | sort) == ["fileName", "gameVersion", "sha256", "sha512", "size", "versionNumber"] and
      .versionNumber == ("0.1.0+mc" + .gameVersion) and
      .fileName == ("strata-runtime-minecraft-fabric-" + .gameVersion + "-0.1.0.jar") and
      (.size | type) == "number" and .size == (.size | floor) and 0 < .size and
      (.sha256 | test("^[0-9a-f]{64}$")) and
      (.sha512 | test("^[0-9a-f]{128}$"))
    )
  ' "$artifact_evidence" >/dev/null || fail 'The immutable v0.1.0 public artifact evidence schema differs.'
jq -e --arg operation "$operation" '.allowedOperations | index($operation) != null' "$contract_file" >/dev/null || \
  fail "Unsupported Modrinth backlog operation: $operation"
[[ "$project_id" == 'CAdZ3jVr' && "$release_tag" == 'v0.1.3' && "$source_commit" == "$expected_source_commit" ]] || \
  fail 'The requested Modrinth backlog release identity differs.'

[[ "$(git rev-parse HEAD)" == "$expected_source_commit" ]] || fail 'The checked-out product source is not exact v0.1.3.'
[[ "$(git rev-parse "refs/tags/v0.1.3^{commit}")" == "$expected_source_commit" ]] || fail 'The v0.1.3 tag commit differs.'
[[ "$(git rev-parse refs/tags/v0.1.3)" == "$expected_source_object" ]] || fail 'The v0.1.3 tag object differs.'
[[ "$(git rev-parse "$expected_source_commit:docs/modrinth-project.md")" == "$expected_source_body_blob" ]] || \
  fail 'The v0.1.3 Modrinth body blob differs.'
[[ "$(git rev-parse origin/master)" == "$controller_commit" ]] || fail 'The active controller is not exact origin/master.'
git merge-base --is-ancestor "$expected_source_commit" "$controller_commit" || fail 'The release source is outside controller history.'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || fail 'Tracked product source must be clean before backlog reconciliation.'
git diff --cached --quiet -- . || fail 'The product index changed before backlog reconciliation.'

revalidate_controller_boundary() {
  [[ -n "${GH_TOKEN:-}" ]] || fail 'GH_TOKEN is required for the protected stage boundary.'
  git fetch --force origin \
    '+refs/heads/master:refs/remotes/origin/master' \
    "+refs/tags/$release_tag:refs/tags/$release_tag"
  [[ "$(git rev-parse origin/master)" == "$controller_commit" && "$(git rev-parse HEAD)" == "$source_commit" ]] || \
    fail 'The protected release source or controller changed before Modrinth staging.'
  [[ "$(git rev-parse "refs/tags/$release_tag^{commit}")" == "$source_commit" && \
    "$(git rev-parse "refs/tags/$release_tag")" == "$expected_source_object" ]] || \
    fail 'The protected release tag changed before Modrinth staging.'
  git --no-replace-objects cat-file blob "$controller_commit:release/verify-controller-tools.sh" | \
    bash -s -- verify "$controller_commit" "$controller_tool_directory"
  bash "$controller_tool_directory/verify-github-tag-ruleset.sh" \
    "$controller_tool_directory/github-release-tag-ruleset.json" \
    "$controller_tool_directory/github-release-tag-ruleset-receipt.json" >/dev/null
  read -r release_pages_run_id _ _ controller_pages_run_id _ _ <<< "$expected_pages_record"
  git --no-replace-objects cat-file blob "$controller_commit:release/verify-controller-tools.sh" | \
    bash -s -- verify "$controller_commit" "$controller_tool_directory"
  [[ "$(bash "$controller_tool_directory/verify-pages-deployment-source.sh" \
    "$release_pages_run_id" "$release_tag" "$source_commit" \
    "$controller_pages_run_id" "$controller_commit")" == "$expected_pages_record" ]] || \
    fail 'Protected Pages evidence changed before Modrinth staging.'
  git fetch --force origin \
    '+refs/heads/master:refs/remotes/origin/master' \
    "+refs/tags/$release_tag:refs/tags/$release_tag"
  [[ "$(git rev-parse origin/master)" == "$controller_commit" && \
    "$(git rev-parse "refs/tags/$release_tag^{commit}")" == "$source_commit" && \
    "$(git rev-parse "refs/tags/$release_tag")" == "$expected_source_object" ]] || \
    fail 'Protected controller or tag changed during the final Modrinth stage boundary.'
}

runner_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
[[ -d "$runner_parent" && ! -L "$runner_parent" ]] || fail 'The backlog runner temporary parent is not a regular directory.'
temporary="$(mktemp -d "$runner_parent/strata-modrinth-v013-backlog.XXXXXX")"
manifest='build/release/modrinth/manifest.json'
manifest_backup="$temporary/manifest.json"
manifest_patched="$temporary/patched-manifest.json"
manifest_was_patched=0
original_manifest_sha256=''
receipt_temporary=''
receipt=''
historical_worktrees=()

restore_historical_manifest() {
  local index="$1"
  local worktree="$2"
  local backup="$temporary/historical-$index-canonical-manifest.json"
  local expected_hash
  [[ -f "$backup" ]] || return 0
  expected_hash="$(<"$backup.sha256")"
  [[ "$expected_hash" =~ ^[0-9a-f]{64}$ && ! -L "$backup" &&
    "$(sha256sum "$backup" | cut -d ' ' -f 1)" == "$expected_hash" ]] || return 1
  cp "$backup" "$worktree/build/release/modrinth/manifest.json" || return 1
  [[ "$(sha256sum "$worktree/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$expected_hash" ]]
}

restore_current_manifest() {
  [[ "$manifest_was_patched" == '1' ]] || return 0
  cp "$manifest_backup" "$manifest" || return 1
  [[ "$(sha256sum "$manifest" | cut -d ' ' -f 1)" == "$original_manifest_sha256" ]] || return 1
  manifest_was_patched=0
}

cleanup() {
  local status=$?
  local cleanup_failed=0
  trap - EXIT INT TERM
  set +e
  for historical_index in "${!historical_worktrees[@]}"; do
    historical_worktree="${historical_worktrees[$historical_index]}"
    [[ -z "$historical_worktree" ]] && continue
    historical_resolved="$(realpath -m "$historical_worktree")"
    if [[ "$historical_resolved" != "$temporary/"* ]]; then
      echo 'A historical verification worktree escaped the backlog temporary directory.' >&2
      cleanup_failed=1
      continue
    fi
    if restore_historical_manifest "$historical_index" "$historical_worktree"; then
      if ! git worktree remove --force "$historical_worktree" >/dev/null 2>&1; then
        echo 'A historical verification worktree could not be removed.' >&2
        cleanup_failed=1
      fi
    else
      echo 'A historical canonical manifest could not be restored exactly.' >&2
      cleanup_failed=1
    fi
  done
  if ! restore_current_manifest; then
    echo 'The canonical Modrinth manifest could not be restored exactly.' >&2
    cleanup_failed=1
  fi
  if [[ -n "$receipt_temporary" && -f "$receipt_temporary" && ! -L "$receipt_temporary" ]]; then
    rm -f -- "$receipt_temporary" || cleanup_failed=1
  fi
  if ! git diff --quiet -- . || ! git diff --cached --quiet -- . || [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
    echo 'The Modrinth backlog runner did not preserve clean tagged source.' >&2
    cleanup_failed=1
  fi
  if [[ "$cleanup_failed" == '0' ]]; then
    rm -rf -- "$temporary" || cleanup_failed=1
  fi
  if [[ "$cleanup_failed" != '0' ]]; then
    status=1
  fi
  if [[ "$status" != '0' && -n "$receipt" && -f "$receipt" && ! -L "$receipt" ]]; then
    rm -f -- "$receipt" || status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

normalize_to_file() {
  local input_file="$1"
  local output_file="$2"
  jq --null-input --rawfile body "$input_file" --raw-output --join-output \
    '$body | gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n"' > "$output_file"
}

manifest_version_core() {
  local source_manifest="$1"
  local destination="$2"
  jq '
      def normalize_changelog:
        gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n";
      . as $manifest |
      [
        .artifacts[] |
        {
          project_id: $manifest.projectId,
          name: .versionName,
          version_number: .versionNumber,
          changelog: ($manifest.changelog | normalize_changelog),
          dependencies: ($manifest.requiredProjectDependencies | map({
            project_id: ., version_id: null, file_name: null, dependency_type: "required"
          }) | sort_by([.dependency_type, (.project_id // ""), (.version_id // ""), (.file_name // "")])),
          game_versions: [.gameVersion],
          version_type: $manifest.versionType,
          loaders: [$manifest.loader],
          featured: $manifest.featured,
          status: "listed",
          environment: $manifest.environment,
          files: [{filename: .fileName, primary: true, size: .size, sha512: .sha512, sha256: .sha256}]
        }
      ] | sort_by(.version_number)
    ' "$source_manifest" > "$destination"
}

verify_historical_release() {
  local index="$1"
  local tag commit tag_object body_blob expected_body body_source normalized_body
  tag="$(jq -er --argjson index "$index" '.baselineReleases[$index].tag' "$contract_file")"
  commit="$(jq -er --argjson index "$index" '.baselineReleases[$index].commit' "$contract_file")"
  tag_object="$(jq -er --argjson index "$index" '.baselineReleases[$index].tagObject' "$contract_file")"
  body_blob="$(jq -er --argjson index "$index" '.baselineReleases[$index].bodyBlob' "$contract_file")"
  expected_body="$(jq -er --argjson index "$index" '.baselineReleases[$index].bodySha256' "$contract_file")"
  [[ "$(git rev-parse "refs/tags/$tag^{commit}")" == "$commit" ]] || fail "Historical Modrinth tag commit differs: $tag"
  [[ "$(git rev-parse "refs/tags/$tag")" == "$tag_object" ]] || fail "Historical Modrinth tag object differs: $tag"
  [[ "$(git rev-parse "$commit:docs/modrinth-project.md")" == "$body_blob" ]] || \
    fail "Historical Modrinth body blob differs: $tag"
  bash release/verify-release-tag.sh "$tag" "$commit" "$tag_object" >/dev/null
  body_source="$temporary/${tag#v}-body-source"
  normalized_body="$temporary/${tag#v}-body-normalized"
  git --no-replace-objects cat-file blob "$commit:docs/modrinth-project.md" > "$body_source"
  normalize_to_file "$body_source" "$normalized_body"
  [[ "$(sha256sum "$normalized_body" | cut -d ' ' -f 1)" == "$expected_body" ]] || \
    fail "Historical Modrinth body differs from its pinned hash: $tag"
}

verify_historical_release 0
verify_historical_release 1
verify_historical_release 2
bash release/verify-release-tag.sh v0.1.3 "$expected_source_commit" "$expected_source_object" >/dev/null

verify_historical_remote() {
  local index="$1"
  local expected_count="$2"
  local tag commit release_version expected_manifest_sha256 worktree
  local generated_manifest overlaid_manifest generated_structure_sha256 overlaid_structure_sha256
  local artifact_evidence_canonical_sha256
  tag="$(jq -er --argjson index "$index" '.baselineReleases[$index].tag' "$contract_file")"
  commit="$(jq -er --argjson index "$index" '.baselineReleases[$index].commit' "$contract_file")"
  release_version="$(jq -er --argjson index "$index" '.baselineReleases[$index].releaseVersion' "$contract_file")"
  expected_manifest_sha256="$(jq -er --argjson index "$index" '.baselineReleases[$index].manifestCanonicalSha256' "$contract_file")"
  worktree="$temporary/${tag#v}-remote-verification"
  [[ ! -e "$worktree" && ! -L "$worktree" ]] || fail "Historical verification worktree already exists: $tag"
  git worktree add --detach "$worktree" "$commit" >/dev/null
  historical_worktrees+=("$worktree")
  (
    cd "$worktree"
    cleanup_historical() {
      local status=$?
      trap - EXIT INT TERM
      if ! restore_historical_manifest "$index" "$worktree"; then
        echo "The historical canonical manifest could not be restored exactly: $tag" >&2
        status=1
      fi
      exit "$status"
    }
    trap cleanup_historical EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    bash ./gradlew --no-parallel --max-workers=2 --no-build-cache modrinthReleaseManifest \
      "-Pstrata.sourceRevision=$tag" \
      "-Pstrata.sourceCommit=$commit" \
      "-Pstrata.modrinthProjectId=$project_id"
    jq -e \
      --arg project "$project_id" \
      --arg release "$release_version" \
      --argjson count "$expected_count" '
        .projectId == $project and
        .releaseVersion == $release and
        (.artifacts | length) == $count
      ' build/release/modrinth/manifest.json >/dev/null || \
      fail "Historical Modrinth manifest inventory differs: $tag"
    artifact_evidence_canonical_sha256=''
    generated_structure_sha256=''
    if [[ "$index" == '0' ]]; then
      artifact_evidence_canonical_sha256="$(jq -cS . "$artifact_evidence" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
      [[ "$artifact_evidence_canonical_sha256" == "$(jq -er '.baselineReleases[0].artifactEvidenceCanonicalSha256' "$contract_file")" ]] || \
        fail 'The v0.1.0 artifact evidence changed during historical verification.'
      jq -e --slurpfile evidence "$artifact_evidence" '
          ([.artifacts[] | [.gameVersion, .versionNumber, .fileName]] | sort) ==
          ($evidence[0].artifacts | map([.gameVersion, .versionNumber, .fileName]) | sort)
        ' build/release/modrinth/manifest.json >/dev/null || \
        fail 'The generated v0.1.0 manifest identity differs from the public artifact evidence.'
      generated_manifest="$temporary/historical-$index-generated-manifest.json"
      overlaid_manifest="$temporary/historical-$index-overlaid-manifest.json"
      cp build/release/modrinth/manifest.json "$generated_manifest"
      jq --slurpfile evidence "$artifact_evidence" '
          ($evidence[0].artifacts |
            map({key: ([.gameVersion, .versionNumber, .fileName] | @tsv), value: .}) |
            from_entries) as $pinned |
          .artifacts |= map(
            ([.gameVersion, .versionNumber, .fileName] | @tsv) as $key |
            ($pinned[$key] // error("Missing v0.1.0 public artifact evidence.")) as $pin |
            .size = $pin.size |
            .sha256 = $pin.sha256 |
            .sha512 = $pin.sha512
          )
        ' "$generated_manifest" > "$overlaid_manifest"
      generated_structure_sha256="$(jq -cS '.artifacts |= map(del(.size, .sha256, .sha512))' "$generated_manifest" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
      overlaid_structure_sha256="$(jq -cS '.artifacts |= map(del(.size, .sha256, .sha512))' "$overlaid_manifest" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
      [[ "$generated_structure_sha256" == "$overlaid_structure_sha256" ]] || \
        fail 'The v0.1.0 evidence overlay changed fields outside artifact size and hashes.'
      jq -e --slurpfile evidence "$artifact_evidence" '
          ($evidence[0].artifacts |
            map({key: ([.gameVersion, .versionNumber, .fileName] | @tsv), value: .}) |
            from_entries) as $pinned |
          all(.artifacts[];
            ([.gameVersion, .versionNumber, .fileName] | @tsv) as $key |
            .size == $pinned[$key].size and
            .sha256 == $pinned[$key].sha256 and
            .sha512 == $pinned[$key].sha512
          )
        ' "$overlaid_manifest" >/dev/null || fail 'The v0.1.0 manifest does not contain the exact public artifact evidence.'
      mv "$overlaid_manifest" build/release/modrinth/manifest.json
    fi
    manifest_sha256="$(jq -cS . build/release/modrinth/manifest.json | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
    [[ "$manifest_sha256" == "$expected_manifest_sha256" ]] || \
      fail "Historical Modrinth manifest differs from its pinned canonical hash: $tag"
    canonical_manifest_file_sha256="$(sha256sum build/release/modrinth/manifest.json | cut -d ' ' -f 1)"
    historical_manifest_contract_sha256="$(jq -cS 'del(.project.previousBodySha256)' build/release/modrinth/manifest.json | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
    if [[ "$index" == '2' ]]; then
      historical_manifest_backup="$temporary/historical-$index-canonical-manifest.json"
      cp build/release/modrinth/manifest.json "$historical_manifest_backup"
      printf '%s\n' "$canonical_manifest_file_sha256" > "$historical_manifest_backup.sha256"
      historical_manifest_patched="$temporary/historical-$index-recovery-manifest.json"
      jq --arg backlog "$backlog_body_sha256" '.project.previousBodySha256 = $backlog' \
        "$historical_manifest_backup" > "$historical_manifest_patched"
      [[ "$(jq -cS 'del(.project.previousBodySha256)' "$historical_manifest_patched" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" == "$historical_manifest_contract_sha256" ]] || \
        fail 'Historical backlog recovery changed the manifest outside previousBodySha256.'
      [[ "$(jq -er '.project.previousBodySha256' "$historical_manifest_patched")" == "$backlog_body_sha256" ]] || \
        fail 'Historical backlog recovery does not contain the exact immutable backlog body hash.'
      cp "$historical_manifest_patched" build/release/modrinth/manifest.json
    fi
    recovery_manifest_file_sha256="$(sha256sum build/release/modrinth/manifest.json | cut -d ' ' -f 1)"
    manifest_version_core build/release/modrinth/manifest.json "$temporary/historical-$index-core.json"
    jq -e --argjson count "$expected_count" '
        length == $count and ([.[].version_number] | length == (unique | length))
      ' "$temporary/historical-$index-core.json" >/dev/null || \
      fail "Historical Modrinth core contract inventory differs: $tag"
    bash ./gradlew --no-parallel --max-workers=2 --no-build-cache modrinthReleasePreflight \
      -x modrinthReleaseManifest -x verifyPublishedConsumer \
      "-Pstrata.sourceRevision=$tag" \
      "-Pstrata.sourceCommit=$commit" \
      "-Pstrata.modrinthProjectId=$project_id"
    [[ "$(sha256sum build/release/modrinth/manifest.json | cut -d ' ' -f 1)" == "$recovery_manifest_file_sha256" ]] || \
      fail "Historical preflight changed the temporary recovery manifest: $tag"
    jq -e \
      --arg project "$project_id" \
      --argjson count "$expected_count" '
        .operation == "preflight" and
        .projectId == $project and
        (.projectStatus == "processing" or .projectStatus == "approved") and
        (.absent | length) == 0 and
        (.listed | length) == $count
      ' build/release/modrinth-receipts/preflight.json >/dev/null || \
      fail "Historical authenticated Modrinth preflight differs: $tag"
    preflight_receipt_sha256="$(sha256sum build/release/modrinth-receipts/preflight.json | cut -d ' ' -f 1)"
    restore_historical_manifest "$index" "$worktree" || fail "Historical canonical manifest restoration failed: $tag"
    jq --null-input \
      --arg tag "$tag" \
      --arg manifestCanonicalSha256 "$manifest_sha256" \
      --arg canonicalManifestFileSha256 "$canonical_manifest_file_sha256" \
      --arg recoveryManifestFileSha256 "$recovery_manifest_file_sha256" \
      --arg manifestContractSha256 "$historical_manifest_contract_sha256" \
      --arg manifestArtifactContractSha256 "$generated_structure_sha256" \
      --arg artifactEvidenceCanonicalSha256 "$artifact_evidence_canonical_sha256" \
      --arg preflightReceiptSha256 "$preflight_receipt_sha256" \
      --argjson versionCount "$expected_count" '
        {
          tag: $tag,
          manifestCanonicalSha256: $manifestCanonicalSha256,
          canonicalManifestFileSha256: $canonicalManifestFileSha256,
          recoveryManifestFileSha256: $recoveryManifestFileSha256,
          manifestContractSha256: $manifestContractSha256,
          manifestArtifactContractSha256: (if $manifestArtifactContractSha256 == "" then null else $manifestArtifactContractSha256 end),
          artifactEvidenceCanonicalSha256: (if $artifactEvidenceCanonicalSha256 == "" then null else $artifactEvidenceCanonicalSha256 end),
          preflightReceiptSha256: $preflightReceiptSha256,
          versionCount: $versionCount
        }
      ' > "$temporary/historical-$index-evidence.json"
    [[ -z "$(git status --porcelain --untracked-files=all)" ]] || \
      fail "Historical Modrinth preflight changed tagged source: $tag"
  )
}

[[ -f "$manifest" && ! -L "$manifest" ]] || fail 'The canonical v0.1.3 Modrinth manifest is missing.'
manifest_resolved="$(realpath "$manifest")"
[[ "$manifest_resolved" == "$repository_root/build/release/modrinth/manifest.json" ]] || fail 'The Modrinth manifest escaped its canonical path.'
original_manifest_sha256="$(sha256sum "$manifest" | cut -d ' ' -f 1)"
canonical_manifest_sha256="$(jq -cS . "$manifest" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
expected_manifest_sha256="$(jq -er '.releaseSource.manifestCanonicalSha256' "$contract_file")"
[[ "$canonical_manifest_sha256" == "$expected_manifest_sha256" ]] || \
  fail 'The canonical v0.1.3 Modrinth manifest differs from its pinned canonical hash.'
cp "$manifest" "$manifest_backup"

jq -e \
  --arg project "$project_id" \
  --arg predecessor "$predecessor_body_sha256" '
    .schemaVersion == 1 and
    .projectId == $project and
    .releaseVersion == "0.1.3" and
    .loader == "fabric" and
    .versionType == "release" and
    .environment == "client_only" and
    .project.slug == "strata-ui" and
    .project.previousBodySha256 == $predecessor and
    (.artifacts | length) == 21 and
    ([.artifacts[].versionNumber] | length == (unique | length))
  ' "$manifest" >/dev/null || fail 'The canonical v0.1.3 Modrinth manifest identity differs.'
manifest_body="$temporary/current-body"
jq --raw-output --join-output '.project.body | gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n"' \
  "$manifest" > "$manifest_body"
[[ "$(sha256sum "$manifest_body" | cut -d ' ' -f 1)" == "$current_body_sha256" ]] || \
  fail 'The canonical v0.1.3 Modrinth body differs.'
current_expected_core="$temporary/current-expected-core.json"
baseline_expected_core="$temporary/historical-expected-core.json"
manifest_version_core "$manifest" "$current_expected_core"
jq -e 'length == 21 and ([.[].version_number] | length == (unique | length))' "$current_expected_core" >/dev/null || \
  fail 'The canonical v0.1.3 Modrinth core contract inventory differs.'

artifact_receipt_before="$temporary/artifacts-before.sha256"
while IFS=$'\t' read -r relative_path expected_size expected_sha256 expected_sha512; do
  [[ "$relative_path" =~ ^artifacts/[^/]+\.jar$ ]] || fail "Unsafe Modrinth artifact path: $relative_path"
  artifact="$repository_root/build/release/modrinth/$relative_path"
  [[ -f "$artifact" && ! -L "$artifact" ]] || fail "Canonical Modrinth artifact is not a regular file: $relative_path"
  resolved_artifact="$(realpath "$artifact")"
  [[ "$resolved_artifact" == "$repository_root/build/release/modrinth/artifacts/"* ]] || \
    fail "Canonical Modrinth artifact escaped its bundle: $relative_path"
  actual_sha256="$(sha256sum "$artifact" | cut -d ' ' -f 1)"
  actual_sha512="$(sha512sum "$artifact" | cut -d ' ' -f 1)"
  actual_size="$(wc -c < "$artifact" | tr -d '[:space:]')"
  [[ "$actual_size" == "$expected_size" ]] || fail "Canonical Modrinth artifact size differs: $relative_path"
  [[ "$actual_sha256" == "$expected_sha256" ]] || fail "Canonical Modrinth artifact differs: $relative_path"
  [[ "$actual_sha512" == "$expected_sha512" ]] || fail "Canonical Modrinth artifact SHA-512 differs: $relative_path"
  printf '%s\t%s\t%s\t%s\n' "$actual_size" "$actual_sha256" "$actual_sha512" "$relative_path" >> "$artifact_receipt_before"
done < <(jq -er '.artifacts[] | [.relativePath, .size, .sha256, .sha512] | @tsv' "$manifest" | tr -d '\r')
[[ "$(wc -l < "$artifact_receipt_before" | tr -d '[:space:]')" == '21' ]] || fail 'The artifact receipt must contain exactly 21 JARs.'
artifact_receipt_sha256="$(sha256sum "$artifact_receipt_before" | cut -d ' ' -f 1)"

baseline_versions="$temporary/baseline-versions"
baseline_versions_json="$temporary/baseline-versions.json"
current_versions="$temporary/current-versions"
current_versions_json="$temporary/current-versions.json"
allowed_versions="$temporary/allowed-versions"
jq -r '.baselineReleases[] | .releaseVersion as $release | .gameVersions[] | "\($release)+mc\(.)"' \
  "$contract_file" | LC_ALL=C sort > "$baseline_versions"
jq --raw-input --slurp 'split("\n") | map(select(length != 0))' "$baseline_versions" > "$baseline_versions_json"
jq -r '.artifacts[].versionNumber' "$manifest" | LC_ALL=C sort > "$current_versions"
jq --raw-input --slurp 'split("\n") | map(select(length != 0))' "$current_versions" > "$current_versions_json"
cat "$baseline_versions" "$current_versions" | LC_ALL=C sort -u > "$allowed_versions"
[[ "$(wc -l < "$baseline_versions" | tr -d '[:space:]')" == '62' ]] || fail 'The pinned backlog baseline must contain 62 versions.'
[[ "$(wc -l < "$current_versions" | tr -d '[:space:]')" == '21' ]] || fail 'The v0.1.3 manifest must contain 21 versions.'
[[ "$(wc -l < "$allowed_versions" | tr -d '[:space:]')" == '83' ]] || fail 'The backlog union must contain 83 unique versions.'

require_lifecycle_transition() {
  local before="$1"
  local after="$2"
  [[ "$before" == "$after" || ( "$before" == 'processing' && "$after" == 'approved' ) ]] || \
    fail 'The Modrinth project lifecycle regressed during backlog reconciliation.'
}

fetch_state() {
  local prefix="$1"
  local require_current="$2"
  local project_json="$temporary/$prefix-project.json"
  local versions_json="$temporary/$prefix-versions.json"
  local body="$temporary/$prefix-body"
  local remote_versions="$temporary/$prefix-remote-versions"
  local duplicate_versions="$temporary/$prefix-duplicate-versions"
  local baseline_metadata="$temporary/$prefix-baseline-metadata.json"
  local current_metadata="$temporary/$prefix-current-metadata.json"
  local remote_stable_metadata="$temporary/$prefix-stable-metadata.json"
  local remote_core="$temporary/$prefix-remote-core.json"
  local baseline_actual_core="$temporary/$prefix-baseline-core.json"
  local current_actual_core="$temporary/$prefix-current-core.json"
  local current_expected_subset="$temporary/$prefix-current-expected-core.json"
  curl --fail --silent --show-error --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' --header "Authorization: $MODRINTH_TOKEN" \
    "https://api.modrinth.com/v2/project/$project_id" --output "$project_json"
  curl --fail --silent --show-error --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' --header "Authorization: $MODRINTH_TOKEN" \
    "https://api.modrinth.com/v2/project/$project_id/version?include_changelog=true" --output "$versions_json"
  jq -e '
      def nonblank_string: type == "string" and test("[^[:space:]]");
      def optional_string: . == null or type == "string";
      type == "array" and all(.[];
        (.id | nonblank_string) and
        (.project_id | nonblank_string) and
        (.author_id | nonblank_string) and
        (.name | nonblank_string) and
        (.version_number | nonblank_string) and
        (.changelog == null or (.changelog | type) == "string") and
        ((.dependencies | type) == "array" and all(.dependencies[];
          (.project_id | optional_string) and
          (.version_id | optional_string) and
          (.file_name | optional_string) and
          (.dependency_type | nonblank_string)
        )) and
        ((.game_versions | type) == "array" and all(.game_versions[]; nonblank_string)) and
        (.version_type | nonblank_string) and
        ((.loaders | type) == "array" and all(.loaders[]; nonblank_string)) and
        ((.featured | type) == "boolean") and
        (.status | nonblank_string) and
        (.environment | nonblank_string) and
        ((.files | type) == "array" and all(.files[];
          (.filename | nonblank_string) and
          ((.primary | type) == "boolean") and
          ((.size | type) == "number" and .size == (.size | floor) and 0 < .size) and
          (.url | nonblank_string) and
          (.file_type | optional_string) and
          ((.hashes | type) == "object") and
          (.hashes.sha512 | type == "string" and test("^[0-9a-f]{128}$")) and
          (.hashes.sha1 == null or (.hashes.sha1 | type == "string" and test("^[0-9a-f]{40}$"))) and
          (.hashes.sha256 == null or (.hashes.sha256 | type == "string" and test("^[0-9a-f]{64}$")))
        ))
      )
    ' "$versions_json" >/dev/null || fail 'The Modrinth v2 version response schema differs.'
  jq -e --arg project "$project_id" '
    .id == $project and
    .slug == "strata-ui" and
    .project_type == "mod" and
    ((.status == "processing" and .requested_status == "approved") or
      (.status == "approved" and (.requested_status == null or .requested_status == "approved")))
  ' "$project_json" >/dev/null || fail 'The Modrinth project identity or backlog lifecycle state differs.'
  jq --raw-output --join-output '(.body // "") | gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n"' \
    "$project_json" > "$body"
  [[ "$(sha256sum "$body" | cut -d ' ' -f 1)" == "$backlog_body_sha256" ]] || \
    fail 'The remote Modrinth body is not the exact immutable v0.1.0 backlog body.'
  jq -e --arg project "$project_id" '
    type == "array" and all(.[];
      .project_id == $project and
      (.version_number | type == "string") and
      .status == "listed"
    )
  ' "$versions_json" >/dev/null || fail 'The remote Modrinth backlog version metadata is not a listed project inventory.'
  jq -r '.[].version_number' "$versions_json" | LC_ALL=C sort > "$remote_versions"
  uniq -d "$remote_versions" > "$duplicate_versions"
  [[ ! -s "$duplicate_versions" ]] || fail 'The remote Modrinth backlog contains duplicate version numbers.'
  [[ -z "$(comm -23 "$baseline_versions" "$remote_versions")" ]] || fail 'The remote Modrinth backlog is missing a v0.1.0, v0.1.1, or v0.1.2 version.'
  [[ -z "$(comm -13 "$allowed_versions" "$remote_versions")" ]] || fail 'The remote Modrinth backlog contains an unexpected version.'
  if [[ "$require_current" == '1' ]]; then
    [[ -z "$(comm -23 "$current_versions" "$remote_versions")" ]] || fail 'The remote Modrinth backlog is missing a v0.1.3 version.'
  fi
  jq '
      def normalize_changelog:
        gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n";
      [
        .[] |
        {
          id,
          project_id,
          author_id,
          name,
          version_number,
          changelog: ((.changelog // "") | normalize_changelog),
          dependencies: ([
            .dependencies[] |
            {
              project_id: (.project_id // null),
              version_id: (.version_id // null),
              file_name: (.file_name // null),
              dependency_type
            }
          ] | sort_by([.dependency_type, (.project_id // ""), (.version_id // ""), (.file_name // "")])),
          game_versions: (.game_versions | sort),
          version_type,
          loaders: (.loaders | sort),
          featured,
          status,
          environment,
          files: ([
            .files[] |
            {
              filename,
              primary,
              size,
              url,
              file_type: (.file_type // null),
              hashes: {
                sha512: .hashes.sha512,
                sha1: (.hashes.sha1 // null),
                sha256: (.hashes.sha256 // null)
              }
            }
          ] | sort_by([.filename, .url]))
        }
      ] | sort_by([.version_number, .id])
    ' "$versions_json" > "$remote_stable_metadata"
  jq --slurpfile versions "$baseline_versions_json" '
      [.[] | select(.version_number as $number | ($versions[0] | index($number)) != null)] | sort_by([.version_number, .id])
    ' "$remote_stable_metadata" > "$baseline_metadata"
  jq --slurpfile versions "$current_versions_json" '
      [.[] | select(.version_number as $number | ($versions[0] | index($number)) != null)] | sort_by([.version_number, .id])
    ' "$remote_stable_metadata" > "$current_metadata"
  jq -e 'length == 62' "$baseline_metadata" >/dev/null || fail 'The historical Modrinth metadata projection must contain 62 versions.'
  baseline_metadata_sha256="$(jq -cS . "$baseline_metadata" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
  current_metadata_sha256="$(jq -cS . "$current_metadata" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
  jq '
      def normalize_changelog:
        gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "") + "\n";
      [
        .[] |
        {
          project_id,
          name,
          version_number,
          changelog: ((.changelog // "") | normalize_changelog),
          dependencies: ([
            .dependencies[] |
            {
              project_id: (.project_id // null),
              version_id: (.version_id // null),
              file_name: (.file_name // null),
              dependency_type
            }
          ] | sort_by([.dependency_type, (.project_id // ""), (.version_id // ""), (.file_name // "")])),
          game_versions: (.game_versions | sort),
          version_type,
          loaders: (.loaders | sort),
          featured,
          status,
          environment,
          files: ([
            .files[] |
            {filename, primary, size, sha512: .hashes.sha512, sha256: (.hashes.sha256 // null)}
          ] | sort_by(.filename))
        }
      ] | sort_by(.version_number)
    ' "$versions_json" > "$remote_core"
  baseline_core_sha256='pending-historical-preflight'
  if [[ -s "$baseline_expected_core" ]]; then
    jq --slurpfile versions "$baseline_versions_json" '
        [.[] | select(.version_number as $number | ($versions[0] | index($number)) != null)] | sort_by(.version_number)
      ' "$remote_core" > "$baseline_actual_core"
    jq --slurpfile expected "$baseline_expected_core" '
        ($expected[0] | map({key: .version_number, value: .files[0].sha256}) | from_entries) as $sha256 |
        map(. as $version | .files |= map(.sha256 = (.sha256 // $sha256[$version.version_number])))
      ' "$baseline_actual_core" > "$baseline_actual_core.normalized"
    mv "$baseline_actual_core.normalized" "$baseline_actual_core"
    baseline_core_sha256="$(jq -cS . "$baseline_actual_core" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
    [[ "$baseline_core_sha256" == "$(jq -cS . "$baseline_expected_core" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" ]] || \
      fail 'Historical Modrinth version metadata differs from the three signed release manifests.'
  fi
  jq --slurpfile versions "$current_versions_json" '
      [.[] | select(.version_number as $number | ($versions[0] | index($number)) != null)] | sort_by(.version_number)
    ' "$remote_core" > "$current_actual_core"
  jq --slurpfile expected "$current_expected_core" '
      ($expected[0] | map({key: .version_number, value: .files[0].sha256}) | from_entries) as $sha256 |
      map(. as $version | .files |= map(.sha256 = (.sha256 // $sha256[$version.version_number])))
    ' "$current_actual_core" > "$current_actual_core.normalized"
  mv "$current_actual_core.normalized" "$current_actual_core"
  jq --slurpfile actual "$current_actual_core" '
      [.[] | select(.version_number as $number | ($actual[0] | map(.version_number) | index($number)) != null)] |
      sort_by(.version_number)
    ' "$current_expected_core" > "$current_expected_subset"
  current_core_sha256="$(jq -cS . "$current_actual_core" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
  [[ "$current_core_sha256" == "$(jq -cS . "$current_expected_subset" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" ]] || \
    fail 'Existing Modrinth v0.1.3 metadata differs from the exact signed release manifest.'
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(jq -er '.status' "$project_json")" \
    "$(jq -r '.requested_status // "none"' "$project_json")" \
    "$(sha256sum "$remote_versions" | cut -d ' ' -f 1)" \
    "$(wc -l < "$remote_versions" | tr -d '[:space:]')" \
    "$baseline_metadata_sha256" \
    "$current_metadata_sha256" \
    "$baseline_core_sha256" \
    "$current_core_sha256"
}

receipt_directory='build/release/modrinth-controller-receipts'
mkdir -p "$receipt_directory"
[[ -d "$receipt_directory" && ! -L "$receipt_directory" && \
  "$(realpath "$receipt_directory")" == "$repository_root/$receipt_directory" ]] || \
  fail 'The controller receipt directory escaped the tagged build output.'
receipt="$receipt_directory/$operation.json"
[[ ! -L "$receipt" ]] || fail 'The controller receipt destination must not be a symlink.'
rm -f -- "$receipt"

require_current_before=0
if [[ "$operation" == 'observe' ]]; then
  require_current_before=1
fi
IFS=$'\t' read -r status_before requested_status_before inventory_before count_before baseline_metadata_before current_metadata_before baseline_core_before current_core_before < \
  <(fetch_state before "$require_current_before")

verify_historical_remote 0 20
verify_historical_remote 1 21
verify_historical_remote 2 21
jq --slurp 'add | sort_by(.version_number)' \
  "$temporary/historical-0-core.json" "$temporary/historical-1-core.json" "$temporary/historical-2-core.json" > "$baseline_expected_core"
jq -e 'length == 62 and ([.[].version_number] | length == (unique | length))' "$baseline_expected_core" >/dev/null || \
  fail 'The combined signed historical Modrinth core contract must contain 62 unique versions.'
historical_core_contract_sha256="$(jq -cS . "$baseline_expected_core" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
current_core_contract_sha256="$(jq -cS . "$current_expected_core" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
require_current_boundary=0
if [[ "$operation" == 'observe' ]]; then
  require_current_boundary=1
fi
IFS=$'\t' read -r status_boundary requested_status_boundary inventory_boundary count_boundary baseline_metadata_boundary current_metadata_boundary baseline_core_boundary current_core_boundary < \
  <(fetch_state boundary "$require_current_boundary")
require_lifecycle_transition "$status_before" "$status_boundary"
[[ "$inventory_boundary" == "$inventory_before" && "$count_boundary" == "$count_before" && \
  "$baseline_metadata_boundary" == "$baseline_metadata_before" && \
  "$current_metadata_boundary" == "$current_metadata_before" && \
  "$current_core_boundary" == "$current_core_before" ]] || \
  fail 'The Modrinth lifecycle, inventory, or historical metadata changed during historical backlog verification.'

patched_manifest_sha256="$original_manifest_sha256"
manifest_contract_before="$(jq -cS 'del(.project.previousBodySha256)' "$manifest" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)"
jq --arg backlog "$backlog_body_sha256" '.project.previousBodySha256 = $backlog' "$manifest" > "$manifest_patched"
[[ "$(jq -cS 'del(.project.previousBodySha256)' "$manifest_patched" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" == "$manifest_contract_before" ]] || \
  fail 'The backlog recovery changed the manifest outside previousBodySha256.'
[[ "$(jq -er '.project.previousBodySha256' "$manifest_patched")" == "$backlog_body_sha256" ]] || \
  fail 'The backlog recovery manifest does not contain the exact immutable backlog body hash.'
manifest_was_patched=1
cp "$manifest_patched" "$manifest"
patched_manifest_sha256="$(sha256sum "$manifest" | cut -d ' ' -f 1)"

status_write=''
requested_status_write=''
inventory_write=''
count_write=''
baseline_metadata_write=''
baseline_core_write=''
current_core_write=''
current_metadata_write=''
case "$operation" in
  preflight|observe) gradle_task=modrinthReleasePreflight ;;
  stage)
    gradle_task=modrinthReleaseStage
    revalidate_controller_boundary
    IFS=$'\t' read -r status_write requested_status_write inventory_write count_write baseline_metadata_write current_metadata_write baseline_core_write current_core_write < \
      <(fetch_state write-boundary 0)
    require_lifecycle_transition "$status_boundary" "$status_write"
    [[ "$inventory_write" == "$inventory_boundary" && "$count_write" == "$count_boundary" && \
      "$baseline_metadata_write" == "$baseline_metadata_boundary" && \
      "$current_metadata_write" == "$current_metadata_boundary" && \
      "$baseline_core_write" == "$baseline_core_boundary" && "$current_core_write" == "$current_core_boundary" ]] || \
      fail 'The Modrinth lifecycle, inventory, or historical metadata changed at the protected stage boundary.'
    ;;
  *) fail "Unsupported backlog operation: $operation" ;;
esac
bash ./gradlew --no-parallel --max-workers=2 --no-build-cache "$gradle_task" \
  -x modrinthReleaseManifest -x verifyPublishedConsumer \
  "-Pstrata.sourceRevision=$release_tag" \
  "-Pstrata.sourceCommit=$source_commit" \
  "-Pstrata.modrinthProjectId=$project_id"
[[ "$(sha256sum "$manifest" | cut -d ' ' -f 1)" == "$patched_manifest_sha256" ]] || \
  fail 'The tagged Gradle operation changed the temporary recovery manifest.'

require_current_after=0
if [[ "$operation" == 'stage' || "$operation" == 'observe' ]]; then
  require_current_after=1
fi
IFS=$'\t' read -r status_after requested_status_after inventory_after count_after baseline_metadata_after current_metadata_after baseline_core_after current_core_after < \
  <(fetch_state after "$require_current_after")
require_lifecycle_transition "${status_write:-$status_boundary}" "$status_after"
if [[ "$require_current_after" == '1' ]]; then
  [[ "$current_core_after" == "$current_core_contract_sha256" ]] || \
    fail 'The complete Modrinth v0.1.3 inventory differs from the signed release core contract.'
fi
if [[ "$operation" == 'preflight' || "$operation" == 'observe' ]]; then
  [[ "$inventory_after" == "$inventory_before" && "$count_after" == "$count_before" && \
    "$baseline_metadata_after" == "$baseline_metadata_before" && \
    "$current_metadata_after" == "$current_metadata_before" && \
    "$baseline_core_after" == "$baseline_core_boundary" && "$current_core_after" == "$current_core_before" ]] || \
    fail 'The read-only Modrinth backlog operation observed a concurrent inventory or historical metadata change.'
fi
if [[ "$operation" == 'stage' ]]; then
  [[ "$count_after" == '83' ]] || fail 'The staged Modrinth backlog must contain exactly 83 release versions.'
  [[ "$baseline_metadata_after" == "$baseline_metadata_write" ]] || \
    fail 'The append-only Modrinth stage changed historical release metadata.'
  [[ "$baseline_core_after" == "$baseline_core_write" ]] || \
    fail 'The append-only Modrinth stage changed the signed historical core contract.'
  after_existing_current_metadata="$temporary/after-existing-current-metadata.json"
  jq --slurpfile before "$temporary/write-boundary-current-metadata.json" '
      ($before[0] | map(.version_number)) as $numbers |
      [.[] | select(.version_number as $number | ($numbers | index($number)) != null)] |
      sort_by([.version_number, .id])
    ' "$temporary/after-current-metadata.json" > "$after_existing_current_metadata"
  [[ "$(jq -cS . "$after_existing_current_metadata" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" == "$current_metadata_write" ]] || \
    fail 'The append-only Modrinth stage changed a pre-existing v0.1.3 version identity or file URL.'
fi

if [[ "$operation" == 'preflight' ]]; then
  operation_receipt='build/release/modrinth-receipts/preflight.json'
  jq -e --arg project "$project_id" '
    .operation == "preflight" and
    .projectId == $project and
    ((.absent | length) + (.listed | length) == 21)
  ' "$operation_receipt" >/dev/null || fail 'The tagged Modrinth preflight receipt differs.'
elif [[ "$operation" == 'stage' ]]; then
  operation_receipt='build/release/modrinth-receipts/stage.json'
  jq -e --arg project "$project_id" '
    .operation == "stage" and
    .projectId == $project and
    (.absent | length) == 0 and
    (.listed | length) == 21
  ' "$operation_receipt" >/dev/null || fail 'The tagged Modrinth stage receipt differs.'
else
  operation_receipt='build/release/modrinth-receipts/preflight.json'
  jq -e --arg project "$project_id" '
    .operation == "preflight" and
    .projectId == $project and
    (.absent | length) == 0 and
    (.listed | length) == 21
  ' "$operation_receipt" >/dev/null || fail 'The tagged Modrinth observe preflight receipt differs.'
fi
absent_count="$(jq -er '.absent | length' "$operation_receipt")"
listed_count="$(jq -er '.listed | length' "$operation_receipt")"
operation_receipt_sha256="$(sha256sum "$operation_receipt" | cut -d ' ' -f 1)"

artifact_receipt_after="$temporary/artifacts-after.sha256"
while IFS=$'\t' read -r relative_path expected_size expected_sha256 expected_sha512; do
  artifact="$repository_root/build/release/modrinth/$relative_path"
  [[ -f "$artifact" && ! -L "$artifact" ]] || fail "The backlog operation changed an artifact type: $relative_path"
  resolved_artifact="$(realpath "$artifact")"
  [[ "$resolved_artifact" == "$repository_root/build/release/modrinth/artifacts/"* ]] || \
    fail "An artifact escaped its bundle after backlog reconciliation: $relative_path"
  actual_size="$(wc -c < "$artifact" | tr -d '[:space:]')"
  actual_sha256="$(sha256sum "$artifact" | cut -d ' ' -f 1)"
  actual_sha512="$(sha512sum "$artifact" | cut -d ' ' -f 1)"
  [[ "$actual_size" == "$expected_size" ]] || fail "The backlog operation changed an artifact size: $relative_path"
  [[ "$actual_sha256" == "$expected_sha256" ]] || fail "The backlog operation changed a canonical artifact: $relative_path"
  [[ "$actual_sha512" == "$expected_sha512" ]] || fail "The backlog operation changed an artifact SHA-512: $relative_path"
  printf '%s\t%s\t%s\t%s\n' "$actual_size" "$actual_sha256" "$actual_sha512" "$relative_path" >> "$artifact_receipt_after"
done < <(jq -er '.artifacts[] | [.relativePath, .size, .sha256, .sha512] | @tsv' "$manifest_backup" | tr -d '\r')
cmp --silent "$artifact_receipt_before" "$artifact_receipt_after" || fail 'The backlog operation changed the canonical artifact receipt.'
restore_current_manifest || fail 'The canonical Modrinth manifest could not be restored before receipt publication.'

write_scope='none'
if [[ "$operation" == 'stage' ]]; then
  write_scope='missing-v0.1.3-versions-only'
fi
receipt_temporary="$(mktemp "$receipt_directory/.${operation}.XXXXXX")"
[[ -f "$receipt_temporary" && ! -L "$receipt_temporary" ]] || fail 'The atomic controller receipt is not a regular file.'
jq --null-input \
  --slurpfile historical0 "$temporary/historical-0-evidence.json" \
  --slurpfile historical1 "$temporary/historical-1-evidence.json" \
  --slurpfile historical2 "$temporary/historical-2-evidence.json" \
  --arg operation "$operation" \
  --arg projectId "$project_id" \
  --arg bodyState 'v0.1.0-backlog' \
  --arg bodySha256Before "$backlog_body_sha256" \
  --arg bodySha256After "$backlog_body_sha256" \
  --arg releaseTag "$release_tag" \
  --arg releaseTagObject "$expected_source_object" \
  --arg sourceCommit "$source_commit" \
  --arg controllerCommit "$controller_commit" \
  --arg canonicalManifestFileSha256 "$original_manifest_sha256" \
  --arg canonicalManifestSha256 "$canonical_manifest_sha256" \
  --arg recoveryManifestFileSha256 "$patched_manifest_sha256" \
  --arg manifestContractSha256 "$manifest_contract_before" \
  --arg artifactReceiptSha256 "$artifact_receipt_sha256" \
  --arg operationReceiptSha256 "$operation_receipt_sha256" \
  --arg statusBefore "$status_before" \
  --arg statusBoundary "$status_boundary" \
  --arg statusWriteBoundary "$status_write" \
  --arg statusAfter "$status_after" \
  --arg requestedStatusBefore "$requested_status_before" \
  --arg requestedStatusBoundary "$requested_status_boundary" \
  --arg requestedStatusWriteBoundary "$requested_status_write" \
  --arg requestedStatusAfter "$requested_status_after" \
  --arg versionNumberInventorySha256Before "$inventory_before" \
  --arg versionNumberInventorySha256Boundary "$inventory_boundary" \
  --arg versionNumberInventorySha256WriteBoundary "$inventory_write" \
  --arg versionNumberInventorySha256After "$inventory_after" \
  --arg baselineMetadataSha256Before "$baseline_metadata_before" \
  --arg baselineMetadataSha256Boundary "$baseline_metadata_boundary" \
  --arg baselineMetadataSha256WriteBoundary "$baseline_metadata_write" \
  --arg baselineMetadataSha256After "$baseline_metadata_after" \
  --arg currentMetadataSha256Before "$current_metadata_before" \
  --arg currentMetadataSha256Boundary "$current_metadata_boundary" \
  --arg currentMetadataSha256WriteBoundary "$current_metadata_write" \
  --arg currentMetadataSha256After "$current_metadata_after" \
  --arg historicalCoreContractSha256 "$historical_core_contract_sha256" \
  --arg historicalCoreSha256Boundary "$baseline_core_boundary" \
  --arg historicalCoreSha256WriteBoundary "$baseline_core_write" \
  --arg historicalCoreSha256After "$baseline_core_after" \
  --arg currentCoreContractSha256 "$current_core_contract_sha256" \
  --arg currentCoreSha256Before "$current_core_before" \
  --arg currentCoreSha256Boundary "$current_core_boundary" \
  --arg currentCoreSha256WriteBoundary "$current_core_write" \
  --arg currentCoreSha256After "$current_core_after" \
  --arg writeScope "$write_scope" \
  --argjson versionCountBefore "$count_before" \
  --argjson versionCountBoundary "$count_boundary" \
  --arg versionCountWriteBoundary "$count_write" \
  --argjson versionCountAfter "$count_after" \
  --argjson absentCount "$absent_count" \
  --argjson listedCount "$listed_count" \
  '{
    schemaVersion: 3,
    operation: $operation,
    projectId: $projectId,
    bodyState: $bodyState,
    bodySha256Before: $bodySha256Before,
    bodySha256After: $bodySha256After,
    releaseTag: $releaseTag,
    releaseTagObject: $releaseTagObject,
    sourceCommit: $sourceCommit,
    controllerCommit: $controllerCommit,
    canonicalManifestFileSha256: $canonicalManifestFileSha256,
    canonicalManifestSha256: $canonicalManifestSha256,
    recoveryManifestFileSha256: $recoveryManifestFileSha256,
    manifestContractSha256: $manifestContractSha256,
    artifactReceiptSha256: $artifactReceiptSha256,
    operationReceiptSha256: $operationReceiptSha256,
    historicalPreflights: [$historical0[0], $historical1[0], $historical2[0]],
    projectStatusBefore: $statusBefore,
    projectStatusBoundary: $statusBoundary,
    projectStatusWriteBoundary: (if $statusWriteBoundary == "" then null else $statusWriteBoundary end),
    projectStatusAfter: $statusAfter,
    requestedStatusBefore: $requestedStatusBefore,
    requestedStatusBoundary: $requestedStatusBoundary,
    requestedStatusWriteBoundary: (if $requestedStatusWriteBoundary == "" then null else $requestedStatusWriteBoundary end),
    requestedStatusAfter: $requestedStatusAfter,
    versionNumberInventorySha256Before: $versionNumberInventorySha256Before,
    versionNumberInventorySha256Boundary: $versionNumberInventorySha256Boundary,
    versionNumberInventorySha256WriteBoundary: (if $versionNumberInventorySha256WriteBoundary == "" then null else $versionNumberInventorySha256WriteBoundary end),
    versionNumberInventorySha256After: $versionNumberInventorySha256After,
    baselineMetadataSha256Before: $baselineMetadataSha256Before,
    baselineMetadataSha256Boundary: $baselineMetadataSha256Boundary,
    baselineMetadataSha256WriteBoundary: (if $baselineMetadataSha256WriteBoundary == "" then null else $baselineMetadataSha256WriteBoundary end),
    baselineMetadataSha256After: $baselineMetadataSha256After,
    currentMetadataSha256Before: $currentMetadataSha256Before,
    currentMetadataSha256Boundary: $currentMetadataSha256Boundary,
    currentMetadataSha256WriteBoundary: (if $currentMetadataSha256WriteBoundary == "" then null else $currentMetadataSha256WriteBoundary end),
    currentMetadataSha256After: $currentMetadataSha256After,
    historicalCoreContractSha256: $historicalCoreContractSha256,
    historicalCoreSha256Boundary: $historicalCoreSha256Boundary,
    historicalCoreSha256WriteBoundary: (if $historicalCoreSha256WriteBoundary == "" then null else $historicalCoreSha256WriteBoundary end),
    historicalCoreSha256After: $historicalCoreSha256After,
    currentCoreContractSha256: $currentCoreContractSha256,
    currentCoreSha256Before: $currentCoreSha256Before,
    currentCoreSha256Boundary: $currentCoreSha256Boundary,
    currentCoreSha256WriteBoundary: (if $currentCoreSha256WriteBoundary == "" then null else $currentCoreSha256WriteBoundary end),
    currentCoreSha256After: $currentCoreSha256After,
    versionCountBefore: $versionCountBefore,
    versionCountBoundary: $versionCountBoundary,
    versionCountWriteBoundary: (if $versionCountWriteBoundary == "" then null else ($versionCountWriteBoundary | tonumber) end),
    versionCountAfter: $versionCountAfter,
    absentCount: $absentCount,
    listedCount: $listedCount,
    writeScope: $writeScope
  }' > "$receipt_temporary"
chmod a-w -- "$receipt_temporary"
mv -f -- "$receipt_temporary" "$receipt"
receipt_temporary=''

echo "Modrinth v0.1.3 backlog $operation completed without changing the shared project body or tagged artifacts."

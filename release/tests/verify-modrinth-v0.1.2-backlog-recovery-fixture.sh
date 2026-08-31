#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
runner_source="$repository_root/release/run-modrinth-v0.1.2-backlog-recovery.sh"
contract_source="$repository_root/release/modrinth-v0.1.2-backlog-recovery.json"
artifact_evidence_source="$repository_root/release/modrinth-v0.1.0-artifacts.json"

fail() {
  echo "$1" >&2
  exit 1
}

for required in "$runner_source" "$contract_source" "$artifact_evidence_source"; do
  [[ -f "$required" && ! -L "$required" ]] || fail "Recovery fixture input is missing or unsafe: $required"
done

real_git="$(command -v git)"
real_jq="$(command -v jq)"
real_rm="$(command -v rm)"
real_sha256sum="$(command -v sha256sum)"
real_sha512sum="$(command -v sha512sum)"
fixture_parent="${TMPDIR:-/tmp}"
[[ -d "$fixture_parent" && ! -L "$fixture_parent" ]] || fail 'The recovery fixture temporary parent is unsafe.'
test_root="$(mktemp -d "$fixture_parent/strata-modrinth-v012-backlog-fixture.XXXXXX")"
fixture_parent_resolved="$(realpath "$fixture_parent")"
test_root_resolved="$(realpath "$test_root")"
[[ "$test_root_resolved" == "$fixture_parent_resolved/strata-modrinth-v012-backlog-fixture."* ]] || \
  fail 'The recovery fixture escaped its bounded temporary parent.'

cleanup() {
  local cleanup_target
  cleanup_target="$(realpath -m "$test_root")"
  if [[ "$cleanup_target" != "$fixture_parent_resolved/strata-modrinth-v012-backlog-fixture."* ]]; then
    echo 'Refusing to clean an unbounded recovery fixture path.' >&2
    return 1
  fi
  chmod -R u+w -- "$test_root" >/dev/null 2>&1 || true
  "$real_rm" -rf -- "$test_root"
}
trap cleanup EXIT INT TERM

fixture_repository="$test_root/repository"
fixture_bundle="$fixture_repository/controller"
fixture_bin="$test_root/bin"
fixture_data="$test_root/data"
fixture_state="$test_root/state"
fixture_runner_temp="$test_root/runner-temp"
fixture_log="$test_root/gradle-tasks.log"
mkdir -p \
  "$fixture_bundle" \
  "$fixture_bin" \
  "$fixture_data/manifests" \
  "$fixture_data/versions" \
  "$fixture_repository/release" \
  "$fixture_repository/build/release/modrinth/artifacts" \
  "$fixture_runner_temp" \
  "$fixture_state"

cp "$runner_source" "$fixture_bundle/run-modrinth-v0.1.2-backlog-recovery.sh"
cp "$contract_source" "$fixture_bundle/modrinth-v0.1.2-backlog-recovery.json"
cp "$artifact_evidence_source" "$fixture_bundle/modrinth-v0.1.0-artifacts.json"

source_commit='b541fc5492b798b6805c0c4d24e09f43ceff938a'
source_tag_object='49195293b3e163abd0beefc9fc8e61a428b8eb24'
v010_commit='d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8'
v010_tag_object='ccf221fe7f133fe5598fafc4ad01e6bc69ba2230'
v011_commit='6e35f5984bbca06c18f8ca8080f45a70b09831bb'
v011_tag_object='013bb6b0c4835229402f5843b967151f5dfdc5b2'
controller_commit="$("$real_git" -C "$repository_root" rev-parse origin/master)"
[[ "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || fail 'The fixture controller commit is not a full SHA-1.'
pages_record='101 release-pages v0.1.2 202 controller-pages controller'

"$real_git" -C "$repository_root" show "$v010_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.0-body.md"
"$real_git" -C "$repository_root" show "$v011_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.1-body.md"
"$real_git" -C "$repository_root" show "$source_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.2-body.md"
[[ "$("$real_sha256sum" "$fixture_data/v0.1.0-body.md" | cut -d ' ' -f 1)" == \
  'd036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33' ]] || \
  fail 'The fixture could not recover the exact v0.1.0 body.'

jq '.baselineReleases[1].gameVersions' "$contract_source" > "$fixture_data/v0.1.1-games.json"
cp "$fixture_data/v0.1.1-games.json" "$fixture_data/v0.1.2-games.json"

generate_artifacts() {
  local release_version="$1"
  local games_file="$2"
  local artifact_directory="$3"
  local output_file="$4"
  local records="$test_root/artifacts-$release_version.ndjson"
  local game_version file_name relative_path artifact_file size sha256 sha512
  : > "$records"
  mkdir -p "$artifact_directory"
  while IFS= read -r game_version; do
    file_name="strata-runtime-minecraft-fabric-$game_version-$release_version.jar"
    relative_path="artifacts/$file_name"
    artifact_file="$artifact_directory/$file_name"
    printf 'isolated fixture artifact %s for Minecraft %s\n' "$release_version" "$game_version" > "$artifact_file"
    size="$(wc -c < "$artifact_file" | tr -d '[:space:]')"
    sha256="$("$real_sha256sum" "$artifact_file" | cut -d ' ' -f 1)"
    sha512="$("$real_sha512sum" "$artifact_file" | cut -d ' ' -f 1)"
    jq --null-input \
      --arg gameVersion "$game_version" \
      --arg versionNumber "$release_version+mc$game_version" \
      --arg versionName "Strata $release_version for Minecraft $game_version" \
      --arg fileName "$file_name" \
      --arg relativePath "$relative_path" \
      --arg sha256 "$sha256" \
      --arg sha512 "$sha512" \
      --argjson size "$size" '
        {
          gameVersion: $gameVersion,
          versionNumber: $versionNumber,
          versionName: $versionName,
          fileName: $fileName,
          relativePath: $relativePath,
          size: $size,
          sha256: $sha256,
          sha512: $sha512
        }
      ' >> "$records"
  done < <(jq -r '.[]' "$games_file" | tr -d '\r')
  jq --slurp '.' "$records" > "$output_file"
}

generate_artifacts \
  '0.1.1' \
  "$fixture_data/v0.1.1-games.json" \
  "$fixture_data/v0.1.1-artifacts" \
  "$fixture_data/v0.1.1-artifacts.json"
generate_artifacts \
  '0.1.2' \
  "$fixture_data/v0.1.2-games.json" \
  "$fixture_repository/build/release/modrinth/artifacts" \
  "$fixture_data/v0.1.2-artifacts.json"
jq '
    [
      .artifacts[] |
      . + {
        versionName: ("Strata 0.1.0 for Minecraft " + .gameVersion),
        relativePath: ("artifacts/" + .fileName)
      }
    ]
  ' "$artifact_evidence_source" > "$fixture_data/v0.1.0-artifacts.json"

generate_manifest() {
  local release_version="$1"
  local body_file="$2"
  local previous_body_sha256="$3"
  local artifacts_file="$4"
  local output_file="$5"
  jq --null-input \
    --arg releaseVersion "$release_version" \
    --rawfile body "$body_file" \
    --arg previousBodySha256 "$previous_body_sha256" \
    --slurpfile artifacts "$artifacts_file" '
      {
        schemaVersion: 1,
        projectId: "CAdZ3jVr",
        releaseVersion: $releaseVersion,
        changelog: ("Isolated fixture changelog for " + $releaseVersion + ".\n"),
        requiredProjectDependencies: ["P7dR8mSH"],
        loader: "fabric",
        versionType: "release",
        featured: false,
        environment: "client_only",
        project: {
          slug: "strata-ui",
          body: $body,
          previousBodySha256: (if $previousBodySha256 == "" then null else $previousBodySha256 end)
        },
        artifacts: $artifacts[0]
      }
    ' > "$output_file"
}

generate_manifest \
  '0.1.0' \
  "$fixture_data/v0.1.0-body.md" \
  '' \
  "$fixture_data/v0.1.0-artifacts.json" \
  "$fixture_data/manifests/v0.1.0.json"
generate_manifest \
  '0.1.1' \
  "$fixture_data/v0.1.1-body.md" \
  'd036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33' \
  "$fixture_data/v0.1.1-artifacts.json" \
  "$fixture_data/manifests/v0.1.1.json"
generate_manifest \
  '0.1.2' \
  "$fixture_data/v0.1.2-body.md" \
  '3e79db82a612df73b387d1bc5a4b8b1f495ff0ae9042d1a9bae7a01da14bda1b' \
  "$fixture_data/v0.1.2-artifacts.json" \
  "$fixture_data/manifests/v0.1.2.json"
cp "$fixture_data/manifests/v0.1.2.json" "$fixture_repository/build/release/modrinth/manifest.json"

manifest_to_versions() {
  local manifest_file="$1"
  local output_file="$2"
  jq '
      . as $manifest |
      [
        .artifacts[] |
        {
          id: ("fixture-" + (.versionNumber | gsub("[^0-9A-Za-z]"; "-"))),
          project_id: $manifest.projectId,
          author_id: "fixture-author",
          name: .versionName,
          version_number: .versionNumber,
          changelog: $manifest.changelog,
          dependencies: ($manifest.requiredProjectDependencies | map({
            project_id: ., version_id: null, file_name: null, dependency_type: "required"
          })),
          game_versions: [.gameVersion],
          version_type: $manifest.versionType,
          loaders: [$manifest.loader],
          featured: $manifest.featured,
          status: "listed",
          environment: $manifest.environment,
          files: [{
            filename: .fileName,
            primary: true,
            size: .size,
            url: ("https://cdn.example.invalid/" + .fileName),
            file_type: null,
            hashes: {sha512: .sha512, sha1: null, sha256: .sha256}
          }]
        }
      ] | sort_by(.version_number)
    ' "$manifest_file" > "$output_file"
}

manifest_to_versions "$fixture_data/manifests/v0.1.0.json" "$fixture_data/versions/v0.1.0.json"
manifest_to_versions "$fixture_data/manifests/v0.1.1.json" "$fixture_data/versions/v0.1.1.json"
manifest_to_versions "$fixture_data/manifests/v0.1.2.json" "$fixture_data/versions/v0.1.2.json"
jq --slurp 'add | sort_by(.version_number)' \
  "$fixture_data/versions/v0.1.0.json" \
  "$fixture_data/versions/v0.1.1.json" > "$fixture_data/baseline-versions.json"
cp "$fixture_data/baseline-versions.json" "$fixture_state/versions.json"
jq --null-input --rawfile body "$fixture_data/v0.1.0-body.md" '
    {
      id: "CAdZ3jVr",
      slug: "strata-ui",
      project_type: "mod",
      status: "processing",
      requested_status: "approved",
      body: $body
    }
  ' > "$fixture_state/project.json"

cat > "$fixture_repository/release/verify-release-tag.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
cat > "$fixture_bundle/verify-github-tag-ruleset.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
cat > "$fixture_bundle/verify-pages-deployment-source.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$FIXTURE_PAGES_RECORD"
EOF
printf '{}\n' > "$fixture_bundle/github-release-tag-ruleset.json"
printf '{}\n' > "$fixture_bundle/github-release-tag-ruleset-receipt.json"

fake_gradle="$fixture_data/fake-gradlew"
cat > "$fake_gradle" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

task=''
revision=''
for argument in "$@"; do
  case "$argument" in
    modrinthReleaseManifest|modrinthReleasePreflight|modrinthReleaseStage)
      if [[ -z "$task" ]]; then task="$argument"; fi
      ;;
    -Pstrata.sourceRevision=*) revision="${argument#*=}" ;;
  esac
done
[[ -n "$task" && -n "$revision" ]] || { echo 'The fixture Gradle invocation is incomplete.' >&2; exit 1; }
printf '%s\t%s\n' "$revision" "$task" >> "$FIXTURE_GRADLE_LOG"
mkdir -p build/release/modrinth build/release/modrinth-receipts

case "$task" in
  modrinthReleaseManifest)
    cp "$FIXTURE_DATA_DIRECTORY/manifests/$revision.json" build/release/modrinth/manifest.json
    ;;
  modrinthReleasePreflight)
    jq --slurpfile remote "$FIXTURE_STATE_DIRECTORY/versions.json" \
      --arg project 'CAdZ3jVr' \
      --arg status "$(jq -er '.status' "$FIXTURE_STATE_DIRECTORY/project.json")" '
        {
          operation: "preflight",
          projectId: $project,
          projectStatus: $status,
          absent: [
            .artifacts[] |
            select(.versionNumber as $number | ($remote[0] | map(.version_number) | index($number)) == null)
          ],
          listed: [
            .artifacts[] |
            select(.versionNumber as $number | ($remote[0] | map(.version_number) | index($number)) != null)
          ]
        }
      ' build/release/modrinth/manifest.json > build/release/modrinth-receipts/preflight.json
    ;;
  modrinthReleaseStage)
    state_temporary="$FIXTURE_STATE_DIRECTORY/.versions-stage.json"
    jq --slurpfile current "$FIXTURE_DATA_DIRECTORY/versions/v0.1.2.json" '
        . as $existing |
        reduce $current[0][] as $candidate (
          $existing;
          if (map(.version_number) | index($candidate.version_number)) == null
          then . + [$candidate]
          else .
          end
        ) | sort_by(.version_number)
      ' "$FIXTURE_STATE_DIRECTORY/versions.json" > "$state_temporary"
    mv "$state_temporary" "$FIXTURE_STATE_DIRECTORY/versions.json"
    jq --arg project 'CAdZ3jVr' \
      --arg status "$(jq -er '.status' "$FIXTURE_STATE_DIRECTORY/project.json")" '
        {
          operation: "stage",
          projectId: $project,
          projectStatus: $status,
          absent: [],
          listed: [.artifacts[]]
        }
      ' build/release/modrinth/manifest.json > build/release/modrinth-receipts/stage.json
    ;;
  *) echo "Unexpected fixture Gradle task: $task" >&2; exit 1 ;;
esac
EOF

cat > "$fixture_bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" != '0' ]]; then
  if [[ "$#" == '1' ]]; then
    case "$1" in
      */0.1.0-body-normalized)
        printf '%s  %s\n' 'd036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33' "$1"
        exit 0
        ;;
      */0.1.1-body-normalized)
        printf '%s  %s\n' '3e79db82a612df73b387d1bc5a4b8b1f495ff0ae9042d1a9bae7a01da14bda1b' "$1"
        exit 0
        ;;
      */current-body)
        printf '%s  %s\n' 'f214e060534509ae59024a4bd9295d39a07c29e7f1665d2a9e943a90fe7f34f9' "$1"
        exit 0
        ;;
      */before-body|*/boundary-body|*/write-boundary-body|*/after-body)
        printf '%s  %s\n' 'd036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33' "$1"
        exit 0
        ;;
    esac
  fi
  exec "$FIXTURE_REAL_SHA256SUM" "$@"
fi
input="$(mktemp "$FIXTURE_RUNNER_TEMP/fixture-sha256.XXXXXX")"
trap 'rm -f -- "$input"' EXIT
cat > "$input"
if jq -e '
    type == "object" and
    .releaseTag == "v0.1.0" and
    .releaseCommit == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" and
    (.artifacts | length) == 20
  ' "$input" >/dev/null 2>&1; then
  printf '%s  -\n' '29cda6910f37d01c98f2fc47412868b493cddbe5a3ff70869222757045d83b3a'
  exit 0
fi
if jq -e '
    type == "object" and
    has("releaseVersion") and
    has("project") and
    (.project | has("previousBodySha256")) and
    (.artifacts | type == "array" and length > 0) and
    all(.artifacts[]; has("size") and has("sha256") and has("sha512"))
  ' "$input" >/dev/null 2>&1; then
  case "$(jq -er '.releaseVersion' "$input")" in
    0.1.0) digest='0392b28deb9e7838dbe9b6a25e9fd49cac510e4045bb1ebcff7682a4f7e83e08' ;;
    0.1.1) digest='ba15a712879d2fd1191a34015262d236a7f28169bf9946af4b52534d98cd413c' ;;
    0.1.2) digest='5a26f471445f2e53d5c24e6431bf902d5d659e449a9d295d3c199fa132a757c6' ;;
    *) digest='' ;;
  esac
  if [[ -n "$digest" ]]; then
    printf '%s  -\n' "$digest"
    exit 0
  fi
fi
"$FIXTURE_REAL_SHA256SUM" "$input" | sed 's#  .*#  -#'
EOF

cat > "$fixture_bin/jq" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
"$FIXTURE_REAL_JQ" "$@" | tr -d '\r'
EOF

cat > "$fixture_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

output=''
url=''
while [[ "$#" != '0' ]]; do
  case "$1" in
    --output) output="${2:-}"; shift 2 ;;
    https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[[ -n "$output" && -n "$url" ]] || { echo 'The fixture curl invocation is incomplete.' >&2; exit 1; }
case "$url" in
  'https://api.modrinth.com/v2/project/CAdZ3jVr')
    cp "$FIXTURE_STATE_DIRECTORY/project.json" "$output"
    ;;
  'https://api.modrinth.com/v2/project/CAdZ3jVr/version?include_changelog=true')
    cp "$FIXTURE_STATE_DIRECTORY/versions.json" "$output"
    ;;
  *) echo "The fixture blocked an unexpected network URL: $url" >&2; exit 1 ;;
esac
EOF

cat > "$fixture_bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == '--no-replace-objects' ]]; then
  shift
fi
command_name="${1:-}"
shift || true
case "$command_name" in
  rev-parse)
    if [[ "${1:-}" == '--verify' ]]; then shift; fi
    case "${1:-}" in
      --show-toplevel) printf '%s\n' "$FIXTURE_REPOSITORY" ;;
      HEAD) printf '%s\n' "$FIXTURE_SOURCE_COMMIT" ;;
      origin/master) printf '%s\n' "$FIXTURE_CONTROLLER_COMMIT" ;;
      'refs/tags/v0.1.0^{commit}') printf '%s\n' "$FIXTURE_V010_COMMIT" ;;
      refs/tags/v0.1.0) printf '%s\n' "$FIXTURE_V010_TAG_OBJECT" ;;
      'refs/tags/v0.1.1^{commit}') printf '%s\n' "$FIXTURE_V011_COMMIT" ;;
      refs/tags/v0.1.1) printf '%s\n' "$FIXTURE_V011_TAG_OBJECT" ;;
      'refs/tags/v0.1.2^{commit}') printf '%s\n' "$FIXTURE_SOURCE_COMMIT" ;;
      refs/tags/v0.1.2) printf '%s\n' "$FIXTURE_SOURCE_TAG_OBJECT" ;;
      "$FIXTURE_V010_COMMIT:docs/modrinth-project.md") printf '%s\n' '6b68a2e42278c0e3c389137df25c2ac9d6637d0a' ;;
      "$FIXTURE_V011_COMMIT:docs/modrinth-project.md") printf '%s\n' 'b6b2229e2b2bc679d12b6b43137fa73e80147293' ;;
      "$FIXTURE_SOURCE_COMMIT:docs/modrinth-project.md") printf '%s\n' '075d941895ed9b70bcb42d9814948fb1bdc4a05d' ;;
      *) echo "Unexpected fixture git rev-parse target: ${1:-}" >&2; exit 1 ;;
    esac
    ;;
  cat-file)
    [[ "${1:-}" == 'blob' ]] || { echo 'The fixture permits only git cat-file blob.' >&2; exit 1; }
    case "${2:-}" in
      "$FIXTURE_V010_COMMIT:docs/modrinth-project.md") cat "$FIXTURE_DATA_DIRECTORY/v0.1.0-body.md" ;;
      "$FIXTURE_V011_COMMIT:docs/modrinth-project.md") cat "$FIXTURE_DATA_DIRECTORY/v0.1.1-body.md" ;;
      "$FIXTURE_CONTROLLER_COMMIT:release/verify-controller-tools.sh")
        printf '#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n'
        ;;
      *) echo "Unexpected fixture git cat-file target: ${2:-}" >&2; exit 1 ;;
    esac
    ;;
  merge-base|fetch|status|diff)
    exit 0
    ;;
  worktree)
    subcommand="${1:-}"
    shift || true
    case "$subcommand" in
      add)
        [[ "${1:-}" == '--detach' ]] || { echo 'Fixture worktree add must be detached.' >&2; exit 1; }
        target="${2:-}"
        mkdir -p "$target"
        cp "$FIXTURE_FAKE_GRADLE" "$target/gradlew"
        ;;
      remove)
        [[ "${1:-}" == '--force' ]] || { echo 'Fixture worktree removal must be forced.' >&2; exit 1; }
        target="${2:-}"
        case "$(realpath -m "$target")" in
          "$FIXTURE_RUNNER_TEMP"/strata-modrinth-v012-backlog.*/*-remote-verification)
            "$FIXTURE_REAL_RM" -rf -- "$target"
            ;;
          *) echo 'Fixture worktree cleanup escaped the runner temporary directory.' >&2; exit 1 ;;
        esac
        ;;
      *) echo "Unexpected fixture git worktree operation: $subcommand" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Unexpected fixture git command: $command_name" >&2; exit 1 ;;
esac
EOF

chmod +x "$fake_gradle" "$fixture_bin/sha256sum" "$fixture_bin/jq" "$fixture_bin/curl" "$fixture_bin/git"
cp "$fake_gradle" "$fixture_repository/gradlew"
chmod +x \
  "$fixture_repository/gradlew" \
  "$fixture_repository/release/verify-release-tag.sh" \
  "$fixture_bundle/verify-github-tag-ruleset.sh" \
  "$fixture_bundle/verify-pages-deployment-source.sh"

run_recovery() {
  local operation="$1"
  local output_file="$2"
  local error_file="$3"
  (
    cd "$fixture_repository"
    PATH="$fixture_bin:$PATH" \
      MODRINTH_TOKEN='isolated-fixture-token' \
      GH_TOKEN='isolated-fixture-github-token' \
      RUNNER_TEMP="$fixture_runner_temp" \
      FIXTURE_REPOSITORY="$fixture_repository" \
      FIXTURE_DATA_DIRECTORY="$fixture_data" \
      FIXTURE_STATE_DIRECTORY="$fixture_state" \
      FIXTURE_RUNNER_TEMP="$fixture_runner_temp" \
      FIXTURE_GRADLE_LOG="$fixture_log" \
      FIXTURE_FAKE_GRADLE="$fake_gradle" \
      FIXTURE_REAL_RM="$real_rm" \
      FIXTURE_REAL_JQ="$real_jq" \
      FIXTURE_REAL_SHA256SUM="$real_sha256sum" \
      FIXTURE_CONTROLLER_COMMIT="$controller_commit" \
      FIXTURE_SOURCE_COMMIT="$source_commit" \
      FIXTURE_SOURCE_TAG_OBJECT="$source_tag_object" \
      FIXTURE_V010_COMMIT="$v010_commit" \
      FIXTURE_V010_TAG_OBJECT="$v010_tag_object" \
      FIXTURE_V011_COMMIT="$v011_commit" \
      FIXTURE_V011_TAG_OBJECT="$v011_tag_object" \
      FIXTURE_PAGES_RECORD="$pages_record" \
      bash "$fixture_bundle/run-modrinth-v0.1.2-backlog-recovery.sh" \
        "$operation" \
        "$fixture_bundle/modrinth-v0.1.2-backlog-recovery.json" \
        CAdZ3jVr \
        v0.1.2 \
        "$source_commit" \
        "$controller_commit" \
        "$pages_record"
  ) > "$output_file" 2> "$error_file"
}

manifest_sha256="$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)"
baseline_core_sha256="$(jq -cS . "$fixture_data/baseline-versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)"

if ! run_recovery preflight "$test_root/preflight.out" "$test_root/preflight.err"; then
  sed 's/^/fixture preflight: /' "$test_root/preflight.err" >&2
  fail 'The isolated backlog preflight failed.'
fi
preflight_receipt="$fixture_repository/build/release/modrinth-controller-receipts/preflight.json"
jq -e \
  --arg controller "$controller_commit" '
    .schemaVersion == 3 and
    .operation == "preflight" and
    .controllerCommit == $controller and
    .projectStatusBefore == "processing" and
    .requestedStatusBefore == "approved" and
    .versionCountBefore == 41 and
    .versionCountAfter == 41 and
    .absentCount == 21 and
    .listedCount == 0 and
    .writeScope == "none" and
    (.historicalPreflights | length) == 2
  ' "$preflight_receipt" >/dev/null || fail 'The isolated preflight controller receipt differs.'
[[ "$(jq 'length' "$fixture_state/versions.json")" == '41' ]] || fail 'Preflight changed the 41-version baseline.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Preflight did not restore the canonical v0.1.2 manifest.'

if ! run_recovery stage "$test_root/stage.out" "$test_root/stage.err"; then
  sed 's/^/fixture stage: /' "$test_root/stage.err" >&2
  fail 'The isolated backlog stage failed.'
fi
stage_receipt="$fixture_repository/build/release/modrinth-controller-receipts/stage.json"
jq -e '
    .schemaVersion == 3 and
    .operation == "stage" and
    .projectStatusBefore == "processing" and
    .projectStatusWriteBoundary == "processing" and
    .requestedStatusWriteBoundary == "approved" and
    .versionCountBefore == 41 and
    .versionCountWriteBoundary == 41 and
    .versionCountAfter == 62 and
    .absentCount == 0 and
    .listedCount == 21 and
    .writeScope == "missing-v0.1.2-versions-only" and
    .bodySha256Before == .bodySha256After
  ' "$stage_receipt" >/dev/null || fail 'The isolated stage controller receipt differs.'
jq -e 'length == 62 and ([.[].version_number] | length == (unique | length))' "$fixture_state/versions.json" >/dev/null || \
  fail 'Stage did not produce exactly 62 unique versions.'
after_baseline_core_sha256="$(
  jq '[.[] | select(.version_number | startswith("0.1.0+") or startswith("0.1.1+"))]' "$fixture_state/versions.json" |
    jq -cS . | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1
)"
[[ "$after_baseline_core_sha256" == "$baseline_core_sha256" ]] || fail 'Stage changed a historical remote version fixture.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Stage did not restore the canonical v0.1.2 manifest.'

state_before_observe="$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)"
if ! run_recovery observe "$test_root/observe.out" "$test_root/observe.err"; then
  sed 's/^/fixture observe: /' "$test_root/observe.err" >&2
  fail 'The isolated backlog observation failed.'
fi
observe_receipt="$fixture_repository/build/release/modrinth-controller-receipts/observe.json"
jq -e '
    .schemaVersion == 3 and
    .operation == "observe" and
    .versionCountBefore == 62 and
    .versionCountAfter == 62 and
    .absentCount == 0 and
    .listedCount == 21 and
    .writeScope == "none" and
    .projectStatusWriteBoundary == null
  ' "$observe_receipt" >/dev/null || fail 'The isolated observe controller receipt differs.'
[[ "$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)" == "$state_before_observe" ]] || \
  fail 'Observe changed the exact 62-version state.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Observe did not restore the canonical v0.1.2 manifest.'

if grep --extended-regexp --line-regexp '[^[:space:]]+[[:space:]]+(modrinthReleaseSubmit|modrinthReleaseFinalizeProject|modrinthReleaseVerify|publishAndReleaseToMavenCentral)' \
  "$fixture_log" >/dev/null; then
  fail 'The isolated success path invoked a forbidden remote mutation or finalizer task.'
fi
[[ "$(grep --fixed-strings -c $'v0.1.2\tmodrinthReleaseStage' "$fixture_log")" == '1' ]] || \
  fail 'The isolated success path did not invoke exactly one append-only stage task.'

cp "$fixture_data/baseline-versions.json" "$fixture_state/versions.json"
jq '.[0].name += " metadata-drift"' "$fixture_state/versions.json" > "$fixture_state/.versions-drift.json"
mv "$fixture_state/.versions-drift.json" "$fixture_state/versions.json"
drift_state_sha256="$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)"
chmod u+w -- "$preflight_receipt" >/dev/null 2>&1 || true
rm -f -- "$preflight_receipt"
if run_recovery preflight "$test_root/drift.out" "$test_root/drift.err"; then
  fail 'The isolated runner accepted historical metadata drift.'
fi
grep --fixed-strings 'Historical Modrinth version metadata differs from the two signed release manifests.' \
  "$test_root/drift.err" >/dev/null || fail 'Historical metadata drift did not fail at the exact core-contract boundary.'
[[ "$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)" == "$drift_state_sha256" ]] || \
  fail 'The rejected metadata-drift path changed remote fixture state.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'The rejected metadata-drift path changed the canonical manifest.'
[[ ! -e "$preflight_receipt" && ! -L "$preflight_receipt" ]] || fail 'The rejected metadata-drift path published a controller receipt.'

echo 'Isolated Modrinth v0.1.2 backlog recovery fixture passed.'

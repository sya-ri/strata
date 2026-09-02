#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
runner_source="$repository_root/release/run-modrinth-v0.1.3-backlog-recovery.sh"
contract_source="$repository_root/release/modrinth-v0.1.3-backlog-recovery.json"
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
test_root="$(mktemp -d "$fixture_parent/strata-modrinth-v013-backlog-fixture.XXXXXX")"
fixture_parent_resolved="$(realpath "$fixture_parent")"
test_root_resolved="$(realpath "$test_root")"
[[ "$test_root_resolved" == "$fixture_parent_resolved/strata-modrinth-v013-backlog-fixture."* ]] || \
  fail 'The recovery fixture escaped its bounded temporary parent.'

cleanup() {
  local cleanup_target
  cleanup_target="$(realpath -m "$test_root")"
  if [[ "$cleanup_target" != "$fixture_parent_resolved/strata-modrinth-v013-backlog-fixture."* ]]; then
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

cp "$runner_source" "$fixture_bundle/run-modrinth-v0.1.3-backlog-recovery.sh"
cp "$contract_source" "$fixture_bundle/modrinth-v0.1.3-backlog-recovery.json"
cp "$artifact_evidence_source" "$fixture_bundle/modrinth-v0.1.0-artifacts.json"

source_commit='86bfe6a0d7a229b107538101b7ef4abee10e7fae'
source_tag_object='7bb84df1a4ae40be6699a866411e4c66b3bfe0bd'
v010_commit='d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8'
v010_tag_object='ccf221fe7f133fe5598fafc4ad01e6bc69ba2230'
v011_commit='6e35f5984bbca06c18f8ca8080f45a70b09831bb'
v011_tag_object='013bb6b0c4835229402f5843b967151f5dfdc5b2'
v012_commit='b541fc5492b798b6805c0c4d24e09f43ceff938a'
v012_tag_object='49195293b3e163abd0beefc9fc8e61a428b8eb24'
current_manifest_pin="$(jq -er '.releaseSource.manifestCanonicalSha256' "$contract_source")"
[[ "$current_manifest_pin" == '6087d67cf83f316e125ef8f2af538bb1fd30e709b33325271db2e6cbb4d82fdc' ]] || \
  fail 'The fixture current manifest pin differs from the exact v0.1.3 contract.'
controller_commit="$("$real_git" -C "$repository_root" rev-parse origin/master)"
[[ "$controller_commit" =~ ^[0-9a-f]{40}$ ]] || fail 'The fixture controller commit is not a full SHA-1.'
pages_record='101 release-pages v0.1.3 202 controller-pages controller'

"$real_git" -C "$repository_root" show "$v010_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.0-body.md"
"$real_git" -C "$repository_root" show "$v011_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.1-body.md"
"$real_git" -C "$repository_root" show "$v012_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.2-body.md"
"$real_git" -C "$repository_root" show "$source_commit:docs/modrinth-project.md" > "$fixture_data/v0.1.3-body.md"
[[ "$("$real_sha256sum" "$fixture_data/v0.1.0-body.md" | cut -d ' ' -f 1)" == \
  'd036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33' ]] || \
  fail 'The fixture could not recover the exact v0.1.0 body.'

jq '.baselineReleases[1].gameVersions' "$contract_source" > "$fixture_data/v0.1.1-games.json"
cp "$fixture_data/v0.1.1-games.json" "$fixture_data/v0.1.3-games.json"
jq '.baselineReleases[2].gameVersions' "$contract_source" > "$fixture_data/v0.1.2-games.json"

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
  "$fixture_data/v0.1.2-artifacts" \
  "$fixture_data/v0.1.2-artifacts.json"
generate_artifacts \
  '0.1.3' \
  "$fixture_data/v0.1.3-games.json" \
  "$fixture_repository/build/release/modrinth/artifacts" \
  "$fixture_data/v0.1.3-artifacts.json"
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
generate_manifest \
  '0.1.3' \
  "$fixture_data/v0.1.3-body.md" \
  'f214e060534509ae59024a4bd9295d39a07c29e7f1665d2a9e943a90fe7f34f9' \
  "$fixture_data/v0.1.3-artifacts.json" \
  "$fixture_data/manifests/v0.1.3.json"
cp "$fixture_data/manifests/v0.1.3.json" "$fixture_repository/build/release/modrinth/manifest.json"
cp -R "$fixture_data/manifests" "$fixture_data/golden-manifests"
cp -R "$fixture_repository/build/release/modrinth/artifacts" "$fixture_data/golden-artifacts"

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
manifest_to_versions "$fixture_data/manifests/v0.1.3.json" "$fixture_data/versions/v0.1.3.json"
jq --slurp 'add | sort_by(.version_number)' \
  "$fixture_data/versions/v0.1.0.json" \
  "$fixture_data/versions/v0.1.1.json" \
  "$fixture_data/versions/v0.1.2.json" > "$fixture_data/baseline-versions.json"
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
cp "$fixture_state/project.json" "$fixture_data/baseline-project.json"

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
    if [[ "$revision" == 'v0.1.2' ]]; then
      cp build/release/modrinth/manifest.json "$FIXTURE_STATE_DIRECTORY/historical-original.json"
    fi
    ;;
  modrinthReleasePreflight)
    if [[ "$revision" == 'v0.1.2' || "$revision" == 'v0.1.3' ]]; then
      jq -e --slurpfile original "$FIXTURE_DATA_DIRECTORY/golden-manifests/$revision.json" '
          .project.previousBodySha256 == "d036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33" and
          (del(.project.previousBodySha256) == ($original[0] | del(.project.previousBodySha256)))
        ' build/release/modrinth/manifest.json >/dev/null || {
          echo "Fixture rejected a missing or non-minimal recovery overlay: $revision" >&2
          exit 1
        }
      printf 'overlay:%s\n' "$revision" >> "$FIXTURE_BOUNDARY_LOG"
    fi
    if [[ "$revision" == 'v0.1.2' ]]; then
      case "$FIXTURE_FAULT" in
        approve-during-history)
          jq '.status = "approved" | .requested_status = null' \
            "$FIXTURE_STATE_DIRECTORY/project.json" > "$FIXTURE_STATE_DIRECTORY/.project-history.json"
          mv "$FIXTURE_STATE_DIRECTORY/.project-history.json" "$FIXTURE_STATE_DIRECTORY/project.json"
          printf 'approved-during-history\n' >> "$FIXTURE_BOUNDARY_LOG"
          ;;
        historical-preflight-fail)
          echo 'Injected historical preflight failure.' >&2
          exit 1
          ;;
        historical-preflight-interrupt)
          echo 'Injected historical preflight interruption.' >&2
          kill -TERM "$PPID"
          exit 143
          ;;
      esac
    fi
    if [[ "$revision" == 'v0.1.3' && "$FIXTURE_FAULT" == 'current-preflight-fail' ]]; then
      echo 'Injected current preflight failure.' >&2
      exit 1
    fi
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
    case "$FIXTURE_FAULT:$revision" in
      historical-operation-drift:v0.1.2|current-operation-drift:v0.1.3)
        jq '.project.slug = "changed-after-preflight"' build/release/modrinth/manifest.json \
          > build/release/modrinth/.changed-manifest.json
        mv build/release/modrinth/.changed-manifest.json build/release/modrinth/manifest.json
        printf 'operation-drift:%s\n' "$revision" >> "$FIXTURE_BOUNDARY_LOG"
        ;;
    esac
    ;;
  modrinthReleaseStage)
    jq -e --slurpfile original "$FIXTURE_DATA_DIRECTORY/golden-manifests/v0.1.3.json" '
        .project.previousBodySha256 == "d036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33" and
        (del(.project.previousBodySha256) == ($original[0] | del(.project.previousBodySha256)))
      ' build/release/modrinth/manifest.json >/dev/null || {
        echo 'Fixture rejected a missing or non-minimal stage overlay.' >&2
        exit 1
      }
    printf 'stage-write\n' >> "$FIXTURE_BOUNDARY_LOG"
    state_temporary="$FIXTURE_STATE_DIRECTORY/.versions-stage.json"
    jq --slurpfile current "$FIXTURE_DATA_DIRECTORY/versions/v0.1.3.json" '
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
    case "$FIXTURE_FAULT" in
      stage-baseline-drift)
        jq 'map(if (.version_number | startswith("0.1.2+")) then .name += " drift" else . end)' \
          "$FIXTURE_STATE_DIRECTORY/versions.json" > "$state_temporary"
        mv "$state_temporary" "$FIXTURE_STATE_DIRECTORY/versions.json"
        ;;
      stage-artifact-drift)
        printf 'changed\n' >> "$FIXTURE_REPOSITORY/build/release/modrinth/artifacts/strata-runtime-minecraft-fabric-1.20-0.1.3.jar"
        ;;
    esac
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
  exec "$FIXTURE_REAL_SHA256SUM" "$@"
fi
input="$(mktemp "$FIXTURE_RUNNER_TEMP/fixture-sha256.XXXXXX")"
trap 'rm -f -- "$input"' EXIT
cat > "$input"
release_version="$("$FIXTURE_REAL_JQ" -r 'if type == "object" then .releaseVersion // empty else empty end' "$input" 2>/dev/null || true)"
case "$release_version" in
  0.1.0|0.1.1|0.1.2|0.1.3)
  revision="v$release_version"
  golden="$FIXTURE_DATA_DIRECTORY/golden-manifests/$revision.json"
  if cmp -s "$input" <("$FIXTURE_REAL_JQ" -cS . "$golden" | tr -d '\r'); then
    case "$revision" in
      v0.1.0) digest='0392b28deb9e7838dbe9b6a25e9fd49cac510e4045bb1ebcff7682a4f7e83e08' ;;
      v0.1.1) digest='ba15a712879d2fd1191a34015262d236a7f28169bf9946af4b52534d98cd413c' ;;
      v0.1.2) digest='5a26f471445f2e53d5c24e6431bf902d5d659e449a9d295d3c199fa132a757c6' ;;
      v0.1.3) digest="$FIXTURE_CURRENT_MANIFEST_PIN" ;;
    esac
    printf '%s  -\n' "$digest"
    exit 0
  fi
  ;;
esac
"$FIXTURE_REAL_SHA256SUM" "$input" | sed 's#  .*#  -#'
EOF

cat > "$fixture_bin/jq" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
overlay=0
for argument in "$@"; do
  if [[ "$argument" == *'.project.previousBodySha256 = '* ]]; then overlay=1; fi
done
if [[ "$overlay" == '1' && "$FIXTURE_FAULT" == *'-overlay-'* ]]; then
  output="$(mktemp "$FIXTURE_RUNNER_TEMP/fixture-jq.XXXXXX")"
  trap 'rm -f -- "$output"' EXIT
  "$FIXTURE_REAL_JQ" "$@" | tr -d '\r' > "$output"
  revision="$("$FIXTURE_REAL_JQ" -r '.releaseVersion' "$output")"
  case "$FIXTURE_FAULT:$revision" in
    historical-overlay-field:0.1.2|current-overlay-field:0.1.3)
      "$FIXTURE_REAL_JQ" '.project.slug = "fixture-drift"' "$output" | tr -d '\r'
      exit 0
      ;;
    historical-overlay-artifact:0.1.2|current-overlay-artifact:0.1.3)
      "$FIXTURE_REAL_JQ" '.artifacts[0].size += 1' "$output" | tr -d '\r'
      exit 0
      ;;
  esac
  cat "$output"
  exit 0
fi
"$FIXTURE_REAL_JQ" "$@" | tr -d '\r'
EOF

cat > "$fixture_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

output=''
url=''
method='GET'
while [[ "$#" != '0' ]]; do
  case "$1" in
    --output) output="${2:-}"; shift 2 ;;
    --request|-X) method="${2:-}"; shift 2 ;;
    https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[[ -n "$output" && -n "$url" ]] || { echo 'The fixture curl invocation is incomplete.' >&2; exit 1; }
[[ "$method" == 'GET' ]] || { echo 'The fixture blocked a mutating HTTP request.' >&2; exit 1; }
case "$url" in
  'https://api.modrinth.com/v2/project/CAdZ3jVr')
    count="$(cat "$FIXTURE_STATE_DIRECTORY/project-read-count")"
    count="$((count + 1))"
    printf '%s\n' "$count" > "$FIXTURE_STATE_DIRECTORY/project-read-count"
    case "$FIXTURE_FAULT:$count" in
      approve-read-2:2|approve-read-3:3|approve-read-4:4)
        jq '.status = "approved" | .requested_status = null' \
          "$FIXTURE_STATE_DIRECTORY/project.json" > "$FIXTURE_STATE_DIRECTORY/.project-next.json"
        mv "$FIXTURE_STATE_DIRECTORY/.project-next.json" "$FIXTURE_STATE_DIRECTORY/project.json"
        ;;
      regress-read-2:2|regress-read-3:3|regress-read-4:4)
        jq '.status = "processing" | .requested_status = "approved"' \
          "$FIXTURE_STATE_DIRECTORY/project.json" > "$FIXTURE_STATE_DIRECTORY/.project-next.json"
        mv "$FIXTURE_STATE_DIRECTORY/.project-next.json" "$FIXTURE_STATE_DIRECTORY/project.json"
        ;;
      boundary-body-drift:3)
        jq '.body += "drift\n"' \
          "$FIXTURE_STATE_DIRECTORY/project.json" > "$FIXTURE_STATE_DIRECTORY/.project-next.json"
        mv "$FIXTURE_STATE_DIRECTORY/.project-next.json" "$FIXTURE_STATE_DIRECTORY/project.json"
        ;;
      boundary-baseline-drift:3)
        jq 'map(if (.version_number | startswith("0.1.2+")) then .author_id = "changed-author" else . end)' \
          "$FIXTURE_STATE_DIRECTORY/versions.json" > "$FIXTURE_STATE_DIRECTORY/.versions-next.json"
        mv "$FIXTURE_STATE_DIRECTORY/.versions-next.json" "$FIXTURE_STATE_DIRECTORY/versions.json"
        ;;
    esac
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
      origin/master)
        if [[ -f "$FIXTURE_STATE_DIRECTORY/controller-drift" ]]; then
          printf '%040d\n' 9
        else
          printf '%s\n' "$FIXTURE_CONTROLLER_COMMIT"
        fi
        ;;
      'refs/tags/v0.1.0^{commit}') printf '%s\n' "$FIXTURE_V010_COMMIT" ;;
      refs/tags/v0.1.0) printf '%s\n' "$FIXTURE_V010_TAG_OBJECT" ;;
      'refs/tags/v0.1.1^{commit}') printf '%s\n' "$FIXTURE_V011_COMMIT" ;;
      refs/tags/v0.1.1) printf '%s\n' "$FIXTURE_V011_TAG_OBJECT" ;;
      'refs/tags/v0.1.2^{commit}') printf '%s\n' "$FIXTURE_V012_COMMIT" ;;
      refs/tags/v0.1.2) printf '%s\n' "$FIXTURE_V012_TAG_OBJECT" ;;
      'refs/tags/v0.1.3^{commit}') printf '%s\n' "$FIXTURE_SOURCE_COMMIT" ;;
      refs/tags/v0.1.3) printf '%s\n' "$FIXTURE_SOURCE_TAG_OBJECT" ;;
      "$FIXTURE_V010_COMMIT:docs/modrinth-project.md") printf '%s\n' '6b68a2e42278c0e3c389137df25c2ac9d6637d0a' ;;
      "$FIXTURE_V011_COMMIT:docs/modrinth-project.md") printf '%s\n' 'b6b2229e2b2bc679d12b6b43137fa73e80147293' ;;
      "$FIXTURE_V012_COMMIT:docs/modrinth-project.md") printf '%s\n' '075d941895ed9b70bcb42d9814948fb1bdc4a05d' ;;
      "$FIXTURE_SOURCE_COMMIT:docs/modrinth-project.md") printf '%s\n' 'a64da014ecc6befe6924d1210cb1039a51ea90ee' ;;
      *) echo "Unexpected fixture git rev-parse target: ${1:-}" >&2; exit 1 ;;
    esac
    ;;
  cat-file)
    [[ "${1:-}" == 'blob' ]] || { echo 'The fixture permits only git cat-file blob.' >&2; exit 1; }
    case "${2:-}" in
      "$FIXTURE_V010_COMMIT:docs/modrinth-project.md") cat "$FIXTURE_DATA_DIRECTORY/v0.1.0-body.md" ;;
      "$FIXTURE_V011_COMMIT:docs/modrinth-project.md") cat "$FIXTURE_DATA_DIRECTORY/v0.1.1-body.md" ;;
      "$FIXTURE_V012_COMMIT:docs/modrinth-project.md") cat "$FIXTURE_DATA_DIRECTORY/v0.1.2-body.md" ;;
      "$FIXTURE_CONTROLLER_COMMIT:release/verify-controller-tools.sh")
        printf '#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n'
        ;;
      *) echo "Unexpected fixture git cat-file target: ${2:-}" >&2; exit 1 ;;
    esac
    ;;
  fetch)
    count="$(cat "$FIXTURE_STATE_DIRECTORY/fetch-count")"
    count="$((count + 1))"
    printf '%s\n' "$count" > "$FIXTURE_STATE_DIRECTORY/fetch-count"
    if [[ "$FIXTURE_FAULT" == 'controller-drift' && "$count" == '2' ]]; then
      printf 'drift\n' > "$FIXTURE_STATE_DIRECTORY/controller-drift"
    fi
    exit 0
    ;;
  merge-base|status|diff)
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
          "$FIXTURE_RUNNER_TEMP"/strata-modrinth-v013-backlog.*/*-remote-verification)
            if [[ "$target" == *'/0.1.2-remote-verification' && -f "$FIXTURE_STATE_DIRECTORY/historical-original.json" ]]; then
              cmp -s "$target/build/release/modrinth/manifest.json" "$FIXTURE_STATE_DIRECTORY/historical-original.json" || {
                echo 'Fixture found an unrestored v0.1.2 historical manifest.' >&2
                printf 'unrestored:v0.1.2\n' >> "$FIXTURE_BOUNDARY_LOG"
                exit 1
              }
              printf 'restored:v0.1.2\n' >> "$FIXTURE_BOUNDARY_LOG"
              if [[ "$FIXTURE_FAULT" == 'cleanup-fail' ]]; then
                receipt="$FIXTURE_REPOSITORY/build/release/modrinth-controller-receipts/preflight.json"
                [[ -f "$receipt" && ! -L "$receipt" ]] || {
                  echo 'Fixture cleanup failure did not reach the final receipt boundary.' >&2
                  exit 1
                }
                printf 'receipt-before-cleanup-failure\n' >> "$FIXTURE_BOUNDARY_LOG"
                exit 1
              fi
            fi
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
  printf '0\n' > "$fixture_state/project-read-count"
  printf '0\n' > "$fixture_state/fetch-count"
  : > "$fixture_state/boundaries.log"
  "$real_rm" -f -- "$fixture_state/historical-original.json" "$fixture_state/controller-drift"
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
      FIXTURE_BOUNDARY_LOG="$fixture_state/boundaries.log" \
      FIXTURE_FAULT="${fixture_fault:-none}" \
      FIXTURE_CURRENT_MANIFEST_PIN="$current_manifest_pin" \
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
      FIXTURE_V012_COMMIT="$v012_commit" \
      FIXTURE_V012_TAG_OBJECT="$v012_tag_object" \
      FIXTURE_PAGES_RECORD="$pages_record" \
      bash "$fixture_bundle/run-modrinth-v0.1.3-backlog-recovery.sh" \
        "$operation" \
        "$fixture_bundle/modrinth-v0.1.3-backlog-recovery.json" \
        CAdZ3jVr \
        v0.1.3 \
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
    .versionCountBefore == 62 and
    .versionCountAfter == 62 and
    .absentCount == 21 and
    .listedCount == 0 and
    .writeScope == "none" and
    (.historicalPreflights | length) == 3 and
    all(.historicalPreflights[];
      (.canonicalManifestFileSha256 | test("^[0-9a-f]{64}$")) and
      (.recoveryManifestFileSha256 | test("^[0-9a-f]{64}$")) and
      (.manifestContractSha256 | test("^[0-9a-f]{64}$"))
    ) and
    .historicalPreflights[2].manifestCanonicalSha256 == "5a26f471445f2e53d5c24e6431bf902d5d659e449a9d295d3c199fa132a757c6" and
    .historicalPreflights[2].canonicalManifestFileSha256 != .historicalPreflights[2].recoveryManifestFileSha256
  ' "$preflight_receipt" >/dev/null || fail 'The isolated preflight controller receipt differs.'
[[ "$(jq 'length' "$fixture_state/versions.json")" == '62' ]] || fail 'Preflight changed the 62-version baseline.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Preflight did not restore the canonical v0.1.3 manifest.'
grep --fixed-strings 'overlay:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
  fail 'Preflight did not adapt the v0.1.2 predecessor lineage.'
grep --fixed-strings 'restored:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
  fail 'Preflight did not restore the exact v0.1.2 manifest before cleanup.'

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
    .versionCountBefore == 62 and
    .versionCountWriteBoundary == 62 and
    .versionCountAfter == 83 and
    .absentCount == 0 and
    .listedCount == 21 and
    .writeScope == "missing-v0.1.3-versions-only" and
    .bodySha256Before == .bodySha256After
  ' "$stage_receipt" >/dev/null || fail 'The isolated stage controller receipt differs.'
jq -e 'length == 83 and ([.[].version_number] | length == (unique | length))' "$fixture_state/versions.json" >/dev/null || \
  fail 'Stage did not produce exactly 83 unique versions.'
after_baseline_core_sha256="$(
  jq '[.[] | select(.version_number | startswith("0.1.0+") or startswith("0.1.1+") or startswith("0.1.2+"))]' "$fixture_state/versions.json" |
    jq -cS . | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1
)"
[[ "$after_baseline_core_sha256" == "$baseline_core_sha256" ]] || fail 'Stage changed a historical remote version fixture.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Stage did not restore the canonical v0.1.3 manifest.'

state_before_observe="$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)"
if ! run_recovery observe "$test_root/observe.out" "$test_root/observe.err"; then
  sed 's/^/fixture observe: /' "$test_root/observe.err" >&2
  fail 'The isolated backlog observation failed.'
fi
observe_receipt="$fixture_repository/build/release/modrinth-controller-receipts/observe.json"
jq -e '
    .schemaVersion == 3 and
    .operation == "observe" and
    .versionCountBefore == 83 and
    .versionCountAfter == 83 and
    .absentCount == 0 and
    .listedCount == 21 and
    .writeScope == "none" and
    .projectStatusWriteBoundary == null
  ' "$observe_receipt" >/dev/null || fail 'The isolated observe controller receipt differs.'
[[ "$(jq -cS . "$fixture_state/versions.json" | tr -d '\r' | "$real_sha256sum" | cut -d ' ' -f 1)" == "$state_before_observe" ]] || \
  fail 'Observe changed the exact 83-version state.'
[[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$manifest_sha256" ]] || \
  fail 'Observe did not restore the canonical v0.1.3 manifest.'

if grep --extended-regexp --line-regexp '[^[:space:]]+[[:space:]]+(modrinthReleaseSubmit|modrinthReleaseFinalizeProject|modrinthReleaseVerify|publishAndReleaseToMavenCentral)' \
  "$fixture_log" >/dev/null; then
  fail 'The isolated success path invoked a forbidden remote mutation or finalizer task.'
fi
[[ "$(grep --fixed-strings -c $'v0.1.3\tmodrinthReleaseStage' "$fixture_log")" == '1' ]] || \
  fail 'The isolated success path did not invoke exactly one append-only stage task.'

reset_fixture() {
  fixture_fault='none'
  cp "$fixture_data/baseline-versions.json" "$fixture_state/versions.json"
  cp "$fixture_data/baseline-project.json" "$fixture_state/project.json"
  cp "$fixture_data/golden-manifests/"*.json "$fixture_data/manifests/"
  cp "$fixture_data/golden-manifests/v0.1.3.json" "$fixture_repository/build/release/modrinth/manifest.json"
  cp "$fixture_data/golden-artifacts/"*.jar "$fixture_repository/build/release/modrinth/artifacts/"
  for receipt_name in preflight stage observe; do
    receipt_file="$fixture_repository/build/release/modrinth-controller-receipts/$receipt_name.json"
    chmod u+w -- "$receipt_file" >/dev/null 2>&1 || true
    "$real_rm" -f -- "$receipt_file"
  done
}

mutate_json() {
  local expression="$1"
  local file="$2"
  jq "$expression" "$file" > "$test_root/mutation.json"
  mv "$test_root/mutation.json" "$file"
}

assert_historical_restored() {
  if grep --fixed-strings 'unrestored:v0.1.2' "$fixture_state/boundaries.log" >/dev/null; then
    fail 'A v0.1.2 historical manifest was removed before exact restoration.'
  fi
  if grep --fixed-strings 'overlay:v0.1.2' "$fixture_state/boundaries.log" >/dev/null; then
    grep --fixed-strings 'restored:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
      fail 'The v0.1.2 preflight overlay was not restored before cleanup.'
  fi
}

expect_success() {
  local case_name="$1"
  local operation="$2"
  if ! run_recovery "$operation" "$test_root/$case_name.out" "$test_root/$case_name.err"; then
    sed "s/^/$case_name: /" "$test_root/$case_name.err" >&2
    fail "The isolated success case failed: $case_name"
  fi
  cmp -s "$fixture_repository/build/release/modrinth/manifest.json" \
    "$fixture_data/golden-manifests/v0.1.3.json" || fail "The current manifest was not restored: $case_name"
  grep --fixed-strings 'overlay:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
    fail "The baseline v0.1.2 preflight was not actually adapted: $case_name"
  assert_historical_restored
  echo "Passed recovery fixture: $case_name"
}

expect_failure() {
  local case_name="$1"
  local operation="$2"
  local expected_error="$3"
  local allow_stage="$4"
  local initial_manifest_hash
  initial_manifest_hash="$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)"
  if run_recovery "$operation" "$test_root/$case_name.out" "$test_root/$case_name.err"; then
    fail "The isolated runner accepted the rejected case: $case_name"
  fi
  if [[ -n "$expected_error" ]]; then
    grep --fixed-strings "$expected_error" "$test_root/$case_name.err" >/dev/null || {
      sed "s/^/$case_name: /" "$test_root/$case_name.err" >&2
      fail "The rejected case failed at the wrong boundary: $case_name"
    }
  fi
  [[ "$("$real_sha256sum" "$fixture_repository/build/release/modrinth/manifest.json" | cut -d ' ' -f 1)" == "$initial_manifest_hash" ]] || \
    fail "The rejected path did not restore the original current manifest: $case_name"
  receipt_file="$fixture_repository/build/release/modrinth-controller-receipts/$operation.json"
  [[ ! -e "$receipt_file" && ! -L "$receipt_file" ]] || fail "The rejected path published a controller receipt: $case_name"
  if [[ "$allow_stage" == 'no' ]] && grep --fixed-strings 'stage-write' "$fixture_state/boundaries.log" >/dev/null; then
    fail "The rejected path crossed the stage write boundary: $case_name"
  fi
  assert_historical_restored
  echo "Passed recovery rejection: $case_name"
}

reset_fixture
jq --slurp 'add | sort_by(.version_number)' "$fixture_data/baseline-versions.json" \
  "$fixture_data/versions/v0.1.3.json" > "$fixture_state/versions.json"
idempotent_before="$(jq -cS . "$fixture_state/versions.json" | "$real_sha256sum" | cut -d ' ' -f 1)"
expect_success 'stage-idempotent' stage
[[ "$(jq -cS . "$fixture_state/versions.json" | "$real_sha256sum" | cut -d ' ' -f 1)" == "$idempotent_before" ]] || \
  fail 'An idempotent stage changed an existing version identity, metadata, or file URL.'

reset_fixture
jq --slurpfile current "$fixture_data/versions/v0.1.3.json" '. + $current[0][0:7] | sort_by(.version_number)' \
  "$fixture_data/baseline-versions.json" > "$fixture_state/versions.json"
expect_success 'stage-partial' stage
jq -e 'length == 83 and ([.[].version_number] | length == (unique | length))' \
  "$fixture_state/versions.json" >/dev/null || fail 'Partial recovery did not produce exactly 83 unique versions.'

reset_fixture
mutate_json 'map(.files[0].hashes |= del(.sha256))' "$fixture_state/versions.json"
expect_success 'optional-sha256' preflight

for operation in preflight stage observe; do
  reset_fixture
  mutate_json '.status = "approved" | .requested_status = null' "$fixture_state/project.json"
  if [[ "$operation" == 'observe' ]]; then
    jq --slurp 'add | sort_by(.version_number)' "$fixture_data/baseline-versions.json" \
      "$fixture_data/versions/v0.1.3.json" > "$fixture_state/versions.json"
  fi
  expect_success "approved-entry-$operation" "$operation"
  jq -e '.projectStatusBefore == "approved" and .projectStatusAfter == "approved"' \
    "$fixture_repository/build/release/modrinth-controller-receipts/$operation.json" >/dev/null || \
    fail 'Approved-at-entry recovery did not preserve approval.'
done

reset_fixture
mutate_json '.status = "approved" | .requested_status = "approved"' "$fixture_state/project.json"
expect_success 'approved-requested-approved' preflight

reset_fixture
fixture_fault='approve-during-history'
expect_success 'approval-during-historical-preflight' stage
grep --fixed-strings 'approved-during-history' "$fixture_state/boundaries.log" >/dev/null || \
  fail 'Approval did not occur inside the adapted historical preflight.'
jq -e '.projectStatusBefore == "processing" and .projectStatusBoundary == "approved" and .projectStatusAfter == "approved" and .versionCountAfter == 83' \
  "$fixture_repository/build/release/modrinth-controller-receipts/stage.json" >/dev/null || \
  fail 'Approval inside historical preflight did not complete the exact inventory.'

for read_index in 2 3 4; do
  reset_fixture
  fixture_fault="approve-read-$read_index"
  expect_success "approval-transition-$read_index" stage
  jq -e '.projectStatusBefore == "processing" and .projectStatusAfter == "approved" and .versionCountAfter == 83' \
    "$fixture_repository/build/release/modrinth-controller-receipts/stage.json" >/dev/null || \
    fail 'A monotonic approval transition did not complete the exact inventory.'
done

for version in 0.1.0 0.1.1 0.1.2; do
  reset_fixture
  mutate_json "map(if (.version_number | startswith(\"$version+\")) then .name += \" metadata-drift\" else . end)" \
    "$fixture_state/versions.json"
  drift_state_hash="$(jq -cS . "$fixture_state/versions.json" | "$real_sha256sum" | cut -d ' ' -f 1)"
  expect_failure "baseline-$version-metadata-drift" preflight \
    'Historical Modrinth version metadata differs from the three signed release manifests.' no
  [[ "$(jq -cS . "$fixture_state/versions.json" | "$real_sha256sum" | cut -d ' ' -f 1)" == "$drift_state_hash" ]] || \
    fail 'A rejected historical metadata drift changed remote fixture state.'
done

for mutation in '.project.slug = "changed"' '.artifacts[0].size += 1' '.project.previousBodySha256 = "changed"'; do
  reset_fixture
  mutate_json "$mutation" "$fixture_data/manifests/v0.1.2.json"
  expect_failure 'baseline-v012-canonical-drift' preflight \
    'Historical Modrinth manifest differs from its pinned canonical hash: v0.1.2' no
done

for mutation in '.project.slug = "changed"' '.artifacts[0].size += 1' '.project.previousBodySha256 = "changed"'; do
  reset_fixture
  mutate_json "$mutation" "$fixture_repository/build/release/modrinth/manifest.json"
  expect_failure 'current-canonical-drift' preflight \
    'The canonical v0.1.3 Modrinth manifest differs from its pinned canonical hash.' no
done

for fault in historical-overlay-field historical-overlay-artifact current-overlay-field current-overlay-artifact; do
  reset_fixture
  fixture_fault="$fault"
  expect_failure "$fault" preflight '' no
  if grep --fixed-strings 'Fixture rejected a missing or non-minimal' "$test_root/$fault.err" >/dev/null; then
    fail "The production structural guard did not reject a non-minimal overlay: $fault"
  fi
done

for fault in historical-preflight-fail historical-preflight-interrupt current-preflight-fail; do
  reset_fixture
  fixture_fault="$fault"
  expect_failure "$fault" preflight 'Injected ' no
  grep --fixed-strings 'overlay:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
    fail 'The historical failure injection did not reach the adapted preflight.'
  grep --fixed-strings 'restored:v0.1.2' "$fixture_state/boundaries.log" >/dev/null || \
    fail 'The historical manifest was not restored after an injected failure or interruption.'
done

for revision in v0.1.2 v0.1.3; do
  reset_fixture
  if [[ "$revision" == 'v0.1.2' ]]; then
    fixture_fault='historical-operation-drift'
    expected_error='Historical preflight changed the temporary recovery manifest: v0.1.2'
  else
    fixture_fault='current-operation-drift'
    expected_error='The tagged Gradle operation changed the temporary recovery manifest.'
  fi
  expect_failure "$fixture_fault" preflight "$expected_error" no
  grep --fixed-strings "operation-drift:$revision" "$fixture_state/boundaries.log" >/dev/null || \
    fail 'The post-preflight manifest drift injection was not reached.'
done

reset_fixture
fixture_fault='cleanup-fail'
expect_failure 'cleanup-failure-invalidates-receipt' preflight \
  'A historical verification worktree could not be removed.' no
grep --fixed-strings 'receipt-before-cleanup-failure' "$fixture_state/boundaries.log" >/dev/null || \
  fail 'The cleanup failure did not invalidate a previously published final receipt.'
retained_entries=()
while IFS= read -r -d '' retained_entry; do
  retained_entries+=("$retained_entry")
done < <(find "$fixture_runner_temp" -mindepth 1 -maxdepth 1 -print0)
[[ "${#retained_entries[@]}" == '1' ]] || fail 'Cleanup failure retained an unexpected number of recovery directories.'
retained_directory="${retained_entries[0]}"
retained_resolved="$(realpath -m "$retained_directory")"
[[ "$retained_resolved" == "$fixture_runner_temp/strata-modrinth-v013-backlog."* &&
  "$retained_resolved" == "$retained_directory" && -d "$retained_directory" && ! -L "$retained_directory" ]] || \
  fail 'Cleanup failure recovery evidence escaped the bounded fixture directory.'
[[ ! -e "$retained_directory/0.1.0-remote-verification" && ! -e "$retained_directory/0.1.1-remote-verification" ]] || \
  fail 'Cleanup failure retained unrelated historical worktrees.'
cmp -s "$retained_directory/0.1.2-remote-verification/build/release/modrinth/manifest.json" \
  "$fixture_state/historical-original.json" || fail 'Retained historical recovery evidence is not the exact original manifest.'
cmp -s "$retained_directory/historical-2-canonical-manifest.json" \
  "$fixture_state/historical-original.json" || fail 'Cleanup failure did not retain the exact historical backup.'
chmod -R u+w -- "$retained_directory"
"$real_rm" -rf -- "$retained_directory"
[[ ! -e "$retained_directory" && ! -L "$retained_directory" ]] || \
  fail 'The bounded cleanup-failure fixture evidence was not removed.'

for status in draft rejected archived unlisted unknown; do
  reset_fixture
  mutate_json ".status = \"$status\"" "$fixture_state/project.json"
  expect_failure "invalid-status-$status" preflight \
    'The Modrinth project identity or backlog lifecycle state differs.' no
done

for state in 'processing null' 'processing processing' 'approved processing' 'approved rejected'; do
  reset_fixture
  read -r status requested <<< "$state"
  if [[ "$requested" == 'null' ]]; then
    mutate_json ".status = \"$status\" | .requested_status = null" "$fixture_state/project.json"
  else
    mutate_json ".status = \"$status\" | .requested_status = \"$requested\"" "$fixture_state/project.json"
  fi
  expect_failure "invalid-requested-$status-$requested" preflight \
    'The Modrinth project identity or backlog lifecycle state differs.' no
done

for read_index in 2 3 4; do
  reset_fixture
  mutate_json '.status = "approved" | .requested_status = null' "$fixture_state/project.json"
  fixture_fault="regress-read-$read_index"
  allow_stage='no'
  if [[ "$read_index" == '4' ]]; then allow_stage='yes'; fi
  expect_failure "lifecycle-regression-$read_index" stage '' "$allow_stage"
done

reset_fixture
mutate_json '.body += "drift\n"' "$fixture_state/project.json"
expect_failure 'backlog-body-drift' preflight \
  'The remote Modrinth body is not the exact immutable v0.1.0 backlog body.' no

reset_fixture
mutate_json '. + [.[0]]' "$fixture_state/versions.json"
expect_failure 'duplicate-version' preflight 'The remote Modrinth backlog contains duplicate version numbers.' no

reset_fixture
mutate_json 'map(select(.version_number != "0.1.2+mc1.20"))' "$fixture_state/versions.json"
expect_failure 'missing-v012-version' preflight '' no

reset_fixture
mutate_json '. + [.[0] | .version_number = "0.1.4+mc1.20"]' "$fixture_state/versions.json"
expect_failure 'unexpected-version' preflight 'The remote Modrinth backlog contains an unexpected version.' no

reset_fixture
mutate_json '.[0].files[0].size = "invalid"' "$fixture_state/versions.json"
expect_failure 'malformed-version-schema' preflight 'The Modrinth v2 version response schema differs.' no

reset_fixture
jq --slurpfile current "$fixture_data/versions/v0.1.3.json" '. + [$current[0][0] | .name += " drift"]' \
  "$fixture_data/baseline-versions.json" > "$fixture_state/versions.json"
expect_failure 'current-remote-metadata-drift' preflight \
  'Existing Modrinth v0.1.3 metadata differs from the exact signed release manifest.' no

reset_fixture
expect_failure 'observe-incomplete' observe '' no

for fault in boundary-body-drift boundary-baseline-drift controller-drift; do
  reset_fixture
  fixture_fault="$fault"
  expect_failure "$fault" stage '' no
done

for fault in stage-baseline-drift stage-artifact-drift; do
  reset_fixture
  fixture_fault="$fault"
  expect_failure "$fault" stage '' yes
done

if grep --extended-regexp --line-regexp '[^[:space:]]+[[:space:]]+(modrinthReleaseSubmit|modrinthReleaseFinalizeProject|modrinthReleaseVerify|publishAndReleaseToMavenCentral)' \
  "$fixture_log" >/dev/null; then
  fail 'The fixture invoked a forbidden remote mutation or finalizer task.'
fi
echo 'Isolated Modrinth v0.1.3 backlog recovery fixture passed.'

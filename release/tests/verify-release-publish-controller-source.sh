#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
strata_jq_path="$(type -P jq || true)"
strata_od_path="$(type -P od || true)"
strata_tr_path="$(type -P tr || true)"
if [[ "$strata_jq_path" == /* && -x "$strata_jq_path" && \
  "$strata_od_path" == /* && -x "$strata_od_path" && \
  "$strata_tr_path" == /* && -x "$strata_tr_path" ]]; then
  :
else
  echo 'Portable jq initialization requires absolute executable jq, od, and tr paths.' >&2
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
  echo 'jq output-mode byte probing failed.' >&2
  exit 1
fi
if [[ "$strata_jq_probe_hex" == '780a' ]]; then
  unset strata_jq_probe_hex
else
  echo 'jq output mode does not produce exact LF-delimited bytes.' >&2
  exit 1
fi

portable_jq() {
  "$strata_jq_path" "${strata_jq_binary_options[@]}" "$@"
}
readonly -f portable_jq
workflow="$repository_root/.github/workflows/publish-release.yml"
jvm_workflow="$repository_root/.github/workflows/jvm.yml"
sealed_previous="$repository_root/.github/workflows/release-v0.1.1.yml"
sealed_initial="$repository_root/.github/workflows/release.yml"
controller_guard="$repository_root/release/verify-controller-tools.sh"
controller_metadata="$repository_root/release/current-controller.json"
recovery_wrapper="$repository_root/release/run-publish-controller-recovery.sh"
release_order_verifier="$repository_root/release/verify-current-controller-release-order.sh"
backlog_guard="$repository_root/release/tests/verify-modrinth-v0.1.2-backlog-recovery.sh"
current_backlog_guard="$repository_root/release/tests/verify-modrinth-v0.1.3-backlog-recovery.sh"

fail() {
  echo "$1" >&2
  exit 1
}

step_block() {
  local name="$1"
  awk -v header="      - name: $name" '
    $0 == header { inside = 1 }
    inside && $0 != header && (/^      - name:/ || /^  [a-z_]+:/) { exit }
    inside { print }
  ' "$workflow"
}

step_run() {
  local name="$1"
  awk -v header="      - name: $name" '
    $0 == header { inside = 1; next }
    inside && /^        run: \|$/ { in_run = 1; next }
    in_run && (/^      - name:/ || /^  [a-z_]+:/) { exit }
    in_run { sub(/^          /, ""); print }
  ' "$workflow"
}

require_before() {
  local block="$1"
  local first="$2"
  local second="$3"
  local first_line=""
  local second_line=""
  first_line="$(grep -n -m 1 --fixed-strings "$first" <<< "$block" | cut -d: -f1 || true)"
  second_line="$(grep -n -m 1 --fixed-strings "$second" <<< "$block" | cut -d: -f1 || true)"
  [[ -n "$first_line" && -n "$second_line" && "$first_line" -lt "$second_line" ]] || \
    fail "Required controller ordering is missing: $first before $second"
}

require_source_before() {
  local first_pattern="$1"
  local second_pattern="$2"
  local label="$3"
  local first_line=""
  local second_line=""
  first_line="$(grep -n -m 1 -E "$first_pattern" "${BASH_SOURCE[0]}" | cut -d: -f1 || true)"
  second_line="$(grep -n -m 1 -E "$second_pattern" "${BASH_SOURCE[0]}" | cut -d: -f1 || true)"
  [[ -n "$first_line" && -n "$second_line" && "$first_line" -lt "$second_line" ]] || \
    fail "Fixture signing isolation is not established before its first operation: $label"
}

require_immediate_guard() {
  local block="$1"
  local call="$2"
  local guard="$3"
  local call_lines=""
  local line_number=""
  local previous=""
  call_lines="$(grep -n --fixed-strings "$call" <<< "$block" | cut -d: -f1 || true)"
  [[ -n "$call_lines" ]] || fail "Guarded controller call is missing: $call"
  while IFS= read -r line_number; do
    previous="$(sed -n "$((line_number - 1))p" <<< "$block")"
    [[ "${previous#"${previous%%[![:space:]]*}"}" == "$guard" ]] || fail "Controller call is not immediately guarded: $call"
  done <<< "$call_lines"
}

for required in "$workflow" "$jvm_workflow" "$sealed_previous" "$sealed_initial" "$controller_guard" "$controller_metadata" "$recovery_wrapper" "$release_order_verifier" "$backlog_guard" "$current_backlog_guard"; do
  [[ -f "$required" && ! -L "$required" ]] || fail "Required forward-controller source is missing or not regular: $required"
done
bash -n "$controller_guard"
bash -n "$recovery_wrapper"
bash -n "$release_order_verifier"
bash "$backlog_guard"
bash "$current_backlog_guard"

grep --fixed-strings 'for source_guard in release/tests/verify-release*-source.sh; do' "$jvm_workflow" >/dev/null || \
  fail 'JVM CI no longer discovers stable release source guards.'
grep --fixed-strings 'bash "$source_guard"' "$jvm_workflow" >/dev/null || fail 'JVM CI no longer executes discovered release source guards.'
[[ "$(grep -E -c '^git -C "\$fixture_repository" config commit\.gpgsign false$' "${BASH_SOURCE[0]}")" == '1' ]] || \
  fail 'The controller materialization fixture does not disable inherited commit signing exactly once.'
[[ "$(grep -E -c '^git -C "\$order_repository" config commit\.gpgsign false$' "${BASH_SOURCE[0]}")" == '1' ]] || \
  fail 'The release-order fixture does not disable inherited commit signing exactly once.'
[[ "$(grep -E -c '^git -C "\$order_repository" config tag\.gpgsign false$' "${BASH_SOURCE[0]}")" == '1' ]] || \
  fail 'The release-order fixture does not disable inherited tag signing exactly once.'
require_source_before \
  '^git -C "\$fixture_repository" config commit\.gpgsign false$' \
  '^git -C "\$fixture_repository" commit ' \
  'controller materialization commits'
require_source_before \
  '^git -C "\$order_repository" config commit\.gpgsign false$' \
  '^git -C "\$order_repository" commit ' \
  'release-order commits'
require_source_before \
  '^git -C "\$order_repository" config tag\.gpgsign false$' \
  '^git -C "\$order_repository" tag ' \
  'release-order tags'
source_guard_discovered=false
for source_guard in "$repository_root"/release/tests/verify-release*-source.sh; do
  if [[ "$(realpath "$source_guard")" == "$(realpath "${BASH_SOURCE[0]}")" ]]; then
    source_guard_discovered=true
  fi
done
[[ "$source_guard_discovered" == true ]] || fail 'The publish-controller source guard is outside the JVM CI discovery glob.'

jq -e '
  type == "object" and
  keys == ["current", "predecessor", "schemaVersion"] and
  .schemaVersion == 1 and
  (.current | type == "object" and
    keys == ["commit", "representativeMinecraftVersions", "tag", "tagObject"] and
    (.tag | test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
    (.commit | test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
    (.tagObject | test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
    (.representativeMinecraftVersions | type == "array" and length > 0) and
    (.representativeMinecraftVersions | length == (unique | length)) and
    all(.representativeMinecraftVersions[]; type == "string" and test("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*$"))
  ) and
  (.predecessor | type == "object" and
    keys == ["commit", "tag", "tagObject"] and
    (.tag | test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
    (.commit | test("^([0-9a-f]{40}|[0-9a-f]{64})$")) and
    (.tagObject | test("^([0-9a-f]{40}|[0-9a-f]{64})$"))
  ) and
  .current.tag != .predecessor.tag and
  .current.commit != .predecessor.commit and
  .current.tagObject != .predecessor.tagObject
' "$controller_metadata" >/dev/null || fail 'Current-controller metadata does not use the fixed identity-pair schema.'

current_tag="$(portable_jq -er '.current.tag' "$controller_metadata")"
predecessor_tag="$(portable_jq -er '.predecessor.tag' "$controller_metadata")"
[[ "$(printf '%s\n' "${predecessor_tag#v}" "${current_tag#v}" | sort -V | head -n 1)" == "${predecessor_tag#v}" && \
  "$predecessor_tag" != "$current_tag" ]] || fail 'Tracked predecessor semantic version is not earlier than current.'
current_commit="$(portable_jq -er '.current.commit' "$controller_metadata")"
while IFS= read -r minecraft_version; do
  for project_kind in runtime integration; do
    project_path="$project_kind/minecraft-fabric-$minecraft_version/build.gradle.kts"
    record="$(git -C "$repository_root" --no-replace-objects ls-tree --full-tree "$current_commit" -- "$project_path")"
    [[ -n "$record" && "$record" != *$'\n'* ]] || fail "Representative Minecraft fixture project is missing or ambiguous: $project_path"
    read -r mode object_type project_blob verified_path <<< "$record"
    [[ "$mode" == '100644' && "$object_type" == 'blob' && "$project_blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_path" == "$project_path" ]] || \
      fail "Representative Minecraft fixture project is not a regular blob: $project_path"
  done
done < <(portable_jq -er '.current.representativeMinecraftVersions[]' "$controller_metadata")

[[ ! -e "$repository_root/release/maven-coordinates.txt" && ! -L "$repository_root/release/maven-coordinates.txt" ]] || \
  fail 'The forward source retains a handwritten Maven artifact inventory.'

mapfile -t versioned_release_workflows < <(
  find "$repository_root/.github/workflows" -maxdepth 1 -type f \
    \( -name 'release-v*.yml' -o -name 'release-v*.yaml' \) -printf '%f\n' | LC_ALL=C sort
)
[[ "${#versioned_release_workflows[@]}" == '1' && "${versioned_release_workflows[0]}" == 'release-v0.1.1.yml' ]] || \
  fail 'A new per-release workflow was added beside the one sealed historical controller.'

grep --fixed-strings 'name: Publish release' "$workflow" >/dev/null || fail 'Forward workflow name differs.'
grep --fixed-strings 'group: release-v0.1.0' "$workflow" >/dev/null || fail 'Legacy cross-controller concurrency group was not preserved.'
if awk '$0 != "  group: release-v0.1.0"' "$workflow" | grep --extended-regexp 'v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)' >/dev/null; then
  fail 'Forward workflow contains a stable Strata release-number literal outside the legacy concurrency group.'
fi
if grep --extended-regexp '(^|[^0-9a-f])[0-9a-f]{40}([^0-9a-f]|$)' "$workflow" >/dev/null; then
  fail 'Forward workflow contains a forty-hex identity literal.'
fi
for forbidden in \
  'release-v[0-9]+\.[0-9]+\.[0-9]+\.ya?ml' \
  'github-tag-ruleset-v[0-9]+\.[0-9]+\.[0-9]+' \
  'modrinth-v[0-9]+\.[0-9]+\.[0-9]+' \
  'run-modrinth-v[0-9]+\.[0-9]+\.[0-9]+'; do
  if grep --extended-regexp "$forbidden" "$workflow" >/dev/null; then
    fail "Forward workflow retains per-release source coupling: $forbidden"
  fi
done
if grep --extended-regexp '(==|!=)[[:space:]]*"?(21|26|43|130|260|520)"?|length\)[[:space:]]*==[[:space:]]*(21|26|43|130|260|520)' "$workflow" >/dev/null; then
  fail 'Forward workflow retains a release-inventory count literal.'
fi
if grep --extended-regexp 'minecraft-fabric-[0-9]+(\.[0-9]+)*:runPublishedCoordinateClientGameTest' "$workflow" >/dev/null; then
  fail 'Forward workflow retains a fixed representative Minecraft client task.'
fi
if grep --fixed-strings 'java-version: |' "$workflow" >/dev/null; then
  fail 'Forward workflow retains a handwritten Java toolchain matrix.'
fi

for required_contract in \
  'current-controller.json' \
  'current_tag: ${{ steps.source.outputs.current_tag }}' \
  'representative_minecraft_versions: ${{ steps.source.outputs.representative_minecraft_versions }}' \
  'predecessor_tag: ${{ steps.source.outputs.predecessor_tag }}' \
  'current_tag: ${{ steps.source.outputs.current_tag }}' \
  'predecessor_commit: ${{ steps.source.outputs.predecessor_commit }}' \
  'refs/tags/v*' \
  'verify-current-controller-release-order.sh' \
  'list-java-toolchains.sh' \
  'for candidate in build/release/maven-coordinates.txt release/maven-coordinates.txt; do' \
  'RELEASE_MAVEN_INVENTORY_SHA256=' \
  'verify_release_inventory() {' \
  'project_path="$project_kind/minecraft-fabric-$minecraft_version/build.gradle.kts"' \
  'task=":integration:minecraft-fabric-$minecraft_version:runPublishedCoordinateClientGameTest"' \
  'run-publish-controller-recovery.sh' \
  'backlog-recovery.json' \
  'Verify complete predecessor release before Modrinth body finalization' \
  'Finalize current Modrinth body and verify approved release'; do
  grep --fixed-strings "$required_contract" "$workflow" >/dev/null || fail "Forward-controller contract is missing: $required_contract"
done

[[ "$(grep --fixed-strings -c 'representative_minecraft_versions="$(portable_jq -cer '\''.current.representativeMinecraftVersions'\'' "$metadata")"' "$workflow")" == '2' ]] || \
  fail 'Controller metadata does not preserve representative versions as portable JSON in both source checks.'
[[ "$(grep --fixed-strings -c 'portable_jq -er '\''.current.representativeMinecraftVersions[]'\'' "$metadata"' "$workflow")" == '2' ]] || \
  fail 'Controller metadata representative-version loops do not use portable jq output.'
[[ "$(grep --fixed-strings -c 'portable_jq -er '\''.[]'\'' <<< "$RELEASE_REPRESENTATIVE_MINECRAFT_VERSIONS"' "$workflow")" == '4' ]] || \
  fail 'Release representative-version loops are not all normalized by portable jq output.'
[[ "$(grep --fixed-strings -c 'portable_jq -er '\''.[]'\'' <<< "$EXPECTED_REPRESENTATIVE_MINECRAFT_VERSIONS"' "$workflow")" == '1' ]] || \
  fail 'Final representative-version verification is not normalized by portable jq output.'
if grep --extended-regexp 'git([^|]*)(show|cat-file)[^|]*release/maven-coordinates\.txt' "$workflow" >/dev/null; then
  fail 'Forward workflow reads a handwritten Maven artifact inventory directly from tag source.'
fi
for publication_contract in \
  'val releasePublicationProjectPaths =' \
  'val releaseArtifactByProjectPath =' \
  'val releaseArtifacts = releasePublicationProjectPaths.map' \
  'dependsOn("mavenArtifactInventory")' \
  'dependsOn(verifyReleasePublicationMatrix)' \
  'mavenArtifacts.set(releaseArtifacts)'; do
  grep --fixed-strings "$publication_contract" "$repository_root/build.gradle.kts" >/dev/null || \
    fail "Generated Maven publication inventory contract is missing: $publication_contract"
done

[[ "$(grep --fixed-strings -c 'run_controller_tool_guard() {' "$workflow")" == '3' ]] || fail 'Every controller-loading job must bootstrap the exact guard.'
[[ "$(grep --fixed-strings -c 'git --no-replace-objects cat-file blob "$blob"' "$workflow")" == '3' ]] || fail 'Every bootstrap must execute the exact guard blob with replacement objects disabled.'
[[ "$(grep --fixed-strings -c 'run_controller_tool_guard materialize' "$workflow")" == '3' ]] || fail 'Every controller-loading job must materialize one bounded bundle.'
[[ "$(grep --fixed-strings -c 'steps.controller_tools.outputs.directory' "$workflow")" == '4' ]] || fail 'Cleanup must remain bound to both controller initialization outputs.'
for hardening_contract in \
  'git --no-replace-objects cat-file -t "$controller_commit"' \
  'git --no-replace-objects ls-tree --full-tree "$controller_commit"' \
  'git --no-replace-objects cat-file blob "$blob"' \
  'git hash-object --no-filters -- "$destination"'; do
  grep --fixed-strings "$hardening_contract" "$controller_guard" >/dev/null || fail "Controller guard hardening is missing: $hardening_contract"
done
for controller_mode_contract in \
  'verify_controller_tool release/current-controller.json current-controller.json controller 100644' \
  'verify_controller_tool release/verify-release-tag.sh verify-release-tag.sh bash 100644' \
  'verify_controller_tool release/list-release-tags.sh list-release-tags.sh bash 100755' \
  'verify_controller_tool release/verify-current-controller-release-order.sh verify-current-controller-release-order.sh bash 100755' \
  'verify_controller_tool release/verify-github-tag-ruleset.sh verify-github-tag-ruleset.sh bash 100644' \
  'verify_controller_tool release/github-release-tag-ruleset.json github-release-tag-ruleset.json json 100644' \
  'verify_controller_tool release/github-release-tag-ruleset-receipt.json github-release-tag-ruleset-receipt.json json 100644' \
  'verify_controller_tool release/verify-pages-deployment-source.sh verify-pages-deployment-source.sh bash 100644' \
  'verify_controller_tool release/verify-pages-artifact-equivalence.sh verify-pages-artifact-equivalence.sh bash 100644' \
  'verify_controller_tool release/wait-for-pages-source-receipt.sh wait-for-pages-source-receipt.sh bash 100644' \
  'verify_controller_tool release/run-publish-controller-recovery.sh run-publish-controller-recovery.sh bash 100755' \
  'verify_controller_tool gradle/list-java-toolchains.sh list-java-toolchains.sh bash 100755'; do
  grep --fixed-strings "$controller_mode_contract" "$controller_guard" >/dev/null || \
    fail "Controller source does not declare its exact reviewed Git mode: $controller_mode_contract"
done
grep --fixed-strings '  verify-pages-artifact-equivalence.sh' "$recovery_wrapper" >/dev/null || \
  fail 'Recovery wrapper does not preserve the adjacent Pages artifact comparator.'

release_init_block="$(step_block 'Revalidate protected release request')"
verify_init_block="$(step_block 'Validate final verification source and Pages provenance')"
for init_spec in "release|$release_init_block|EXPECTED_CONTROLLER_COMMIT" "verify|$verify_init_block|GITHUB_SHA"; do
  init_label="${init_spec%%|*}"
  init_remainder="${init_spec#*|}"
  init_block="${init_remainder%|*}"
  init_commit="${init_remainder##*|}"
  [[ "$(grep --fixed-strings -c 'run_controller_tool_guard materialize' <<< "$init_block")" == '1' ]] || fail "$init_label initialization does not materialize exactly one bundle."
  grep --fixed-strings 'echo "directory=$controller_tool_directory" >> "$GITHUB_OUTPUT"' <<< "$init_block" >/dev/null || fail "$init_label cleanup directory is not a step output."
  require_before "$init_block" 'run_controller_tool_guard materialize' 'bash "$controller_tool_directory/verify-release-tag.sh"'
  require_immediate_guard "$init_block" 'bash "$controller_ruleset_verifier"' "run_controller_tool_guard verify \"\$$init_commit\" \"\$controller_tool_directory\""
  require_immediate_guard "$init_block" 'bash "$controller_pages_verifier"' "run_controller_tool_guard verify \"\$$init_commit\" \"\$controller_tool_directory\""
  require_immediate_guard "$init_block" 'bash "$controller_pages_waiter"' "run_controller_tool_guard verify \"\$$init_commit\" \"\$controller_tool_directory\""
done

for mutation_spec in \
  'Publish wholly absent Maven Central release|publishAndReleaseToMavenCentral' \
  'Stage only missing Modrinth versions|modrinthReleaseStage' \
  'Create or verify immutable GitHub Release|gh release create' \
  'Submit or observe Modrinth review|modrinthReleaseSubmit'; do
  mutation_name="${mutation_spec%%|*}"
  mutation_write="${mutation_spec#*|}"
  mutation_block="$(step_block "$mutation_name")"
  grep --fixed-strings 'git --no-replace-objects cat-file blob "$EXPECTED_CONTROLLER_COMMIT:release/verify-controller-tools.sh"' <<< "$mutation_block" >/dev/null || fail "Mutation does not revalidate the exact controller guard: $mutation_name"
  require_immediate_guard "$mutation_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' 'verify_controller_tools'
  require_before "$mutation_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' "$mutation_write"
done

central_block="$(step_block 'Publish wholly absent Maven Central release')"
github_block="$(step_block 'Create or verify immutable GitHub Release')"
[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$central_block")" == '2' ]] || fail 'Central publication must revalidate before absence confirmation and write.'
[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$github_block")" == '3' ]] || fail 'Every GitHub Release mutation phase must revalidate source and controller tools.'

for controller_call_spec in \
  'Preflight Modrinth without mutation|bash "$CONTROLLER_TOOL_DIRECTORY/run-publish-controller-recovery.sh"' \
  'Stage only missing Modrinth versions|bash "$CONTROLLER_TOOL_DIRECTORY/run-publish-controller-recovery.sh"' \
  'Submit or observe Modrinth review|bash "$CONTROLLER_TOOL_DIRECTORY/run-publish-controller-recovery.sh"' \
  'Verify public Pages and tagged Skill source|bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"' \
  'Verify public Pages and tagged Skill source|bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"' \
  'Verify public predecessor services and Pages provenance|bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"' \
  'Verify public predecessor services and Pages provenance|bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"' \
  'Finalize current Modrinth body and verify approved release|bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"'; do
  controller_step="${controller_call_spec%%|*}"
  controller_call="${controller_call_spec#*|}"
  require_immediate_guard "$(step_block "$controller_step")" "$controller_call" 'verify_controller_tools'
done

verify_dokka_inventory_step() {
  local label="$1"
  local step_name="$2"
  local inventory_variable="$3"
  local run=""
  local required_loop='for required in / /index.html /source-receipt.json /source-revision.txt; do'
  local required_check="grep --fixed-strings --line-regexp \"\$required\" \"\$$inventory_variable\" >/dev/null"
  local sorted_inventory="LC_ALL=C sort --check --unique \"\$$inventory_variable\""
  run="$(step_run "$step_name")"
  [[ -n "$run" ]] || fail "$label public Pages verification step is missing."
  [[ "$(grep --fixed-strings -c "$required_loop" <<< "$run" || true)" == '1' ]] || \
    fail "$label public Pages verification does not require exactly one Dokka root and source-evidence inventory."
  [[ "$(grep --fixed-strings -c "$required_check" <<< "$run" || true)" == '1' ]] || \
    fail "$label public Pages verification does not check every required Dokka path."
  [[ "$(grep --fixed-strings -c "$sorted_inventory" <<< "$run" || true)" == '1' ]] || \
    fail "$label public Pages verification does not require one sorted unique inventory."
  [[ "$(grep --fixed-strings -c 'while IFS= read -r public_path || [[ -n "$public_path" ]]; do' <<< "$run" || true)" == '1' ]] || \
    fail "$label public Pages verification does not contain exactly one final-line-safe complete-inventory loop."
  grep --fixed-strings "done < \"\$$inventory_variable\"" <<< "$run" >/dev/null || \
    fail "$label public Pages verification no longer reads every generated inventory entry."
  if grep --fixed-strings '/guide' <<< "$run" >/dev/null; then
    fail "$label public Pages verification still assumes a separately published reader guide."
  fi
}

verify_dokka_inventory_step current 'Verify public Pages and tagged Skill source' inventory
verify_dokka_inventory_step predecessor 'Verify public predecessor services and Pages provenance' pages_inventory

release_job="$(sed -n '/^  release:$/,/^  public_skills:$/p' "$workflow")"
public_job="$(sed -n '/^  public_skills:$/,/^  verify:$/p' "$workflow")"
verify_job="$(sed -n '/^  verify:$/,$p' "$workflow")"
[[ "$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/run-publish-controller-recovery.sh"' <<< "$release_job")" == '3' ]] || fail 'Release job must contain exactly three generic recovery calls.'
if grep --fixed-strings 'run-publish-controller-recovery.sh' <<< "$verify_job" >/dev/null; then
  fail 'Final verification must not invoke backlog recovery.'
fi
grep --fixed-strings '    permissions: {}' <<< "$public_job" >/dev/null || fail 'Public Skill job must remain tokenless.'
if grep --fixed-strings '${{ secrets.' <<< "$public_job" >/dev/null || grep --fixed-strings 'uses: actions/checkout' <<< "$public_job" >/dev/null; then
  fail 'Public Skill job regained a release secret or credentialed checkout.'
fi
grep --fixed-strings '    needs: public_skills' "$workflow" >/dev/null || fail 'Secret-bearing final verification must depend on isolated public Skills.'

for cleanup_name in 'Clean up release controller tools' 'Clean up verification controller tools'; do
  cleanup_block="$(step_block "$cleanup_name")"
  grep --fixed-strings "if: always() && steps.controller_tools.outputs.directory != ''" <<< "$cleanup_block" >/dev/null || fail "Terminal cleanup is not unconditional: $cleanup_name"
  grep --fixed-strings 'CONTROLLER_TOOL_DIRECTORY: ${{ steps.controller_tools.outputs.directory }}' <<< "$cleanup_block" >/dev/null || fail "Cleanup is not bound to the initialization output: $cleanup_name"
  grep --fixed-strings '"$(dirname -- "$directory")" == "${RUNNER_TEMP%/}"' <<< "$cleanup_block" >/dev/null || fail "Cleanup is not restricted to RUNNER_TEMP: $cleanup_name"
done

central_write='publishAndReleaseToMaven''Central'
[[ "$(grep --fixed-strings -c "$central_write" "$workflow")" == '1' ]] || fail 'Forward controller must contain exactly one Central write.'
[[ "$(grep --fixed-strings -c "$central_write" "$sealed_previous")" == '1' ]] || fail 'Sealed predecessor controller lost its single Central write.'
[[ "$(grep --fixed-strings -c "$central_write" "$sealed_initial" || true)" == '0' ]] || fail 'Sealed initial controller gained a Central write.'

[[ "$(grep --fixed-strings -c 'publishAndReleaseToMavenCentral' "$workflow")" == '1' ]] || fail 'Forward controller must contain exactly one Central write.'
[[ "$(grep --fixed-strings -c "+refs/tags/*:refs/tags/*" "$workflow")" -ge 7 ]] || fail 'Mutation boundaries do not repeatedly fetch the complete release-tag namespace.'
[[ "$(grep --fixed-strings -c 'verify-current-controller-release-order.sh' "$workflow")" -ge 7 ]] || fail 'Mutation boundaries do not repeatedly prove the frozen pair is still latest.'
[[ "$(grep --fixed-strings -c 'PREDECESSOR_RELEASE_OBJECT' "$workflow")" -ge 5 ]] || fail 'Mutation boundaries do not repeatedly compare the frozen predecessor object.'

step_line() {
  grep -n -m 1 --fixed-strings "      - name: $1" "$workflow" | cut -d: -f1
}

central_line="$(step_line 'Publish wholly absent Maven Central release')"
github_line="$(step_line 'Create or verify immutable GitHub Release')"
submit_line="$(step_line 'Submit or observe Modrinth review')"
predecessor_line="$(step_line 'Verify complete predecessor release before Modrinth body finalization')"
finalize_line="$(step_line 'Finalize current Modrinth body and verify approved release')"
[[ -n "$central_line" && -n "$github_line" && -n "$submit_line" && -n "$predecessor_line" && -n "$finalize_line" ]] || fail 'A mutation or predecessor boundary is missing.'
(( central_line < github_line && github_line < submit_line && predecessor_line < finalize_line )) || fail 'Release mutation or finalization ordering differs.'

controller_test_root="$(mktemp -d)"
cleanup_controller_test() {
  chmod -R u+w -- "$controller_test_root" >/dev/null 2>&1 || true
  rm -rf -- "$controller_test_root"
}
trap cleanup_controller_test EXIT INT TERM
fixture_repository="$controller_test_root/repository"
mkdir -p "$fixture_repository/release" "$fixture_repository/gradle"
git init --quiet "$fixture_repository"
[[ -d "$fixture_repository/.git" && ! -L "$fixture_repository/.git" ]] || \
  fail 'Controller fixture initialization escaped into the enclosing repository.'
git -C "$fixture_repository" config user.name 'Strata Controller Test'
git -C "$fixture_repository" config user.email 'controller-test@example.invalid'
git -C "$fixture_repository" config commit.gpgsign false

write_script() {
  printf '#!/usr/bin/env bash\nset -euo pipefail\n' > "$fixture_repository/$1"
}

write_metadata() {
  local current_fixture_tag="$1"
  local current_fixture_commit="$2"
  local current_fixture_object="$3"
  local predecessor_fixture_tag="$4"
  local predecessor_fixture_commit="$5"
  local predecessor_fixture_object="$6"
  jq -n \
    --arg current_tag "$current_fixture_tag" \
    --arg current_commit "$current_fixture_commit" \
    --arg current_object "$current_fixture_object" \
    --arg predecessor_tag "$predecessor_fixture_tag" \
    --arg predecessor_commit "$predecessor_fixture_commit" \
    --arg predecessor_object "$predecessor_fixture_object" '{
      schemaVersion: 1,
      current: {
        tag: $current_tag,
        commit: $current_commit,
        tagObject: $current_object,
        representativeMinecraftVersions: ["4.5", "8.9.1"]
      },
      predecessor: {tag: $predecessor_tag, commit: $predecessor_commit, tagObject: $predecessor_object}
    }' > "$fixture_repository/release/current-controller.json"
}

for source in \
  release/verify-release-tag.sh \
  release/list-release-tags.sh \
  release/verify-current-controller-release-order.sh \
  release/verify-github-tag-ruleset.sh \
  release/verify-pages-deployment-source.sh \
  release/verify-pages-artifact-equivalence.sh \
  release/wait-for-pages-source-receipt.sh \
  release/run-publish-controller-recovery.sh \
  gradle/list-java-toolchains.sh; do
  write_script "$source"
done
printf '{}\n' > "$fixture_repository/release/github-release-tag-ruleset.json"
printf '{}\n' > "$fixture_repository/release/github-release-tag-ruleset-receipt.json"
fixture_current_commit="$(printf 'a%.0s' {1..40})"
fixture_current_object="$(printf 'b%.0s' {1..40})"
fixture_predecessor_commit="$(printf 'c%.0s' {1..40})"
fixture_predecessor_object="$(printf 'd%.0s' {1..40})"
write_metadata v8.4.2 "$fixture_current_commit" "$fixture_current_object" v7.9.6 "$fixture_predecessor_commit" "$fixture_predecessor_object"
git -C "$fixture_repository" add release gradle
git -C "$fixture_repository" update-index --chmod=-x \
  release/verify-release-tag.sh \
  release/verify-github-tag-ruleset.sh \
  release/verify-pages-deployment-source.sh \
  release/verify-pages-artifact-equivalence.sh \
  release/wait-for-pages-source-receipt.sh
git -C "$fixture_repository" update-index --chmod=+x \
  release/list-release-tags.sh \
  release/verify-current-controller-release-order.sh \
  release/run-publish-controller-recovery.sh \
  gradle/list-java-toolchains.sh
git -C "$fixture_repository" commit --quiet -m 'Create arbitrary controller fixture'
valid_commit="$(git -C "$fixture_repository" rev-parse HEAD)"

for fixture_mode_spec in \
  '100644|release/current-controller.json' \
  '100644|release/verify-release-tag.sh' \
  '100755|release/list-release-tags.sh' \
  '100755|release/verify-current-controller-release-order.sh' \
  '100755|release/run-publish-controller-recovery.sh' \
  '100755|gradle/list-java-toolchains.sh'; do
  expected_fixture_mode="${fixture_mode_spec%%|*}"
  fixture_mode_path="${fixture_mode_spec#*|}"
  fixture_mode_record="$(git -C "$fixture_repository" ls-tree --full-tree "$valid_commit" -- "$fixture_mode_path")"
  read -r fixture_mode object_type fixture_blob verified_fixture_path <<< "$fixture_mode_record"
  [[ "$fixture_mode" == "$expected_fixture_mode" && "$object_type" == "blob" && \
    "$fixture_blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_fixture_path" == "$fixture_mode_path" ]] || \
    fail "Controller fixture did not preserve $expected_fixture_mode for $fixture_mode_path."
done

expect_guard_failure() {
  local label="$1"
  local operation="$2"
  local commit="$3"
  local directory="$4"
  if (cd "$fixture_repository" && bash "$controller_guard" "$operation" "$commit" "$directory" >/dev/null 2>&1); then
    fail "Controller guard accepted invalid fixture: $label"
  fi
}

valid_directory="$controller_test_root/valid"
mkdir "$valid_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$valid_directory")
(cd "$fixture_repository" && bash "$controller_guard" verify "$valid_commit" "$valid_directory")
for materialized_mode_spec in \
  'release/current-controller.json|current-controller.json' \
  'release/list-release-tags.sh|list-release-tags.sh'; do
  materialized_source="${materialized_mode_spec%%|*}"
  materialized_destination="${materialized_mode_spec#*|}"
  materialized_blob="$(git -C "$fixture_repository" rev-parse "$valid_commit:$materialized_source")"
  [[ "$(git -C "$fixture_repository" hash-object --no-filters -- "$valid_directory/$materialized_destination")" == "$materialized_blob" ]] || \
    fail "Materialized 100644/100755 fixture bytes differ from $materialized_source."
done
for generic in current-controller.json verify-release-tag.sh list-release-tags.sh verify-current-controller-release-order.sh \
  verify-github-tag-ruleset.sh github-release-tag-ruleset.json \
  github-release-tag-ruleset-receipt.json verify-pages-deployment-source.sh verify-pages-artifact-equivalence.sh wait-for-pages-source-receipt.sh \
  run-publish-controller-recovery.sh list-java-toolchains.sh; do
  [[ -f "$valid_directory/$generic" && ! -L "$valid_directory/$generic" ]] || fail "Generic controller mapping is missing: $generic"
done
for absent in backlog-recovery-runner backlog-recovery.json backlog-artifact-evidence.json; do
  [[ ! -e "$valid_directory/$absent" && ! -L "$valid_directory/$absent" ]] || fail "Unbound recovery was materialized: $absent"
done

order_directory="$controller_test_root/order"
mkdir "$order_directory"
jq '.current.tag = "v8.4.2" | .predecessor.tag = "v7.9.6"' \
  "$controller_metadata" > "$order_directory/current-controller.json"
cp "$repository_root/release/list-release-tags.sh" "$order_directory/list-release-tags.sh"
cp "$release_order_verifier" "$order_directory/verify-current-controller-release-order.sh"
order_repository="$controller_test_root/order-repository"
git init --quiet "$order_repository"
git -C "$order_repository" config user.name 'Strata Release Order Test'
git -C "$order_repository" config user.email 'release-order-test@example.invalid'
git -C "$order_repository" config commit.gpgsign false
git -C "$order_repository" config tag.gpgsign false
printf 'fixture\n' > "$order_repository/fixture.txt"
git -C "$order_repository" add fixture.txt
git -C "$order_repository" commit --quiet -m 'Create release-order fixture'
git -C "$order_repository" tag --annotate v7.9.6 --message predecessor
git -C "$order_repository" tag --annotate v8.4.2 --message current
bash "$order_directory/verify-current-controller-release-order.sh" "$order_directory/current-controller.json" "$order_repository" >/dev/null
git -C "$order_repository" tag v9.0.0
if bash "$order_directory/verify-current-controller-release-order.sh" "$order_directory/current-controller.json" "$order_repository" >/dev/null 2>&1; then
  fail 'Release-order verifier accepted a newer lightweight stable tag.'
fi
git -C "$order_repository" tag --delete v9.0.0 >/dev/null
git -C "$order_repository" tag --annotate v8.1.0 --message intervening
if bash "$order_directory/verify-current-controller-release-order.sh" "$order_directory/current-controller.json" "$order_repository" >/dev/null 2>&1; then
  fail 'Release-order verifier accepted metadata that skipped an intervening stable tag.'
fi

writable_file_directory="$controller_test_root/writable-file"
mkdir "$writable_file_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$writable_file_directory")
chmod u+w -- "$writable_file_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'writable materialized file' verify "$valid_commit" "$writable_file_directory"

swapped_symlink_directory="$controller_test_root/swapped-symlink"
mkdir "$swapped_symlink_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$swapped_symlink_directory")
printf 'outside\n' > "$controller_test_root/symlink-target"
chmod u+w -- "$swapped_symlink_directory"
rm -- "$swapped_symlink_directory/verify-pages-deployment-source.sh"
ln -s "$controller_test_root/symlink-target" "$swapped_symlink_directory/verify-pages-deployment-source.sh"
chmod a-w -- "$swapped_symlink_directory"
expect_guard_failure 'materialized symlink swap' verify "$valid_commit" "$swapped_symlink_directory"

preexisting_directory="$controller_test_root/preexisting"
mkdir "$preexisting_directory"
printf 'occupied\n' > "$preexisting_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'preexisting destination' materialize "$valid_commit" "$preexisting_directory"

symlink_directory="$controller_test_root/symlink-destination"
mkdir "$symlink_directory"
ln -s "$controller_test_root/symlink-target" "$symlink_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'symlink destination' materialize "$valid_commit" "$symlink_directory"

chmod u+w -- "$valid_directory" "$valid_directory/current-controller.json"
printf '\n' >> "$valid_directory/current-controller.json"
chmod a-w -- "$valid_directory" "$valid_directory/current-controller.json"
expect_guard_failure 'metadata byte tamper' verify "$valid_commit" "$valid_directory"

writable_directory="$controller_test_root/writable"
mkdir "$writable_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$writable_directory")
chmod u+w -- "$writable_directory"
expect_guard_failure 'writable materialized directory' verify "$valid_commit" "$writable_directory"

for invalid_fixture in extra-key missing-field invalid-tag invalid-hash duplicate-identity empty-representatives duplicate-representatives invalid-representative; do
  git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
  case "$invalid_fixture" in
    extra-key)
      temporary_metadata="$controller_test_root/metadata"
      jq '.unexpected = true' "$fixture_repository/release/current-controller.json" > "$temporary_metadata"
      cp "$temporary_metadata" "$fixture_repository/release/current-controller.json"
      ;;
    missing-field) jq 'del(.predecessor.tagObject)' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    invalid-tag) jq '.current.tag = "release-8"' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    invalid-hash) jq '.current.commit = "ABCDEF"' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    duplicate-identity) jq '.predecessor = .current' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    empty-representatives) jq '.current.representativeMinecraftVersions = []' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    duplicate-representatives) jq '.current.representativeMinecraftVersions = ["4.5", "4.5"]' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
    invalid-representative) jq '.current.representativeMinecraftVersions = ["latest"]' "$fixture_repository/release/current-controller.json" > "$controller_test_root/metadata"; cp "$controller_test_root/metadata" "$fixture_repository/release/current-controller.json" ;;
  esac
  git -C "$fixture_repository" add release/current-controller.json
  git -C "$fixture_repository" commit --quiet -m "Create $invalid_fixture metadata fixture"
  invalid_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
  invalid_directory="$controller_test_root/$invalid_fixture"
  mkdir "$invalid_directory"
  expect_guard_failure "$invalid_fixture schema" materialize "$invalid_commit" "$invalid_directory"
done

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
git -C "$fixture_repository" update-index --chmod=+x release/current-controller.json
git -C "$fixture_repository" commit --quiet -m 'Make controller metadata executable'
mode_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/mode"
expect_guard_failure 'metadata source mode' materialize "$mode_commit" "$controller_test_root/mode"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
git -C "$fixture_repository" update-index --chmod=-x release/list-release-tags.sh
git -C "$fixture_repository" commit --quiet -m 'Make executable controller source non-executable'
reverse_mode_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/reverse-mode"
expect_guard_failure 'executable source mode' materialize "$reverse_mode_commit" "$controller_test_root/reverse-mode"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
git -C "$fixture_repository" rm --quiet release/wait-for-pages-source-receipt.sh
git -C "$fixture_repository" commit --quiet -m 'Remove controller tool fixture'
missing_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/missing-source"
expect_guard_failure 'missing source' materialize "$missing_commit" "$controller_test_root/missing-source"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
symlink_blob="$(printf 'verify-pages-deployment-source.sh\n' | git -C "$fixture_repository" hash-object -w --stdin)"
git -C "$fixture_repository" update-index --add --cacheinfo "120000,$symlink_blob,release/wait-for-pages-source-receipt.sh"
git -C "$fixture_repository" commit --quiet -m 'Make controller tool fixture a symlink'
symlink_source_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/symlink-source"
expect_guard_failure 'symlink source mode' materialize "$symlink_source_commit" "$controller_test_root/symlink-source"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
git -C "$fixture_repository" update-index --add --cacheinfo "160000,$valid_commit,release/wait-for-pages-source-receipt.sh"
git -C "$fixture_repository" commit --quiet -m 'Make controller tool fixture a gitlink'
gitlink_source_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/gitlink-source"
expect_guard_failure 'gitlink source mode' materialize "$gitlink_source_commit" "$controller_test_root/gitlink-source"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
printf '[]\n' > "$fixture_repository/release/github-release-tag-ruleset.json"
git -C "$fixture_repository" add release/github-release-tag-ruleset.json
git -C "$fixture_repository" commit --quiet -m 'Make controller JSON fixture invalid'
invalid_json_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/invalid-json"
expect_guard_failure 'non-object JSON' materialize "$invalid_json_commit" "$controller_test_root/invalid-json"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
printf '#!/usr/bin/env bash\nif\n' > "$fixture_repository/release/verify-pages-deployment-source.sh"
git -C "$fixture_repository" add release/verify-pages-deployment-source.sh
git -C "$fixture_repository" commit --quiet -m 'Make controller Bash fixture invalid'
invalid_bash_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
mkdir "$controller_test_root/invalid-bash"
expect_guard_failure 'invalid Bash syntax' materialize "$invalid_bash_commit" "$controller_test_root/invalid-bash"

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
printf '#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n' > "$fixture_repository/release/verify-github-tag-ruleset.sh"
git -C "$fixture_repository" add release/verify-github-tag-ruleset.sh
git -C "$fixture_repository" commit --quiet -m 'Create replacement controller fixture'
replacement_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
git -C "$fixture_repository" replace "$valid_commit" "$replacement_commit"
replace_commit_directory="$controller_test_root/replace-commit"
mkdir "$replace_commit_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$replace_commit_directory")
original_verifier_blob="$(git -C "$fixture_repository" --no-replace-objects rev-parse "$valid_commit:release/verify-github-tag-ruleset.sh")"
[[ "$(git -C "$fixture_repository" hash-object --no-filters -- "$replace_commit_directory/verify-github-tag-ruleset.sh")" == "$original_verifier_blob" ]] || fail 'Commit replacement changed materialized controller bytes.'
git -C "$fixture_repository" replace -d "$valid_commit" >/dev/null

replacement_verifier_blob="$(printf '#!/usr/bin/env bash\nexit 0\n' | git -C "$fixture_repository" hash-object -w --stdin)"
git -C "$fixture_repository" replace "$original_verifier_blob" "$replacement_verifier_blob"
replace_blob_directory="$controller_test_root/replace-blob"
mkdir "$replace_blob_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$valid_commit" "$replace_blob_directory")
[[ "$(git -C "$fixture_repository" hash-object --no-filters -- "$replace_blob_directory/verify-github-tag-ruleset.sh")" == "$original_verifier_blob" ]] || fail 'Blob replacement changed materialized controller bytes.'
git -C "$fixture_repository" replace -d "$original_verifier_blob" >/dev/null

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
baseline_tag=v3.2.1
baseline_commit="$(printf 'e%.0s' {1..40})"
baseline_object="$(printf 'f%.0s' {1..40})"
jq -n \
  --arg current_tag v8.4.2 --arg current_commit "$fixture_current_commit" --arg current_object "$fixture_current_object" \
  --arg predecessor_tag v7.9.6 --arg predecessor_commit "$fixture_predecessor_commit" --arg predecessor_object "$fixture_predecessor_object" \
  --arg baseline_tag "$baseline_tag" --arg baseline_commit "$baseline_commit" --arg baseline_object "$baseline_object" '{
    releaseSource: {tag: $current_tag, commit: $current_commit, tagObject: $current_object},
    baselineReleases: [
      {tag: $baseline_tag, commit: $baseline_commit, tagObject: $baseline_object},
      {tag: $predecessor_tag, commit: $predecessor_commit, tagObject: $predecessor_object}
    ]
  }' > "$fixture_repository/release/fixture-backlog-recovery.json"
write_script release/run-fixture-backlog-recovery.sh
jq -n --arg tag "$baseline_tag" --arg commit "$baseline_commit" '{releaseTag: $tag, releaseCommit: $commit}' > "$fixture_repository/release/fixture-artifacts.json"
git -C "$fixture_repository" add release
git -C "$fixture_repository" commit --quiet -m 'Bind arbitrary recovery fixture'
recovery_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
recovery_directory="$controller_test_root/recovery"
mkdir "$recovery_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$recovery_commit" "$recovery_directory")
(cd "$fixture_repository" && bash "$controller_guard" verify "$recovery_commit" "$recovery_directory")
for generic in backlog-recovery-runner backlog-recovery.json backlog-artifact-evidence.json; do
  [[ -f "$recovery_directory/$generic" && ! -L "$recovery_directory/$generic" ]] || fail "Identity-bound recovery generic mapping is missing: $generic"
done
if find "$recovery_directory" -maxdepth 1 -type f | grep --extended-regexp 'v[0-9]+\.[0-9]+\.[0-9]+' >/dev/null; then
  fail 'Materialized recovery destinations expose a release-number filename.'
fi

git -C "$fixture_repository" reset --hard "$recovery_commit" >/dev/null
jq '.releaseSource.commit = "0000000000000000000000000000000000000000"' \
  "$fixture_repository/release/fixture-backlog-recovery.json" > "$controller_test_root/unbound-contract"
cp "$controller_test_root/unbound-contract" "$fixture_repository/release/fixture-backlog-recovery.json"
git -C "$fixture_repository" add release/fixture-backlog-recovery.json
git -C "$fixture_repository" commit --quiet -m 'Unbind recovery fixture'
unbound_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
unbound_directory="$controller_test_root/unbound"
mkdir "$unbound_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$unbound_commit" "$unbound_directory")
for absent in backlog-recovery-runner backlog-recovery.json backlog-artifact-evidence.json; do
  [[ ! -e "$unbound_directory/$absent" && ! -L "$unbound_directory/$absent" ]] || fail "Unbound recovery destination was enabled: $absent"
done

write_recovery_metadata() {
  local contract="$1"
  write_metadata \
    "$(portable_jq -er '.releaseSource.tag' "$contract")" \
    "$(portable_jq -er '.releaseSource.commit' "$contract")" \
    "$(portable_jq -er '.releaseSource.tagObject' "$contract")" \
    "$(portable_jq -er '.baselineReleases[-1].tag' "$contract")" \
    "$(portable_jq -er '.baselineReleases[-1].commit' "$contract")" \
    "$(portable_jq -er '.baselineReleases[-1].tagObject' "$contract")"
}

require_absent_recovery() {
  local directory="$1"
  local label="$2"
  local absent=""
  for absent in backlog-recovery-runner backlog-recovery.json backlog-artifact-evidence.json; do
    [[ ! -e "$directory/$absent" && ! -L "$directory/$absent" ]] || \
      fail "$label enabled an unbound recovery destination: $absent"
  done
}

git -C "$fixture_repository" reset --hard "$valid_commit" >/dev/null
release_recovery_sources=(
  release/modrinth-v0.1.0-artifacts.json
  release/modrinth-v0.1.2-backlog-recovery.json
  release/run-modrinth-v0.1.2-backlog-recovery.sh
  release/modrinth-v0.1.3-backlog-recovery.json
  release/run-modrinth-v0.1.3-backlog-recovery.sh
)
for source in "${release_recovery_sources[@]}"; do
  [[ -f "$repository_root/$source" && ! -L "$repository_root/$source" ]] || \
    fail "Release recovery fixture source is missing or unsafe: $source"
  cp "$repository_root/$source" "$fixture_repository/$source"
done
git -C "$fixture_repository" add release
git -C "$fixture_repository" update-index --chmod=-x "${release_recovery_sources[@]}"
git -C "$fixture_repository" commit --quiet -m 'Retain both release recovery fixture bundles'
release_recovery_fixture_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
current_recovery_fixture_commit=''
current_recovery_directory=''
for recovery_tag in v0.1.2 v0.1.3; do
  git -C "$fixture_repository" reset --hard "$release_recovery_fixture_commit" >/dev/null
  recovery_contract_path="release/modrinth-$recovery_tag-backlog-recovery.json"
  write_recovery_metadata "$fixture_repository/$recovery_contract_path"
  git -C "$fixture_repository" add release/current-controller.json
  git -C "$fixture_repository" commit --quiet -m "Select $recovery_tag recovery fixture"
  selected_recovery_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
  selected_recovery_directory="$controller_test_root/release-recovery-$recovery_tag"
  mkdir "$selected_recovery_directory"
  (cd "$fixture_repository" && bash "$controller_guard" materialize "$selected_recovery_commit" "$selected_recovery_directory")
  (cd "$fixture_repository" && bash "$controller_guard" verify "$selected_recovery_commit" "$selected_recovery_directory")
  for mapping in \
    "$recovery_contract_path|backlog-recovery.json" \
    "release/run-modrinth-$recovery_tag-backlog-recovery.sh|backlog-recovery-runner" \
    'release/modrinth-v0.1.0-artifacts.json|backlog-artifact-evidence.json'; do
    source="${mapping%%|*}"
    destination="${mapping#*|}"
    expected_blob="$(git -C "$fixture_repository" rev-parse "$selected_recovery_commit:$source")"
    [[ -f "$selected_recovery_directory/$destination" && ! -L "$selected_recovery_directory/$destination" && \
      "$(git -C "$fixture_repository" hash-object --no-filters -- "$selected_recovery_directory/$destination")" == "$expected_blob" ]] || \
      fail "$recovery_tag selected the wrong recovery source for $destination."
  done
  case "$recovery_tag" in
    v0.1.2)
      jq -e '.schemaVersion == 1 and (.baselineReleases | length) == 2' \
        "$selected_recovery_directory/backlog-recovery.json" >/dev/null || fail 'The historical recovery fixture schema or baseline inventory changed.'
      ;;
    v0.1.3)
      jq -e '.schemaVersion == 2 and (.baselineReleases | length) == 3 and has("stagingProvenance") == false' \
        "$selected_recovery_directory/backlog-recovery.json" >/dev/null || fail 'The current recovery fixture schema or baseline inventory differs.'
      current_recovery_fixture_commit="$selected_recovery_commit"
      current_recovery_directory="$selected_recovery_directory"
      ;;
  esac
done
[[ -n "$current_recovery_fixture_commit" && -n "$current_recovery_directory" ]] || \
  fail 'The current release recovery fixture was not selected.'

git -C "$fixture_repository" reset --hard "$current_recovery_fixture_commit" >/dev/null
current_recovery_contract="$fixture_repository/release/modrinth-v0.1.3-backlog-recovery.json"
write_metadata v0.1.4 "$fixture_current_commit" "$fixture_current_object" \
  "$(portable_jq -er '.releaseSource.tag' "$current_recovery_contract")" \
  "$(portable_jq -er '.releaseSource.commit' "$current_recovery_contract")" \
  "$(portable_jq -er '.releaseSource.tagObject' "$current_recovery_contract")"
git -C "$fixture_repository" add release/current-controller.json
git -C "$fixture_repository" commit --quiet -m 'Advance beyond both recovery fixture pairs'
future_recovery_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
future_recovery_directory="$controller_test_root/future-release-recovery"
mkdir "$future_recovery_directory"
(cd "$fixture_repository" && bash "$controller_guard" materialize "$future_recovery_commit" "$future_recovery_directory")
(cd "$fixture_repository" && bash "$controller_guard" verify "$future_recovery_commit" "$future_recovery_directory")
require_absent_recovery "$future_recovery_directory" 'A future release pair'

for mismatch_spec in \
  'release-tag|.releaseSource.tag = "v9.9.9"' \
  'release-commit|.releaseSource.commit = "0000000000000000000000000000000000000000"' \
  'release-object|.releaseSource.tagObject = "0000000000000000000000000000000000000000"' \
  'predecessor-tag|.baselineReleases[-1].tag = "v9.9.9"' \
  'predecessor-commit|.baselineReleases[-1].commit = "0000000000000000000000000000000000000000"' \
  'predecessor-object|.baselineReleases[-1].tagObject = "0000000000000000000000000000000000000000"' \
  'last-baseline-position|.baselineReleases += [.baselineReleases[0]]'; do
  mismatch_label="${mismatch_spec%%|*}"
  mismatch_filter="${mismatch_spec#*|}"
  git -C "$fixture_repository" reset --hard "$current_recovery_fixture_commit" >/dev/null
  portable_jq "$mismatch_filter" "$current_recovery_contract" > "$controller_test_root/mismatched-recovery.json"
  cp "$controller_test_root/mismatched-recovery.json" "$current_recovery_contract"
  git -C "$fixture_repository" add release/modrinth-v0.1.3-backlog-recovery.json
  git -C "$fixture_repository" commit --quiet -m "Mismatch recovery fixture $mismatch_label"
  mismatched_recovery_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
  mismatched_recovery_directory="$controller_test_root/mismatched-recovery-$mismatch_label"
  mkdir "$mismatched_recovery_directory"
  (cd "$fixture_repository" && bash "$controller_guard" materialize "$mismatched_recovery_commit" "$mismatched_recovery_directory")
  (cd "$fixture_repository" && bash "$controller_guard" verify "$mismatched_recovery_commit" "$mismatched_recovery_directory")
  require_absent_recovery "$mismatched_recovery_directory" "Recovery mismatch $mismatch_label"
  expect_guard_failure "stale recovery after $mismatch_label mismatch" verify \
    "$mismatched_recovery_commit" "$current_recovery_directory"
done

git -C "$fixture_repository" reset --hard "$current_recovery_fixture_commit" >/dev/null
cp "$current_recovery_contract" "$fixture_repository/release/duplicate-backlog-recovery.json"
cp "$fixture_repository/release/run-modrinth-v0.1.3-backlog-recovery.sh" \
  "$fixture_repository/release/run-duplicate-backlog-recovery.sh"
git -C "$fixture_repository" add release/duplicate-backlog-recovery.json release/run-duplicate-backlog-recovery.sh
git -C "$fixture_repository" update-index --chmod=-x \
  release/duplicate-backlog-recovery.json release/run-duplicate-backlog-recovery.sh
git -C "$fixture_repository" commit --quiet -m 'Duplicate a matching recovery fixture'
duplicate_recovery_commit="$(git -C "$fixture_repository" rev-parse HEAD)"
duplicate_recovery_directory="$controller_test_root/duplicate-release-recovery"
mkdir "$duplicate_recovery_directory"
expect_guard_failure 'duplicate identity-bound recovery contracts' materialize \
  "$duplicate_recovery_commit" "$duplicate_recovery_directory"

wrapper_directory="$controller_test_root/wrapper"
mkdir "$wrapper_directory"
cp "$recovery_wrapper" "$wrapper_directory/run-publish-controller-recovery.sh"
jq -n '{releaseSource: {tag: "v9.8.7"}, baselineReleases: [{tag: "v1.2.3"}]}' > "$wrapper_directory/backlog-recovery.json"
printf '{}\n' > "$wrapper_directory/backlog-artifact-evidence.json"
cp "$recovery_directory/verify-pages-deployment-source.sh" "$wrapper_directory/verify-pages-deployment-source.sh"
cp "$recovery_directory/verify-pages-artifact-equivalence.sh" "$wrapper_directory/verify-pages-artifact-equivalence.sh"
wrapper_pages_verifier_sha256="$(sha256sum "$wrapper_directory/verify-pages-deployment-source.sh" | cut -d ' ' -f 1)"
wrapper_pages_comparator_sha256="$(sha256sum "$wrapper_directory/verify-pages-artifact-equivalence.sh" | cut -d ' ' -f 1)"
cat > "$wrapper_directory/backlog-recovery-runner" <<'WRAPPER_FIXTURE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$(basename "$0")" == 'run-modrinth-v9.8.7-backlog-recovery.sh' ]]
[[ "$(basename "$2")" == 'modrinth-v9.8.7-backlog-recovery.json' ]]
[[ -f "$(dirname "$0")/modrinth-v1.2.3-artifacts.json" ]]
[[ -f "$(dirname "$0")/verify-pages-deployment-source.sh" && \
  ! -L "$(dirname "$0")/verify-pages-deployment-source.sh" ]]
[[ -f "$(dirname "$0")/verify-pages-artifact-equivalence.sh" && \
  ! -L "$(dirname "$0")/verify-pages-artifact-equivalence.sh" ]]
[[ "$(sha256sum "$(dirname "$0")/verify-pages-deployment-source.sh" | cut -d ' ' -f 1)" == \
  "$WRAPPER_PAGES_VERIFIER_SHA256" ]]
[[ "$(sha256sum "$(dirname "$0")/verify-pages-artifact-equivalence.sh" | cut -d ' ' -f 1)" == \
  "$WRAPPER_PAGES_COMPARATOR_SHA256" ]]
printf 'passed\n' > "$WRAPPER_MARKER"
WRAPPER_FIXTURE
wrapper_marker="$controller_test_root/wrapper-passed"
RUNNER_TEMP="$controller_test_root" WRAPPER_MARKER="$wrapper_marker" \
  WRAPPER_PAGES_VERIFIER_SHA256="$wrapper_pages_verifier_sha256" \
  WRAPPER_PAGES_COMPARATOR_SHA256="$wrapper_pages_comparator_sha256" \
  bash "$wrapper_directory/run-publish-controller-recovery.sh" preflight \
    "$wrapper_directory/backlog-recovery.json" project v9.8.7 \
    "$fixture_current_commit" "$valid_commit" 'pages record'
[[ "$(cat "$wrapper_marker")" == 'passed' ]] || fail 'Generic recovery wrapper did not reconstruct the immutable incident bundle.'

release_cleanup_script="$(step_run 'Clean up release controller tools')"
verify_cleanup_script="$(step_run 'Clean up verification controller tools')"
[[ -n "$release_cleanup_script" && -n "$verify_cleanup_script" ]] || fail 'A controller cleanup run block could not be extracted.'
cleanup_runner="$controller_test_root/cleanup-runner"
mkdir "$cleanup_runner"
for cleanup_fixture in \
  "release|strata-release-controller-tools|$release_cleanup_script" \
  "verify|strata-verify-controller-tools|$verify_cleanup_script"; do
  cleanup_label="${cleanup_fixture%%|*}"
  cleanup_remainder="${cleanup_fixture#*|}"
  cleanup_prefix="${cleanup_remainder%%|*}"
  cleanup_script="${cleanup_remainder#*|}"

  cleanup_directory="$cleanup_runner/$cleanup_prefix.Ab12Cd"
  mkdir "$cleanup_directory"
  printf 'read-only\n' > "$cleanup_directory/tool"
  chmod a-w -- "$cleanup_directory" "$cleanup_directory/tool"
  RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_directory" bash -c "$cleanup_script"
  [[ ! -e "$cleanup_directory" && ! -L "$cleanup_directory" ]] || fail "$cleanup_label cleanup left a valid read-only bundle."

  cleanup_non_directory="$cleanup_runner/$cleanup_prefix.Ef34Gh"
  printf 'not-a-directory\n' > "$cleanup_non_directory"
  if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_non_directory" bash -c "$cleanup_script" >/dev/null 2>&1; then
    fail "$cleanup_label cleanup accepted a non-directory target."
  fi
  [[ -f "$cleanup_non_directory" ]] || fail "$cleanup_label cleanup removed a rejected non-directory target."

  cleanup_symlink_target="$controller_test_root/$cleanup_label-cleanup-symlink-target"
  mkdir "$cleanup_symlink_target"
  printf 'retained\n' > "$cleanup_symlink_target/evidence"
  cleanup_symlink="$cleanup_runner/$cleanup_prefix.Ij56Kl"
  ln -s "$cleanup_symlink_target" "$cleanup_symlink"
  if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_symlink" bash -c "$cleanup_script" >/dev/null 2>&1; then
    fail "$cleanup_label cleanup accepted a symlink target."
  fi
  [[ -f "$cleanup_symlink_target/evidence" ]] || fail "$cleanup_label cleanup followed a rejected symlink."

  cleanup_bad_prefix="$cleanup_runner/$cleanup_prefix.bad"
  mkdir "$cleanup_bad_prefix"
  if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_bad_prefix" bash -c "$cleanup_script" >/dev/null 2>&1; then
    fail "$cleanup_label cleanup accepted an invalid temporary prefix."
  fi
  [[ -d "$cleanup_bad_prefix" ]] || fail "$cleanup_label cleanup removed an invalid-prefix directory."
done

cleanup_controller_test
trap - EXIT INT TERM

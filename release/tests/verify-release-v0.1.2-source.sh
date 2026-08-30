#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
workflow="$repository_root/.github/workflows/publish-release.yml"
sealed_previous="$repository_root/.github/workflows/release-v0.1.1.yml"
sealed_initial="$repository_root/.github/workflows/release.yml"
jvm_workflow="$repository_root/.github/workflows/jvm.yml"
controller_guard="$repository_root/release/verify-controller-tools.sh"

fail() {
  echo "$1" >&2
  exit 1
}

step_block() {
  local name="$1"
  awk -v header="      - name: $name" '
    $0 == header { inside = 1 }
    inside && $0 != header && /^      - name:/ { exit }
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

require_pattern_before() {
  local block="$1"
  local first_pattern="$2"
  local second="$3"
  local first_line=""
  local second_line=""
  first_line="$(grep -n -m 1 --extended-regexp "$first_pattern" <<< "$block" | cut -d: -f1 || true)"
  second_line="$(grep -n -m 1 --fixed-strings "$second" <<< "$block" | cut -d: -f1 || true)"
  [[ -n "$first_line" && -n "$second_line" && "$first_line" -lt "$second_line" ]] || \
    fail "Required controller ordering is missing: $first_pattern before $second"
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
    [[ "${previous#"${previous%%[![:space:]]*}"}" == "$guard" ]] || \
      fail "Controller call is not immediately guarded: $call"
  done <<< "$call_lines"
}

for required in "$workflow" "$sealed_previous" "$sealed_initial" "$jvm_workflow" "$controller_guard"; do
  [[ -f "$required" ]] || fail "Required release source is missing: $required"
done

[[ "$(find "$repository_root/.github/workflows" -maxdepth 1 -type f \
  \( -name 'release-v*.yml' -o -name 'release-v*.yaml' \) -print | wc -l | tr -d '[:space:]')" == '1' ]] || \
  fail 'Only the sealed v0.1.1 version-specific release workflow may exist.'

topology_script="$(
  awk '
    /^      - name: Check pinned workflow topology$/ { found_step = 1; next }
    found_step && /^        run: \|$/ { in_run = 1; next }
    found_step && /^      - name:/ { exit }
    in_run { sub(/^          /, ""); print }
  ' "$workflow"
)"
[[ -n "$topology_script" ]] || fail 'The v0.1.2 topology guard is missing.'
(cd "$repository_root" && bash -c "$topology_script") || fail 'The v0.1.2 topology guard does not execute successfully.'

grep --fixed-strings 'name: Publish release' "$workflow" >/dev/null || fail 'The active release workflow name differs.'
grep --fixed-strings 'default: v0.1.2' "$workflow" >/dev/null || fail 'The v0.1.2 tag input is not pinned.'
grep --fixed-strings '[[ "$RELEASE_TAG" == "v0.1.2"' "$workflow" >/dev/null || fail 'The v0.1.2 runtime tag guard is missing.'
grep --fixed-strings '[[ "$tag_commit" == "b541fc5492b798b6805c0c4d24e09f43ceff938a" ]]' "$workflow" >/dev/null || fail 'Release does not pin the reviewed v0.1.2 source commit.'
[[ "$(grep --fixed-strings -c 'release/github-release-tag-ruleset.json' "$workflow")" == '2' ]] || fail 'The wildcard ruleset contract must be referenced directly only by controller preflight.'
[[ "$(grep --fixed-strings -c 'release/github-release-tag-ruleset-receipt.json' "$workflow")" == '2' ]] || fail 'The wildcard ruleset receipt must be referenced directly only by controller preflight.'
[[ "$(grep --fixed-strings -c 'run_controller_tool_guard() {' "$workflow")" == '2' ]] || fail 'Release and final verification must each bootstrap the exact controller guard.'
[[ "$(grep --fixed-strings -c 'git --no-replace-objects cat-file blob "$blob"' "$workflow")" == '2' ]] || fail 'Each bootstrap must execute the exact guard blob with replacement objects disabled.'
[[ "$(grep --fixed-strings -c 'run_controller_tool_guard materialize' "$workflow")" == '2' ]] || fail 'Each tagged job must materialize one bounded controller bundle.'
[[ "$(grep --fixed-strings -c 'verify_controller_tools() {' "$workflow")" == '7' ]] || fail 'Every later controller-tool step must define exact-commit revalidation.'
[[ "$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' "$workflow")" == '5' ]] || fail 'Every current-release ruleset boundary must use the verified controller directory.'
[[ "$(grep --fixed-strings -c 'bash "$controller_pages_waiter"' "$workflow")" == '2' && \
  "$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"' "$workflow")" == '2' ]] || fail 'Every current or predecessor Pages poll must use the verified controller directory.'
[[ "$(grep --fixed-strings -c 'bash "$controller_pages_verifier"' "$workflow")" == '4' && \
  "$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"' "$workflow")" == '3' ]] || fail 'Every current or predecessor Pages proof must use the verified controller directory.'
[[ "$(grep --fixed-strings -c 'steps.controller_tools.outputs.directory' "$workflow")" == '4' ]] || fail 'Cleanup must be bound to each initialization step output.'
for forbidden_path_variable in CONTROLLER_TAG_RULESET_VERIFIER CONTROLLER_TAG_RULESET_CONTRACT CONTROLLER_TAG_RULESET_RECEIPT CONTROLLER_PAGES_VERIFIER CONTROLLER_PAGES_WAITER; do
  if grep --fixed-strings "$forbidden_path_variable" "$workflow" >/dev/null; then
    fail "A rebindable controller tool path remains: $forbidden_path_variable"
  fi
done
if grep --fixed-strings 'git show "$GITHUB_SHA:release/' "$workflow" >/dev/null; then
  fail 'Tagged jobs must not materialize controller tools with an unvalidated git show redirection.'
fi

[[ "$(grep --extended-regexp -c '^verify_controller_tool release/' "$controller_guard")" == '5' ]] || fail 'The controller guard must retain exactly five fixed tool mappings.'
grep --fixed-strings 'git --no-replace-objects cat-file -t "$controller_commit"' "$controller_guard" >/dev/null || fail 'The controller guard must ignore commit replacement objects.'
grep --fixed-strings 'git --no-replace-objects ls-tree --full-tree "$controller_commit"' "$controller_guard" >/dev/null || fail 'The controller guard must ignore tree replacement objects.'
grep --fixed-strings 'git --no-replace-objects cat-file blob "$blob"' "$controller_guard" >/dev/null || fail 'The controller guard must ignore blob replacement objects.'
grep --fixed-strings 'git hash-object --no-filters -- "$destination"' "$controller_guard" >/dev/null || fail 'The controller guard must hash materialized bytes without filters.'

release_init_block="$(step_block 'Revalidate protected release request')"
verify_init_block="$(step_block 'Validate final verification source and Pages provenance')"
for init_block in "$release_init_block" "$verify_init_block"; do
  [[ "$(grep --fixed-strings -c 'run_controller_tool_guard materialize' <<< "$init_block")" == '1' ]] || fail 'A tagged job must materialize exactly one controller bundle.'
  [[ "$(grep --fixed-strings -c 'run_controller_tool_guard verify' <<< "$init_block")" == '4' ]] || fail 'A tagged job must verify the bundle before every initial controller-tool use.'
  grep --fixed-strings 'echo "directory=$controller_tool_directory" >> "$GITHUB_OUTPUT"' <<< "$init_block" >/dev/null || fail 'Controller cleanup directory is not a step output.'
  require_before "$init_block" 'run_controller_tool_guard materialize' 'bash release/verify-release-tag.sh'
  require_before "$init_block" 'bash release/verify-release-tag.sh' 'run_controller_tool_guard verify'
  require_before "$init_block" 'run_controller_tool_guard verify' 'bash "$controller_ruleset_verifier"'
done

require_immediate_guard "$release_init_block" 'bash "$controller_ruleset_verifier"' 'run_controller_tool_guard verify "$EXPECTED_CONTROLLER_COMMIT" "$controller_tool_directory"'
require_immediate_guard "$release_init_block" 'bash "$controller_pages_verifier"' 'run_controller_tool_guard verify "$EXPECTED_CONTROLLER_COMMIT" "$controller_tool_directory"'
require_immediate_guard "$release_init_block" 'bash "$controller_pages_waiter"' 'run_controller_tool_guard verify "$EXPECTED_CONTROLLER_COMMIT" "$controller_tool_directory"'
require_immediate_guard "$verify_init_block" 'bash "$controller_ruleset_verifier"' 'run_controller_tool_guard verify "$GITHUB_SHA" "$controller_tool_directory"'
require_immediate_guard "$verify_init_block" 'bash "$controller_pages_verifier"' 'run_controller_tool_guard verify "$GITHUB_SHA" "$controller_tool_directory"'
require_immediate_guard "$verify_init_block" 'bash "$controller_pages_waiter"' 'run_controller_tool_guard verify "$GITHUB_SHA" "$controller_tool_directory"'

for mutation_spec in \
  'Publish wholly absent Maven Central release|publishAndReleaseToMavenCentral' \
  'Stage only missing Modrinth versions|modrinthReleaseStage' \
  'Create or verify immutable GitHub Release|gh release create' \
  'Submit or observe Modrinth review|modrinthReleaseSubmit'; do
  mutation_name="${mutation_spec%%|*}"
  mutation_write="${mutation_spec#*|}"
  mutation_block="$(step_block "$mutation_name")"
  [[ "$(grep --extended-regexp -c '^[[:space:]]+verify_controller_tools$' <<< "$mutation_block")" == '1' ]] || fail "Controller bundle is not revalidated exactly once in $mutation_name."
  grep --fixed-strings 'git --no-replace-objects cat-file blob "$EXPECTED_CONTROLLER_COMMIT:release/verify-controller-tools.sh"' <<< "$mutation_block" >/dev/null || fail "Controller guard does not execute from the exact release controller in $mutation_name."
  grep --fixed-strings 'bash -s -- verify "$EXPECTED_CONTROLLER_COMMIT" "$CONTROLLER_TOOL_DIRECTORY"' <<< "$mutation_block" >/dev/null || fail "Controller bundle verification arguments differ in $mutation_name."
  require_pattern_before "$mutation_block" '^[[:space:]]+verify_controller_tools$' 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"'
  require_immediate_guard "$mutation_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' 'verify_controller_tools'
  require_before "$mutation_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' "$mutation_write"
done

[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$(step_block 'Publish wholly absent Maven Central release')")" == '2' ]] || fail 'Central publication must revalidate before both absence confirmation and write.'
[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$(step_block 'Create or verify immutable GitHub Release')")" == '3' ]] || fail 'Every GitHub Release mutation phase must revalidate controller tools and source.'

current_pages_block="$(step_block 'Verify public Pages and tagged Skill source')"
previous_pages_block="$(step_block 'Verify public v0.1.1 services and Pages provenance')"
finalize_controller_block="$(step_block 'Finalize v0.1.2 Modrinth body and verify approved release')"
for final_controller_block in "$current_pages_block" "$previous_pages_block" "$finalize_controller_block"; do
  grep --fixed-strings 'git --no-replace-objects cat-file blob "$GITHUB_SHA:release/verify-controller-tools.sh"' <<< "$final_controller_block" >/dev/null || fail 'Final verification does not execute the guard from the exact controller.'
  grep --fixed-strings 'bash -s -- verify "$GITHUB_SHA" "$CONTROLLER_TOOL_DIRECTORY"' <<< "$final_controller_block" >/dev/null || fail 'Final controller bundle verification arguments differ.'
done
[[ "$(grep --extended-regexp -c '^[[:space:]]+verify_controller_tools$' <<< "$current_pages_block")" == '2' ]] || fail 'Current public Pages tools are not revalidated before both uses.'
[[ "$(grep --extended-regexp -c '^[[:space:]]+verify_controller_tools$' <<< "$previous_pages_block")" == '3' ]] || fail 'Predecessor public Pages tools are not revalidated before all uses.'
require_pattern_before "$current_pages_block" '^[[:space:]]+verify_controller_tools$' 'bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"'
require_pattern_before "$previous_pages_block" '^[[:space:]]+verify_controller_tools$' 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"'
require_immediate_guard "$current_pages_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"' 'verify_controller_tools'
require_immediate_guard "$current_pages_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"' 'verify_controller_tools'
require_immediate_guard "$previous_pages_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/wait-for-pages-source-receipt.sh"' 'verify_controller_tools'
require_immediate_guard "$previous_pages_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-pages-deployment-source.sh"' 'verify_controller_tools'

[[ "$(grep --extended-regexp -c '^[[:space:]]+verify_controller_tools$' <<< "$finalize_controller_block")" == '1' ]] || fail 'Final Modrinth mutation must revalidate controller tools once.'
require_pattern_before "$finalize_controller_block" '^[[:space:]]+verify_controller_tools$' 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"'
require_immediate_guard "$finalize_controller_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' 'verify_controller_tools'
require_before "$finalize_controller_block" 'bash "$CONTROLLER_TOOL_DIRECTORY/verify-github-tag-ruleset.sh"' 'modrinthReleaseFinalizeProject'

for cleanup_name in 'Clean up release controller tools' 'Clean up verification controller tools'; do
  cleanup_block="$(step_block "$cleanup_name")"
  grep --fixed-strings "if: always() && steps.controller_tools.outputs.directory != ''" <<< "$cleanup_block" >/dev/null || fail "Terminal cleanup is not unconditional after initialization: $cleanup_name"
  grep --fixed-strings 'CONTROLLER_TOOL_DIRECTORY: ${{ steps.controller_tools.outputs.directory }}' <<< "$cleanup_block" >/dev/null || fail "Cleanup does not use the immutable step output: $cleanup_name"
  grep --fixed-strings '"$(dirname -- "$directory")" == "${RUNNER_TEMP%/}"' <<< "$cleanup_block" >/dev/null || fail "Cleanup is not restricted to the runner temporary root: $cleanup_name"
done

controller_test_root="$(mktemp -d)"
cleanup_controller_test() {
  chmod -R u+w -- "$controller_test_root" >/dev/null 2>&1 || true
  rm -rf -- "$controller_test_root"
}
trap cleanup_controller_test EXIT INT TERM
controller_test_repository="$controller_test_root/repository"
mkdir -p "$controller_test_repository/release"
git -C "$controller_test_repository" init --quiet
git -C "$controller_test_repository" config user.name 'Strata Release Test'
git -C "$controller_test_repository" config user.email 'release-test@example.invalid'
for script_name in verify-github-tag-ruleset.sh verify-pages-deployment-source.sh wait-for-pages-source-receipt.sh; do
  printf '#!/usr/bin/env bash\nset -euo pipefail\n' > "$controller_test_repository/release/$script_name"
done
printf '{}\n' > "$controller_test_repository/release/github-release-tag-ruleset.json"
printf '{}\n' > "$controller_test_repository/release/github-release-tag-ruleset-receipt.json"
git -C "$controller_test_repository" add release
git -C "$controller_test_repository" commit --quiet -m 'Create valid controller fixtures'
valid_controller_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"

expect_guard_failure() {
  local label="$1"
  local operation="$2"
  local commit="$3"
  local directory="$4"
  if (cd "$controller_test_repository" && bash "$controller_guard" "$operation" "$commit" "$directory" >/dev/null 2>&1); then
    fail "Controller guard accepted invalid evidence: $label"
  fi
}

valid_materialized="$controller_test_root/valid-materialized"
mkdir "$valid_materialized"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$valid_materialized")
(cd "$controller_test_repository" && bash "$controller_guard" verify "$valid_controller_commit" "$valid_materialized")
chmod u+w -- "$valid_materialized" "$valid_materialized/verify-github-tag-ruleset.sh"
printf '# tampered\n' >> "$valid_materialized/verify-github-tag-ruleset.sh"
chmod a-w -- "$valid_materialized" "$valid_materialized/verify-github-tag-ruleset.sh"
expect_guard_failure 'post-materialization byte change' verify "$valid_controller_commit" "$valid_materialized"

writable_file_directory="$controller_test_root/writable-file"
mkdir "$writable_file_directory"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$writable_file_directory")
chmod u+w -- "$writable_file_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'post-materialization writable file' verify "$valid_controller_commit" "$writable_file_directory"

writable_directory="$controller_test_root/writable-directory"
mkdir "$writable_directory"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$writable_directory")
chmod u+w -- "$writable_directory"
expect_guard_failure 'post-materialization writable directory' verify "$valid_controller_commit" "$writable_directory"

swapped_symlink_directory="$controller_test_root/swapped-symlink"
mkdir "$swapped_symlink_directory"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$swapped_symlink_directory")
chmod u+w -- "$swapped_symlink_directory"
rm -- "$swapped_symlink_directory/verify-pages-deployment-source.sh"
ln -s "$controller_test_root/symlink-target" "$swapped_symlink_directory/verify-pages-deployment-source.sh"
chmod a-w -- "$swapped_symlink_directory"
expect_guard_failure 'post-materialization symlink swap' verify "$valid_controller_commit" "$swapped_symlink_directory"

preexisting_directory="$controller_test_root/preexisting"
mkdir "$preexisting_directory"
printf 'occupied\n' > "$preexisting_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'preexisting destination' materialize "$valid_controller_commit" "$preexisting_directory"

symlink_directory="$controller_test_root/symlink"
mkdir "$symlink_directory"
printf 'outside\n' > "$controller_test_root/symlink-target"
ln -s "$controller_test_root/symlink-target" "$symlink_directory/verify-github-tag-ruleset.sh"
expect_guard_failure 'symlink destination' materialize "$valid_controller_commit" "$symlink_directory"

git -C "$controller_test_repository" update-index --chmod=+x release/verify-github-tag-ruleset.sh
git -C "$controller_test_repository" commit --quiet -m 'Make controller tool executable'
executable_controller_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
mkdir "$controller_test_root/executable"
expect_guard_failure 'mode 100755 source' materialize "$executable_controller_commit" "$controller_test_root/executable"
git -C "$controller_test_repository" reset --hard "$valid_controller_commit" >/dev/null

git -C "$controller_test_repository" rm --quiet release/wait-for-pages-source-receipt.sh
git -C "$controller_test_repository" commit --quiet -m 'Remove controller tool'
missing_controller_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
mkdir "$controller_test_root/missing"
expect_guard_failure 'missing source' materialize "$missing_controller_commit" "$controller_test_root/missing"
git -C "$controller_test_repository" reset --hard "$valid_controller_commit" >/dev/null

symlink_blob="$(printf 'verify-pages-deployment-source.sh\n' | git -C "$controller_test_repository" hash-object -w --stdin)"
git -C "$controller_test_repository" update-index --add --cacheinfo "120000,$symlink_blob,release/wait-for-pages-source-receipt.sh"
git -C "$controller_test_repository" commit --quiet -m 'Make controller tool a symlink'
symlink_controller_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
mkdir "$controller_test_root/tree-symlink"
expect_guard_failure 'mode 120000 source' materialize "$symlink_controller_commit" "$controller_test_root/tree-symlink"
git -C "$controller_test_repository" reset --hard "$valid_controller_commit" >/dev/null

printf '[]\n' > "$controller_test_repository/release/github-release-tag-ruleset.json"
git -C "$controller_test_repository" add release/github-release-tag-ruleset.json
git -C "$controller_test_repository" commit --quiet -m 'Make controller JSON invalid'
invalid_json_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
mkdir "$controller_test_root/invalid-json"
expect_guard_failure 'non-object JSON' materialize "$invalid_json_commit" "$controller_test_root/invalid-json"
git -C "$controller_test_repository" reset --hard "$valid_controller_commit" >/dev/null

printf '#!/usr/bin/env bash\nif\n' > "$controller_test_repository/release/verify-pages-deployment-source.sh"
git -C "$controller_test_repository" add release/verify-pages-deployment-source.sh
git -C "$controller_test_repository" commit --quiet -m 'Make controller Bash invalid'
invalid_bash_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
mkdir "$controller_test_root/invalid-bash"
expect_guard_failure 'invalid Bash syntax' materialize "$invalid_bash_commit" "$controller_test_root/invalid-bash"
git -C "$controller_test_repository" reset --hard "$valid_controller_commit" >/dev/null

printf '#!/usr/bin/env bash\nset -euo pipefail\nexit 0\n' > "$controller_test_repository/release/verify-github-tag-ruleset.sh"
git -C "$controller_test_repository" add release/verify-github-tag-ruleset.sh
git -C "$controller_test_repository" commit --quiet -m 'Create replacement controller commit'
replacement_controller_commit="$(git -C "$controller_test_repository" rev-parse HEAD)"
git -C "$controller_test_repository" replace "$valid_controller_commit" "$replacement_controller_commit"
replace_commit_directory="$controller_test_root/replace-commit"
mkdir "$replace_commit_directory"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$replace_commit_directory")
original_verifier_blob="$(git -C "$controller_test_repository" --no-replace-objects rev-parse "$valid_controller_commit:release/verify-github-tag-ruleset.sh")"
[[ "$(git -C "$controller_test_repository" hash-object --no-filters -- "$replace_commit_directory/verify-github-tag-ruleset.sh")" == "$original_verifier_blob" ]] || fail 'Commit replacement changed materialized controller bytes.'
git -C "$controller_test_repository" replace -d "$valid_controller_commit" >/dev/null

replacement_verifier_blob="$(printf '#!/usr/bin/env bash\nexit 0\n' | git -C "$controller_test_repository" hash-object -w --stdin)"
git -C "$controller_test_repository" replace "$original_verifier_blob" "$replacement_verifier_blob"
replace_blob_directory="$controller_test_root/replace-blob"
mkdir "$replace_blob_directory"
(cd "$controller_test_repository" && bash "$controller_guard" materialize "$valid_controller_commit" "$replace_blob_directory")
[[ "$(git -C "$controller_test_repository" hash-object --no-filters -- "$replace_blob_directory/verify-github-tag-ruleset.sh")" == "$original_verifier_blob" ]] || fail 'Blob replacement changed materialized controller bytes.'
git -C "$controller_test_repository" replace -d "$original_verifier_blob" >/dev/null

release_cleanup_script="$(step_run 'Clean up release controller tools')"
verify_cleanup_script="$(step_run 'Clean up verification controller tools')"
[[ -n "$release_cleanup_script" && -n "$verify_cleanup_script" ]] || fail 'A controller cleanup run block could not be extracted.'
cleanup_runner="$controller_test_root/cleanup-runner"
mkdir "$cleanup_runner"
for cleanup_fixture in \
  "release|strata-release-controller-tools.Ab12Cd|$release_cleanup_script" \
  "verify|strata-verify-controller-tools.Ef34Gh|$verify_cleanup_script"; do
  cleanup_label="${cleanup_fixture%%|*}"
  cleanup_remainder="${cleanup_fixture#*|}"
  cleanup_basename="${cleanup_remainder%%|*}"
  cleanup_script="${cleanup_remainder#*|}"
  cleanup_directory="$cleanup_runner/$cleanup_basename"
  mkdir "$cleanup_directory"
  printf 'read-only\n' > "$cleanup_directory/tool"
  chmod a-w -- "$cleanup_directory" "$cleanup_directory/tool"
  RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_directory" bash -c "$cleanup_script"
  [[ ! -e "$cleanup_directory" && ! -L "$cleanup_directory" ]] || fail "$cleanup_label cleanup left a valid read-only bundle."
done

cleanup_non_directory="$cleanup_runner/strata-release-controller-tools.Ij56Kl"
printf 'not-a-directory\n' > "$cleanup_non_directory"
if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_non_directory" bash -c "$release_cleanup_script" >/dev/null 2>&1; then
  fail 'Release cleanup accepted a non-directory target.'
fi
[[ -f "$cleanup_non_directory" ]] || fail 'Release cleanup removed a rejected non-directory target.'

cleanup_symlink_target="$controller_test_root/cleanup-symlink-target"
mkdir "$cleanup_symlink_target"
printf 'retained\n' > "$cleanup_symlink_target/evidence"
cleanup_symlink="$cleanup_runner/strata-release-controller-tools.Mn78Op"
ln -s "$cleanup_symlink_target" "$cleanup_symlink"
if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_symlink" bash -c "$release_cleanup_script" >/dev/null 2>&1; then
  fail 'Release cleanup accepted a symlink target.'
fi
[[ -f "$cleanup_symlink_target/evidence" ]] || fail 'Release cleanup followed a rejected symlink.'

cleanup_bad_prefix="$cleanup_runner/strata-release-controller-tools.bad"
mkdir "$cleanup_bad_prefix"
if RUNNER_TEMP="$cleanup_runner" CONTROLLER_TOOL_DIRECTORY="$cleanup_bad_prefix" bash -c "$release_cleanup_script" >/dev/null 2>&1; then
  fail 'Release cleanup accepted an invalid temporary prefix.'
fi
[[ -d "$cleanup_bad_prefix" ]] || fail 'Release cleanup removed an invalid-prefix directory.'

cleanup_controller_test
trap - EXIT INT TERM

grep --fixed-strings 'release/maven-coordinates.txt)" == "26"' "$workflow" >/dev/null || fail 'The Maven inventory is not fixed at 26 coordinates.'
[[ "$(grep --fixed-strings -c '.releaseVersion == "0.1.2" and (.artifacts | length) == 21' "$workflow")" == '2' ]] || fail 'Release and verification must require 21 Modrinth artifacts.'
[[ "$(grep --fixed-strings -c '"${#assets[@]}" == "43"' "$workflow")" == '3' ]] || fail 'Every current GitHub bundle boundary must require 43 assets.'
[[ "$(grep --fixed-strings -c '.verifiedFileCount == 260 and .verifiedChecksumCount == 520' "$workflow")" == '4' ]] || fail 'Current and predecessor Central verification counts differ.'
[[ "$(grep --fixed-strings -c '.verifiedContentFileCount == 260 and .verifiedChecksumCount == 520' "$workflow")" == '4' ]] || fail 'Current and predecessor Portal verification counts differ.'
[[ "$(grep --fixed-strings -c '.deploymentState == "PUBLISHED"' "$workflow")" == '3' ]] || fail 'Every exact Portal phase must use the uppercase wire state.'
[[ "$(grep --fixed-strings -c '== "130"' "$workflow")" == '3' ]] || fail 'Current and predecessor signature verification must require 130 signatures.'

central_write='publishAndReleaseToMaven''Central'
[[ "$(grep --fixed-strings -c "$central_write" "$workflow")" == '1' ]] || fail 'The active workflow must contain exactly one Central write.'
[[ "$(grep --fixed-strings -c "$central_write" "$sealed_previous")" == '1' ]] || fail 'The sealed v0.1.1 workflow must retain its single Central write.'
[[ "$(grep --fixed-strings -c "$central_write" "$sealed_initial" || true)" == '0' ]] || fail 'The sealed v0.1.0 workflow gained a Central write.'
[[ "$(grep --fixed-strings -c 'v0.1.2' "$sealed_previous" || true)" == '0' ]] || fail 'The sealed v0.1.1 workflow was repurposed.'
[[ "$(grep --fixed-strings -c 'v0.1.2' "$sealed_initial" || true)" == '0' ]] || fail 'The sealed v0.1.0 workflow was repurposed.'

for version in 1.20 1.21.11 26.2; do
  task=":integration:minecraft-fabric-$version:runPublishedCoordinateClientGameTest"
  [[ "$(grep --fixed-strings -c "$task" "$workflow")" == '2' ]] || fail "Representative $version must run from Maven Local and Central."
done

central_step="$(grep -n -m 1 --fixed-strings '      - name: Publish wholly absent Maven Central release' "$workflow" | cut -d: -f1)"
github_step="$(grep -n -m 1 --fixed-strings '      - name: Create or verify immutable GitHub Release' "$workflow" | cut -d: -f1)"
submit_step="$(grep -n -m 1 --fixed-strings '      - name: Submit or observe Modrinth review' "$workflow" | cut -d: -f1)"
[[ -n "$central_step" && -n "$github_step" && -n "$submit_step" ]] || fail 'A release mutation boundary is missing.'
(( central_step < github_step && github_step < submit_step )) || fail 'Central and GitHub Release must complete independently before Modrinth review submission.'

grep --fixed-strings '[[ "$previous_commit" == "6e35f5984bbca06c18f8ca8080f45a70b09831bb" && "$previous_object" == "013bb6b0c4835229402f5843b967151f5dfdc5b2" ]]' "$workflow" >/dev/null || fail 'Public Skill verification does not pin v0.1.1.'
predecessor_step="$(grep -n -m 1 --fixed-strings '      - name: Verify complete v0.1.1 release before Modrinth body finalization' "$workflow" | cut -d: -f1)"
finalize_step="$(grep -n -m 1 --fixed-strings '      - name: Finalize v0.1.2 Modrinth body and verify approved release' "$workflow" | cut -d: -f1)"
[[ -n "$predecessor_step" && -n "$finalize_step" && "$predecessor_step" -lt "$finalize_step" ]] || fail 'Predecessor verification must precede Modrinth finalization.'
predecessor_block="$(sed -n "${predecessor_step},$((finalize_step - 1))p" "$workflow")"
grep --fixed-strings 'expected_previous_commit=6e35f5984bbca06c18f8ca8080f45a70b09831bb' <<< "$predecessor_block" >/dev/null || fail 'The v0.1.1 predecessor commit is not pinned.'
grep --fixed-strings 'expected_previous_object=013bb6b0c4835229402f5843b967151f5dfdc5b2' <<< "$predecessor_block" >/dev/null || fail 'The v0.1.1 predecessor tag object is not pinned.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$predecessor_block" >/dev/null || fail 'The predecessor ruleset receipt is not rechecked.'
grep --fixed-strings 'mavenCentralPortalVerify mavenCentralReleaseVerify githubReleaseBundle' <<< "$predecessor_block" >/dev/null || fail 'The predecessor does not use its own Central verifiers.'
if grep --fixed-strings 'v0.1.1-central-signature-checksums' <<< "$predecessor_block" >/dev/null; then
  fail 'The predecessor still depends on a nonexistent Central overlay.'
fi
grep --fixed-strings '(.listed | length) == 21' <<< "$predecessor_block" >/dev/null || fail 'The predecessor Modrinth inventory is not fixed at 21.'
grep --fixed-strings '"$verified_signatures" == "130"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor signature count differs.'
grep --fixed-strings '"${#predecessor_assets[@]}" == "43"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub asset count differs.'

finalize_block="$(sed -n "${finalize_step},$((finalize_step + 50))p" "$workflow")"
grep --fixed-strings '6e35f5984bbca06c18f8ca8080f45a70b09831bb' <<< "$finalize_block" >/dev/null || fail 'Finalization does not retain the proved predecessor commit.'
grep --fixed-strings '013bb6b0c4835229402f5843b967151f5dfdc5b2' <<< "$finalize_block" >/dev/null || fail 'Finalization does not retain the proved predecessor object.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$finalize_block" >/dev/null || fail 'Finalization does not recheck the predecessor ruleset.'

jvm_push_trigger="$(sed -n '/^  push:$/,/^  pull_request:$/p' "$jvm_workflow")"
jvm_pull_request_trigger="$(sed -n '/^  pull_request:$/,/^permissions:$/p' "$jvm_workflow")"
[[ "$(grep --fixed-strings -c '      - qodana.yaml' <<< "$jvm_push_trigger")" == '1' ]] || fail 'Qodana changes must trigger the JVM master check.'
[[ "$(grep --fixed-strings -c '      - qodana.yaml' <<< "$jvm_pull_request_trigger")" == '1' ]] || fail 'Qodana changes must trigger the JVM pull-request check.'

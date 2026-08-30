#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
workflow="$repository_root/.github/workflows/release-v0.1.1.yml"
active_workflow="$repository_root/.github/workflows/publish-release.yml"
sealed_workflow="$repository_root/.github/workflows/release.yml"
jvm_workflow="$repository_root/.github/workflows/jvm.yml"

fail() {
  echo "$1" >&2
  exit 1
}

[[ -f "$workflow" ]] || fail 'The sealed v0.1.1 release workflow is missing.'
[[ -f "$active_workflow" ]] || fail 'The active release workflow is missing.'
[[ -f "$sealed_workflow" ]] || fail 'The sealed v0.1.0 release workflow is missing.'
[[ -f "$jvm_workflow" ]] || fail 'The JVM workflow is missing.'
sealed_workflow_blob='c0adab3b8564b8510884dcf0830b16527f55e6fe'
[[ "$(git hash-object "$workflow")" == "$sealed_workflow_blob" ]] || \
  fail 'The sealed v0.1.1 release workflow changed.'

[[ "$(find "$repository_root/.github/workflows" -maxdepth 1 -type f \
  \( -name 'release-v*.yml' -o -name 'release-v*.yaml' \) -print | wc -l | tr -d '[:space:]')" == '1' ]] || \
  fail 'Only the sealed v0.1.1 version-specific release workflow may exist.'

jvm_push_trigger="$(sed -n '/^  push:$/,/^  pull_request:$/p' "$jvm_workflow")"
jvm_pull_request_trigger="$(sed -n '/^  pull_request:$/,/^permissions:$/p' "$jvm_workflow")"
[[ "$(grep --fixed-strings -c '      - qodana.yaml' <<< "$jvm_push_trigger")" == '1' ]] || fail 'Qodana configuration changes must trigger the JVM master check required by release preflight.'
[[ "$(grep --fixed-strings -c '      - qodana.yaml' <<< "$jvm_pull_request_trigger")" == '1' ]] || fail 'Qodana configuration changes must trigger JVM pull-request checks before merge.'

topology_script="$(
  awk '
    /^      - name: Check pinned workflow topology$/ { found_step = 1; next }
    found_step && /^        run: \|$/ { in_run = 1; next }
    found_step && /^      - name:/ { exit }
    in_run {
      sub(/^          /, "")
      print
    }
  ' "$workflow"
)"
[[ -n "$topology_script" ]] || fail 'The pinned workflow topology guard script is missing.'
# The sealed workflow retains its historical topology guard byte-for-byte.
# It intentionally names the active controller and is inspected rather than executed.

grep --fixed-strings 'name: Publish release' "$workflow" >/dev/null || fail 'The active release workflow name differs.'
grep --fixed-strings 'workflow=.github/workflows/publish-release.yml' "$workflow" >/dev/null || fail 'The active release workflow does not check its own source.'
grep --fixed-strings 'default: v0.1.1' "$workflow" >/dev/null || fail 'The v0.1.1 tag input is not pinned.'
grep --fixed-strings '[[ "$RELEASE_TAG" == "v0.1.1"' "$workflow" >/dev/null || fail 'The v0.1.1 runtime tag guard is missing.'
grep --fixed-strings '[[ "$tag_commit" == "6e35f5984bbca06c18f8ca8080f45a70b09831bb" ]]' "$workflow" >/dev/null || fail 'Release does not pin the reviewed v0.1.1 source commit.'
grep --fixed-strings 'git merge-base --is-ancestor "$tag_commit" origin/master' "$workflow" >/dev/null || fail 'Release does not prove master ancestry.'
grep --fixed-strings 'root_version="$(git show "${tag_commit}:build.gradle.kts"' "$workflow" >/dev/null || fail 'Release does not read the root version from the signed tag.'
[[ "$(grep --fixed-strings -c 'release/github-tag-ruleset-v0.1.1.json' "$workflow")" == '9' ]] || fail 'Every v0.1.1 tag mutation boundary must use the dedicated ruleset contract.'
[[ "$(grep --fixed-strings -c 'release/github-tag-ruleset-v0.1.1-receipt.json' "$workflow")" == '8' ]] || fail 'Every v0.1.1 tag mutation boundary must use the dedicated ruleset receipt.'

grep --fixed-strings '[[ "$(grep -cve '\''^[[:space:]]*$'\'' release/maven-coordinates.txt)" == "26" ]]' "$workflow" >/dev/null || fail 'The local Maven inventory is not fixed at 26 coordinates.'
[[ "$(grep --fixed-strings -c '.coordinateCount == 26' "$workflow")" == '3' ]] || fail 'Every initial, fresh-write, and final public Central phase must require 26 coordinates.'
[[ "$(grep --fixed-strings -c '.releaseVersion == "0.1.1" and (.artifacts | length) == 21' "$workflow")" == '2' ]] || fail 'Release and final verification must both require 21 Modrinth artifacts.'
[[ "$(grep --fixed-strings -c '"${#assets[@]}" == "43"' "$workflow")" == '3' ]] || fail 'Bundle creation, GitHub mutation, and final verification must each require 43 assets.'
[[ "$(grep --fixed-strings -c '.verifiedFileCount == 260 and .verifiedChecksumCount == 520' "$workflow")" == '3' ]] || fail 'Public Central evidence must be fixed at 260 files and 520 checksums in every exact phase.'
[[ "$(grep --fixed-strings -c '.verifiedContentFileCount == 260 and .verifiedChecksumCount == 520' "$workflow")" == '3' ]] || fail 'Portal evidence must be fixed at 260 files and 520 checksums in every exact phase.'
[[ "$(grep --fixed-strings -c '.deploymentState == "PUBLISHED"' "$workflow")" == '2' ]] || fail 'Every exact Portal phase must use the typed uppercase published wire state.'
[[ "$(grep --fixed-strings -c '== "130"' "$workflow")" == '2' ]] || fail 'Release and final verification must both verify 130 Central signatures.'

central_write='publishAndReleaseToMaven''Central'
[[ "$(grep --fixed-strings -c "$central_write" "$workflow")" == '1' ]] || fail 'The v0.1.1 workflow must contain exactly one Central write task.'
central_write_line="$(grep -n --fixed-strings "$central_write" "$workflow" | cut -d: -f1)"
central_condition_line="$(grep -n -m 1 --extended-regexp "^        if: steps\.central\.outputs\.public_state == 'absent' && steps\.central\.outputs\.portal_state == 'absent'$" "$workflow" | cut -d: -f1)"
[[ -n "$central_write_line" && -n "$central_condition_line" ]] || fail 'The conditional Central publication boundary is incomplete.'
(( central_condition_line < central_write_line )) || fail 'Central publication is not guarded before its only write.'
if sed -n "${central_condition_line},${central_write_line}p" "$workflow" | grep --fixed-strings 'if:' | grep --fixed-strings -v "if: steps.central.outputs.public_state == 'absent' && steps.central.outputs.portal_state == 'absent'" >/dev/null; then
  fail 'An unexpected condition separates Central absence proof from publication.'
fi

central_step="$(grep -n -m 1 --fixed-strings '      - name: Publish wholly absent Maven Central release' "$workflow" | cut -d: -f1)"
central_verify_step="$(grep -n -m 1 --fixed-strings '      - name: Verify Publisher Portal, Central repository, and release bundle' "$workflow" | cut -d: -f1)"
[[ -n "$central_step" && -n "$central_verify_step" ]] || fail 'The Central mutation or verification phase is missing.'
central_block="$(sed -n "${central_step},$((central_verify_step - 1))p" "$workflow")"
[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$central_block")" == '2' ]] || fail 'Central publication must freshly revalidate source before preflight and again before mutation.'
grep --fixed-strings 'git fetch --force origin' <<< "$central_block" >/dev/null || fail 'Central publication does not freshly fetch master and tag.'
grep --fixed-strings 'mavenCentralReleasePreflight mavenCentralPortalPreflight' <<< "$central_block" >/dev/null || fail 'Central publication does not rerun both fresh preflights.'
grep --fixed-strings '.state == "absent" and .coordinateCount == 26 and .verifiedFileCount == 0 and .verifiedChecksumCount == 0' <<< "$central_block" >/dev/null || fail 'Fresh public Central preflight does not require complete absence.'
grep --fixed-strings '.state == "absent" and .deploymentId == null and .deploymentState == null' <<< "$central_block" >/dev/null || fail 'Fresh Portal preflight does not require complete absence.'
grep --fixed-strings '[[ "$GITHUB_SHA" == "$EXPECTED_CONTROLLER_COMMIT" && "$(git rev-parse origin/master)" == "$EXPECTED_CONTROLLER_COMMIT" ]]' <<< "$central_block" >/dev/null || fail 'Central publication does not pin the exact controller master.'
grep --fixed-strings '[[ "$(git rev-parse "refs/tags/$RELEASE_TAG")" == "$EXPECTED_TAG_OBJECT" ]]' <<< "$central_block" >/dev/null || fail 'Central publication does not pin the signed tag object.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$central_block" >/dev/null || fail 'Central publication does not recheck the v0.1.1 ruleset receipt.'

for version in 1.20 1.21.11 26.2; do
  task=":integration:minecraft-fabric-$version:runPublishedCoordinateClientGameTest"
  [[ "$(grep --fixed-strings -c "$task" "$workflow")" == '2' ]] || fail "Representative $version must run once from Maven Local and once from Maven Central."
done

stage_step="$(grep -n -m 1 --fixed-strings '      - name: Stage only missing Modrinth versions' "$workflow" | cut -d: -f1)"
central_client_step="$(grep -n -m 1 --fixed-strings '      - name: Run representative clients from Maven Central' "$workflow" | cut -d: -f1)"
github_step="$(grep -n -m 1 --fixed-strings '      - name: Create or verify immutable GitHub Release' "$workflow" | cut -d: -f1)"
submit_step="$(grep -n -m 1 --fixed-strings '      - name: Submit or observe Modrinth review' "$workflow" | cut -d: -f1)"
verify_job="$(grep -n -m 1 --fixed-strings '  verify:' "$workflow" | cut -d: -f1)"
[[ -n "$stage_step" && -n "$central_client_step" && -n "$github_step" && -n "$submit_step" && -n "$verify_job" ]] || fail 'An external mutation boundary is missing.'

stage_block="$(sed -n "${stage_step},$((central_client_step - 1))p" "$workflow")"
grep --fixed-strings 'git fetch --force origin' <<< "$stage_block" >/dev/null || fail 'Modrinth staging does not freshly fetch source refs.'
grep --fixed-strings '[[ "$GITHUB_SHA" == "$EXPECTED_CONTROLLER_COMMIT" && "$(git rev-parse origin/master)" == "$EXPECTED_CONTROLLER_COMMIT" ]]' <<< "$stage_block" >/dev/null || fail 'Modrinth staging does not pin controller master.'
grep --fixed-strings '[[ "$(git rev-parse "refs/tags/$RELEASE_TAG")" == "$EXPECTED_TAG_OBJECT" ]]' <<< "$stage_block" >/dev/null || fail 'Modrinth staging does not pin the tag object.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$stage_block" >/dev/null || fail 'Modrinth staging does not recheck the ruleset receipt.'
stage_gate_line="$(grep -n -m 1 --fixed-strings '          revalidate_release_source' <<< "$stage_block" | cut -d: -f1)"
stage_write_line="$(grep -n -m 1 --fixed-strings 'modrinthReleaseStage' <<< "$stage_block" | cut -d: -f1)"
[[ -n "$stage_gate_line" && -n "$stage_write_line" ]] || fail 'Modrinth stage gate or task is missing.'
(( stage_gate_line < stage_write_line )) || fail 'Modrinth stage source gate is not before its mutation phase.'

github_block="$(sed -n "${github_step},$((submit_step - 1))p" "$workflow")"
grep --fixed-strings 'git fetch --force origin' <<< "$github_block" >/dev/null || fail 'GitHub Release mutations do not freshly fetch source refs.'
grep --fixed-strings '[[ "$GITHUB_SHA" == "$EXPECTED_CONTROLLER_COMMIT" && "$(git rev-parse origin/master)" == "$EXPECTED_CONTROLLER_COMMIT" ]]' <<< "$github_block" >/dev/null || fail 'GitHub Release mutations do not pin controller master.'
grep --fixed-strings '[[ "$(git rev-parse "refs/tags/$RELEASE_TAG")" == "$EXPECTED_TAG_OBJECT" ]]' <<< "$github_block" >/dev/null || fail 'GitHub Release mutations do not pin the tag object.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$github_block" >/dev/null || fail 'GitHub Release mutations do not recheck the ruleset receipt.'
[[ "$(grep --extended-regexp -c '^[[:space:]]+revalidate_release_source$' <<< "$github_block")" == '3' ]] || fail 'GitHub draft creation, asset upload, and publication each require fresh source validation.'
grep --fixed-strings 'verify_release_contract() {' <<< "$github_block" >/dev/null || fail 'GitHub Release does not define a repeated exact remote verifier.'
grep --fixed-strings '[[ "$(jq -r '\''.tagName'\'' <<< "$release_json")" == "$RELEASE_TAG" && "$(jq -r '\''.name'\'' <<< "$release_json")" == "$expected_title" ]]' <<< "$github_block" >/dev/null || fail 'The repeated GitHub verifier does not require exact identity.'
grep --fixed-strings 'cmp --silent "docs/releases/$RELEASE_TAG.md" "$actual_body"' <<< "$github_block" >/dev/null || fail 'The repeated GitHub verifier does not require the exact body.'
grep --fixed-strings '[[ "$(jq -r '\''.isDraft'\'' <<< "$release_json")" == "$expected_draft" && "$(jq -r '\''.isPrerelease'\'' <<< "$release_json")" == "false" ]]' <<< "$github_block" >/dev/null || fail 'The repeated GitHub verifier does not require the expected lifecycle.'
grep --fixed-strings '[[ -z "$unexpected" ]]' <<< "$github_block" >/dev/null || fail 'The repeated GitHub verifier permits unexpected assets.'
grep --fixed-strings 'cmp --silent "$asset" "$snapshot_directory/$name"' <<< "$github_block" >/dev/null || fail 'The repeated GitHub verifier does not compare every existing asset byte-for-byte.'
[[ "$(grep --fixed-strings -c 'verify_release_contract true false' <<< "$github_block")" == '3' ]] || fail 'Draft creation and upload paths do not repeatedly verify exact incomplete-draft state.'
[[ "$(grep --fixed-strings -c 'verify_release_contract true true' <<< "$github_block")" == '2' ]] || fail 'Upload completion and publication do not repeatedly verify an exact complete draft.'
[[ "$(grep --fixed-strings -c 'verify_release_contract false true' <<< "$github_block")" == '2' ]] || fail 'Existing and newly published releases do not receive full exact verification.'
grep --fixed-strings 'gh release create "$RELEASE_TAG" --draft' <<< "$github_block" >/dev/null || fail 'GitHub draft creation is missing.'
grep --fixed-strings 'gh release upload "$RELEASE_TAG" "$asset"' <<< "$github_block" >/dev/null || fail 'GitHub asset upload is missing.'
grep --fixed-strings 'gh release edit "$RELEASE_TAG" --draft=false' <<< "$github_block" >/dev/null || fail 'GitHub draft publication is missing.'

submit_block="$(sed -n "${submit_step},$((verify_job - 1))p" "$workflow")"
grep --fixed-strings 'git fetch --force origin' <<< "$submit_block" >/dev/null || fail 'Modrinth submission does not freshly fetch source refs.'
grep --fixed-strings '[[ "$GITHUB_SHA" == "$EXPECTED_CONTROLLER_COMMIT" && "$(git rev-parse origin/master)" == "$EXPECTED_CONTROLLER_COMMIT" ]]' <<< "$submit_block" >/dev/null || fail 'Modrinth submission does not pin controller master.'
grep --fixed-strings '[[ "$(git rev-parse "refs/tags/$RELEASE_TAG")" == "$EXPECTED_TAG_OBJECT" ]]' <<< "$submit_block" >/dev/null || fail 'Modrinth submission does not pin the tag object.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$submit_block" >/dev/null || fail 'Modrinth submission does not recheck the ruleset receipt.'
grep --fixed-strings 'modrinthReleaseSubmit' <<< "$submit_block" >/dev/null || fail 'The gated Modrinth submission task is missing.'

grep --fixed-strings 'group: release-v0.1.0' "$workflow" >/dev/null || fail 'v0.1.1 does not share the v0.1.0 release lock.'
grep --fixed-strings 'default: v0.1.0' "$sealed_workflow" >/dev/null || fail 'The sealed workflow no longer defaults to v0.1.0.'
expected_sealed_group='group: release-$''{{ inputs.tag }}'
grep --fixed-strings "$expected_sealed_group" "$sealed_workflow" >/dev/null || fail 'The sealed workflow no longer resolves to the shared v0.1.0 release lock.'

predecessor_step="$(grep -n -m 1 --fixed-strings '      - name: Verify complete v0.1.0 release before Modrinth body finalization' "$workflow" | cut -d: -f1)"
finalize_step="$(grep -n -m 1 --fixed-strings '      - name: Finalize v0.1.1 Modrinth body and verify approved release' "$workflow" | cut -d: -f1)"
public_current_step="$(grep -n -m 1 --fixed-strings '      - name: Verify public 21-version Modrinth inventory and CDN files' "$workflow" | cut -d: -f1)"
[[ -n "$predecessor_step" && -n "$finalize_step" && -n "$public_current_step" ]] || fail 'The predecessor, finalization, or current public verification phase is missing.'
(( predecessor_step < finalize_step && finalize_step < public_current_step )) || fail 'Modrinth predecessor proof must complete before finalization and current public verification.'
[[ "$(grep --fixed-strings -c 'modrinthReleaseFinalizeProject' "$workflow")" == '1' ]] || fail 'The project body must have exactly one explicit finalization task.'

predecessor_block="$(sed -n "${predecessor_step},$((finalize_step - 1))p" "$workflow")"
grep --fixed-strings 'git fetch --force origin' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not freshly fetch its tag.'
[[ "$(grep --fixed-strings -c 'previous_tag=v0.1.0' <<< "$predecessor_block")" == '2' ]] || fail 'Both predecessor verification phases must pin v0.1.0.'
grep --fixed-strings 'expected_previous_commit=d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not pin the immutable v0.1.0 commit.'
grep --fixed-strings 'expected_previous_object=ccf221fe7f133fe5598fafc4ad01e6bc69ba2230' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not pin the immutable v0.1.0 tag object.'
grep --fixed-strings '[[ "$previous_commit" == "$expected_previous_commit" ]]' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not compare the fetched commit with its immutable pin.'
grep --fixed-strings '[[ "$previous_object" == "$expected_previous_object" ]]' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not compare the fetched tag object with its immutable pin.'
grep --fixed-strings 'release/github-tag-ruleset-receipt.json' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not recheck the v0.1.0 ruleset receipt.'
grep --fixed-strings 'for workflow in jvm.yml qodana.yml; do' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not recheck historical JVM and Qodana success.'
grep --fixed-strings 'No successful predecessor $workflow run exists for $previous_commit.' <<< "$predecessor_block" >/dev/null || fail 'Historical predecessor CI is not pinned to the immutable commit.'
grep --fixed-strings 'git worktree add --detach "$worktree" "$previous_commit"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor is not checked out as a detached immutable worktree.'

grep --fixed-strings 'case "$remote_body_sha256" in' <<< "$predecessor_block" >/dev/null || fail 'Predecessor verification does not classify the shared Modrinth body.'
grep --fixed-strings '"$predecessor_body_sha256") modrinth_body_state=predecessor ;;' <<< "$predecessor_block" >/dev/null || fail 'The exact predecessor-body branch is missing.'
grep --fixed-strings '"$current_body_sha256") modrinth_body_state=current ;;' <<< "$predecessor_block" >/dev/null || fail 'The exact current-body recovery branch is missing.'
grep --fixed-strings "*) echo 'Remote Modrinth body is neither the tracked predecessor nor exact current body.'" <<< "$predecessor_block" >/dev/null || fail 'Unexpected Modrinth bodies do not fail closed.'
grep --fixed-strings 'if [[ "$modrinth_body_state" == "current" ]]; then' <<< "$predecessor_block" >/dev/null || fail 'The current-body recovery branch does not adapt the immutable predecessor manifest.'
grep --fixed-strings 'jq --rawfile body "$current_body" '\''.project.body = $body'\'' ' <<< "$predecessor_block" >/dev/null || fail 'The recovery manifest does not use the normalized tracked exact v0.1.1 body.'
grep --fixed-strings "jq -cS 'del(.project.body)'" <<< "$predecessor_block" >/dev/null || fail 'The current-body recovery branch does not prove every non-body manifest field unchanged.'
grep --fixed-strings -- '-x modrinthReleaseManifest -x verifyPublishedConsumer' <<< "$predecessor_block" >/dev/null || fail 'The detached authenticated verifier may overwrite the selected recovery manifest or rebuild consumer evidence.'
grep --fixed-strings '(.listed | length) == 20' <<< "$predecessor_block" >/dev/null || fail 'The detached authenticated verifier does not require all 20 predecessor versions.'

grep --fixed-strings 'git show "$GITHUB_SHA:release/run-central-controller-overlay.sh" > "$central_runner"' <<< "$predecessor_block" >/dev/null || fail 'The audited v0.1.0 Central runner is not loaded from the exact controller.'
grep --fixed-strings 'git show "$GITHUB_SHA:release/controller-overlays/v0.1.0-central-signature-checksums.json" > "$central_manifest"' <<< "$predecessor_block" >/dev/null || fail 'The audited v0.1.0 Central manifest is not loaded from the exact controller.'
grep --fixed-strings 'git show "$GITHUB_SHA:release/controller-overlays/v0.1.0-central-signature-checksums.patch" > "$central_patch"' <<< "$predecessor_block" >/dev/null || fail 'The audited v0.1.0 Central patch is not loaded from the exact controller.'
grep --fixed-strings 'previous_controller_commit="$(jq -er '\''.controllerCommit'\'' "$central_manifest")"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor controller commit is not loaded from the audited manifest.'
grep --fixed-strings '[[ "$previous_controller_commit" == "b85f1b4f470357c5d1ff8410d20b7e316b50e316" ]]' <<< "$predecessor_block" >/dev/null || fail 'The predecessor controller commit is not pinned exactly.'
grep --fixed-strings 'git merge-base --is-ancestor "$previous_controller_commit" origin/master' <<< "$predecessor_block" >/dev/null || fail 'The reviewed predecessor controller is not required to remain in current master history.'
grep --fixed-strings 'bash "$central_runner" release-verify "$central_manifest" "$central_patch"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor does not run the sealed read-only Central verification contract.'
grep --fixed-strings '"$previous_tag" "$previous_commit" "$GITHUB_SHA"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor Central overlay does not use the active current controller commit.'
grep --fixed-strings -- '-u MODRINTH_TOKEN -u GH_TOKEN -u GITHUB_TOKEN' <<< "$predecessor_block" >/dev/null || fail 'The sealed predecessor Central overlay receives external-service credentials.'
grep --fixed-strings -- '-u ORG_GRADLE_PROJECT_signingInMemoryKey -u ORG_GRADLE_PROJECT_signingInMemoryKeyPassword' <<< "$predecessor_block" >/dev/null || fail 'The sealed predecessor Central overlay receives signing credentials.'
grep --fixed-strings '.coordinateCount == 24 and .verifiedFileCount == 240 and .verifiedChecksumCount == 480' <<< "$predecessor_block" >/dev/null || fail 'The predecessor Central inventory is not fixed at 24/240/480.'
grep --fixed-strings '[[ "$verified_signatures" == "120" ]]' <<< "$predecessor_block" >/dev/null || fail 'The predecessor does not verify all 120 Central signatures.'
grep --fixed-strings '[[ "${#predecessor_assets[@]}" == "41" ]]' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub Release inventory is not fixed at 41 assets.'
grep --fixed-strings 'gh release view "$previous_tag" --json tagName,name,body,isDraft,isPrerelease' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub Release metadata is not re-read.'
grep --fixed-strings 'cmp --silent "docs/releases/$previous_tag.md" "$actual_release_body"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub Release body is not exact.'
grep --fixed-strings '[[ "$actual_assets" == "$expected_assets" ]]' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub Release asset inventory is not exact.'
grep --fixed-strings 'cmp --silent "$asset" "$downloads/$name"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor GitHub Release assets are not compared byte-for-byte.'

grep --fixed-strings 'env -u GH_TOKEN -u GITHUB_TOKEN' <<< "$predecessor_block" >/dev/null || fail 'The detached v0.1.0 public Modrinth verifier receives a GitHub token.'
grep --fixed-strings 'bash release/verify-public-modrinth.sh build/release/modrinth/manifest.json "$PROJECT_ID" strata-ui' <<< "$predecessor_block" >/dev/null || fail 'The detached v0.1.0 public inventory and CDN verification is missing.'
grep --fixed-strings 'release/verify-pages-deployment-source.sh' <<< "$predecessor_block" >/dev/null || fail 'The generalized Pages source verifier is not loaded from the exact controller.'
grep --fixed-strings 'release/wait-for-pages-source-receipt.sh' <<< "$predecessor_block" >/dev/null || fail 'The generalized Pages public receipt waiter is not loaded from the exact controller.'
grep --fixed-strings '"$previous_pages_run_id" "$previous_tag" "$PREVIOUS_RELEASE_COMMIT"' <<< "$predecessor_block" >/dev/null || fail 'The predecessor Pages deployment is not bound to the immutable tag and commit.'
grep --fixed-strings 'pages_base="https://gh.s7a.dev/strata/releases/${previous_tag#v}"' <<< "$predecessor_block" >/dev/null || fail 'The immutable predecessor Pages tree is not derived from its pinned tag.'
grep --fixed-strings 'git worktree remove --force "$worktree"' <<< "$predecessor_block" >/dev/null || fail 'The detached predecessor worktree is not removed.'

public_services_step="$(grep -n -m 1 --fixed-strings '      - name: Verify public v0.1.0 services and Pages provenance' "$workflow" | cut -d: -f1)"
[[ -n "$public_services_step" ]] || fail 'The predecessor public-service phase is missing.'
(( predecessor_step < public_services_step && public_services_step < finalize_step )) || fail 'Predecessor secret-bearing, public-service, and finalization phases are misordered.'
signing_cleanup_line="$(grep -n -m 1 --fixed-strings 'rm -rf -- "$predecessor_temporary/gnupg"' "$workflow" | cut -d: -f1)"
[[ -n "$signing_cleanup_line" ]] || fail 'Predecessor signing-key cleanup is missing.'
(( signing_cleanup_line < public_services_step )) || fail 'Predecessor public verification starts before signing-key cleanup.'
public_services_header="$(sed -n "${public_services_step},$((public_services_step + 6))p" "$workflow")"
if grep --fixed-strings 'secrets.' <<< "$public_services_header" >/dev/null; then
  fail 'The predecessor public-service step maps a release secret.'
fi

public_skills_job="$(grep -n -m 1 --fixed-strings '  public_skills:' "$workflow" | cut -d: -f1)"
[[ -n "$public_skills_job" && "$public_skills_job" -lt "$verify_job" ]] || fail 'The isolated public Skill job is missing before protected verification.'
public_skills_block="$(sed -n "${public_skills_job},$((verify_job - 1))p" "$workflow")"
grep --fixed-strings "if: inputs.operation == 'verify'" <<< "$public_skills_block" >/dev/null || fail 'The public Skill job is not pinned to final verification.'
grep --fixed-strings 'permissions: {}' <<< "$public_skills_block" >/dev/null || fail 'The public Skill job retains repository token permissions.'
if grep --fixed-strings 'environment:' <<< "$public_skills_block" >/dev/null || \
  grep --fixed-strings '${{ secrets.' <<< "$public_skills_block" >/dev/null || \
  grep --fixed-strings '${{ github.token }}' <<< "$public_skills_block" >/dev/null || \
  grep --extended-regexp '^[[:space:]]+(GH_TOKEN|GITHUB_TOKEN):' <<< "$public_skills_block" >/dev/null; then
  fail 'The isolated public Skill job receives a protected environment or credential.'
fi
if grep --fixed-strings 'actions/checkout' <<< "$public_skills_block" >/dev/null; then
  fail 'The isolated public Skill job uses a credential-capable checkout action.'
fi
grep --fixed-strings 'git -C "$repository" remote add origin https://github.com/sya-ri/strata.git' <<< "$public_skills_block" >/dev/null || fail 'The isolated public Skill job does not use anonymous Git source retrieval.'
grep --fixed-strings 'expected_previous_commit' <<< "$predecessor_block" >/dev/null || fail 'The predecessor release identity pin is missing.'
grep --fixed-strings '"$previous_commit" == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" && "$previous_object" == "ccf221fe7f133fe5598fafc4ad01e6bc69ba2230"' <<< "$public_skills_block" >/dev/null || fail 'The isolated Skill job does not pin v0.1.0 source identity.'
grep --fixed-strings 'for tag in v0.1.0 v0.1.1; do' <<< "$public_skills_block" >/dev/null || fail 'The isolated runner does not verify both public release Skills.'
grep --fixed-strings 'for forbidden_secret in GH_TOKEN GITHUB_TOKEN MODRINTH_TOKEN SIGNING_KEY SIGNING_PASSWORD' <<< "$public_skills_block" >/dev/null || fail 'The isolated public Skill runner does not fail closed on credentials.'
[[ "$(grep --fixed-strings -c 'gh skill preview sya-ri/strata' <<< "$public_skills_block")" == '1' ]] || fail 'Public Skill preview must run only in the isolated two-tag loop.'
[[ "$(grep --fixed-strings -c 'npx --yes skills add' <<< "$public_skills_block")" == '1' ]] || fail 'Skills CLI installation must run only in the isolated two-tag loop.'
verify_block="$(sed -n "${verify_job},\$p" "$workflow")"
grep --fixed-strings 'needs: public_skills' <<< "$verify_block" >/dev/null || fail 'Protected finalization does not wait for isolated public Skill verification.'
if grep --fixed-strings 'gh skill preview' <<< "$verify_block" >/dev/null || grep --fixed-strings 'npx --yes skills add' <<< "$verify_block" >/dev/null; then
  fail 'Untrusted public Skill tooling remains on the secret-bearing verification runner.'
fi

if grep --fixed-strings 'gsub("\\r\\n"; "\\n")' <<< "$predecessor_block" >/dev/null || \
  grep --fixed-strings 'contains("\\r")' <<< "$predecessor_block" >/dev/null; then
  fail 'A predecessor jq program matches literal backslash bytes instead of CR/LF characters.'
fi
normalizer_line="$(grep -m 1 --fixed-strings "jq --raw-output --join-output '(.body // \"\") | gsub" <<< "$predecessor_block")"
normalizer_program="$(sed -E "s/^[^']*'([^']*)'.*$/\\1/" <<< "$normalizer_line")"
normalized_hex="$(printf '{\"body\":\"line\\r\\n\\r\\n\"}' | jq --raw-output --join-output "$normalizer_program" | od -An -t x1 | tr -d '[:space:]')"
[[ "$normalized_hex" == '6c696e650a' || "$normalized_hex" == '6c696e650d0a' ]] || fail 'The workflow body normalizer does not convert CRLF and retain exactly one platform-emitted line ending.'
lone_cr_line="$(grep -m 1 --fixed-strings 'error("release body contains a lone carriage return")' <<< "$predecessor_block")"
lone_cr_program="$(sed -E "s/^[^']*'([^']*)'.*$/\\1/" <<< "$lone_cr_line")"
if printf '{"body":"line\\rrest"}' | jq --raw-output --join-output "$lone_cr_program" >/dev/null 2>&1; then
  fail 'The workflow GitHub body verifier accepts a lone carriage return.'
fi

case_line="$(grep -n -m 1 --fixed-strings 'case "$remote_body_sha256" in' <<< "$predecessor_block" | cut -d: -f1)"
common_verify_line="$(grep -n -m 1 --fixed-strings 'modrinthReleaseVerify' <<< "$predecessor_block" | cut -d: -f1)"
central_overlay_line="$(grep -n -m 1 --fixed-strings 'bash "$central_runner" release-verify' <<< "$predecessor_block" | cut -d: -f1)"
github_exact_line="$(grep -n -m 1 --fixed-strings '[[ "${#predecessor_assets[@]}" == "41" ]]' <<< "$predecessor_block" | cut -d: -f1)"
pages_exact_line="$(grep -n -m 1 --fixed-strings 'pages_base="https://gh.s7a.dev/strata/releases/${previous_tag#v}"' <<< "$predecessor_block" | cut -d: -f1)"
[[ -n "$case_line" && -n "$common_verify_line" && -n "$central_overlay_line" && -n "$github_exact_line" && -n "$pages_exact_line" ]] || fail 'A common predecessor recovery gate is missing.'
(( case_line < common_verify_line && common_verify_line < central_overlay_line && central_overlay_line < github_exact_line && github_exact_line < pages_exact_line )) || fail 'Both exact body states must converge before every complete v0.1.0 public gate.'

classify_recovery_state() {
  local remote="$1"
  local predecessor="$2"
  local current="$3"
  case "$remote" in
    "$predecessor") printf '%s\n' predecessor ;;
    "$current") printf '%s\n' current ;;
    *) return 1 ;;
  esac
}
[[ "$(classify_recovery_state old-hash old-hash current-hash)" == 'predecessor' ]] || fail 'A first run from the predecessor body is not recoverable.'
[[ "$(classify_recovery_state current-hash old-hash current-hash)" == 'current' ]] || fail 'A fresh rerun from the exact current body is not recoverable.'
if classify_recovery_state unrelated-hash old-hash current-hash >/dev/null; then
  fail 'An unrelated Modrinth body is recoverable.'
fi

current_block="$(sed -n "${finalize_step},${public_current_step}p" "$workflow")"
grep --fixed-strings 'git fetch --force origin' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization does not freshly fetch source refs.'
grep --fixed-strings '[[ "$PREVIOUS_RELEASE_COMMIT" == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" && "$PREVIOUS_RELEASE_OBJECT" == "ccf221fe7f133fe5598fafc4ad01e6bc69ba2230" ]]' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization does not preserve the proved predecessor identity.'
grep --fixed-strings '[[ "$MODRINTH_BODY_STATE" == "predecessor" || "$MODRINTH_BODY_STATE" == "current" ]]' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization cannot resume from both exact monotonic states.'
grep --fixed-strings '[[ "$(git rev-parse --verify '\''refs/tags/v0.1.0^{commit}'\'')" == "$PREVIOUS_RELEASE_COMMIT"' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization does not pin the verified predecessor tag.'
grep --fixed-strings 'release/github-tag-ruleset-v0.1.1-receipt.json' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization does not recheck the current ruleset receipt.'
grep --fixed-strings 'release/github-tag-ruleset-receipt.json' <<< "$current_block" >/dev/null || fail 'Modrinth body finalization does not recheck the predecessor ruleset receipt.'
grep --fixed-strings 'modrinthReleaseFinalizeProject' <<< "$current_block" >/dev/null || fail 'The current block does not explicitly finalize the tracked body.'
grep --fixed-strings 'modrinthReleaseVerify' <<< "$current_block" >/dev/null || fail 'The current block does not run strict authenticated verification after finalization.'
grep --fixed-strings '.projectStatus == "approved"' <<< "$current_block" >/dev/null || fail 'The current authenticated verifier does not require approval.'
grep --fixed-strings '(.listed | length) == 21' <<< "$current_block" >/dev/null || fail 'The current authenticated verifier does not require all 21 versions.'
public_current_block="$(sed -n "${public_current_step},\$p" "$workflow")"
grep --fixed-strings 'env -u MODRINTH_TOKEN -u GH_TOKEN -u GITHUB_TOKEN' <<< "$public_current_block" >/dev/null || fail 'The current public Modrinth verifier receives a token.'
grep --fixed-strings 'bash release/verify-public-modrinth.sh build/release/modrinth/manifest.json "$PROJECT_ID" strata-ui' <<< "$public_current_block" >/dev/null || fail 'The strict current public inventory and CDN verifier is missing.'

[[ "$(grep --fixed-strings -c 'v0.1.1' "$sealed_workflow" || true)" == '0' ]] || fail 'The sealed v0.1.0 workflow was repurposed for v0.1.1.'
for forbidden in publishAndReleaseToMavenCentral publishToMavenCentral MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD mavenCentralUsername mavenCentralPassword; do
  if grep --fixed-strings "$forbidden" "$sealed_workflow" >/dev/null; then
    fail "The sealed v0.1.0 workflow gained a Central write or credential: $forbidden"
  fi
done

echo 'v0.1.1 release source guards passed.'

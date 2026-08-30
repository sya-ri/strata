#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
workflow="$repository_root/.github/workflows/publish-release.yml"
sealed_previous="$repository_root/.github/workflows/release-v0.1.1.yml"
sealed_initial="$repository_root/.github/workflows/release.yml"
jvm_workflow="$repository_root/.github/workflows/jvm.yml"

fail() {
  echo "$1" >&2
  exit 1
}

for required in "$workflow" "$sealed_previous" "$sealed_initial" "$jvm_workflow"; do
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
[[ "$(grep --fixed-strings -c 'release/github-release-tag-ruleset.json' "$workflow")" == '9' ]] || fail 'Every current tag mutation boundary must use the wildcard ruleset contract.'
[[ "$(grep --fixed-strings -c 'release/github-release-tag-ruleset-receipt.json' "$workflow")" == '9' ]] || fail 'Every current tag mutation boundary must use the wildcard ruleset receipt.'

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

#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
runner="$repository_root/release/run-modrinth-v0.1.2-backlog-recovery.sh"
contract="$repository_root/release/modrinth-v0.1.2-backlog-recovery.json"
artifact_evidence="$repository_root/release/modrinth-v0.1.0-artifacts.json"
workflow="$repository_root/.github/workflows/publish-release.yml"

fail() {
  echo "$1" >&2
  exit 1
}

for required in "$runner" "$contract" "$artifact_evidence" "$workflow"; do
  [[ -f "$required" && ! -L "$required" ]] || fail "Backlog recovery source is missing or unsafe: $required"
done

bash -n "$runner"
jq -e '
  .schemaVersion == 1 and
  .projectId == "CAdZ3jVr" and
  .slug == "strata-ui" and
  .releaseSource.tag == "v0.1.2" and
  .releaseSource.commit == "b541fc5492b798b6805c0c4d24e09f43ceff938a" and
  .releaseSource.tagObject == "49195293b3e163abd0beefc9fc8e61a428b8eb24" and
  .releaseSource.bodyBlob == "075d941895ed9b70bcb42d9814948fb1bdc4a05d" and
  .releaseSource.artifactCount == 21 and
  .releaseSource.manifestCanonicalSha256 == "5a26f471445f2e53d5c24e6431bf902d5d659e449a9d295d3c199fa132a757c6" and
  .bodyLineage == {
    backlogSha256: "d036e6399abd278d3fb0dfb2740749f2458302df3c84f503f0b3bf9051350a33",
    predecessorSha256: "3e79db82a612df73b387d1bc5a4b8b1f495ff0ae9042d1a9bae7a01da14bda1b",
    currentSha256: "f214e060534509ae59024a4bd9295d39a07c29e7f1665d2a9e943a90fe7f34f9"
  } and
  .allowedOperations == ["preflight", "stage", "observe"] and
  .allowedProjectStatuses == ["processing"] and
  .allowedCompletionStatuses == ["processing", "approved"] and
  .processingRequestedStatus == "approved" and
  (.baselineReleases | length) == 2 and
  .baselineReleases[0].tag == "v0.1.0" and
  .baselineReleases[0].commit == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" and
  .baselineReleases[0].tagObject == "ccf221fe7f133fe5598fafc4ad01e6bc69ba2230" and
  .baselineReleases[0].bodyBlob == "6b68a2e42278c0e3c389137df25c2ac9d6637d0a" and
  .baselineReleases[0].artifactEvidenceCanonicalSha256 == "29cda6910f37d01c98f2fc47412868b493cddbe5a3ff70869222757045d83b3a" and
  .baselineReleases[0].manifestCanonicalSha256 == "0392b28deb9e7838dbe9b6a25e9fd49cac510e4045bb1ebcff7682a4f7e83e08" and
  (.baselineReleases[0].gameVersions | length) == 20 and
  .baselineReleases[1].tag == "v0.1.1" and
  .baselineReleases[1].commit == "6e35f5984bbca06c18f8ca8080f45a70b09831bb" and
  .baselineReleases[1].tagObject == "013bb6b0c4835229402f5843b967151f5dfdc5b2" and
  .baselineReleases[1].bodyBlob == "b6b2229e2b2bc679d12b6b43137fa73e80147293" and
  .baselineReleases[1].manifestCanonicalSha256 == "ba15a712879d2fd1191a34015262d236a7f28169bf9946af4b52534d98cd413c" and
  (.baselineReleases[1].gameVersions | length) == 21 and
  all(.baselineReleases[].manifestCanonicalSha256; test("^[0-9a-f]{64}$")) and
  .stagingProvenance == {
    runId: 33301278440,
    controllerCommit: "505d6cf875a24211045269036aa76bc6deb79ea6",
    releaseJobId: 99230634453
  }
' "$contract" >/dev/null || fail 'The exact v0.1.2 backlog recovery contract differs.'
[[ "$(jq -cS . "$artifact_evidence" | tr -d '\r' | sha256sum | cut -d ' ' -f 1)" == '29cda6910f37d01c98f2fc47412868b493cddbe5a3ff70869222757045d83b3a' ]] || \
  fail 'The v0.1.0 public artifact evidence differs from its pinned canonical hash.'
jq -e '
    .schemaVersion == 1 and
    .releaseTag == "v0.1.0" and
    .releaseCommit == "d0be1ccf74ee8fa0aca1a23e9d50eb5ba45c39a8" and
    .githubReleaseUrl == "https://github.com/sya-ri/strata/releases/tag/v0.1.0" and
    (.artifacts | length) == 20 and
    ([.artifacts[].gameVersion] | length == (unique | length)) and
    ([.artifacts[].versionNumber] | length == (unique | length)) and
    ([.artifacts[].fileName] | length == (unique | length)) and
    all(.artifacts[];
      (keys | sort) == ["fileName", "gameVersion", "sha256", "sha512", "size", "versionNumber"] and
      .versionNumber == ("0.1.0+mc" + .gameVersion) and
      .fileName == ("strata-runtime-minecraft-fabric-" + .gameVersion + "-0.1.0.jar") and
      (.size | type) == "number" and 0 < .size and
      (.sha256 | test("^[0-9a-f]{64}$")) and
      (.sha512 | test("^[0-9a-f]{128}$"))
    )
  ' "$artifact_evidence" >/dev/null || fail 'The v0.1.0 public artifact evidence schema differs.'

for forbidden_task in modrinthReleaseSubmit modrinthReleaseFinalizeProject modrinthReleaseVerify publishAndReleaseToMavenCentral; do
  if grep --fixed-strings "$forbidden_task" "$runner" >/dev/null; then
    fail "The backlog runner contains a forbidden task: $forbidden_task"
  fi
done
if grep --extended-regexp -- '--request[[:space:]]+(POST|PATCH|PUT|DELETE)' "$runner" >/dev/null; then
  fail 'The backlog runner contains a direct mutating HTTP request.'
fi
[[ "$(grep --fixed-strings -c '"https://api.modrinth.com/v2/project/$project_id"' "$runner")" == '1' && \
  "$(grep --fixed-strings -c '"https://api.modrinth.com/v2/project/$project_id/version?include_changelog=true"' "$runner")" == '1' ]] || \
  fail 'Authenticated recovery requests are not restricted to the two exact Modrinth API endpoints.'

grep --fixed-strings 'preflight|observe) gradle_task=modrinthReleasePreflight' "$runner" >/dev/null || \
  fail 'Observe must use the exact read-only tagged preflight task.'
grep --fixed-strings 'gradle_task=modrinthReleaseStage' "$runner" >/dev/null || fail 'The append-only stage task is missing.'
[[ "$(grep --fixed-strings -c 'verify_historical_remote 0 20' "$runner")" == '1' && \
  "$(grep --fixed-strings -c 'verify_historical_remote 1 21' "$runner")" == '1' ]] || \
  fail 'Every operation must revalidate both exact historical inventories.'
grep --fixed-strings 'fetch_state before "$require_current_before" processing' "$runner" >/dev/null || \
  fail 'Recovery must reject approved-at-entry and non-processing lifecycles.'
grep --fixed-strings 'fetch_state after "$require_current_after" transition' "$runner" >/dev/null || \
  fail 'Only an in-operation transition to approved may complete.'

for inventory_guard in \
  'uniq -d "$remote_versions"' \
  'comm -23 "$baseline_versions" "$remote_versions"' \
  'comm -13 "$allowed_versions" "$remote_versions"' \
  'comm -23 "$current_versions" "$remote_versions"'; do
  grep --fixed-strings "$inventory_guard" "$runner" >/dev/null || fail "Remote inventory guard is missing: $inventory_guard"
done
grep --fixed-strings 'The Modrinth v2 version response schema differs.' "$runner" >/dev/null || \
  fail 'Authenticated version snapshots do not have a fail-closed v2 schema boundary.'
grep --fixed-strings '.sha256 = (.sha256 // $sha256[$version.version_number])' "$runner" >/dev/null || \
  fail 'Optional remote SHA-256 values are not normalized against exact manifest evidence.'
for artifact_guard in \
  '[[ -f "$artifact" && ! -L "$artifact" ]]' \
  'actual_size="$(wc -c < "$artifact"' \
  'actual_sha256="$(sha256sum "$artifact"' \
  'actual_sha512="$(sha512sum "$artifact"' \
  '[[ "$resolved_artifact" == "$repository_root/build/release/modrinth/artifacts/"* ]]'; do
  [[ "$(grep --fixed-strings -c "$artifact_guard" "$runner")" == '2' ]] || \
    fail "The artifact guard must run before and after recovery: $artifact_guard"
done

grep --fixed-strings 'manifestCanonicalSha256' "$runner" >/dev/null || fail 'Canonical manifests are not hash-pinned.'
grep --fixed-strings 'artifactEvidenceCanonicalSha256' "$runner" >/dev/null || \
  fail 'The v0.1.0 public artifact evidence is not pinned by the recovery contract.'
grep --fixed-strings 'del(.size, .sha256, .sha512)' "$runner" >/dev/null || \
  fail 'The v0.1.0 public artifact overlay structural proof is missing.'
grep --fixed-strings 'The v0.1.0 evidence overlay changed fields outside artifact size and hashes.' "$runner" >/dev/null || \
  fail 'The v0.1.0 public artifact overlay is not restricted to three evidence fields.'
grep --fixed-strings "del(.project.previousBodySha256)" "$runner" >/dev/null || \
  fail 'The one-field manifest transformation proof is missing.'
grep --fixed-strings 'revalidate_controller_boundary' "$runner" >/dev/null || \
  fail 'The protected stage boundary is missing.'
grep --fixed-strings 'fetch_state write-boundary 0 processing' "$runner" >/dev/null || \
  fail 'The protected stage boundary does not re-read the exact remote lifecycle and inventory.'
grep --fixed-strings 'baseline_metadata_write" == "$baseline_metadata_boundary' "$runner" >/dev/null || \
  fail 'The protected stage boundary does not preserve historical version metadata.'
grep --fixed-strings 'baseline_metadata_after" == "$baseline_metadata_write' "$runner" >/dev/null || \
  fail 'The append-only stage does not prove that historical version metadata stayed unchanged.'
grep --fixed-strings 'Historical Modrinth version metadata differs from the two signed release manifests.' "$runner" >/dev/null || \
  fail 'Remote historical metadata is not compared with the two signed manifest contracts.'
grep --fixed-strings 'Existing Modrinth v0.1.2 metadata differs from the exact signed release manifest.' "$runner" >/dev/null || \
  fail 'Existing current metadata is not compared with the signed v0.1.2 manifest contract.'
[[ "$(grep --fixed-strings -c "git fetch --force origin" "$runner")" == '2' ]] || \
  fail 'The protected stage boundary must refresh controller and tag before and after Pages verification.'
grep --fixed-strings 'verify-pages-deployment-source.sh' "$runner" >/dev/null || \
  fail 'The protected stage boundary does not revalidate Pages provenance.'

for receipt_field in \
  bodySha256Before bodySha256After releaseTagObject canonicalManifestSha256 recoveryManifestFileSha256 \
  manifestContractSha256 artifactReceiptSha256 operationReceiptSha256 historicalPreflights requestedStatusBefore \
  requestedStatusAfter versionNumberInventorySha256Before versionNumberInventorySha256After \
  baselineMetadataSha256Before baselineMetadataSha256Boundary baselineMetadataSha256WriteBoundary baselineMetadataSha256After \
  currentMetadataSha256Before currentMetadataSha256Boundary currentMetadataSha256WriteBoundary currentMetadataSha256After \
  historicalCoreContractSha256 historicalCoreSha256After currentCoreContractSha256 currentCoreSha256After writeScope; do
  grep --fixed-strings "$receipt_field" "$runner" >/dev/null || fail "Controller receipt field is missing: $receipt_field"
done
grep --fixed-strings 'receipt_temporary="$(mktemp "$receipt_directory/' "$runner" >/dev/null || \
  fail 'Controller receipt creation is not bounded to its destination directory.'
grep --fixed-strings 'mv -f -- "$receipt_temporary" "$receipt"' "$runner" >/dev/null || \
  fail 'Controller receipt publication is not atomic.'

release_job="$(sed -n '/^  release:$/,/^  public_skills:$/p' "$workflow")"
verify_job="$(sed -n '/^  verify:$/,$p' "$workflow")"
[[ "$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/run-modrinth-v0.1.2-backlog-recovery.sh"' <<< "$release_job")" == '3' ]] || \
  fail 'Recovery must have exactly preflight, stage, and observe calls in the release job.'
if grep --fixed-strings 'run-modrinth-v0.1.2-backlog-recovery.sh' <<< "$verify_job" >/dev/null; then
  fail 'Recovery must be absent from final verification.'
fi

test_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT INT TERM
controller_commit="$(git -C "$repository_root" rev-parse origin/master)"
pages_record='1 release-pages v0.1.2 2 controller-pages controller'
for forbidden_operation in submit finalize verify; do
  error_file="$test_root/$forbidden_operation.error"
  if (
    cd "$repository_root"
    MODRINTH_TOKEN='fixture-not-a-secret' bash "$runner" "$forbidden_operation" "$contract" CAdZ3jVr v0.1.2 \
      b541fc5492b798b6805c0c4d24e09f43ceff938a "$controller_commit" "$pages_record" 2> "$error_file"
  ); then
    fail "The backlog runner accepted forbidden operation: $forbidden_operation"
  fi
  grep --fixed-strings "Unsupported Modrinth backlog operation: $forbidden_operation" "$error_file" >/dev/null || \
    fail "The forbidden operation did not fail at the contract boundary: $forbidden_operation"
done

tampered_contract="$test_root/tampered-contract.json"
jq '.projectId = "different"' "$contract" > "$tampered_contract"
tampered_error="$test_root/tampered.error"
if (
  cd "$repository_root"
  MODRINTH_TOKEN='fixture-not-a-secret' bash "$runner" preflight "$tampered_contract" CAdZ3jVr v0.1.2 \
    b541fc5492b798b6805c0c4d24e09f43ceff938a "$controller_commit" "$pages_record" 2> "$tampered_error"
); then
  fail 'The backlog runner accepted a tampered contract.'
fi
grep --fixed-strings 'The Modrinth backlog runner and contract are not the same immutable controller bundle.' "$tampered_error" >/dev/null || \
  fail 'A moved or tampered contract did not fail at the immutable-bundle boundary.'

tampered_bundle="$test_root/tampered-bundle"
mkdir "$tampered_bundle"
cp "$runner" "$tampered_bundle/run-modrinth-v0.1.2-backlog-recovery.sh"
cp "$artifact_evidence" "$tampered_bundle/modrinth-v0.1.0-artifacts.json"
jq '.projectId = "different"' "$contract" > "$tampered_bundle/modrinth-v0.1.2-backlog-recovery.json"
tampered_bundle_error="$test_root/tampered-bundle.error"
if (
  cd "$repository_root"
  MODRINTH_TOKEN='fixture-not-a-secret' bash "$tampered_bundle/run-modrinth-v0.1.2-backlog-recovery.sh" preflight \
    "$tampered_bundle/modrinth-v0.1.2-backlog-recovery.json" CAdZ3jVr v0.1.2 \
    b541fc5492b798b6805c0c4d24e09f43ceff938a "$controller_commit" "$pages_record" 2> "$tampered_bundle_error"
); then
  fail 'The backlog runner accepted a tampered same-bundle contract.'
fi
grep --fixed-strings 'The Modrinth backlog recovery contract differs from the reviewed v0.1.2 contract.' "$tampered_bundle_error" >/dev/null || \
  fail 'A tampered same-bundle contract did not fail at the exact contract boundary.'

tampered_evidence_bundle="$test_root/tampered-evidence-bundle"
mkdir "$tampered_evidence_bundle"
cp "$runner" "$tampered_evidence_bundle/run-modrinth-v0.1.2-backlog-recovery.sh"
cp "$contract" "$tampered_evidence_bundle/modrinth-v0.1.2-backlog-recovery.json"
jq '.artifacts[0].size += 1' "$artifact_evidence" > "$tampered_evidence_bundle/modrinth-v0.1.0-artifacts.json"
tampered_evidence_error="$test_root/tampered-evidence.error"
if (
  cd "$repository_root"
  MODRINTH_TOKEN='fixture-not-a-secret' bash "$tampered_evidence_bundle/run-modrinth-v0.1.2-backlog-recovery.sh" preflight \
    "$tampered_evidence_bundle/modrinth-v0.1.2-backlog-recovery.json" CAdZ3jVr v0.1.2 \
    b541fc5492b798b6805c0c4d24e09f43ceff938a "$controller_commit" "$pages_record" 2> "$tampered_evidence_error"
); then
  fail 'The backlog runner accepted tampered v0.1.0 artifact evidence.'
fi
grep --fixed-strings 'The immutable v0.1.0 public artifact evidence differs from its pinned canonical hash.' "$tampered_evidence_error" >/dev/null || \
  fail 'Tampered v0.1.0 artifact evidence did not fail at the exact evidence boundary.'

cleanup
trap - EXIT INT TERM

bash "$repository_root/release/tests/verify-modrinth-v0.1.2-backlog-recovery-fixture.sh"

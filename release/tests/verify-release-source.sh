#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

fake_bin="$temporary_root/bin"
remote="$temporary_root/remote.git"
checkout="$temporary_root/checkout"
mkdir -p "$fake_bin"

cat > "$fake_bin/gh" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == "api" ]] || exit 64
shift
endpoint=""
for argument in "$@"; do
  case "$argument" in
    repos/*)
      endpoint="$argument"
      ;;
  esac
done
case "$endpoint" in
  */git/tags/*)
    printf '%s\tcommit\t%s\n' "$FAKE_VERIFIED" "$FAKE_TARGET_SHA"
    ;;
  */git/ref/tags/*)
    printf '%s\n' "$FAKE_REMOTE_TAG_OBJECT"
    ;;
  */rulesets/*)
    cat "$FAKE_RULESET_RESPONSE"
    ;;
  */actions/artifacts/*/zip)
    cat "$FAKE_PAGES_ARTIFACT_ZIP"
    ;;
  */actions/runs/*/artifacts\?*)
    cat "$FAKE_PAGES_ARTIFACTS_RESPONSE"
    ;;
  */actions/runs/*/jobs\?*)
    cat "$FAKE_PAGES_JOBS_RESPONSE"
    ;;
  */actions/runs/*)
    cat "$FAKE_PAGES_RUN_RESPONSE"
    ;;
  */deployments/*/statuses\?*)
    cat "$FAKE_PAGES_STATUSES_RESPONSE"
    ;;
  */deployments\?*)
    cat "$FAKE_PAGES_DEPLOYMENTS_RESPONSE"
    ;;
  *)
    exit 64
    ;;
esac
SCRIPT

cat > "$fake_bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_CURL_LOG"
output=""
headers=""
url=""
while (( 0 < $# )); do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    --dump-header)
      headers="$2"
      shift 2
      ;;
    https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done
[[ -n "$output" && -n "$headers" && -n "$url" ]] || exit 64
attempt=0
if [[ -f "$FAKE_CURL_STATE" ]]; then
  attempt="$(< "$FAKE_CURL_STATE")"
fi
attempt=$((attempt + 1))
printf '%s\n' "$attempt" > "$FAKE_CURL_STATE"
if [[ "$FAKE_CURL_MODE" == "always-stale" ]]; then
  /bin/sleep 2
fi
if [[ "$FAKE_CURL_MODE" == "stale-then-exact" && "$attempt" == "1" || "$FAKE_CURL_MODE" == "always-stale" ]]; then
  printf '{"commit":"0000000000000000000000000000000000000000","revision":"%s"}\n' "$FAKE_RECEIPT_TAG" > "$output"
else
  printf '{"commit":"%s","revision":"%s"}\n' "$FAKE_RECEIPT_COMMIT" "$FAKE_RECEIPT_TAG" > "$output"
fi
if [[ "$FAKE_CURL_MODE" == "redirect-old-final-no-age" ]]; then
  printf 'HTTP/2 302\r\nage: 100\r\nlocation: https://example.invalid/final\r\n\r\nHTTP/2 200\r\ncache-control: max-age=600\r\n\r\n' > "$headers"
else
  cache_age=0
  if [[ "$FAKE_CURL_MODE" == "cached-exact-then-fresh" && "$attempt" == "1" ]]; then
    cache_age=100
  fi
  printf 'HTTP/2 200\r\ncache-control: max-age=600\r\nage: %s\r\n\r\n' "$cache_age" > "$headers"
fi
SCRIPT

chmod +x "$fake_bin/gh" "$fake_bin/curl"
git init --bare --quiet "$remote"
git init --quiet "$checkout"
git -C "$checkout" config user.email release-test@example.invalid
git -C "$checkout" config user.name "Release Test"
printf 'first\n' > "$checkout/evidence.txt"
git -C "$checkout" add evidence.txt
git -C "$checkout" -c commit.gpgSign=false commit --quiet -m first
git -C "$checkout" branch -M master
git -C "$checkout" remote add origin "$remote"
git -C "$checkout" push --quiet --set-upstream origin master
git -C "$checkout" -c tag.gpgSign=false tag -a v0.1.0 -m original
git -C "$checkout" push --quiet origin v0.1.0

tag_commit="$(git -C "$checkout" rev-parse HEAD)"
tag_object="$(git -C "$checkout" rev-parse refs/tags/v0.1.0)"
export GITHUB_REPOSITORY=test/strata
export GH_TOKEN=test-token
export FAKE_VERIFIED=true
export FAKE_TARGET_SHA="$tag_commit"
export FAKE_REMOTE_TAG_OBJECT="$tag_object"
PATH="$fake_bin:$PATH"
export PATH

ruleset_contract="$temporary_root/github-tag-ruleset.json"
ruleset_receipt="$temporary_root/github-tag-ruleset-receipt.json"
ruleset_response="$temporary_root/github-tag-ruleset-response.json"
cp "$repository_root/release/github-tag-ruleset.json" "$ruleset_contract"
cat > "$ruleset_receipt" <<'JSON'
{
  "rulesetId": 42,
  "updatedAt": "2026-08-25T00:00:00Z",
  "bypassActorsAuditedAt": "2026-08-25T00:00:00Z"
}
JSON
write_ruleset_response() {
  local bypass_actors="$1"
  local updated_at="$2"
  jq -n \
    --arg repository "$GITHUB_REPOSITORY" \
    --arg updatedAt "$updated_at" \
    --argjson bypassActors "$bypass_actors" \
    '{
      id: 42,
      name: "Protect Strata v0.1.0",
      target: "tag",
      source_type: "Repository",
      source: $repository,
      enforcement: "active",
      bypass_actors: $bypassActors,
      conditions: {
        ref_name: {
          include: ["refs/tags/v0.1.0"],
          exclude: []
        }
      },
      rules: [
        {
          type: "update",
          parameters: {
            update_allows_fetch_and_merge: false
          }
        },
        {type: "deletion"}
      ],
      updated_at: $updatedAt
    }' > "$ruleset_response"
}
export FAKE_RULESET_RESPONSE="$ruleset_response"

write_ruleset_response '[]' '2026-08-25T00:00:00Z'
ruleset_result="$(bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt")"
[[ "$ruleset_result" == '42 2026-08-25T00:00:00Z' ]] || fail 'An exact tag ruleset did not pass verification.'

write_ruleset_response 'null' '2026-08-25T00:00:00Z'
bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null || \
  fail 'A read-only GitHub response with a matching administrator audit did not pass verification.'

write_ruleset_response 'null' '2026-08-25T09:00:00+09:00'
bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null || \
  fail 'Equivalent GitHub ruleset timestamps with different UTC offsets did not pass verification.'

jq 'del(.bypass_actors)' "$ruleset_response" > "$ruleset_response.omitted"
mv "$ruleset_response.omitted" "$ruleset_response"
bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null || \
  fail 'A read-only GitHub response omitting bypass actors did not honor the matching administrator audit.'

write_ruleset_response 'null' '2026-08-25T00:00:00Z'
jq '(.rules[] | select(.type == "update")) |= del(.parameters)' "$ruleset_response" > "$ruleset_response.omitted"
mv "$ruleset_response.omitted" "$ruleset_response"
bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null || \
  fail 'GitHub normalization that omits the default-false update parameters was rejected.'

write_ruleset_response 'null' '2026-08-25T00:00:00Z'
jq '(.rules[] | select(.type == "update").parameters.update_allows_fetch_and_merge) = true' \
  "$ruleset_response" > "$ruleset_response.changed"
mv "$ruleset_response.changed" "$ruleset_response"
if bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null 2>&1; then
  fail 'A tag ruleset that allows update through fetch-and-merge was accepted.'
fi

write_ruleset_response '[{"actor_id":1,"actor_type":"Team","bypass_mode":"always"}]' '2026-08-25T00:00:00Z'
if bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null 2>&1; then
  fail 'A visible tag-ruleset bypass actor was accepted.'
fi

write_ruleset_response 'null' '2026-08-25T00:00:01Z'
if bash "$repository_root/release/verify-github-tag-ruleset.sh" "$ruleset_contract" "$ruleset_receipt" >/dev/null 2>&1; then
  fail 'A ruleset changed after the administrator bypass audit was accepted.'
fi

if bash "$repository_root/release/verify-github-tag-ruleset.sh" \
  "$ruleset_contract" "$repository_root/release/github-tag-ruleset-receipt.json" >/dev/null 2>&1; then
  fail 'An unpopulated tag-ruleset receipt was accepted.'
fi

tag_result="$(cd "$checkout" && bash "$repository_root/release/verify-release-tag.sh" v0.1.0 "$tag_commit")"
[[ "$tag_result" == "$tag_object $tag_commit" ]] || fail 'Exact release tag verification did not return its immutable object and commit.'

original_object="$tag_object"
git -C "$checkout" -c tag.gpgSign=false tag --force -a v0.1.0 -m replaced >/dev/null
git -C "$checkout" push --quiet --force origin v0.1.0
tag_object="$(git -C "$checkout" rev-parse refs/tags/v0.1.0)"
export FAKE_REMOTE_TAG_OBJECT="$tag_object"
if (cd "$checkout" && bash "$repository_root/release/verify-release-tag.sh" v0.1.0 "$tag_commit" "$original_object" >/dev/null 2>&1); then
  fail 'A replaced annotated tag object was accepted.'
fi

printf 'second\n' >> "$checkout/evidence.txt"
git -C "$checkout" add evidence.txt
git -C "$checkout" -c commit.gpgSign=false commit --quiet -m second
replacement_commit="$(git -C "$checkout" rev-parse HEAD)"
git -C "$checkout" -c tag.gpgSign=false tag --force -a v0.1.0 -m second-commit >/dev/null
git -C "$checkout" push --quiet origin master
git -C "$checkout" push --quiet --force origin v0.1.0
tag_object="$(git -C "$checkout" rev-parse refs/tags/v0.1.0)"
export FAKE_TARGET_SHA="$replacement_commit"
export FAKE_REMOTE_TAG_OBJECT="$tag_object"
if (cd "$checkout" && bash "$repository_root/release/verify-release-tag.sh" v0.1.0 "$tag_commit" >/dev/null 2>&1); then
  fail 'A release tag moved to another commit was accepted.'
fi

export FAKE_VERIFIED=false
if (cd "$checkout" && bash "$repository_root/release/verify-release-tag.sh" v0.1.0 "$replacement_commit" "$tag_object" >/dev/null 2>&1); then
  fail 'An unverified GitHub tag signature was accepted.'
fi
export FAKE_VERIFIED=true
export FAKE_REMOTE_TAG_OBJECT=ffffffffffffffffffffffffffffffffffffffff
if (cd "$checkout" && bash "$repository_root/release/verify-release-tag.sh" v0.1.0 "$replacement_commit" "$tag_object" >/dev/null 2>&1); then
  fail 'A tag ref changed during verification was accepted.'
fi

pages_run_id=123
pages_artifact_id=55
pages_deployment_id=77
pages_site="$temporary_root/pages-site"
pages_archive="$temporary_root/pages-artifact.tar"
pages_zip="$temporary_root/pages-artifact.zip"
pages_run_response="$temporary_root/pages-run.json"
pages_jobs_response="$temporary_root/pages-jobs.json"
pages_artifacts_response="$temporary_root/pages-artifacts.json"
pages_deployments_response="$temporary_root/pages-deployments.json"
pages_statuses_response="$temporary_root/pages-statuses.json"
pages_python=""
for candidate in python3 python; do
  if "$candidate" -c 'import zipfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || fail 'Python with the standard zipfile module is required for the Pages artifact fixture.'
export FAKE_PAGES_ARTIFACT_ZIP="$pages_zip"
export FAKE_PAGES_RUN_RESPONSE="$pages_run_response"
export FAKE_PAGES_JOBS_RESPONSE="$pages_jobs_response"
export FAKE_PAGES_ARTIFACTS_RESPONSE="$pages_artifacts_response"
export FAKE_PAGES_DEPLOYMENTS_RESPONSE="$pages_deployments_response"
export FAKE_PAGES_STATUSES_RESPONSE="$pages_statuses_response"

write_pages_artifact_zip_metadata() {
  "$pages_python" - "$pages_zip" "$pages_archive" <<'PY'
import pathlib
import sys
import zipfile

destination = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(source, arcname="artifact.tar")
PY
  local artifact_size
  local artifact_digest
  artifact_size="$(stat -c '%s' "$pages_zip")"
  artifact_digest="sha256:$(sha256sum "$pages_zip" | cut -d ' ' -f 1)"
  jq -n \
    --argjson artifactId "$pages_artifact_id" \
    --argjson artifactSize "$artifact_size" \
    --arg artifactDigest "$artifact_digest" \
    --argjson runId "$pages_run_id" \
    --arg commit "$replacement_commit" \
    '{
      total_count: 1,
      artifacts: [
        {
          id: $artifactId,
          name: "github-pages",
          size_in_bytes: $artifactSize,
          digest: $artifactDigest,
          expired: false,
          workflow_run: {
            id: $runId,
            head_branch: "v0.1.0",
            head_sha: $commit
          }
        }
      ]
    }' > "$pages_artifacts_response"
}

write_pages_artifact() {
  local receipt_commit="$1"
  rm -rf -- "$pages_site"
  mkdir -p "$pages_site/releases/0.1.0"
  printf '{"commit":"%s","revision":"v0.1.0"}\n' "$receipt_commit" > "$pages_site/source-receipt.json"
  cp "$pages_site/source-receipt.json" "$pages_site/releases/0.1.0/source-receipt.json"
  tar -cf "$pages_archive" -C "$pages_site" .
  write_pages_artifact_zip_metadata
}

jq -n \
  --argjson runId "$pages_run_id" \
  --arg commit "$replacement_commit" \
  '{
    id: $runId,
    path: ".github/workflows/pages.yml",
    event: "push",
    head_branch: "v0.1.0",
    head_sha: $commit,
    status: "completed",
    conclusion: "success"
  }' > "$pages_run_response"
jq -n '{
  jobs: [
    {name: "build", status: "completed", conclusion: "success"},
    {name: "deploy", status: "completed", conclusion: "success"}
  ]
}' > "$pages_jobs_response"
jq -n \
  --argjson deploymentId "$pages_deployment_id" \
  --arg commit "$replacement_commit" \
  '[{
    id: $deploymentId,
    ref: "v0.1.0",
    sha: $commit,
    environment: "github-pages",
    task: "deploy"
  }]' > "$pages_deployments_response"
jq -n \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
  '[{
    state: "success",
    environment: "github-pages",
    log_url: ($runUrl + "/job/456"),
    target_url: "https://gh.s7a.dev/strata/"
  }]' > "$pages_statuses_response"

write_pages_artifact "$replacement_commit"
pages_result="$(
  bash "$repository_root/release/verify-pages-deployment-source.sh" \
    "$pages_run_id" v0.1.0 "$replacement_commit"
)"
[[ "$pages_result" == "$pages_run_id $pages_artifact_id $pages_deployment_id" ]] || \
  fail 'Exact Pages run, artifact, receipt, and deployment evidence did not pass verification.'

jq '(.artifacts[0].digest) = "sha256:0000000000000000000000000000000000000000000000000000000000000000"' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" >/dev/null 2>&1; then
  fail 'A downloaded Pages artifact whose ZIP digest differed from Actions metadata was accepted.'
fi

write_pages_artifact "$replacement_commit"
tar -rf "$pages_archive" -C "$pages_site" ./source-receipt.json
write_pages_artifact_zip_metadata
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" >/dev/null 2>&1; then
  fail 'A Pages artifact with a duplicate root source receipt was accepted.'
fi

write_pages_artifact 0000000000000000000000000000000000000000
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" >/dev/null 2>&1; then
  fail 'A Pages artifact carrying another source commit was accepted.'
fi

write_pages_artifact "$replacement_commit"
jq -n '[{
  state: "success",
  environment: "github-pages",
  log_url: "https://github.com/test/strata/actions/runs/999/job/456",
  target_url: "https://github.com/test/strata/actions/runs/999"
}]' > "$pages_statuses_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" >/dev/null 2>&1; then
  fail 'A successful Pages deployment status linked to another workflow run was accepted.'
fi
jq -n \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
  '[{
    state: "success",
    environment: "github-pages",
    log_url: ($runUrl + "/job/456"),
    target_url: "https://gh.s7a.dev/strata/"
  }]' > "$pages_statuses_response"

jq -n \
  --arg commit "$replacement_commit" \
  --argjson deploymentId "$pages_deployment_id" \
  '[
    {
      id: 88,
      ref: "master",
      sha: "0000000000000000000000000000000000000000",
      environment: "github-pages",
      task: "deploy"
    },
    {
      id: $deploymentId,
      ref: "v0.1.0",
      sha: $commit,
      environment: "github-pages",
      task: "deploy"
    }
  ]' > "$pages_deployments_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" >/dev/null 2>&1; then
  fail 'An older exact deployment was accepted while a different global github-pages deployment was newer.'
fi

jq -n \
  --argjson deploymentId "$pages_deployment_id" \
  --arg commit "$replacement_commit" \
  '[{
    id: $deploymentId,
    ref: "v0.1.0",
    sha: $commit,
    environment: "github-pages",
    task: "deploy"
  }]' > "$pages_deployments_response"

export FAKE_RECEIPT_TAG=v0.1.0
export FAKE_RECEIPT_COMMIT="$replacement_commit"
export FAKE_CURL_MODE=stale-then-exact
export FAKE_CURL_STATE="$temporary_root/curl-state"
export FAKE_CURL_LOG="$temporary_root/curl-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" v0.1.0 "$replacement_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "2" ]] || fail 'Pages receipt polling did not retry a stale HTTP 200 response.'

export FAKE_CURL_MODE=cached-exact-then-fresh
export FAKE_CURL_STATE="$temporary_root/cached-state"
export FAKE_CURL_LOG="$temporary_root/cached-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" v0.1.0 "$replacement_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "2" ]] || fail 'A matching but old CDN response was accepted before its Age returned near zero.'

export FAKE_CURL_MODE=redirect-old-final-no-age
export FAKE_CURL_STATE="$temporary_root/redirect-state"
export FAKE_CURL_LOG="$temporary_root/redirect-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" v0.1.0 "$replacement_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "1" ]] || \
  fail 'A redirect Age leaked into the final header block and delayed a current public Pages receipt.'

grep --fixed-strings 'timeout_seconds="${3:-900}"' \
  "$repository_root/release/wait-for-pages-source-receipt.sh" >/dev/null || \
  fail 'The public Pages polling default does not outlive the observed 600-second CDN TTL.'

export FAKE_CURL_MODE=always-stale
export FAKE_CURL_STATE="$temporary_root/stale-state"
export FAKE_CURL_LOG="$temporary_root/stale-log"
if bash "$repository_root/release/wait-for-pages-source-receipt.sh" v0.1.0 "$replacement_commit" 1 0 >/dev/null 2>&1; then
  fail 'Permanently stale Pages evidence was accepted.'
fi

workflow="$repository_root/.github/workflows/release.yml"
grep --fixed-strings 'id: controller_overlay' "$workflow" >/dev/null || \
  fail 'The audited controller overlay loader does not expose step-scoped outputs.'
overlay_loader="$temporary_root/controller-overlay-loader.yml"
sed -n '/name: Load the audited Modrinth controller overlay$/,/name: Resolve immutable Modrinth project ID$/p' \
  "$workflow" > "$overlay_loader"
grep --fixed-strings '>> "$GITHUB_OUTPUT"' "$overlay_loader" >/dev/null || \
  fail 'The audited controller overlay loader does not write step-scoped outputs.'
if grep --fixed-strings '$GITHUB_ENV' "$overlay_loader" >/dev/null; then
  fail 'Controller overlay paths must not be exported to later release steps through GITHUB_ENV.'
fi
[[ "$(grep -c 'CONTROLLER_OVERLAY_RUNNER: \${{ steps.controller_overlay.outputs.runner }}' "$workflow")" == '2' ]] || \
  fail 'Only Modrinth preflight and stage may receive the controller overlay runner.'
[[ "$(grep -c 'CONTROLLER_OVERLAY_MANIFEST: \${{ steps.controller_overlay.outputs.manifest }}' "$workflow")" == '2' ]] || \
  fail 'Only Modrinth preflight and stage may receive the controller overlay manifest.'
[[ "$(grep -c 'CONTROLLER_OVERLAY_PATCH: \${{ steps.controller_overlay.outputs.patch }}' "$workflow")" == '2' ]] || \
  fail 'Only Modrinth preflight and stage may receive the controller overlay patch.'
[[ "$(grep -c 'CONTROLLER_OVERLAY_DIRECTORY: \${{ steps.controller_overlay.outputs.directory }}' "$workflow")" == '1' ]] || \
  fail 'Only Modrinth stage may receive the controller overlay input directory.'
[[ "$(grep -c 'bash "$CONTROLLER_OVERLAY_RUNNER" \\' "$workflow")" == '2' ]] || \
  fail 'The audited controller overlay must wrap exactly Modrinth preflight and stage.'
grep --fixed-strings 'preflight "$CONTROLLER_OVERLAY_MANIFEST" "$CONTROLLER_OVERLAY_PATCH" \' "$workflow" >/dev/null || \
  fail 'The immutable-tag Modrinth preflight does not use the audited controller overlay.'
grep --fixed-strings 'stage "$CONTROLLER_OVERLAY_MANIFEST" "$CONTROLLER_OVERLAY_PATCH" \' "$workflow" >/dev/null || \
  fail 'The immutable-tag Modrinth stage does not use the audited controller overlay.'
grep --fixed-strings 'gradle_task=modrinthReleasePreflight' "$repository_root/release/run-controller-overlay.sh" >/dev/null || \
  fail 'The controller overlay does not map its read-only operation to Modrinth preflight.'
grep --fixed-strings 'gradle_task=modrinthReleaseStage' "$repository_root/release/run-controller-overlay.sh" >/dev/null || \
  fail 'The controller overlay does not map its write operation to Modrinth stage.'
grep --fixed-strings -- '-x verifyPublishedConsumer' "$repository_root/release/run-controller-overlay.sh" >/dev/null || \
  fail 'The controller overlay does not exclude the tagged published-consumer dependency.'
grep --fixed-strings -- '-x modrinthReleaseManifest' "$repository_root/release/run-controller-overlay.sh" >/dev/null || \
  fail 'The controller overlay does not exclude tagged product artifact generation.'
grep --fixed-strings 'expected_overlay_directory="$RUNNER_TEMP/strata-controller-overlay-inputs"' "$workflow" >/dev/null || \
  fail 'The release workflow does not bind cleanup to the exact controller overlay input directory.'
grep --fixed-strings 'rm -rf -- "$CONTROLLER_OVERLAY_DIRECTORY"' "$workflow" >/dev/null || \
  fail 'The release workflow does not remove controller overlay inputs after successful staging.'
grep --fixed-strings '[[ ! -e "$CONTROLLER_OVERLAY_DIRECTORY" ]]' "$workflow" >/dev/null || \
  fail 'The release workflow does not verify controller overlay input removal.'
if sed -n '/^tasks.named("modrinthReleasePreflight") {$/,/^}$/p' "$repository_root/build.gradle.kts" |
  grep --fixed-strings 'dependsOn(verifyPublishedConsumer)' >/dev/null; then
  fail 'The read-only Modrinth preflight must not depend on the publishing consumer aggregate.'
fi

[[ "$(grep --fixed-strings -c 'id: central_controller_overlay' "$workflow")" == '2' ]] || \
  fail 'Release and final verification must each load the audited Central controller overlay.'
[[ "$(grep --fixed-strings -c 'CENTRAL_OVERLAY_RUNNER: ${{ steps.central_controller_overlay.outputs.runner }}' "$workflow")" == '3' ]] || \
  fail 'Only the three sealed Central read phases may receive the Central overlay runner.'
[[ "$(grep --fixed-strings -c 'CENTRAL_OVERLAY_MANIFEST: ${{ steps.central_controller_overlay.outputs.manifest }}' "$workflow")" == '3' ]] || \
  fail 'Only the three sealed Central read phases may receive the Central overlay manifest.'
[[ "$(grep --fixed-strings -c 'CENTRAL_OVERLAY_PATCH: ${{ steps.central_controller_overlay.outputs.patch }}' "$workflow")" == '3' ]] || \
  fail 'Only the three sealed Central read phases may receive the Central overlay patch.'
[[ "$(grep --fixed-strings -c 'CENTRAL_OVERLAY_DIRECTORY: ${{ steps.central_controller_overlay.outputs.directory }}' "$workflow")" == '2' ]] || \
  fail 'Only the two exact cleanup phases may receive the Central overlay directory.'
[[ "$(grep --fixed-strings -c 'bash "$CENTRAL_OVERLAY_RUNNER" \' "$workflow")" == '3' ]] || \
  fail 'The audited Central overlay must wrap exactly three sealed public reads.'
[[ "$(grep --fixed-strings -c 'release-preflight "$CENTRAL_OVERLAY_MANIFEST" "$CENTRAL_OVERLAY_PATCH" \' "$workflow")" == '1' ]] || \
  fail 'The release must have exactly one sealed public Central preflight.'
[[ "$(grep --fixed-strings -c 'release-verify "$CENTRAL_OVERLAY_MANIFEST" "$CENTRAL_OVERLAY_PATCH" \' "$workflow")" == '2' ]] || \
  fail 'Release and final verification must each verify the sealed public Central release.'
grep --fixed-strings 'Require the sealed Maven Central release' "$workflow" >/dev/null || \
  fail 'The post-publication Central state is not explicitly sealed.'
grep --fixed-strings 'The sealed Central release differs' "$workflow" >/dev/null || \
  fail 'The sealed Central preflight does not fail closed on any public mismatch.'
central_verify_line="$(grep -n -m 1 --fixed-strings '      - name: Verify Central and build the canonical GitHub bundle' "$workflow" | cut -d: -f1)"
central_signature_line="$(grep -n -m 1 --fixed-strings '      - name: Cryptographically verify canonical Central signatures' "$workflow" | cut -d: -f1)"
modrinth_stage_line="$(grep -n -m 1 --fixed-strings '      - name: Stage listed Modrinth versions' "$workflow" | cut -d: -f1)"
[[ -n "$central_verify_line" && -n "$central_signature_line" && -n "$modrinth_stage_line" ]] || \
  fail 'The sealed Central verification or Modrinth staging boundary is missing.'
(( central_verify_line < central_signature_line && central_signature_line < modrinth_stage_line )) || \
  fail 'Canonical Central signatures must be verified before Modrinth staging can mutate remote state.'
if awk '
  /^      - name:/ {
    if (overlay_step && secret) exposed = 1
    overlay_step = 0
    secret = 0
  }
  /CENTRAL_OVERLAY_RUNNER:/ { overlay_step = 1 }
  /(SIGNING_KEY|signingInMemoryKey|mavenCentralUsername|mavenCentralPassword)/ { secret = 1 }
  END {
    if (overlay_step && secret) exposed = 1
    exit exposed ? 0 : 1
  }
' "$workflow"; then
  fail 'A read-only Central overlay phase receives a signing key or Central credential.'
fi
for read_task in mavenCentralReleasePreflight mavenCentralPortalPreflight mavenCentralPortalVerify 'mavenCentralReleaseVerify githubReleaseBundle'; do
  if grep --fixed-strings "$read_task" "$workflow" >/dev/null; then
    fail "A Central read task bypasses the immutable-tag controller overlay: $read_task"
  fi
done
for forbidden_central_write in publishAndReleaseToMavenCentral publishToMavenCentral MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD mavenCentralUsername mavenCentralPassword; do
  if grep --fixed-strings "$forbidden_central_write" "$workflow" >/dev/null; then
    fail "The sealed v0.1.0 workflow still contains a Central write path or credential: $forbidden_central_write"
  fi
done
if grep -Eiq 'portal|central_publish|verify_portal|steps\.central\.outputs\.publish' "$workflow"; then
  fail 'The sealed v0.1.0 workflow still contains a Portal or conditional Central publication path.'
fi
if grep --fixed-strings '"960"' "$workflow" >/dev/null; then
  fail 'The release workflow still requires nonexistent detached-signature checksum sidecars.'
fi
grep --fixed-strings 'expected_overlay_directory="$RUNNER_TEMP/strata-central-controller-overlay-inputs"' "$workflow" >/dev/null || \
  fail 'Central overlay cleanup is not bound to its exact temporary directory.'
[[ "$(grep --fixed-strings -c 'rm -rf -- "$CENTRAL_OVERLAY_DIRECTORY"' "$workflow")" == '2' ]] || \
  fail 'Release and final verification must each remove their Central overlay inputs.'
[[ "$(grep --fixed-strings -c '[[ ! -e "$CENTRAL_OVERLAY_DIRECTORY" ]]' "$workflow")" == '2' ]] || \
  fail 'Central overlay input removal is not verified in both jobs.'

bash "$repository_root/release/tests/verify-controller-overlay.sh" >/dev/null
bash "$repository_root/release/tests/verify-central-controller-overlay.sh" >/dev/null

echo 'Release source guards passed.'

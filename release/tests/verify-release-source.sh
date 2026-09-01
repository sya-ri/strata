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
paginate=false
slurp=false
for argument in "$@"; do
  case "$argument" in
    --paginate)
      paginate=true
      ;;
    --slurp)
      slurp=true
      ;;
    repos/*)
      endpoint="$argument"
      ;;
  esac
done
emit_paginated_array() {
  [[ "$paginate" == true && "$slurp" == true ]] || exit 64
  jq -s '.' "$1"
}
emit_paginated_object() {
  [[ "$paginate" == true && "$slurp" == true ]] || exit 64
  jq -s '.' "$1"
}
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
  */actions/artifacts/${FAKE_RELEASE_PAGES_ARTIFACT_ID:-missing}/zip)
    cat "$FAKE_RELEASE_PAGES_ARTIFACT_ZIP"
    ;;
  */actions/artifacts/${FAKE_CONTROLLER_PAGES_ARTIFACT_ID:-missing}/zip)
    cat "$FAKE_CONTROLLER_PAGES_ARTIFACT_ZIP"
    ;;
  */actions/artifacts/${FAKE_LEGACY_PAGES_ARTIFACT_ID:-missing}/zip)
    cat "$FAKE_LEGACY_PAGES_ARTIFACT_ZIP"
    ;;
  */actions/runs/${FAKE_LEGACY_PAGES_RUN_ID:-missing}/artifacts\?*)
    emit_paginated_object "$FAKE_LEGACY_PAGES_ARTIFACTS_RESPONSE"
    ;;
  */actions/runs/${FAKE_LEGACY_PAGES_RUN_ID:-missing}/jobs\?*filter=all*)
    emit_paginated_object "$FAKE_LEGACY_PAGES_ALL_JOBS_RESPONSE"
    ;;
  */actions/runs/${FAKE_LEGACY_PAGES_RUN_ID:-missing})
    cat "$FAKE_LEGACY_PAGES_RUN_RESPONSE"
    ;;
  */actions/runs/${FAKE_PAGES_RUN_ID:-missing}/artifacts\?*)
    emit_paginated_object "$FAKE_PAGES_ARTIFACTS_RESPONSE"
    ;;
  */actions/runs/${FAKE_PAGES_RUN_ID:-missing}/jobs\?*filter=all*)
    emit_paginated_object "$FAKE_PAGES_ALL_JOBS_RESPONSE"
    ;;
  */actions/runs/${FAKE_PAGES_RUN_ID:-missing})
    cat "$FAKE_PAGES_RUN_RESPONSE"
    ;;
  */deployments/${FAKE_PAGES_DEPLOYMENT_ID:-missing}/statuses\?*)
    emit_paginated_array "$FAKE_PAGES_STATUSES_RESPONSE"
    ;;
  */deployments/${FAKE_LEGACY_PAGES_DEPLOYMENT_ID:-missing}/statuses\?*)
    emit_paginated_array "$FAKE_LEGACY_PAGES_STATUSES_RESPONSE"
    ;;
  */environments/github-pages-controller/deployment-branch-policies\?*)
    cat "$FAKE_PAGES_CONTROLLER_POLICIES_RESPONSE"
    ;;
  */environments/github-pages-controller)
    cat "$FAKE_PAGES_CONTROLLER_ENVIRONMENT_RESPONSE"
    ;;
  */environments/github-pages/deployment-branch-policies\?*)
    cat "$FAKE_RETIRED_PAGES_POLICIES_RESPONSE"
    ;;
  */environments/github-pages)
    cat "$FAKE_RETIRED_PAGES_ENVIRONMENT_RESPONSE"
    ;;
  */deployments\?*)
    emit_paginated_array "$FAKE_GLOBAL_PAGES_DEPLOYMENTS_RESPONSE"
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
if [[ "$url" == */releases/* ]]; then
  expected_revision="$FAKE_RECEIPT_RELEASE_TAG"
  expected_commit="$FAKE_RECEIPT_RELEASE_COMMIT"
else
  expected_revision=master
  expected_commit="$FAKE_RECEIPT_CONTROLLER_COMMIT"
fi
stale=false
if [[ "$FAKE_CURL_MODE" == "stale-then-exact" && "$attempt" == "1" || \
  "$FAKE_CURL_MODE" == "release-stale-then-exact" && "$attempt" == "2" || \
  "$FAKE_CURL_MODE" == "always-stale" ]]; then
  stale=true
fi
if [[ "$stale" == true ]]; then
  printf '{"commit":"0000000000000000000000000000000000000000","revision":"%s"}\n' "$expected_revision" > "$output"
else
  printf '{"commit":"%s","revision":"%s"}\n' "$expected_commit" "$expected_revision" > "$output"
fi
if [[ "$FAKE_CURL_MODE" == "redirect-old-final-no-age" ]]; then
  printf 'HTTP/2 302\r\nage: 100\r\nlocation: https://example.invalid/final\r\n\r\nHTTP/2 200\r\ncache-control: max-age=600\r\n\r\n' > "$headers"
else
  cache_age=0
  if [[ "$FAKE_CURL_MODE" == "cached-exact-then-fresh" && "$attempt" == "1" ]]; then
    cache_age=100
  elif [[ "$FAKE_CURL_MODE" == "independent-fresh-windows" ]]; then
    if [[ "$url" == */releases/* && "$attempt" -lt "4" || "$url" != */releases/* && "$attempt" != "1" ]]; then
      cache_age=100
    fi
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
  local ruleset_name="${3:-Protect Strata v0.1.0}"
  local ruleset_ref="${4:-refs/tags/v0.1.0}"
  jq -n \
    --arg repository "$GITHUB_REPOSITORY" \
    --arg updatedAt "$updated_at" \
    --arg rulesetName "$ruleset_name" \
    --arg rulesetRef "$ruleset_ref" \
    --argjson bypassActors "$bypass_actors" \
    '{
      id: 42,
      name: $rulesetName,
      target: "tag",
      source_type: "Repository",
      source: $repository,
      enforcement: "active",
      bypass_actors: $bypassActors,
      conditions: {
        ref_name: {
          include: [$rulesetRef],
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

wildcard_contract="$temporary_root/github-release-tag-ruleset.json"
wildcard_receipt="$temporary_root/github-release-tag-ruleset-receipt.json"
cp "$repository_root/release/github-release-tag-ruleset.json" "$wildcard_contract"
jq '.rulesetId = 42 | .updatedAt = "2026-08-25T00:00:00Z" | .bypassActorsAuditedAt = "2026-08-25T00:00:00Z"' \
  "$repository_root/release/github-release-tag-ruleset-receipt.json" > "$wildcard_receipt"
write_ruleset_response '[]' '2026-08-25T00:00:00Z' 'Protect Strata release tags' 'refs/tags/v*'
wildcard_result="$(bash "$repository_root/release/verify-github-tag-ruleset.sh" "$wildcard_contract" "$wildcard_receipt")"
[[ "$wildcard_result" == '42 2026-08-25T00:00:00Z' ]] || fail 'The canonical wildcard release-tag ruleset did not pass verification.'

write_ruleset_response '[]' '2026-08-25T00:00:00Z' 'Protect Strata v0.1.2' 'refs/tags/v*'
if bash "$repository_root/release/verify-github-tag-ruleset.sh" "$wildcard_contract" "$wildcard_receipt" >/dev/null 2>&1; then
  fail 'A wildcard release-tag ruleset with an exact-tag name was accepted.'
fi

jq '.conditions.ref_name.include = ["refs/tags/*"]' "$wildcard_contract" > "$wildcard_contract.invalid"
if bash "$repository_root/release/verify-github-tag-ruleset.sh" "$wildcard_contract.invalid" "$wildcard_receipt" >/dev/null 2>&1; then
  fail 'A wildcard ruleset broader than release tags was accepted.'
fi

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
release_pages_artifact_id=55
controller_pages_artifact_id=56
pages_deployment_id=77
controller_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
release_pages_site="$temporary_root/release-pages-site"
release_pages_zip="$temporary_root/release-pages-evidence.zip"
controller_pages_site="$temporary_root/controller-pages-site"
controller_pages_archive="$temporary_root/controller-pages-artifact.tar"
controller_pages_zip="$temporary_root/controller-pages-artifact.zip"
pages_run_response="$temporary_root/pages-run.json"
pages_all_jobs_response="$temporary_root/pages-all-jobs.json"
pages_artifacts_response="$temporary_root/pages-artifacts.json"
pages_statuses_response="$temporary_root/pages-statuses.json"
global_pages_deployments_response="$temporary_root/global-pages-deployments.json"
pages_controller_environment_response="$temporary_root/pages-controller-environment.json"
pages_controller_policies_response="$temporary_root/pages-controller-policies.json"
retired_pages_environment_response="$temporary_root/retired-pages-environment.json"
retired_pages_policies_response="$temporary_root/retired-pages-policies.json"
pages_python=""
for candidate in python3 python; do
  if "$candidate" -c 'import zipfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || fail 'Python with the standard zipfile module is required for the Pages artifact fixture.'
export FAKE_PAGES_RUN_ID="$pages_run_id"
export FAKE_RELEASE_PAGES_ARTIFACT_ID="$release_pages_artifact_id"
export FAKE_RELEASE_PAGES_ARTIFACT_ZIP="$release_pages_zip"
export FAKE_CONTROLLER_PAGES_ARTIFACT_ID="$controller_pages_artifact_id"
export FAKE_CONTROLLER_PAGES_ARTIFACT_ZIP="$controller_pages_zip"
export FAKE_PAGES_DEPLOYMENT_ID="$pages_deployment_id"
export FAKE_PAGES_RUN_RESPONSE="$pages_run_response"
export FAKE_PAGES_ALL_JOBS_RESPONSE="$pages_all_jobs_response"
export FAKE_PAGES_ARTIFACTS_RESPONSE="$pages_artifacts_response"
export FAKE_PAGES_STATUSES_RESPONSE="$pages_statuses_response"
export FAKE_GLOBAL_PAGES_DEPLOYMENTS_RESPONSE="$global_pages_deployments_response"
export FAKE_PAGES_CONTROLLER_ENVIRONMENT_RESPONSE="$pages_controller_environment_response"
export FAKE_PAGES_CONTROLLER_POLICIES_RESPONSE="$pages_controller_policies_response"
export FAKE_RETIRED_PAGES_ENVIRONMENT_RESPONSE="$retired_pages_environment_response"
export FAKE_RETIRED_PAGES_POLICIES_RESPONSE="$retired_pages_policies_response"

write_release_pages_evidence() {
  local root_commit="$1"
  local immutable_commit="${2:-$root_commit}"
  local immutable_content="${3:-immutable release page}"
  rm -rf -- "$release_pages_site"
  mkdir -p "$release_pages_site/guide" "$release_pages_site/releases/0.1.0/guide"
  printf '{"commit":"%s","revision":"v0.1.0"}\n' "$root_commit" > "$release_pages_site/source-receipt.json"
  printf '%s\n' "$immutable_content" > "$release_pages_site/guide/index.html"
  printf '{"commit":"%s","revision":"v0.1.0"}\n' "$immutable_commit" > \
    "$release_pages_site/releases/0.1.0/source-receipt.json"
  printf '%s\n' "$immutable_content" > "$release_pages_site/releases/0.1.0/guide/index.html"
  "$pages_python" - "$release_pages_site" "$release_pages_zip" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source.rglob("*")):
        archive.write(path, arcname=path.relative_to(source).as_posix())
PY
}

write_controller_pages_zip() {
  "$pages_python" - "$controller_pages_zip" "$controller_pages_archive" <<'PY'
import pathlib
import sys
import zipfile

destination = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(source, arcname="artifact.tar")
PY
}

write_controller_pages_artifact() {
  local root_commit="$1"
  local immutable_content="${2:-immutable release page}"
  rm -rf -- "$controller_pages_site"
  mkdir -p "$controller_pages_site/releases/0.1.0/guide"
  printf '{"commit":"%s","revision":"master"}\n' "$root_commit" > "$controller_pages_site/source-receipt.json"
  printf '{"commit":"%s","revision":"v0.1.0"}\n' "$replacement_commit" > \
    "$controller_pages_site/releases/0.1.0/source-receipt.json"
  printf '%s\n' "$immutable_content" > "$controller_pages_site/releases/0.1.0/guide/index.html"
  tar -cf "$controller_pages_archive" -C "$controller_pages_site" .
  write_controller_pages_zip
}

write_pages_artifacts_response() {
  local release_size
  local release_digest
  local controller_size
  local controller_digest
  release_size="$(stat -c '%s' "$release_pages_zip")"
  release_digest="sha256:$(sha256sum "$release_pages_zip" | cut -d ' ' -f 1)"
  controller_size="$(stat -c '%s' "$controller_pages_zip")"
  controller_digest="sha256:$(sha256sum "$controller_pages_zip" | cut -d ' ' -f 1)"
  jq -n \
    --argjson releaseArtifactId "$release_pages_artifact_id" \
    --argjson releaseSize "$release_size" \
    --arg releaseDigest "$release_digest" \
    --argjson controllerArtifactId "$controller_pages_artifact_id" \
    --argjson controllerSize "$controller_size" \
    --arg controllerDigest "$controller_digest" \
    --argjson runId "$pages_run_id" \
    --arg commit "$controller_commit" \
    '{
      total_count: 4,
      artifacts: [
        {
          id: ($releaseArtifactId - 10),
          name: ("release-pages-evidence-" + ($runId | tostring) + "-release-evidence-1"),
          size_in_bytes: $releaseSize,
          digest: $releaseDigest,
          expired: false,
          created_at: "2026-08-31T11:00:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: ($controllerArtifactId - 10),
          name: ("github-pages-" + ($runId | tostring) + "-build-1"),
          size_in_bytes: $controllerSize,
          digest: $controllerDigest,
          expired: false,
          created_at: "2026-08-31T11:01:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: $releaseArtifactId,
          name: ("release-pages-evidence-" + ($runId | tostring) + "-release-evidence-2"),
          size_in_bytes: $releaseSize,
          digest: $releaseDigest,
          expired: false,
          created_at: "2026-08-31T12:00:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: $controllerArtifactId,
          name: ("github-pages-" + ($runId | tostring) + "-build-2"),
          size_in_bytes: $controllerSize,
          digest: $controllerDigest,
          expired: false,
          created_at: "2026-08-31T12:01:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        }
      ]
    }' > "$pages_artifacts_response"
}

split_artifact_response_pages() {
  local response="$1"
  jq '
    (.artifacts | length) as $length |
    ($length / 2 | floor) as $middle |
    {total_count, artifacts: .artifacts[:$middle]},
    {total_count, artifacts: .artifacts[$middle:]}
  ' "$response" > "$response.pages"
  mv "$response.pages" "$response"
}

split_job_response_pages() {
  local response="$1"
  jq '
    (.jobs | length) as $length |
    ($length / 2 | floor) as $middle |
    {total_count, jobs: .jobs[:$middle]},
    {total_count, jobs: .jobs[$middle:]}
  ' "$response" > "$response.pages"
  mv "$response.pages" "$response"
}

write_pages_deployment_responses() {
  # Deliberately return oldest first so successful verification cannot depend on GitHub's response order.
  jq -n \
    --argjson deploymentId "$pages_deployment_id" \
    --arg commit "$controller_commit" \
    '[
      {
        id: 76,
        ref: "master",
        sha: "0000000000000000000000000000000000000000",
        environment: "github-pages",
        task: "deploy",
        created_at: "2026-08-31T11:00:00Z"
      },
      {
        id: $deploymentId,
        ref: "master",
        sha: $commit,
        environment: "github-pages-controller",
        task: "deploy",
        created_at: "2026-08-31T12:00:00Z"
      }
    ]' > "$global_pages_deployments_response"
  jq -n \
    --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
    '[
      {
        id: 7702,
        state: "pending",
        environment: "github-pages-controller",
        log_url: ($runUrl + "/job/788"),
        target_url: "https://gh.s7a.dev/strata/",
        created_at: "2026-08-31T11:59:00Z"
      },
      {
        id: 7701,
        state: "success",
        environment: "github-pages-controller",
        log_url: ($runUrl + "/job/789"),
        target_url: "https://gh.s7a.dev/strata/",
        created_at: "2026-08-31T12:00:00Z"
      }
    ]' > "$pages_statuses_response"
}

write_pages_environment_responses() {
  jq -n '{
    can_admins_bypass: false,
    deployment_branch_policy: {
      protected_branches: false,
      custom_branch_policies: true
    }
  }' > "$pages_controller_environment_response"
  jq -n '{
    total_count: 1,
    branch_policies: [{name: "master", type: "branch"}]
  }' > "$pages_controller_policies_response"
  jq -n '{
    can_admins_bypass: false,
    deployment_branch_policy: {
      protected_branches: false,
      custom_branch_policies: true
    }
  }' > "$retired_pages_environment_response"
  jq -n '{
    total_count: 0,
    branch_policies: []
  }' > "$retired_pages_policies_response"
}

jq -n \
  --argjson runId "$pages_run_id" \
  --arg commit "$controller_commit" \
  '{
    id: $runId,
    path: ".github/workflows/pages.yml",
    event: "push",
    head_branch: "master",
    head_sha: $commit,
    run_attempt: 3,
    status: "completed",
    conclusion: "success"
  }' > "$pages_run_response"
jq -n '{
  total_count: 9,
  jobs: [
    {id: 1001, name: "build", run_attempt: 1, started_at: "2026-08-31T10:50:00Z", completed_at: "2026-08-31T11:10:00Z", status: "completed", conclusion: "failure"},
    {id: 2001, name: "release-evidence", run_attempt: 1, started_at: "2026-08-31T10:50:00Z", completed_at: "2026-08-31T11:10:00Z", status: "completed", conclusion: "failure"},
    {id: 3001, name: "deploy", run_attempt: 1, started_at: "2026-08-31T11:20:00Z", completed_at: "2026-08-31T11:15:00Z", status: "completed", conclusion: "skipped"},
    {id: 1002, name: "build", run_attempt: 2, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 2002, name: "release-evidence", run_attempt: 2, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 3002, name: "deploy", run_attempt: 2, started_at: "2026-08-31T12:15:00Z", completed_at: "2026-08-31T12:20:00Z", status: "completed", conclusion: "failure"},
    {id: 1003, name: "build", run_attempt: 3, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 2003, name: "release-evidence", run_attempt: 3, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 3003, name: "deploy", run_attempt: 3, started_at: "2026-08-31T12:30:00Z", completed_at: "2026-08-31T12:40:00Z", status: "completed", conclusion: "success"}
  ]
}' > "$pages_all_jobs_response"
jq --argjson runId "$pages_run_id" --arg commit "$controller_commit" \
  '.jobs |= map(. + {run_id: $runId, head_sha: $commit})' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
split_job_response_pages "$pages_all_jobs_response"

write_release_pages_evidence "$replacement_commit"
write_controller_pages_artifact "$controller_commit"
write_pages_artifacts_response
split_artifact_response_pages "$pages_artifacts_response"
write_pages_deployment_responses
write_pages_environment_responses
pages_result="$(
  bash "$repository_root/release/verify-pages-deployment-source.sh" \
    "$pages_run_id" \
    v0.1.0 \
    "$replacement_commit" \
    "$pages_run_id" \
    "$controller_commit"
)"
expected_pages_result="$pages_run_id $release_pages_artifact_id $pages_deployment_id"
expected_pages_result+=" $pages_run_id $controller_pages_artifact_id $pages_deployment_id"
[[ "$pages_result" == "$expected_pages_result" ]] || \
  fail 'A paginated rerun inventory did not select the exact artifacts from the latest logical producer windows.'
write_pages_artifacts_response

if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  124 v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An unverified separate release Pages run was accepted as sealed-workflow compatibility evidence.'
fi

jq --argjson artifactId "$release_pages_artifact_id" \
  '(.artifacts[] | select(.id == $artifactId).digest) =
  "sha256:0000000000000000000000000000000000000000000000000000000000000000"' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A selected Pages evidence artifact with an invalid digest fell back to an older producer attempt.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$release_pages_artifact_id" \
  '(.artifacts[] | select(.id == ($artifactId - 10)).id) = $artifactId' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A Pages rerun artifact inventory with duplicate IDs was accepted.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$release_pages_artifact_id" \
  '(.artifacts[] | select(.id == ($artifactId - 10)).created_at) = "not-a-timestamp"' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A Pages rerun artifact inventory with a malformed creation timestamp was accepted.'
fi

write_pages_artifacts_response
jq '.total_count += 1' "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An incomplete Pages rerun artifact inventory was accepted.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$release_pages_artifact_id" \
  '(.artifacts[] | select(.id == $artifactId).expired) = true' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An expired selected Pages artifact fell back to an older producer attempt.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$release_pages_artifact_id" \
  '.artifacts |= map(select(.id != $artifactId)) | .total_count -= 1' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A missing selected Pages artifact fell back to an older producer attempt.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$controller_pages_artifact_id" --argjson duplicateId 66 \
  --arg duplicateName "github-pages-${pages_run_id}-build-3" '
    (.artifacts[] | select(.id == $artifactId)) as $selected |
    .artifacts += [($selected | .id = $duplicateId | .name = $duplicateName | .created_at = "2026-08-31T12:02:00Z")] |
    .total_count += 1
  ' "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Two controller artifacts inside one latest logical producer window were accepted.'
fi

write_pages_artifacts_response
jq --argjson artifactId "$controller_pages_artifact_id" \
  --arg unmatchedName "github-pages-${pages_run_id}-build-4" \
  '(.artifacts[] | select(.id == $artifactId).name) = $unmatchedName' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A selected Pages artifact whose suffix has no producer job was accepted.'
fi

write_release_pages_evidence "$replacement_commit"
"$pages_python" - "$release_pages_zip" <<'PY'
import warnings
import zipfile
import sys

with warnings.catch_warnings():
    warnings.simplefilter("ignore", UserWarning)
    with zipfile.ZipFile(sys.argv[1], mode="a") as archive:
        archive.writestr(
            "source-receipt.json",
            b'{"commit":"0000000000000000000000000000000000000000","revision":"v0.1.0"}\n',
        )
PY
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Release Pages evidence with a duplicate root source receipt was accepted.'
fi

write_release_pages_evidence 0000000000000000000000000000000000000000 "$replacement_commit"
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Release Pages evidence carrying another root source commit was accepted.'
fi

write_release_pages_evidence "$replacement_commit" 0000000000000000000000000000000000000000
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Release Pages evidence carrying another immutable source commit was accepted.'
fi

write_release_pages_evidence "$replacement_commit"
"$pages_python" - "$release_pages_zip" <<'PY'
import stat
import sys
import zipfile

entry = zipfile.ZipInfo("releases/0.1.0/unsafe-link")
entry.create_system = 3
entry.external_attr = (stat.S_IFLNK | 0o777) << 16
with zipfile.ZipFile(sys.argv[1], mode="a") as archive:
    archive.writestr(entry, "../../source-receipt.json")
PY
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Release Pages evidence with a non-regular immutable entry was accepted.'
fi

write_release_pages_evidence "$replacement_commit"
write_controller_pages_artifact 0000000000000000000000000000000000000000
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact carrying another root commit was accepted.'
fi

write_controller_pages_artifact "$controller_commit" 'changed immutable release page'
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact that changed immutable release bytes was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
tar -rf "$controller_pages_archive" \
  -C "$controller_pages_site" ./releases/0.1.0/guide/index.html
write_controller_pages_zip
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact with a duplicate immutable path was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
"$pages_python" - "$controller_pages_archive" <<'PY'
import sys
import tarfile

with tarfile.open(sys.argv[1], mode="a:") as archive:
    entry = tarfile.TarInfo("./releases/0.1.0/unsafe-link")
    entry.type = tarfile.SYMTYPE
    entry.linkname = "../../source-receipt.json"
    archive.addfile(entry)
PY
write_controller_pages_zip
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact with a non-regular immutable entry was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
"$pages_python" - "$controller_pages_archive" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], mode="a:") as archive:
    payload = b"traversal\n"
    entry = tarfile.TarInfo("./releases/0.1.0/../escape.txt")
    entry.size = len(payload)
    archive.addfile(entry, io.BytesIO(payload))
PY
write_controller_pages_zip
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact containing an immutable-subtree traversal path was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
"$pages_python" - "$controller_pages_archive" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], mode="a:") as archive:
    payload = b"ambiguous path\n"
    entry = tarfile.TarInfo("./releases" + chr(92) + "0.1.0/ambiguous.txt")
    entry.size = len(payload)
    archive.addfile(entry, io.BytesIO(payload))
PY
write_controller_pages_zip
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact containing a non-canonical backslash path was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
"$pages_python" - "$controller_pages_archive" <<'PY'
import sys
import tarfile

with tarfile.open(sys.argv[1], mode="a:") as archive:
    entry = tarfile.TarInfo("./releases/0.1.0/unsafe-device")
    entry.type = tarfile.CHRTYPE
    entry.devmajor = 1
    entry.devminor = 3
    archive.addfile(entry)
PY
write_controller_pages_zip
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A controller Pages artifact with an immutable device entry was accepted.'
fi

write_controller_pages_artifact "$controller_commit"
jq '(.jobs[] | select(.id == 2003).conclusion) = "failure"' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
write_pages_artifacts_response
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A Documentation run with failed release evidence preparation was accepted.'
fi
jq '(.jobs[] | select(.id == 2003).conclusion) = "success"' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"

jq '.run_attempt = 4' "$pages_run_response" > "$pages_run_response.changed"
mv "$pages_run_response.changed" "$pages_run_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A successful deploy job from another overall run attempt was accepted.'
fi
jq '.run_attempt = 3' "$pages_run_response" > "$pages_run_response.changed"
mv "$pages_run_response.changed" "$pages_run_response"

jq -s --argjson runId "$pages_run_id" --arg commit "$controller_commit" \
  '.[0].jobs += [{id: 1999, run_id: $runId, head_sha: $commit, name: "build", run_attempt: 2, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"}] | map(.total_count += 1) | .[]' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Two executions of one Pages job in the same run attempt were accepted.'
fi
jq -s '.[0].jobs |= map(select(.id != 1999)) | map(.total_count -= 1) | .[]' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"

jq '(.jobs[] | select(.id == 1003).head_sha) = "0000000000000000000000000000000000000000"' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A latest producer job from another commit was accepted.'
fi
jq --arg commit "$controller_commit" '(.jobs[] | select(.id == 1003).head_sha) = $commit' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"

jq '(.jobs[] | select(.id == 2003).run_id) = 999' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A latest producer job from another workflow run was accepted.'
fi
jq --argjson runId "$pages_run_id" '(.jobs[] | select(.id == 2003).run_id) = $runId' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"

jq -s --argjson runId "$pages_run_id" --arg commit "$controller_commit" '
  .[0].jobs += [{id: 3999, run_id: $runId, head_sha: $commit, name: "deploy", run_attempt: 4, started_at: null, completed_at: null, status: "completed", conclusion: "skipped"}] |
  map(.total_count += 1) |
  .[]
' "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A future-attempt Pages job was accepted into the completed run history.'
fi
jq -s '.[0].jobs |= map(select(.id != 3999)) | map(.total_count -= 1) | .[]' \
  "$pages_all_jobs_response" > "$pages_all_jobs_response.changed"
mv "$pages_all_jobs_response.changed" "$pages_all_jobs_response"

jq --argjson artifactId "$release_pages_artifact_id" \
  '(.artifacts[] | select(.id == $artifactId).workflow_run.id) = 999' \
  "$pages_artifacts_response" > "$pages_artifacts_response.changed"
mv "$pages_artifacts_response.changed" "$pages_artifacts_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'Release Pages evidence attributed to another workflow run was accepted.'
fi
write_pages_artifacts_response

jq -n '[{
  id: 8801,
  state: "success",
  environment: "github-pages-controller",
  log_url: "https://github.com/test/strata/actions/runs/999/job/456",
  target_url: "https://github.com/test/strata/actions/runs/999",
  created_at: "2026-08-31T12:01:00Z"
}]' > "$pages_statuses_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A master Pages deployment status linked to another workflow run was accepted.'
fi
write_pages_deployment_responses

jq -n \
  --arg commit "$controller_commit" \
  --argjson deploymentId "$pages_deployment_id" \
  '[
    {
      id: 99,
      ref: "master",
      sha: "0000000000000000000000000000000000000000",
      environment: "github-pages",
      task: "deploy",
      created_at: "2026-08-31T13:00:00Z"
    },
    {
      id: $deploymentId,
      ref: "master",
      sha: $commit,
      environment: "github-pages-controller",
      task: "deploy",
      created_at: "2026-08-31T12:00:00Z"
    }
  ]' > "$global_pages_deployments_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An older controller deployment was accepted while a retired Pages deployment was newer.'
fi
write_pages_deployment_responses

jq -n \
  --arg commit "$controller_commit" \
  --argjson deploymentId "$pages_deployment_id" '
    [
      {
        id: 100,
        ref: "release",
        sha: "0000000000000000000000000000000000000000",
        environment: "release",
        task: "deploy",
        created_at: "2026-08-31T13:00:00Z"
      },
      {
        id: $deploymentId,
        ref: "master",
        sha: $commit,
        environment: "github-pages-controller",
        task: "deploy",
        created_at: "2026-08-31T12:00:00Z"
      }
    ]
  ' > "$global_pages_deployments_response"
[[ "$(bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit")" == "$expected_pages_result" ]] || \
  fail 'An unrelated newer deployment environment hid the exact active Pages deployment.'
write_pages_deployment_responses

jq -n \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
  '[
    {
      id: 7701,
      state: "success",
      environment: "github-pages-controller",
      log_url: ($runUrl + "/job/789"),
      target_url: "https://gh.s7a.dev/strata/",
      created_at: "2026-08-31T12:00:00Z"
    },
    {
      id: 7700,
      state: "pending",
      environment: "github-pages-controller",
      log_url: ($runUrl + "/job/788"),
      target_url: "https://gh.s7a.dev/strata/",
      created_at: "2026-08-31T12:00:00Z"
    }
  ]' > "$pages_statuses_response"
[[ "$(bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit")" == "$expected_pages_result" ]] || \
  fail 'Deployment status ordering did not use the numeric ID to break an equal creation-time tie.'
write_pages_deployment_responses

# Each top-level array is one compact fake API page. Two pages exercise complete --paginate --slurp handling
# without creating more than 100 fixture records.
jq -n '[{
  id: 76,
  ref: "master",
  sha: "0000000000000000000000000000000000000000",
  environment: "github-pages",
  task: "deploy",
  created_at: "2026-08-31T11:00:00Z"
}]' > "$global_pages_deployments_response"
jq -n \
  --argjson deploymentId "$pages_deployment_id" \
  --arg commit "$controller_commit" \
  '[{
    id: $deploymentId,
    ref: "master",
    sha: $commit,
    environment: "github-pages-controller",
    task: "deploy",
    created_at: "2026-08-31T12:00:00Z"
  }]' >> "$global_pages_deployments_response"
jq -n \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
  '[{
    id: 7702,
    state: "pending",
    environment: "github-pages-controller",
    log_url: ($runUrl + "/job/788"),
    target_url: "https://gh.s7a.dev/strata/",
    created_at: "2026-08-31T11:59:00Z"
  }]' > "$pages_statuses_response"
jq -n \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$pages_run_id" \
  '[{
    id: 7701,
    state: "success",
    environment: "github-pages-controller",
    log_url: ($runUrl + "/job/789"),
    target_url: "https://gh.s7a.dev/strata/",
    created_at: "2026-08-31T12:00:00Z"
  }]' >> "$pages_statuses_response"
[[ "$(bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit")" == "$expected_pages_result" ]] || \
  fail 'Pages deployment or status verification ignored a later API response page.'
write_pages_deployment_responses

jq '.can_admins_bypass = true' "$pages_controller_environment_response" > "$pages_controller_environment_response.changed"
mv "$pages_controller_environment_response.changed" "$pages_controller_environment_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An active Pages controller environment that permits administrator bypass was accepted.'
fi
write_pages_environment_responses

jq '.total_count = 2 | .branch_policies += [{name: "v*", type: "tag"}]' \
  "$pages_controller_policies_response" > "$pages_controller_policies_response.changed"
mv "$pages_controller_policies_response.changed" "$pages_controller_policies_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'An active Pages controller environment that permits tag deployments was accepted.'
fi
write_pages_environment_responses

jq '.can_admins_bypass = true' "$retired_pages_environment_response" > "$retired_pages_environment_response.changed"
mv "$retired_pages_environment_response.changed" "$retired_pages_environment_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A retired Pages environment that permits administrator bypass was accepted.'
fi
write_pages_environment_responses

jq '.total_count = 1 | .branch_policies = [{name: "master", type: "branch"}]' \
  "$retired_pages_policies_response" > "$retired_pages_policies_response.changed"
mv "$retired_pages_policies_response.changed" "$retired_pages_policies_response"
if bash "$repository_root/release/verify-pages-deployment-source.sh" \
  "$pages_run_id" v0.1.0 "$replacement_commit" "$pages_run_id" "$controller_commit" >/dev/null 2>&1; then
  fail 'A retired Pages environment that still permits master was accepted.'
fi
write_pages_environment_responses

legacy_pages_run_id=124
legacy_pages_artifact_id=155
legacy_pages_deployment_id=177
legacy_controller_run_id=223
legacy_evidence_artifact_id=156
legacy_controller_artifact_id=157
legacy_controller_deployment_id=277
legacy_repository="$temporary_root/legacy-pages-repository"
legacy_caller="$temporary_root/legacy-pages-caller"
legacy_evidence_site="$temporary_root/legacy-current-evidence-site"
legacy_evidence_zip="$temporary_root/legacy-current-evidence.zip"
legacy_controller_site="$temporary_root/legacy-controller-site"
legacy_controller_archive="$temporary_root/legacy-controller-artifact.tar"
legacy_controller_zip="$temporary_root/legacy-controller-artifact.zip"
legacy_release_site="$temporary_root/legacy-release-site"
legacy_release_archive="$temporary_root/legacy-release-artifact.tar"
legacy_release_zip="$temporary_root/legacy-release-artifact.zip"
legacy_run_response="$temporary_root/legacy-run.json"
legacy_all_jobs_response="$temporary_root/legacy-all-jobs.json"
legacy_artifacts_response="$temporary_root/legacy-artifacts.json"
legacy_statuses_response="$temporary_root/legacy-statuses.json"
legacy_controller_run_response="$temporary_root/legacy-controller-run.json"
legacy_controller_all_jobs_response="$temporary_root/legacy-controller-all-jobs.json"
legacy_controller_artifacts_response="$temporary_root/legacy-controller-artifacts.json"
legacy_controller_statuses_response="$temporary_root/legacy-controller-statuses.json"
legacy_global_deployments_response="$temporary_root/legacy-global-deployments.json"

mkdir -p "$legacy_repository/release" "$legacy_caller"
cp "$repository_root/release/verify-pages-deployment-source.sh" "$legacy_caller/verify-pages-deployment-source.sh"
cp "$repository_root/release/wait-for-pages-source-receipt.sh" "$legacy_caller/wait-for-pages-source-receipt.sh"
[[ "$(find "$legacy_caller" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')" == '2' && \
  ! -e "$legacy_caller/verify-pages-artifact-equivalence.sh" ]] || \
  fail 'The sealed-workflow compatibility caller contains unexpected controller material.'

git -C "$legacy_repository" init --quiet
git -C "$legacy_repository" config user.email test@example.invalid
git -C "$legacy_repository" config user.name 'Strata Test'
printf 'historical release source\n' > "$legacy_repository/source.txt"
git -C "$legacy_repository" add source.txt
git -C "$legacy_repository" -c commit.gpgSign=false commit --quiet -m historical
legacy_release_commit="$(git -C "$legacy_repository" rev-parse HEAD)"
printf 'current release source\n' >> "$legacy_repository/source.txt"
git -C "$legacy_repository" add source.txt
git -C "$legacy_repository" -c commit.gpgSign=false commit --quiet -m current
legacy_evidence_commit="$(git -C "$legacy_repository" rev-parse HEAD)"
cp "$repository_root/release/verify-pages-artifact-equivalence.sh" \
  "$legacy_repository/release/verify-pages-artifact-equivalence.sh"
cat > "$legacy_repository/release/current-controller.json" <<EOF
{
  "schemaVersion": 1,
  "current": {
    "tag": "v0.1.2",
    "commit": "$legacy_evidence_commit",
    "tagObject": "1111111111111111111111111111111111111111",
    "representativeMinecraftVersions": ["1.20"]
  },
  "predecessor": {
    "tag": "v0.1.1",
    "commit": "$legacy_release_commit",
    "tagObject": "0000000000000000000000000000000000000000"
  }
}
EOF
git -C "$legacy_repository" add \
  release/current-controller.json release/verify-pages-artifact-equivalence.sh
git -C "$legacy_repository" update-index --chmod=-x -- \
  release/current-controller.json release/verify-pages-artifact-equivalence.sh
git -C "$legacy_repository" -c commit.gpgSign=false commit --quiet -m controller
legacy_controller_commit="$(git -C "$legacy_repository" rev-parse HEAD)"
git -C "$legacy_repository" --no-replace-objects merge-base --is-ancestor \
  "$legacy_release_commit" "$legacy_evidence_commit" || \
  fail 'The sealed-workflow compatibility fixture does not preserve release ancestry.'

write_legacy_tar_zip() {
  local source_archive="$1"
  local destination_zip="$2"
  "$pages_python" - "$source_archive" "$destination_zip" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(source, arcname="artifact.tar")
PY
}

write_legacy_evidence_artifact() {
  rm -rf -- "$legacy_evidence_site"
  mkdir -p \
    "$legacy_evidence_site/guide" \
    "$legacy_evidence_site/releases/0.1.1/guide" \
    "$legacy_evidence_site/releases/0.1.2/guide"
  printf '{"commit":"%s","revision":"v0.1.2"}\n' "$legacy_evidence_commit" > \
    "$legacy_evidence_site/source-receipt.json"
  printf 'current release page\n' > "$legacy_evidence_site/guide/index.html"
  printf '{"commit":"%s","revision":"v0.1.1"}\n' "$legacy_release_commit" > \
    "$legacy_evidence_site/releases/0.1.1/source-receipt.json"
  printf 'historical release page\n' > "$legacy_evidence_site/releases/0.1.1/guide/index.html"
  printf '{"commit":"%s","revision":"v0.1.2"}\n' "$legacy_evidence_commit" > \
    "$legacy_evidence_site/releases/0.1.2/source-receipt.json"
  printf 'current release page\n' > "$legacy_evidence_site/releases/0.1.2/guide/index.html"
  "$pages_python" - "$legacy_evidence_site" "$legacy_evidence_zip" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source.rglob("*")):
        archive.write(path, arcname=path.relative_to(source).as_posix())
PY
}

write_legacy_controller_artifact() {
  local receipt_commit="$1"
  rm -rf -- "$legacy_controller_site"
  mkdir -p \
    "$legacy_controller_site/releases/0.1.1/guide" \
    "$legacy_controller_site/releases/0.1.2/guide"
  printf '{"commit":"%s","revision":"master"}\n' "$receipt_commit" > \
    "$legacy_controller_site/source-receipt.json"
  printf '{"commit":"%s","revision":"v0.1.1"}\n' "$legacy_release_commit" > \
    "$legacy_controller_site/releases/0.1.1/source-receipt.json"
  printf 'historical release page\n' > "$legacy_controller_site/releases/0.1.1/guide/index.html"
  printf '{"commit":"%s","revision":"v0.1.2"}\n' "$legacy_evidence_commit" > \
    "$legacy_controller_site/releases/0.1.2/source-receipt.json"
  printf 'current release page\n' > "$legacy_controller_site/releases/0.1.2/guide/index.html"
  tar -cf "$legacy_controller_archive" -C "$legacy_controller_site" .
  write_legacy_tar_zip "$legacy_controller_archive" "$legacy_controller_zip"
}

write_legacy_release_artifact() {
  local root_content="${1:-historical release page}"
  local subtree_content="${2:-historical release page}"
  rm -rf -- "$legacy_release_site"
  mkdir -p "$legacy_release_site/guide" "$legacy_release_site/releases/0.1.1/guide"
  printf '{"commit":"%s","revision":"v0.1.1"}\n' "$legacy_release_commit" > \
    "$legacy_release_site/source-receipt.json"
  printf '%s\n' "$root_content" > "$legacy_release_site/guide/index.html"
  printf '{"commit":"%s","revision":"v0.1.1"}\n' "$legacy_release_commit" > \
    "$legacy_release_site/releases/0.1.1/source-receipt.json"
  printf '%s\n' "$subtree_content" > "$legacy_release_site/releases/0.1.1/guide/index.html"
  tar -cf "$legacy_release_archive" -C "$legacy_release_site" .
  write_legacy_tar_zip "$legacy_release_archive" "$legacy_release_zip"
}

write_legacy_run_responses() {
  local expected_controller="$1"
  jq -n \
    --argjson runId "$legacy_pages_run_id" \
    --arg commit "$legacy_release_commit" '{
      id: $runId,
      path: ".github/workflows/pages.yml",
      event: "push",
      head_branch: "v0.1.1",
      head_sha: $commit,
      run_attempt: 3,
      status: "completed",
      conclusion: "success"
    }' > "$legacy_run_response"
  jq -n \
    --argjson runId "$legacy_controller_run_id" \
    --arg commit "$expected_controller" '{
      id: $runId,
      path: ".github/workflows/pages.yml",
      event: "push",
      head_branch: "master",
      head_sha: $commit,
      run_attempt: 3,
      status: "completed",
      conclusion: "success"
    }' > "$legacy_controller_run_response"
}

write_legacy_job_responses() {
  jq -n '{total_count: 6, jobs: [
    {id: 4101, name: "build", run_attempt: 1, started_at: "2026-08-31T08:50:00Z", completed_at: "2026-08-31T09:10:00Z", status: "completed", conclusion: "failure"},
    {id: 4301, name: "deploy", run_attempt: 1, started_at: null, completed_at: null, status: "completed", conclusion: "skipped"},
    {id: 4102, name: "build", run_attempt: 2, started_at: "2026-08-31T09:50:00Z", completed_at: "2026-08-31T10:10:00Z", status: "completed", conclusion: "success"},
    {id: 4302, name: "deploy", run_attempt: 2, started_at: "2026-08-31T10:15:00Z", completed_at: "2026-08-31T10:20:00Z", status: "completed", conclusion: "failure"},
    {id: 4103, name: "build", run_attempt: 3, started_at: "2026-08-31T09:50:00Z", completed_at: "2026-08-31T10:10:00Z", status: "completed", conclusion: "success"},
    {id: 4303, name: "deploy", run_attempt: 3, started_at: "2026-08-31T10:30:00Z", completed_at: "2026-08-31T10:40:00Z", status: "completed", conclusion: "success"}
  ]}' > "$legacy_all_jobs_response"
  jq -n '{total_count: 9, jobs: [
    {id: 5101, name: "build", run_attempt: 1, started_at: "2026-08-31T10:50:00Z", completed_at: "2026-08-31T11:10:00Z", status: "completed", conclusion: "failure"},
    {id: 5201, name: "release-evidence", run_attempt: 1, started_at: "2026-08-31T10:50:00Z", completed_at: "2026-08-31T11:10:00Z", status: "completed", conclusion: "failure"},
    {id: 5301, name: "deploy", run_attempt: 1, started_at: "2026-08-31T11:20:00Z", completed_at: "2026-08-31T11:15:00Z", status: "completed", conclusion: "skipped"},
    {id: 5102, name: "build", run_attempt: 2, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 5202, name: "release-evidence", run_attempt: 2, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 5302, name: "deploy", run_attempt: 2, started_at: "2026-08-31T12:15:00Z", completed_at: "2026-08-31T12:20:00Z", status: "completed", conclusion: "failure"},
    {id: 5103, name: "build", run_attempt: 3, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 5203, name: "release-evidence", run_attempt: 3, started_at: "2026-08-31T11:50:00Z", completed_at: "2026-08-31T12:10:00Z", status: "completed", conclusion: "success"},
    {id: 5303, name: "deploy", run_attempt: 3, started_at: "2026-08-31T12:30:00Z", completed_at: "2026-08-31T12:40:00Z", status: "completed", conclusion: "success"}
  ]}' > "$legacy_controller_all_jobs_response"
  jq --argjson runId "$legacy_pages_run_id" --arg commit "$legacy_release_commit" \
    '.jobs |= map(. + {run_id: $runId, head_sha: $commit})' \
    "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
  mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
  jq --argjson runId "$legacy_controller_run_id" --arg commit "$legacy_controller_commit" \
    '.jobs |= map(. + {run_id: $runId, head_sha: $commit})' \
    "$legacy_controller_all_jobs_response" > "$legacy_controller_all_jobs_response.changed"
  mv "$legacy_controller_all_jobs_response.changed" "$legacy_controller_all_jobs_response"
  split_job_response_pages "$legacy_all_jobs_response"
  split_job_response_pages "$legacy_controller_all_jobs_response"
}

write_legacy_artifact_responses() {
  local expected_controller="$1"
  local release_size
  local release_digest
  local evidence_size
  local evidence_digest
  local controller_size
  local controller_digest
  release_size="$(stat -c '%s' "$legacy_release_zip")"
  release_digest="sha256:$(sha256sum "$legacy_release_zip" | cut -d ' ' -f 1)"
  evidence_size="$(stat -c '%s' "$legacy_evidence_zip")"
  evidence_digest="sha256:$(sha256sum "$legacy_evidence_zip" | cut -d ' ' -f 1)"
  controller_size="$(stat -c '%s' "$legacy_controller_zip")"
  controller_digest="sha256:$(sha256sum "$legacy_controller_zip" | cut -d ' ' -f 1)"
  jq -n \
    --argjson artifactId "$legacy_pages_artifact_id" \
    --argjson artifactSize "$release_size" \
    --arg artifactDigest "$release_digest" \
    --argjson runId "$legacy_pages_run_id" \
    --arg commit "$legacy_release_commit" '{
      total_count: 2,
      artifacts: [
        {
          id: ($artifactId - 10),
          name: "github-pages",
          size_in_bytes: $artifactSize,
          digest: $artifactDigest,
          expired: false,
          created_at: "2026-08-31T09:00:00Z",
          workflow_run: {id: $runId, head_branch: "v0.1.1", head_sha: $commit}
        },
        {
          id: $artifactId,
          name: "github-pages",
          size_in_bytes: $artifactSize,
          digest: $artifactDigest,
          expired: false,
          created_at: "2026-08-31T10:00:00Z",
          workflow_run: {id: $runId, head_branch: "v0.1.1", head_sha: $commit}
        }
      ]
    }' > "$legacy_artifacts_response"
  jq -n \
    --argjson evidenceArtifactId "$legacy_evidence_artifact_id" \
    --argjson evidenceSize "$evidence_size" \
    --arg evidenceDigest "$evidence_digest" \
    --argjson controllerArtifactId "$legacy_controller_artifact_id" \
    --argjson controllerSize "$controller_size" \
    --arg controllerDigest "$controller_digest" \
    --argjson runId "$legacy_controller_run_id" \
    --arg commit "$expected_controller" '{
      total_count: 4,
      artifacts: [
        {
          id: ($evidenceArtifactId - 10),
          name: ("release-pages-evidence-" + ($runId | tostring) + "-release-evidence-1"),
          size_in_bytes: $evidenceSize,
          digest: $evidenceDigest,
          expired: false,
          created_at: "2026-08-31T11:00:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: ($controllerArtifactId - 10),
          name: ("github-pages-" + ($runId | tostring) + "-build-1"),
          size_in_bytes: $controllerSize,
          digest: $controllerDigest,
          expired: false,
          created_at: "2026-08-31T11:01:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: $evidenceArtifactId,
          name: ("release-pages-evidence-" + ($runId | tostring) + "-release-evidence-2"),
          size_in_bytes: $evidenceSize,
          digest: $evidenceDigest,
          expired: false,
          created_at: "2026-08-31T12:00:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        },
        {
          id: $controllerArtifactId,
          name: ("github-pages-" + ($runId | tostring) + "-build-2"),
          size_in_bytes: $controllerSize,
          digest: $controllerDigest,
          expired: false,
          created_at: "2026-08-31T12:01:00Z",
          workflow_run: {id: $runId, head_branch: "master", head_sha: $commit}
        }
      ]
    }' > "$legacy_controller_artifacts_response"
}

write_legacy_deployment_responses() {
  local expected_controller="$1"
  jq -n \
    --argjson legacyId "$legacy_pages_deployment_id" \
    --arg releaseCommit "$legacy_release_commit" \
    --argjson controllerId "$legacy_controller_deployment_id" \
    --arg controllerCommit "$expected_controller" '[
      {
        id: $legacyId,
        ref: "v0.1.1",
        sha: $releaseCommit,
        environment: "github-pages",
        task: "deploy",
        created_at: "2026-08-31T10:00:00Z"
      },
      {
        id: $controllerId,
        ref: "master",
        sha: $controllerCommit,
        environment: "github-pages-controller",
        task: "deploy",
        created_at: "2026-08-31T12:00:00Z"
      }
    ]' > "$legacy_global_deployments_response"
}

write_legacy_status_responses() {
  jq -n \
    --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$legacy_pages_run_id" '[
      {
        id: 17701,
        state: "success",
        environment: "github-pages",
        log_url: ($runUrl + "/job/456"),
        target_url: "https://gh.s7a.dev/strata/",
        created_at: "2026-08-31T10:01:00Z"
      },
      {
        id: 17702,
        state: "inactive",
        environment: "github-pages",
        log_url: null,
        target_url: "https://gh.s7a.dev/strata/",
        created_at: "2026-08-31T11:00:00Z"
      }
    ]' > "$legacy_statuses_response"
  jq -n \
    --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$legacy_controller_run_id" '[{
      id: 27701,
      state: "success",
      environment: "github-pages-controller",
      log_url: ($runUrl + "/job/789"),
      target_url: "https://gh.s7a.dev/strata/",
      created_at: "2026-08-31T12:01:00Z"
    }]' > "$legacy_controller_statuses_response"
}

write_legacy_controller_identity() {
  local expected_controller="$1"
  write_legacy_controller_artifact "$expected_controller"
  write_legacy_run_responses "$expected_controller"
  write_legacy_artifact_responses "$expected_controller"
  write_legacy_deployment_responses "$expected_controller"
}

run_legacy_pages_verifier() {
  local expected_controller="$1"
  (
    cd "$legacy_repository"
    bash "$legacy_caller/verify-pages-deployment-source.sh" \
      "$legacy_pages_run_id" v0.1.1 "$legacy_release_commit" \
      "$legacy_controller_run_id" "$expected_controller"
  )
}

reject_legacy_pages_verification() {
  local expected_controller="$1"
  local message="$2"
  if run_legacy_pages_verifier "$expected_controller" >/dev/null 2>&1; then
    fail "$message"
  fi
}

export FAKE_LEGACY_PAGES_RUN_ID="$legacy_pages_run_id"
export FAKE_LEGACY_PAGES_ARTIFACT_ID="$legacy_pages_artifact_id"
export FAKE_LEGACY_PAGES_DEPLOYMENT_ID="$legacy_pages_deployment_id"
export FAKE_LEGACY_PAGES_ARTIFACT_ZIP="$legacy_release_zip"
export FAKE_LEGACY_PAGES_RUN_RESPONSE="$legacy_run_response"
export FAKE_LEGACY_PAGES_ALL_JOBS_RESPONSE="$legacy_all_jobs_response"
export FAKE_LEGACY_PAGES_ARTIFACTS_RESPONSE="$legacy_artifacts_response"
export FAKE_LEGACY_PAGES_STATUSES_RESPONSE="$legacy_statuses_response"
export FAKE_PAGES_RUN_ID="$legacy_controller_run_id"
export FAKE_RELEASE_PAGES_ARTIFACT_ID="$legacy_evidence_artifact_id"
export FAKE_RELEASE_PAGES_ARTIFACT_ZIP="$legacy_evidence_zip"
export FAKE_CONTROLLER_PAGES_ARTIFACT_ID="$legacy_controller_artifact_id"
export FAKE_CONTROLLER_PAGES_ARTIFACT_ZIP="$legacy_controller_zip"
export FAKE_PAGES_DEPLOYMENT_ID="$legacy_controller_deployment_id"
export FAKE_PAGES_RUN_RESPONSE="$legacy_controller_run_response"
export FAKE_PAGES_ALL_JOBS_RESPONSE="$legacy_controller_all_jobs_response"
export FAKE_PAGES_ARTIFACTS_RESPONSE="$legacy_controller_artifacts_response"
export FAKE_PAGES_STATUSES_RESPONSE="$legacy_controller_statuses_response"
export FAKE_GLOBAL_PAGES_DEPLOYMENTS_RESPONSE="$legacy_global_deployments_response"

write_legacy_evidence_artifact
write_legacy_release_artifact
write_legacy_job_responses
write_legacy_status_responses
write_legacy_controller_identity "$legacy_controller_commit"
split_artifact_response_pages "$legacy_artifacts_response"
split_artifact_response_pages "$legacy_controller_artifacts_response"
legacy_pages_result="$(run_legacy_pages_verifier "$legacy_controller_commit")"
expected_legacy_pages_result="$legacy_pages_run_id $legacy_pages_artifact_id $legacy_pages_deployment_id"
expected_legacy_pages_result+=" $legacy_controller_run_id $legacy_controller_artifact_id $legacy_controller_deployment_id"
[[ "$legacy_pages_result" == "$expected_legacy_pages_result" ]] || \
  fail 'Paginated rerun artifacts did not preserve the exact sealed-workflow compatibility record.'
write_legacy_artifact_responses "$legacy_controller_commit"
[[ ! -e "$legacy_caller/verify-pages-artifact-equivalence.sh" ]] || \
  fail 'The materialized controller comparator escaped its private verification directory.'

if (
  cd "$legacy_repository"
  bash "$legacy_caller/verify-pages-deployment-source.sh" \
    "$legacy_pages_run_id" v0.1.1 "$legacy_release_commit" \
    "$legacy_controller_run_id" "$legacy_controller_commit" v0.1.2 >/dev/null 2>&1
); then
  fail 'Pages deployment verification accepted six identity arguments.'
fi
if (
  cd "$legacy_repository"
  bash "$legacy_caller/verify-pages-deployment-source.sh" \
    "$legacy_pages_run_id" v0.1.1 "$legacy_release_commit" \
    "$legacy_controller_run_id" "$legacy_controller_commit" \
    v0.1.2 "$legacy_evidence_commit" unexpected >/dev/null 2>&1
); then
  fail 'Pages deployment verification accepted eight identity arguments.'
fi

jq '.head_branch = "v9.9.9"' "$legacy_run_response" > "$legacy_run_response.changed"
mv "$legacy_run_response.changed" "$legacy_run_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A sealed legacy Pages run from another tag was accepted.'
write_legacy_run_responses "$legacy_controller_commit"

jq '.head_sha = "0000000000000000000000000000000000000000"' \
  "$legacy_run_response" > "$legacy_run_response.changed"
mv "$legacy_run_response.changed" "$legacy_run_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A sealed legacy Pages run from another commit was accepted.'
write_legacy_run_responses "$legacy_controller_commit"

jq '(.jobs[] | select(.id == 4303).conclusion) = "failure"' \
  "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A failed sealed legacy Pages job was accepted.'
write_legacy_job_responses

jq '(.jobs[] | select(.id == 4103).head_sha) = "0000000000000000000000000000000000000000"' \
  "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A sealed legacy Pages producer job attributed to another commit was accepted.'
write_legacy_job_responses

jq '(.jobs[] | select(.id == 4103).run_id) = 999999' \
  "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A sealed legacy Pages producer job attributed to another run was accepted.'
write_legacy_job_responses

jq '(.jobs[] | select(.id == 4103).run_attempt) = 4' \
  "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A future-attempt sealed legacy Pages producer job was accepted.'
write_legacy_job_responses

jq -s --argjson runId "$legacy_pages_run_id" --arg commit "$legacy_release_commit" \
  '.[0].jobs += [{id: 4399, run_id: $runId, head_sha: $commit, name: "deploy", run_attempt: 3, started_at: "2026-08-31T10:30:00Z", completed_at: "2026-08-31T10:40:00Z", status: "completed", conclusion: "success"}] | map(.total_count += 1) | .[]' \
  "$legacy_all_jobs_response" > "$legacy_all_jobs_response.changed"
mv "$legacy_all_jobs_response.changed" "$legacy_all_jobs_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A malformed sealed legacy Pages job inventory was accepted.'
write_legacy_job_responses

jq '.artifacts[0].workflow_run.head_branch = "v9.9.9"' \
  "$legacy_artifacts_response" > "$legacy_artifacts_response.changed"
mv "$legacy_artifacts_response.changed" "$legacy_artifacts_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages artifact attributed to another tag was accepted.'
write_legacy_artifact_responses "$legacy_controller_commit"

jq --argjson artifactId "$legacy_pages_artifact_id" \
  '(.artifacts[] | select(.id == $artifactId).workflow_run.head_sha) = "0000000000000000000000000000000000000000"' \
  "$legacy_artifacts_response" > "$legacy_artifacts_response.changed"
mv "$legacy_artifacts_response.changed" "$legacy_artifacts_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages artifact attributed to another commit was accepted.'
write_legacy_artifact_responses "$legacy_controller_commit"

jq --argjson artifactId "$legacy_pages_artifact_id" \
  '(.artifacts[] | select(.id == $artifactId)) as $selected |
   .artifacts += [($selected | .id = ($artifactId + 1) | .created_at = "2026-08-31T10:01:00Z")] |
   .total_count += 1' \
  "$legacy_artifacts_response" > "$legacy_artifacts_response.changed"
mv "$legacy_artifacts_response.changed" "$legacy_artifacts_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'Two fixed-name legacy Pages artifacts inside one latest logical build window were accepted.'
write_legacy_artifact_responses "$legacy_controller_commit"

write_legacy_release_artifact 'tampered historical root' 'historical release page'
write_legacy_artifact_responses "$legacy_controller_commit"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages artifact with a tampered release root was accepted.'
write_legacy_release_artifact 'historical release page' 'tampered historical subtree'
write_legacy_artifact_responses "$legacy_controller_commit"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages artifact with a tampered immutable subtree was accepted.'
write_legacy_release_artifact
write_legacy_artifact_responses "$legacy_controller_commit"

jq '(.[] | select(.id == 177).ref) = "v9.9.9"' \
  "$legacy_global_deployments_response" > "$legacy_global_deployments_response.changed"
mv "$legacy_global_deployments_response.changed" "$legacy_global_deployments_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages record without an exact historical deployment was accepted.'
write_legacy_deployment_responses "$legacy_controller_commit"

jq '(.[] | select(.state == "success") | .log_url) =
  "https://github.com/test/strata/actions/runs/999/job/456"' \
  "$legacy_statuses_response" > "$legacy_statuses_response.changed"
mv "$legacy_statuses_response.changed" "$legacy_statuses_response"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'A legacy Pages deployment with no status linked to its exact run was accepted.'
write_legacy_status_responses

git -C "$legacy_repository" checkout --quiet --detach "$legacy_controller_commit"
jq '.current.tag = "latest"' \
  "$legacy_repository/release/current-controller.json" > \
  "$legacy_repository/release/current-controller.json.changed"
mv "$legacy_repository/release/current-controller.json.changed" \
  "$legacy_repository/release/current-controller.json"
git -C "$legacy_repository" add release/current-controller.json
git -C "$legacy_repository" -c commit.gpgSign=false commit --quiet -m invalid-metadata
invalid_metadata_controller_commit="$(git -C "$legacy_repository" rev-parse HEAD)"
write_legacy_controller_identity "$invalid_metadata_controller_commit"
reject_legacy_pages_verification "$invalid_metadata_controller_commit" \
  'Invalid exact controller metadata was accepted for a sealed legacy Pages run.'

git -C "$legacy_repository" checkout --quiet --detach "$legacy_controller_commit"
git -C "$legacy_repository" rm --quiet release/verify-pages-artifact-equivalence.sh
git -C "$legacy_repository" -c commit.gpgSign=false commit --quiet -m missing-comparator
missing_comparator_controller_commit="$(git -C "$legacy_repository" rev-parse HEAD)"
write_legacy_controller_identity "$missing_comparator_controller_commit"
reject_legacy_pages_verification "$missing_comparator_controller_commit" \
  'A controller commit without the Pages artifact comparator was accepted.'

git -C "$legacy_repository" checkout --quiet --detach "$legacy_controller_commit"
write_legacy_controller_identity "$legacy_controller_commit"
printf '#!/usr/bin/env bash\nexit 0\n' > "$legacy_caller/verify-pages-artifact-equivalence.sh"
reject_legacy_pages_verification "$legacy_controller_commit" \
  'An adjacent Pages artifact comparator differing from the exact controller blob was accepted.'
rm -f -- "$legacy_caller/verify-pages-artifact-equivalence.sh"

write_legacy_artifact_responses "$legacy_controller_commit"
git -C "$legacy_repository" --no-replace-objects cat-file blob \
  "$legacy_controller_commit:release/verify-pages-artifact-equivalence.sh" > \
  "$legacy_caller/verify-pages-artifact-equivalence.sh"
same_run_predecessor_result="$(
  cd "$legacy_repository"
  bash "$legacy_caller/verify-pages-deployment-source.sh" \
    "$legacy_controller_run_id" v0.1.1 "$legacy_release_commit" \
    "$legacy_controller_run_id" "$legacy_controller_commit" \
    v0.1.2 "$legacy_evidence_commit"
)"
expected_same_run_predecessor_result="$legacy_controller_run_id $legacy_evidence_artifact_id"
expected_same_run_predecessor_result+=" $legacy_controller_deployment_id $legacy_controller_run_id"
expected_same_run_predecessor_result+=" $legacy_controller_artifact_id $legacy_controller_deployment_id"
[[ "$same_run_predecessor_result" == "$expected_same_run_predecessor_result" ]] || \
  fail 'Generalized final Pages verification rejected the exact same-run predecessor evidence.'
rm -f -- "$legacy_caller/verify-pages-artifact-equivalence.sh"

export FAKE_RECEIPT_RELEASE_TAG=v0.1.0
export FAKE_RECEIPT_RELEASE_COMMIT="$replacement_commit"
export FAKE_RECEIPT_CONTROLLER_COMMIT="$controller_commit"
export FAKE_CURL_MODE=stale-then-exact
export FAKE_CURL_STATE="$temporary_root/curl-state"
export FAKE_CURL_LOG="$temporary_root/curl-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "3" ]] || fail 'Pages receipt polling did not retry a stale controller HTTP 200 response.'

export FAKE_CURL_MODE=release-stale-then-exact
export FAKE_CURL_STATE="$temporary_root/release-curl-state"
export FAKE_CURL_LOG="$temporary_root/release-curl-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "3" ]] || fail 'Pages receipt polling did not retry a stale immutable release response.'

export FAKE_CURL_MODE=independent-fresh-windows
export FAKE_CURL_STATE="$temporary_root/independent-fresh-state"
export FAKE_CURL_LOG="$temporary_root/independent-fresh-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "4" ]] || \
  fail 'Pages receipt polling required the independently cached receipts to be fresh simultaneously.'
[[ "$(grep --fixed-strings -c 'https://gh.s7a.dev/strata/source-receipt.json' "$FAKE_CURL_LOG")" == '1' ]] || \
  fail 'A fresh controller receipt was needlessly polled again while waiting for the immutable release receipt.'
[[ "$(grep --fixed-strings -c 'https://gh.s7a.dev/strata/releases/0.1.0/source-receipt.json' "$FAKE_CURL_LOG")" == '3' ]] || \
  fail 'The independently stale immutable release receipt was not retried to freshness.'

export FAKE_CURL_MODE=cached-exact-then-fresh
export FAKE_CURL_STATE="$temporary_root/cached-state"
export FAKE_CURL_LOG="$temporary_root/cached-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "3" ]] || fail 'A matching but old CDN response was accepted before its Age returned near zero.'

export FAKE_CURL_MODE=redirect-old-final-no-age
export FAKE_CURL_STATE="$temporary_root/redirect-state"
export FAKE_CURL_LOG="$temporary_root/redirect-log"
bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 30 0 >/dev/null
[[ "$(< "$FAKE_CURL_STATE")" == "2" ]] || \
  fail 'A redirect Age leaked into the final header block and delayed a current public Pages receipt.'

grep --fixed-strings 'timeout_seconds="${4:-900}"' \
  "$repository_root/release/wait-for-pages-source-receipt.sh" >/dev/null || \
  fail 'The public Pages polling default does not outlive the observed 600-second CDN TTL.'

export FAKE_CURL_MODE=always-stale
export FAKE_CURL_STATE="$temporary_root/stale-state"
export FAKE_CURL_LOG="$temporary_root/stale-log"
if bash "$repository_root/release/wait-for-pages-source-receipt.sh" \
  v0.1.0 "$replacement_commit" "$controller_commit" 1 0 >/dev/null 2>&1; then
  fail 'Permanently stale Pages evidence was accepted.'
fi

workflow="$repository_root/.github/workflows/release.yml"
sealed_workflow_blob='f4cf27e824a9ae9f2de3f03be94ad5df0c168f93'
[[ "$(git hash-object "$workflow")" == "$sealed_workflow_blob" ]] || \
  fail 'The sealed v0.1.0 release workflow changed.'
release_body_filter='(.body // "") | gsub("\r\n"; "\n") | if contains("\r") then error("release body contains a lone carriage return") else @base64 end'
[[ "$(grep --fixed-strings -c "$release_body_filter" "$repository_root/release/github-release-preflight.sh")" == '1' ]] || \
  fail 'The REST GitHub Release preflight does not use the byte-stable CRLF-only body policy.'
[[ "$(grep --fixed-strings -c "$release_body_filter" "$workflow")" == '3' ]] || \
  fail 'Every inline GitHub Release body comparison must use the CRLF-only body policy.'
[[ "$(grep --fixed-strings -c 'base64 --decode > "$actual_body_file"' "$workflow")" == '3' ]] || \
  fail 'Every inline GitHub Release body comparison must decode byte-stable normalized content.'
github_release_preflight_step="$temporary_root/github-release-preflight-step.yml"
sed -n '/name: Preflight GitHub Release without mutation$/,/name: Cryptographically verify all staged Maven Local signatures$/p' \
  "$workflow" > "$github_release_preflight_step"
grep --fixed-strings 'github_release_tool_directory="$(mktemp -d "$RUNNER_TEMP/strata-github-release-tools.XXXXXX")"' \
  "$github_release_preflight_step" >/dev/null || \
  fail 'The protected GitHub Release preflight does not create a fresh controller tool directory.'
grep --fixed-strings 'record="$(git ls-tree "$GITHUB_SHA" -- "$source_path")"' \
  "$github_release_preflight_step" >/dev/null || \
  fail 'The protected GitHub Release preflight is not bound to the exact controller SHA.'
grep --fixed-strings 'load_controller_script release/github-release-preflight.sh "$github_release_preflight"' \
  "$github_release_preflight_step" >/dev/null || \
  fail 'The protected GitHub Release preflight does not load the controller script.'
grep --fixed-strings 'bash "$github_release_preflight" build/release/github' \
  "$github_release_preflight_step" >/dev/null || \
  fail 'The protected GitHub Release preflight does not execute the loaded controller script.'
grep --fixed-strings 'trap '\''rm -rf -- "$github_release_tool_directory"'\'' EXIT' \
  "$github_release_preflight_step" >/dev/null || \
  fail 'The protected GitHub Release preflight does not clean its exact controller tool directory.'
if grep --fixed-strings 'bash release/github-release-preflight.sh' "$github_release_preflight_step" >/dev/null; then
  fail 'The protected GitHub Release preflight still executes the immutable tag copy.'
fi
[[ "$(grep --fixed-strings -c 'select(length == 1)' "$workflow")" == '4' ]] || \
  fail 'Release and final verification do not reject ambiguous release or controller Pages runs.'
[[ "$(grep --fixed-strings -c 'pages_record_after_poll="$(bash' "$workflow")" == '3' ]] || \
  fail 'Every release provenance gate must repeat the API verification after public receipt polling.'
[[ "$(grep --fixed-strings -c 'load_controller_script release/wait-for-pages-source-receipt.sh "$pages_waiter"' "$workflow")" == '3' ]] || \
  fail 'Every protected Pages phase must load the public waiter from the exact controller blob.'
[[ "$(grep --fixed-strings -c 'load_controller_script release/verify-pages-deployment-source.sh "$pages_verifier"' "$workflow")" == '3' ]] || \
  fail 'Every protected Pages phase must load the verifier from the exact controller blob.'
[[ "$(grep --fixed-strings -c 'pages_tool_directory="$(mktemp -d "$RUNNER_TEMP/strata-pages-tools.XXXXXX")"' "$workflow")" == '3' ]] || \
  fail 'Every controller Pages tool load must use a fresh unpredictable temporary directory.'
[[ "$(grep --fixed-strings -c '[[ ! -e "$destination" && ! -L "$destination" ]]' "$workflow")" == '4' ]] || \
  fail 'Controller tool destinations do not reject existing files and dangling symbolic links.'
if grep --fixed-strings 'pages_waiter="$RUNNER_TEMP/' "$workflow" >/dev/null || \
  grep --fixed-strings 'pages_verifier="$RUNNER_TEMP/' "$workflow" >/dev/null; then
  fail 'A later Pages phase reuses a predictable controller tool path from an earlier step.'
fi
public_pages_step="$temporary_root/public-pages-step.yml"
sed -n '/name: Verify public Pages, tagged Skill source, and preview$/,/name: Verify token-free public Skills CLI installation$/p' \
  "$workflow" > "$public_pages_step"
grep --fixed-strings 'pages_tool_directory="$(mktemp -d "$RUNNER_TEMP/strata-pages-tools.XXXXXX")"' \
  "$public_pages_step" >/dev/null || fail 'Final public Pages verification does not create a fresh controller tool directory.'
grep --fixed-strings 'load_controller_script release/verify-pages-deployment-source.sh "$pages_verifier"' \
  "$public_pages_step" >/dev/null || fail 'Final public Pages verification does not reload the controller verifier.'
grep --fixed-strings 'load_controller_script release/wait-for-pages-source-receipt.sh "$pages_waiter"' \
  "$public_pages_step" >/dev/null || fail 'Final public Pages verification does not reload the controller waiter.'
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

reviewed_overlay_controller='b85f1b4f470357c5d1ff8410d20b7e316b50e316'
for overlay_manifest in \
  "$repository_root/release/controller-overlays/v0.1.0-modrinth-generic-draft.json" \
  "$repository_root/release/controller-overlays/v0.1.0-central-signature-checksums.json"; do
  [[ "$(jq -er '.controllerCommit' "$overlay_manifest")" == "$reviewed_overlay_controller" ]] || \
    fail "A v0.1.0 controller overlay does not pin the reviewed controller commit: $overlay_manifest"
done
for overlay_runner in \
  "$repository_root/release/run-controller-overlay.sh" \
  "$repository_root/release/run-central-controller-overlay.sh"; do
  grep --fixed-strings '[[ "$(git rev-parse origin/master)" == "$controller_commit" ]]' "$overlay_runner" >/dev/null || \
    fail "A v0.1.0 controller overlay runner does not require the active current controller: $overlay_runner"
  grep --fixed-strings 'git merge-base --is-ancestor "$reviewed_controller_commit" "$controller_commit"' "$overlay_runner" >/dev/null || \
    fail "A v0.1.0 controller overlay runner does not bind the reviewed controller to current master history: $overlay_runner"
  grep --fixed-strings 'git rev-parse "$reviewed_controller_commit:$path"' "$overlay_runner" >/dev/null || \
    fail "A v0.1.0 controller overlay runner does not read reviewed blobs from the manifest-pinned controller: $overlay_runner"
done

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
central_overlay_runner="$repository_root/release/run-central-controller-overlay.sh"
central_sealed_read_branch="$(
  awk '
    index($0, "$public_operation") && index($0, "$operation") && index($0, "task-graph-test") { capture = 1 }
    capture { print }
    capture && $0 == "fi" { exit }
  ' "$central_overlay_runner"
)"
[[ -n "$central_sealed_read_branch" ]] || \
  fail 'Sealed Central reads and their task-graph regression do not share one dependency-exclusion branch.'
[[ "$(printf '%s\n' "$central_sealed_read_branch" | grep --fixed-strings -c -- '-x verifyPublishedConsumer')" == '1' \
  && "$(grep --fixed-strings -c -- '-x verifyPublishedConsumer' "$central_overlay_runner")" == '1' ]] || \
  fail 'The Central overlay must exclude Maven-local regeneration through one shared sealed-read path.'
[[ "$(printf '%s\n' "$central_sealed_read_branch" | grep --fixed-strings -c -- '-x modrinthReleaseManifest')" == '1' \
  && "$(grep --fixed-strings -c -- '-x modrinthReleaseManifest' "$central_overlay_runner")" == '1' ]] || \
  fail 'The Central overlay must exclude canonical artifact regeneration through one shared sealed-read path.'
release_evidence_line="$(grep -n -m 1 --fixed-strings '      - name: Build deterministic release manifest and verify published-coordinate consumer' "$workflow" | cut -d: -f1)"
central_preflight_line="$(grep -n -m 1 --fixed-strings '      - name: Require the sealed Maven Central release' "$workflow" | cut -d: -f1)"
central_verify_line="$(grep -n -m 1 --fixed-strings '      - name: Verify Central and build the canonical GitHub bundle' "$workflow" | cut -d: -f1)"
central_signature_line="$(grep -n -m 1 --fixed-strings '      - name: Cryptographically verify canonical Central signatures' "$workflow" | cut -d: -f1)"
modrinth_stage_line="$(grep -n -m 1 --fixed-strings '      - name: Stage listed Modrinth versions' "$workflow" | cut -d: -f1)"
final_evidence_line="$(grep -n -m 1 --fixed-strings '      - name: Rebuild deterministic release evidence' "$workflow" | cut -d: -f1)"
final_central_verify_line="$(grep -n -m 1 --fixed-strings '      - name: Verify the sealed Maven Central release remains exact' "$workflow" | cut -d: -f1)"
[[ -n "$release_evidence_line" && -n "$central_preflight_line" && -n "$central_verify_line" \
  && -n "$central_signature_line" && -n "$modrinth_stage_line" && -n "$final_evidence_line" \
  && -n "$final_central_verify_line" ]] || \
  fail 'The sealed Central verification or Modrinth staging boundary is missing.'
(( release_evidence_line < central_preflight_line && central_preflight_line < central_verify_line )) || \
  fail 'The release must build signed local evidence before excluding its generation from sealed Central reads.'
(( central_verify_line < central_signature_line && central_signature_line < modrinth_stage_line )) || \
  fail 'Canonical Central signatures must be verified before Modrinth staging can mutate remote state.'
(( final_evidence_line < final_central_verify_line )) || \
  fail 'Final verification must rebuild signed local evidence before excluding its generation from the sealed Central read.'
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
bash "$repository_root/release/tests/verify-github-release-preflight.sh" >/dev/null

echo 'Release source guards passed.'

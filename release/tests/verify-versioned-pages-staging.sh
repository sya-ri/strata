#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
pages_workflow="$repository_root/.github/workflows/pages.yml"
pages_release_verifier="$repository_root/release/verify-pages-release-source.sh"
pages_artifact_verifier="$repository_root/release/verify-pages-artifact-equivalence.sh"
pages_deployment_verifier="$repository_root/release/verify-pages-deployment-source.sh"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
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

[[ "$(grep -E -c '^git -C "\$fixture" config commit\.gpgsign false$' "${BASH_SOURCE[0]}")" == '1' ]] || \
  fail 'The Pages fixture does not disable inherited commit signing exactly once.'
[[ "$(grep -E -c '^git -C "\$fixture" config tag\.gpgsign false$' "${BASH_SOURCE[0]}")" == '1' ]] || \
  fail 'The Pages fixture does not disable inherited tag signing exactly once.'
require_source_before \
  '^git -C "\$fixture" config commit\.gpgsign false$' \
  '^git -C "\$fixture" commit ' \
  'Pages commits'
require_source_before \
  '^git -C "\$fixture" config tag\.gpgsign false$' \
  '^git -C "\$fixture" tag ' \
  'Pages tags'

if grep --fixed-strings '    paths:' "$pages_workflow" >/dev/null; then
  fail 'Pages path filters do not cover the repository-wide public URL inventory.'
fi
grep --fixed-strings 'release_tags="$(bash release/list-release-tags.sh)"' "$pages_workflow" >/dev/null || \
  fail 'Pages does not discover the canonical release tag inventory.'
if grep --fixed-strings '    tags:' "$pages_workflow" >/dev/null; then
  fail 'Pages still executes a tag-owned workflow definition.'
fi
[[ "$(grep --fixed-strings -c "if: github.ref == 'refs/heads/master'" "$pages_workflow")" == '3' ]] || \
  fail 'Pages does not restrict every workflow job to master.'
[[ "$(grep --fixed-strings -c 'bash release/verify-pages-release-source.sh' "$pages_workflow")" == '3' ]] || \
  fail 'Pages does not verify the exact controller release before both builds and deployment.'
for attempt_bound_artifact_guard in \
  'name=github-pages-${GITHUB_RUN_ID}-build-${GITHUB_RUN_ATTEMPT}' \
  'name=release-pages-evidence-${GITHUB_RUN_ID}-release-evidence-${GITHUB_RUN_ATTEMPT}' \
  'controller_pages_artifact_digest: ${{ steps.artifact_metadata.outputs.digest }}' \
  'controller_pages_artifact_id: ${{ steps.pages_artifact.outputs.artifact_id }}' \
  'release_pages_artifact_id: ${{ steps.pages_artifact.outputs.artifact-id }}' \
  'release_pages_artifact_digest: ${{ steps.pages_artifact.outputs.artifact-digest }}' \
  'EXPECTED_CONTROLLER_ARTIFACT_DIGEST: ${{ needs.build.outputs.controller_pages_artifact_digest }}' \
  'artifact-ids: ${{ needs.build.outputs.controller_pages_artifact_id }}' \
  'artifact-ids: ${{ needs.release-evidence.outputs.release_pages_artifact_id }}' \
  'artifact_name: ${{ needs.build.outputs.controller_pages_artifact_name }}'; do
  grep --fixed-strings "$attempt_bound_artifact_guard" "$pages_workflow" >/dev/null || \
    fail "Pages attempt-bound artifact guard is missing: $attempt_bound_artifact_guard"
done
[[ "$(grep --fixed-strings -c 'verify_producer_artifact \' "$pages_workflow")" == '2' ]] || \
  fail 'Pages does not revalidate both exact producer artifact outputs before download.'
[[ "$(grep --fixed-strings -c 'include-hidden-files: true' "$pages_workflow")" == '2' ]] || \
  fail 'A Pages artifact can silently omit hidden documentation files.'
if sed -n '/^  release-evidence:$/,/^  deploy:$/p' "$pages_workflow" | \
  grep --fixed-strings 'Download controller Pages artifact' >/dev/null; then
  fail 'Release Pages evidence is derived from the controller artifact instead of generated independently.'
fi
if grep --fixed-strings 'git checkout --detach "$RELEASE_COMMIT"' "$pages_workflow" >/dev/null; then
  fail 'Release Pages evidence replaces the trusted controller checkout.'
fi
grep --fixed-strings 'bash release/verify-pages-artifact-equivalence.sh' "$pages_workflow" >/dev/null || \
  fail 'Pages does not compare the independent artifacts before deployment.'
grep --fixed-strings 'name: github-pages-controller' "$pages_workflow" >/dev/null || \
  fail 'Pages still deploys through the rerunnable legacy environment.'
grep --fixed-strings 'environments/github-pages-controller/deployment-branch-policies?per_page=100' \
  "$pages_workflow" >/dev/null || fail 'Pages does not verify the active controller environment policy.'
grep --fixed-strings 'environments/github-pages/deployment-branch-policies?per_page=100' \
  "$pages_workflow" >/dev/null || fail 'Pages does not verify that the legacy environment remains retired.'
grep --fixed-strings 'permissions: {}' "$pages_workflow" >/dev/null || \
  fail 'Pages retains top-level token permissions.'
deploy_job="$(sed -n '/^  deploy:$/,$p' "$pages_workflow")"
for deploy_permission in 'actions: read' 'contents: read' 'deployments: read' 'pages: write' 'id-token: write'; do
  [[ "$(grep --fixed-strings -c "$deploy_permission" <<< "$deploy_job")" == '1' ]] || \
    fail "Pages deploy permission is missing or duplicated: $deploy_permission"
done
build_job="$(sed -n '/^  build:$/,/^  release-evidence:$/p' "$pages_workflow")"
[[ "$(grep --fixed-strings -c 'actions: read' <<< "$build_job")" == '1' ]] || \
  fail 'Pages build cannot read back and freeze its exact upload metadata.'
[[ "$(grep --fixed-strings -c 'uses: ./.github/actions/setup-strata-java' <<< "$build_job")" == '1' ]] || \
  fail 'Pages build no longer uses the checked-out catalog-backed Java setup action.'
if grep --fixed-strings 'uses: $/.github/actions/setup-strata-java' <<< "$build_job" >/dev/null; then
  fail 'Pages build unexpectedly uses the workflow-owned Java setup action.'
fi
grep --fixed-strings \
  "group: \${{ github.run_attempt == 1 && 'pages-controller' || format('pages-controller-rerun-{0}', github.run_id) }}" \
  "$pages_workflow" >/dev/null || fail 'Pages does not isolate reruns from the trusted controller queue.'
grep --fixed-strings 'cancel-in-progress: ${{ github.run_attempt == 1 }}' "$pages_workflow" >/dev/null || \
  fail 'A rerun can cancel an in-progress trusted Pages controller.'
release_evidence_job="$(sed -n '/^  release-evidence:$/,/^  deploy:$/p' "$pages_workflow")"
[[ "$(grep --fixed-strings -c 'uses: ./.github/actions/setup-strata-java' <<< "$release_evidence_job")" == '1' ]] || \
  fail 'Release Pages evidence no longer uses the checked-out catalog-backed Java setup action.'
if grep --fixed-strings 'uses: $/.github/actions/setup-strata-java' <<< "$release_evidence_job" >/dev/null; then
  fail 'Release Pages evidence unexpectedly uses an action reference unsupported by the pinned workflow linter.'
fi
for release_worktree_guard in \
  'release_worktree="$RUNNER_TEMP/strata-release-pages-source"' \
  'git worktree add --detach "$release_worktree" "$RELEASE_COMMIT"' \
  '"$(git -C "$release_worktree" rev-parse HEAD)" == "$RELEASE_COMMIT"' \
  '-z "$(git -C "$release_worktree" status --porcelain --untracked-files=no)"' \
  'cd "$release_worktree"' \
  '"$(git rev-parse HEAD)" == "$CONTROLLER_COMMIT"' \
  '"$(git -C "$release_worktree" rev-parse HEAD)" == "$RELEASE_COMMIT"' \
  'path: ${{ steps.evidence.outputs.path }}'; do
  grep --fixed-strings -- "$release_worktree_guard" <<< "$release_evidence_job" >/dev/null || \
    fail "Release Pages evidence worktree guard is missing: $release_worktree_guard"
done
[[ "$(grep --fixed-strings -c '"$(git -C "$release_worktree" rev-parse HEAD)" == "$RELEASE_COMMIT"' <<< "$release_evidence_job")" == '2' && \
  "$(grep --fixed-strings -c -- '-z "$(git -C "$release_worktree" status --porcelain --untracked-files=no)"' <<< "$release_evidence_job")" == '2' ]] || \
  fail 'Release Pages evidence does not revalidate its exact clean release worktree after generation.'
release_gradle_line="$(grep -n --fixed-strings 'bash ./gradlew --no-parallel --max-workers=2 --no-build-cache' <<< "$release_evidence_job" | cut -d: -f1)"
controller_tools_line="$(grep -n --fixed-strings 'controller_tools="$RUNNER_TEMP/strata-release-pages-controller"' <<< "$release_evidence_job" | cut -d: -f1)"
[[ "$release_gradle_line" =~ ^[1-9][0-9]*$ && "$controller_tools_line" =~ ^[1-9][0-9]*$ && \
  "$release_gradle_line" -lt "$controller_tools_line" ]] || \
  fail 'Release Pages controller tools are materialized before tag-owned Gradle finishes.'
for tool_guard in \
  'controller_blob()' \
  'materialize_controller_tool()' \
  'verify_controller_tool "$stage_pages_tool" "$EXPECTED_STAGE_PAGES_BLOB"'; do
  grep --fixed-strings "$tool_guard" <<< "$release_evidence_job" >/dev/null || \
    fail "Release Pages controller tool provenance guard is missing: $tool_guard"
done
for tool_mode_guard in \
  'list_tags_blob="$(controller_blob release/list-release-tags.sh 100755)"' \
  'stage_pages_blob="$(controller_blob release/stage-versioned-pages.sh 100644)"' \
  'release/list-release-tags.sh "$list_tags_tool" "$EXPECTED_LIST_TAGS_BLOB" 100755' \
  'release/stage-versioned-pages.sh "$stage_pages_tool" "$EXPECTED_STAGE_PAGES_BLOB" 100644'; do
  grep --fixed-strings "$tool_mode_guard" <<< "$release_evidence_job" >/dev/null || \
    fail "Release Pages controller tool mode guard is missing: $tool_mode_guard"
done
grep --fixed-strings 'name: Revalidate exact controller immediately before deployment' "$pages_workflow" >/dev/null || \
  fail 'Pages does not revalidate origin/master after artifact comparison.'
[[ "$(grep --fixed-strings -c -- '--paginate --slurp' "$pages_deployment_verifier")" == '3' ]] || \
  fail 'Pages deployment verification does not fetch complete paginated job, artifact, and deployment API results.'
[[ "$(grep --fixed-strings -c 'sort_by([(.created_at | fromdateiso8601), .id])' "$pages_deployment_verifier")" == '4' ]] || \
  fail 'Pages deployment verification relies on undocumented API response ordering.'
if grep --extended-regexp 'for release_tag in v[0-9]' "$pages_workflow" >/dev/null; then
  fail 'Pages retains a hand-maintained release tag list.'
fi

fixture="$temporary_root/fixture"
mkdir -p "$fixture/release" "$fixture/build/dokka/html/api" "$fixture/build/dokka/html/releases/0.1.0/guide"
cp "$repository_root/release/stage-versioned-pages.sh" "$fixture/release/stage-versioned-pages.sh"
cp "$repository_root/release/list-release-tags.sh" "$fixture/release/list-release-tags.sh"
cp "$repository_root/release/verify-current-controller-release-order.sh" "$fixture/release/verify-current-controller-release-order.sh"
cp "$pages_release_verifier" "$fixture/release/verify-pages-release-source.sh"
printf 'old release\n' > "$fixture/build/dokka/html/releases/0.1.0/index.html"
printf 'old guide\n' > "$fixture/build/dokka/html/releases/0.1.0/guide/index.html"
printf 'current release\n' > "$fixture/build/dokka/html/index.html"
printf 'current API\n' > "$fixture/build/dokka/html/api/index.html"
printf '%s\n' / /api/index.html /index.html /source-receipt.json /source-revision.txt > \
  "$fixture/build/dokka/html/pages-public-urls.txt"

git -C "$fixture" init --quiet
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" config user.name 'Strata Test'
git -C "$fixture" config commit.gpgsign false
git -C "$fixture" config tag.gpgsign false
git -C "$fixture" add release/list-release-tags.sh release/stage-versioned-pages.sh release/verify-current-controller-release-order.sh
git -C "$fixture" update-index --chmod=+x -- \
  release/list-release-tags.sh release/verify-current-controller-release-order.sh
git -C "$fixture" update-index --chmod=-x -- release/stage-versioned-pages.sh
git -C "$fixture" commit --quiet -m fixture
release_commit="$(git -C "$fixture" rev-parse HEAD)"
git -C "$fixture" tag --annotate --message fixture v0.1.10
git -C "$fixture" tag --annotate --message fixture v0.1.2
git -C "$fixture" tag --annotate --message fixture v0.1.1
current_object="$(git -C "$fixture" rev-parse refs/tags/v0.1.10)"
predecessor_object="$(git -C "$fixture" rev-parse refs/tags/v0.1.2)"
cat > "$fixture/release/current-controller.json" <<EOF
{
  "schemaVersion": 1,
  "current": {
    "tag": "v0.1.10",
    "commit": "$release_commit",
    "tagObject": "$current_object",
    "representativeMinecraftVersions": ["1.20"]
  },
  "predecessor": {
    "tag": "v0.1.2",
    "commit": "$release_commit",
    "tagObject": "$predecessor_object"
  }
}
EOF
git -C "$fixture" add release/current-controller.json release/verify-pages-release-source.sh
git -C "$fixture" update-index --chmod=-x -- \
  release/current-controller.json release/verify-pages-release-source.sh
git -C "$fixture" commit --quiet -m controller
controller_commit="$(git -C "$fixture" rev-parse HEAD)"
git -C "$fixture" update-ref refs/remotes/origin/master "$controller_commit"

[[ "$(git -C "$fixture" ls-tree "$controller_commit" -- release/current-controller.json | cut -d ' ' -f 1)" == '100644' && \
  "$(git -C "$fixture" ls-tree "$controller_commit" -- release/list-release-tags.sh | cut -d ' ' -f 1)" == '100755' && \
  "$(git -C "$fixture" ls-tree "$controller_commit" -- release/verify-current-controller-release-order.sh | cut -d ' ' -f 1)" == '100755' ]] || \
  fail 'The Pages source fixture did not preserve the controller material modes.'

bash "$fixture/release/verify-pages-release-source.sh" \
  "$controller_commit" v0.1.10 "$release_commit" "$fixture" >/dev/null || \
  fail 'Pages rejected the latest controller-bound annotated release.'

wrong_mode_index="$temporary_root/wrong-mode-index"
GIT_INDEX_FILE="$wrong_mode_index" git -C "$fixture" read-tree "$controller_commit"
GIT_INDEX_FILE="$wrong_mode_index" git -C "$fixture" update-index --chmod=-x -- release/list-release-tags.sh
wrong_mode_tree="$(GIT_INDEX_FILE="$wrong_mode_index" git -C "$fixture" write-tree)"
wrong_mode_controller_commit="$(git -C "$fixture" commit-tree "$wrong_mode_tree" -p "$controller_commit" -m wrong-mode)"
git -C "$fixture" update-ref refs/remotes/origin/master "$wrong_mode_controller_commit"
if bash "$fixture/release/verify-pages-release-source.sh" \
  "$wrong_mode_controller_commit" v0.1.10 "$release_commit" "$fixture" >/dev/null 2>&1; then
  fail 'Pages accepted an executable controller script recorded with mode 100644.'
fi

controller_order_blob="$(git -C "$fixture" rev-parse "$controller_commit:release/verify-current-controller-release-order.sh")"
symlink_source_index="$temporary_root/symlink-source-index"
GIT_INDEX_FILE="$symlink_source_index" git -C "$fixture" read-tree "$controller_commit"
GIT_INDEX_FILE="$symlink_source_index" git -C "$fixture" update-index --cacheinfo \
  "120000,$controller_order_blob,release/verify-current-controller-release-order.sh"
symlink_source_tree="$(GIT_INDEX_FILE="$symlink_source_index" git -C "$fixture" write-tree)"
symlink_controller_commit="$(git -C "$fixture" commit-tree "$symlink_source_tree" -p "$controller_commit" -m symlink-source)"
git -C "$fixture" update-ref refs/remotes/origin/master "$symlink_controller_commit"
if bash "$fixture/release/verify-pages-release-source.sh" \
  "$symlink_controller_commit" v0.1.10 "$release_commit" "$fixture" >/dev/null 2>&1; then
  fail 'Pages accepted a controller script recorded as a symbolic link.'
fi
fixture_tracked_status="$(git -C "$fixture" status --short --untracked-files=no)"
if [[ "$(git -C "$fixture" rev-parse HEAD)" != "$controller_commit" ]] || \
  ! git -C "$fixture" diff --quiet || ! git -C "$fixture" diff --cached --quiet; then
  fail "Negative controller-source fixtures changed the reusable fixture checkout: $fixture_tracked_status"
fi
git -C "$fixture" update-ref refs/remotes/origin/master "$controller_commit"

if bash "$fixture/release/verify-pages-release-source.sh" \
  "$controller_commit" v0.1.2 "$release_commit" "$fixture" >/dev/null 2>&1; then
  fail 'Pages accepted a historical annotated release over the current site.'
fi

git -C "$fixture" tag --annotate --message fixture v0.1.11 "$release_commit"
if bash "$fixture/release/verify-pages-release-source.sh" \
  "$controller_commit" v0.1.10 "$release_commit" "$fixture" >/dev/null 2>&1; then
  fail 'Pages accepted controller metadata after a newer annotated release appeared.'
fi
git -C "$fixture" tag --delete v0.1.11 >/dev/null

git -C "$fixture" update-ref refs/remotes/origin/master "$release_commit"
if bash "$fixture/release/verify-pages-release-source.sh" \
  "$controller_commit" v0.1.10 "$release_commit" "$fixture" >/dev/null 2>&1; then
  fail 'Pages accepted a controller that was no longer origin/master.'
fi
git -C "$fixture" update-ref refs/remotes/origin/master "$controller_commit"

expected_tags=$'v0.1.1\nv0.1.2\nv0.1.10'
actual_tags="$(bash "$fixture/release/list-release-tags.sh" "$fixture")"
[[ "$actual_tags" == "$expected_tags" ]] || \
  fail "Release tags are not listed in version order: $actual_tags"

git -C "$fixture" tag --annotate --message fixture v0.1
if bash "$fixture/release/list-release-tags.sh" "$fixture" >/dev/null 2>&1; then
  fail 'Release tag listing accepted a malformed v-prefixed tag.'
fi
git -C "$fixture" tag --delete v0.1 >/dev/null

git -C "$fixture" tag v0.1.3
if bash "$fixture/release/list-release-tags.sh" "$fixture" >/dev/null 2>&1; then
  fail 'Release tag listing accepted a lightweight semantic tag.'
fi
git -C "$fixture" tag --delete v0.1.3 >/dev/null

git -C "$fixture" checkout --quiet --detach "$release_commit"
tag_commit="$(git -C "$fixture" rev-parse 'refs/tags/v0.1.1^{commit}')"
git -C "$fixture" checkout --quiet --detach "$tag_commit"
printf '%s\n' v0.1.1 > "$fixture/build/dokka/html/source-revision.txt"
printf '{"commit":"%s","revision":"v0.1.1"}\n' "$tag_commit" > "$fixture/build/dokka/html/source-receipt.json"

(
  cd "$fixture"
  bash release/stage-versioned-pages.sh v0.1.1 build/dokka/html v0.1.1
)

[[ -f "$fixture/build/dokka/html/releases/0.1.0/index.html" ]] || fail 'Staging replaced an older immutable release.'
[[ -f "$fixture/build/dokka/html/releases/0.1.0/guide/index.html" ]] || fail 'Staging removed an immutable legacy guide.'
[[ -f "$fixture/build/dokka/html/releases/0.1.1/index.html" ]] || fail 'Staging omitted the current release root.'
[[ -f "$fixture/build/dokka/html/releases/0.1.1/api/index.html" ]] || fail 'Staging omitted the current Dokka API.'
[[ ! -e "$fixture/build/dokka/html/releases/0.1.1/guide" ]] || fail 'Staging injected a reader guide into the Dokka-only release.'
[[ ! -e "$fixture/build/dokka/html/releases/0.1.1/releases" ]] || fail 'A release snapshot recursively nested older releases.'
cmp --silent "$fixture/build/dokka/html/index.html" "$fixture/build/dokka/html/releases/0.1.1/index.html" || \
  fail 'The staged current release differs from its source root.'
cmp --silent "$fixture/build/dokka/html/pages-public-urls.txt" "$fixture/build/dokka/html/releases/0.1.1/pages-public-urls.txt" || \
  fail 'The staged current release changed its public inventory.'

evidence="$temporary_root/release-evidence"
controller_site="$temporary_root/controller-site"
controller_artifact="$temporary_root/controller-artifact.tar"
printf '{"commit":"%s","revision":"v0.1.0"}\n' "$release_commit" > \
  "$fixture/build/dokka/html/releases/0.1.0/source-receipt.json"
cp -a "$fixture/build/dokka/html" "$evidence"
cp -a "$fixture/build/dokka/html" "$controller_site"
printf '{"commit":"%s","revision":"master"}\n' "$controller_commit" > "$controller_site/source-receipt.json"
mkdir -p "$evidence/releases/0.0.9" "$controller_site/releases/0.0.9"
printf 'older release\n' > "$evidence/releases/0.0.9/index.html"
printf 'older release\n' > "$controller_site/releases/0.0.9/index.html"
controller_only_empty_directory="$controller_site/releases/0.1.1/api/controller-only-empty-directory"
[[ ! -e "$evidence/releases/0.1.1/api/controller-only-empty-directory" ]] || \
  fail 'The controller-only empty-directory fixture already exists in release evidence.'
mkdir -p "$controller_only_empty_directory"
tar -cf "$controller_artifact" -C "$controller_site" .
tar -tf "$controller_artifact" | sed 's#^\./##; s#/$##' | \
  grep --fixed-strings --line-regexp 'releases/0.1.1/api/controller-only-empty-directory' >/dev/null || \
  fail 'The controller artifact fixture did not preserve its controller-only empty directory.'
bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.1 "$tag_commit" "$controller_commit" >/dev/null || \
  fail 'Pages rejected equivalent release and controller artifacts whose transport preserved an extra empty directory.'
printf 'unexpected transported file\n' > "$controller_only_empty_directory/unexpected.txt"
tar -cf "$controller_artifact" -C "$controller_site" .
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.1 "$tag_commit" "$controller_commit" >/dev/null 2>&1; then
  fail 'Pages ignored a regular file inside a controller-only directory.'
fi
rm "$controller_only_empty_directory/unexpected.txt"
tar -cf "$controller_artifact" -C "$controller_site" .
bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" >/dev/null || \
  fail 'Pages rejected predecessor evidence whose artifact root remains bound to the current tag.'
legacy_site="$temporary_root/legacy-release-site"
legacy_artifact="$temporary_root/legacy-release-artifact.tar"
cp -a "$controller_site/releases/0.1.0" "$legacy_site"
mkdir -p "$legacy_site/releases"
cp -a "$controller_site/releases/0.1.0" "$legacy_site/releases/0.1.0"
tar -cf "$legacy_artifact" -C "$legacy_site" .
bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" "$legacy_artifact" >/dev/null || \
  fail 'Pages rejected an exact legacy release root and immutable subtree.'
malformed_legacy_artifact="$temporary_root/malformed-legacy-release-artifact.tar"
pages_python=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import tarfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || fail 'Python with the standard tarfile module is required for the legacy archive fixture.'
"$pages_python" - "$legacy_artifact" "$malformed_legacy_artifact" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], mode="r:") as source, tarfile.open(sys.argv[2], mode="w:") as output:
    for member in source.getmembers():
        normalized = member.name.rstrip("/")
        while normalized.startswith("./"):
            normalized = normalized[2:]
        if normalized == "releases":
            payload = b"not a directory\n"
            replacement = tarfile.TarInfo(member.name.rstrip("/"))
            replacement.mode = 0o644
            replacement.size = len(payload)
            output.addfile(replacement, io.BytesIO(payload))
            continue
        payload = source.extractfile(member) if member.isfile() else None
        output.addfile(member, payload)
PY
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" "$malformed_legacy_artifact" >/dev/null 2>&1; then
  fail 'Pages accepted a legacy release artifact whose releases root was a regular file.'
fi
printf 'changed legacy release root\n' > "$legacy_site/index.html"
tar -cf "$legacy_artifact" -C "$legacy_site" .
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" "$legacy_artifact" >/dev/null 2>&1; then
  fail 'Pages accepted a legacy release artifact whose root differed from the controller target subtree.'
fi
cp "$controller_site/releases/0.1.0/index.html" "$legacy_site/index.html"
printf 'changed legacy immutable subtree\n' > "$legacy_site/releases/0.1.0/index.html"
tar -cf "$legacy_artifact" -C "$legacy_site" .
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" "$legacy_artifact" >/dev/null 2>&1; then
  fail 'Pages accepted a legacy release artifact whose immutable subtree differed.'
fi
printf 'changed predecessor\n' > "$evidence/releases/0.1.0/index.html"
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" >/dev/null 2>&1; then
  fail 'Pages accepted a changed predecessor subtree.'
fi
cp "$controller_site/releases/0.1.0/index.html" "$evidence/releases/0.1.0/index.html"
printf 'changed current subtree\n' > "$evidence/releases/0.1.1/index.html"
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" >/dev/null 2>&1; then
  fail 'Pages accepted a changed current evidence-root subtree.'
fi
cp "$controller_site/releases/0.1.1/index.html" "$evidence/releases/0.1.1/index.html"
printf 'changed older release\n' > "$evidence/releases/0.0.9/index.html"
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.0 "$release_commit" "$controller_commit" \
  v0.1.1 "$tag_commit" >/dev/null 2>&1; then
  fail 'Pages accepted a changed non-target immutable release subtree.'
fi
cp "$controller_site/releases/0.0.9/index.html" "$evidence/releases/0.0.9/index.html"
printf 'changed release root\n' > "$evidence/index.html"
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.1 "$tag_commit" "$controller_commit" >/dev/null 2>&1; then
  fail 'Pages accepted release evidence whose independently generated root differed.'
fi
cp "$controller_site/index.html" "$evidence/index.html"
printf 'changed historical release\n' > "$evidence/releases/0.1.0/index.html"
if bash "$pages_artifact_verifier" \
  "$controller_artifact" "$evidence" v0.1.1 "$tag_commit" "$controller_commit" >/dev/null 2>&1; then
  fail 'Pages accepted a changed historical immutable release subtree.'
fi

if (
  cd "$fixture"
  bash release/stage-versioned-pages.sh latest build/dokka/html master >/dev/null 2>&1
); then
  fail 'Versioned Pages staging accepted a non-semantic release tag.'
fi

echo 'Master-only Pages accepts the latest controller-bound release and preserves immutable Dokka sites and legacy guides.'

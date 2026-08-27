#!/usr/bin/env bash

set -euo pipefail

release_run_id="${1:-}"
release_tag="${2:-}"
expected_release_commit="${3:-}"
controller_run_id="${4:-}"
expected_controller_commit="${5:-}"

[[ "$release_run_id" =~ ^[1-9][0-9]*$ ]] || { echo 'A positive release Documentation workflow run ID is required.' >&2; exit 1; }
[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'Pages deployment verification requires an exact semantic release tag.' >&2
  exit 1
}
[[ "$expected_release_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ "$controller_run_id" =~ ^[1-9][0-9]*$ ]] || { echo 'A positive controller Documentation workflow run ID is required.' >&2; exit 1; }
[[ "$expected_controller_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected controller Pages commit must be a full lowercase Git SHA.' >&2
  exit 1
}
[[ -n "${GITHUB_REPOSITORY:-}" ]] || { echo 'GITHUB_REPOSITORY is required.' >&2; exit 1; }
[[ -n "${GH_TOKEN:-}" ]] || { echo 'GH_TOKEN is required.' >&2; exit 1; }

temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

api_header=(-H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2026-03-10')

verify_pages_run_artifact() {
  local run_id="$1"
  local branch="$2"
  local commit="$3"
  local label="$4"
  local run_response="$temporary_root/$label-run.json"
  local jobs_response="$temporary_root/$label-jobs.json"
  local artifacts_response="$temporary_root/$label-artifacts.json"
  local artifact_record=""
  local artifact_id=""
  local artifact_size=""
  local artifact_digest=""
  local artifact_zip="$temporary_root/$label-github-pages.zip"
  local artifact_tar="$temporary_root/$label-artifact.tar"
  local zip_entries=()

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id" > "$run_response"
  jq -e \
    --argjson runId "$run_id" \
    --arg branch "$branch" \
    --arg commit "$commit" '
      .id == $runId and
      .path == ".github/workflows/pages.yml" and
      .event == "push" and
      .head_branch == $branch and
      .head_sha == $commit and
      .status == "completed" and
      .conclusion == "success"
    ' "$run_response" >/dev/null || {
    echo "The $label Documentation workflow run is not the successful exact branch and commit." >&2
    exit 1
  }

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/jobs?per_page=100" > "$jobs_response"
  jq -e '
    ([.jobs[] | select(.name == "build" and .status == "completed" and .conclusion == "success")] | length) == 1 and
    ([.jobs[] | select(.name == "deploy" and .status == "completed" and .conclusion == "success")] | length) == 1
  ' "$jobs_response" >/dev/null || {
    echo "The $label Documentation run does not contain one successful build and deploy job." >&2
    exit 1
  }

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/artifacts?per_page=100" > "$artifacts_response"
  artifact_record="$(
    jq -er \
      --argjson runId "$run_id" \
      --arg branch "$branch" \
      --arg commit "$commit" '
        [
          .artifacts[] |
          select(
            .name == "github-pages" and
            .expired == false and
            .size_in_bytes > 0 and
            ((.digest // "") | test("^sha256:[0-9a-f]{64}$")) and
            .workflow_run.id == $runId and
            .workflow_run.head_branch == $branch and
            .workflow_run.head_sha == $commit
          )
        ] |
        select(length == 1) |
        .[0] |
        select(.id | type == "number" and . > 0 and . == floor) |
        [.id, .size_in_bytes, .digest] |
        @tsv
      ' "$artifacts_response"
  )" || {
    echo "The $label Documentation run does not have one current commit-bound github-pages artifact." >&2
    exit 1
  }
  IFS=$'\t' read -r artifact_id artifact_size artifact_digest <<< "$artifact_record"

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$artifact_zip"
  [[ "$(stat -c '%s' "$artifact_zip")" == "$artifact_size" ]] || {
    echo "The downloaded $label github-pages ZIP size differs from immutable artifact metadata." >&2
    exit 1
  }
  [[ "sha256:$(sha256sum "$artifact_zip" | cut -d ' ' -f 1)" == "$artifact_digest" ]] || {
    echo "The downloaded $label github-pages ZIP digest differs from immutable artifact metadata." >&2
    exit 1
  }
  mapfile -t zip_entries < <(unzip -Z1 "$artifact_zip")
  [[ "${#zip_entries[@]}" == "1" && "${zip_entries[0]}" == "artifact.tar" ]] || {
    echo "The downloaded $label github-pages ZIP must contain only artifact.tar." >&2
    exit 1
  }
  unzip -p "$artifact_zip" artifact.tar > "$artifact_tar"

  printf '%s\n' "$artifact_id"
}

verify_artifact_receipt() {
  local artifact_tar="$1"
  local receipt_target="$2"
  local expected_receipt="$3"
  local receipt_output="$4"
  local receipt_label="$5"
  local receipt_entries=()

  mapfile -t receipt_entries < <(
    tar -tf "$artifact_tar" |
      while IFS= read -r entry; do
        normalized="$entry"
        while [[ "$normalized" == ./* ]]; do
          normalized="${normalized#./}"
        done
        if [[ "$normalized" == "$receipt_target" ]]; then
          printf '%s\n' "$entry"
        fi
      done
  )
  [[ "${#receipt_entries[@]}" == "1" ]] || {
    echo "The Pages artifact must contain exactly one $receipt_label source receipt." >&2
    exit 1
  }
  tar -xOf "$artifact_tar" "${receipt_entries[0]}" > "$receipt_output"
  cmp --silent "$expected_receipt" "$receipt_output" || {
    echo "The $receipt_label Pages artifact source receipt differs from its exact revision and commit." >&2
    exit 1
  }
}

release_artifact_id="$(verify_pages_run_artifact \
  "$release_run_id" "$release_tag" "$expected_release_commit" release)"
controller_artifact_id="$(verify_pages_run_artifact \
  "$controller_run_id" master "$expected_controller_commit" controller)"

release_receipt="$temporary_root/expected-release-receipt.json"
controller_receipt="$temporary_root/expected-controller-receipt.json"
printf '{"commit":"%s","revision":"%s"}\n' "$expected_release_commit" "$release_tag" > "$release_receipt"
printf '{"commit":"%s","revision":"master"}\n' "$expected_controller_commit" > "$controller_receipt"

verify_artifact_receipt \
  "$temporary_root/release-artifact.tar" \
  'source-receipt.json' \
  "$release_receipt" \
  "$temporary_root/release-root-source-receipt.json" \
  'release root'
verify_artifact_receipt \
  "$temporary_root/release-artifact.tar" \
  "releases/${release_tag#v}/source-receipt.json" \
  "$release_receipt" \
  "$temporary_root/release-immutable-source-receipt.json" \
  'release immutable subtree'
verify_artifact_receipt \
  "$temporary_root/controller-artifact.tar" \
  'source-receipt.json' \
  "$controller_receipt" \
  "$temporary_root/controller-root-source-receipt.json" \
  'controller root'
verify_artifact_receipt \
  "$temporary_root/controller-artifact.tar" \
  "releases/${release_tag#v}/source-receipt.json" \
  "$release_receipt" \
  "$temporary_root/controller-immutable-source-receipt.json" \
  'controller immutable subtree'

pages_python=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import pathlib, shutil, tarfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || {
  echo 'Python with the standard tarfile module is required to compare immutable Pages artifacts.' >&2
  exit 1
}

extract_validated_subtree() {
  local artifact_tar="$1"
  local subtree="$2"
  local destination="$3"
  "$pages_python" - "$artifact_tar" "$subtree" "$destination" <<'PY'
import pathlib
import shutil
import sys
import tarfile

archive_path = pathlib.Path(sys.argv[1])
subtree = sys.argv[2].strip("/")
destination = pathlib.Path(sys.argv[3])
destination.mkdir(parents=True, exist_ok=False)
seen = set()
matched = False

with tarfile.open(archive_path, mode="r:") as archive:
    for member in archive.getmembers():
        normalized = member.name
        while normalized.startswith("./"):
            normalized = normalized[2:]
        normalized = normalized.rstrip("/")
        if "\\" in normalized:
            raise SystemExit("The Pages artifact contains a non-canonical backslash path.")
        if normalized != subtree and not normalized.startswith(f"{subtree}/"):
            continue
        matched = True
        relative_text = normalized[len(subtree):].lstrip("/")
        if not relative_text:
            if not member.isdir():
                raise SystemExit("The immutable Pages subtree root is not a directory.")
            if "" in seen:
                raise SystemExit("The immutable Pages subtree contains a duplicate root.")
            seen.add("")
            continue
        relative = pathlib.PurePosixPath(relative_text)
        if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
            raise SystemExit("The immutable Pages subtree contains an unsafe path.")
        key = relative.as_posix()
        if normalized != f"{subtree}/{key}":
            raise SystemExit("The immutable Pages subtree contains a non-canonical path.")
        if key in seen:
            raise SystemExit("The immutable Pages subtree contains a duplicate path.")
        seen.add(key)
        if not (member.isdir() or member.isfile()):
            raise SystemExit("The immutable Pages subtree contains a non-regular entry.")
        target = destination.joinpath(*relative.parts)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        source = archive.extractfile(member)
        if source is None:
            raise SystemExit("A regular immutable Pages entry has no data.")
        with source, target.open("xb") as output:
            shutil.copyfileobj(source, output)

if not matched:
    raise SystemExit("The immutable Pages subtree is missing.")
PY
}

release_subtree="$temporary_root/release-subtree"
controller_subtree="$temporary_root/controller-subtree"
extract_validated_subtree \
  "$temporary_root/release-artifact.tar" "releases/${release_tag#v}" "$release_subtree"
extract_validated_subtree \
  "$temporary_root/controller-artifact.tar" "releases/${release_tag#v}" "$controller_subtree"
diff --recursive --no-dereference "$release_subtree" "$controller_subtree" >/dev/null || {
  echo 'The controller Pages artifact changed the immutable release documentation subtree.' >&2
  exit 1
}

global_deployments_response="$temporary_root/global-deployments.json"
controller_statuses_response="$temporary_root/controller-statuses.json"
release_deployments_response="$temporary_root/release-deployments.json"
release_statuses_response="$temporary_root/release-statuses.json"

gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/deployments?environment=github-pages&per_page=100" > "$global_deployments_response"
controller_deployment_id="$(
  jq -er \
    --arg commit "$expected_controller_commit" '
      .[0] |
      select(
        .ref == "master" and
        .sha == $commit and
        .environment == "github-pages" and
        .task == "deploy"
      ) |
      .id |
      select(type == "number" and . > 0 and . == floor) |
      tostring
    ' "$global_deployments_response"
)" || {
  echo 'The globally newest github-pages deployment is not bound to the exact controller master commit.' >&2
  exit 1
}
gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/deployments/$controller_deployment_id/statuses?per_page=100" > "$controller_statuses_response"
jq -e \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$controller_run_id" '
    length > 0 and
    .[0].state == "success" and
    .[0].environment == "github-pages" and
    ([.[0].log_url, .[0].target_url] | any(. == $runUrl or startswith("\($runUrl)/")))
  ' "$controller_statuses_response" >/dev/null || {
  echo 'The current controller github-pages deployment does not have a latest successful status bound to its exact run.' >&2
  exit 1
}

gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/deployments?sha=$expected_release_commit&environment=github-pages&per_page=100" > "$release_deployments_response"
release_deployment_id="$(
  jq -er \
    --arg tag "$release_tag" \
    --arg commit "$expected_release_commit" '
      [
        .[] |
        select(
          .ref == $tag and
          .sha == $commit and
          .environment == "github-pages" and
          .task == "deploy"
        )
      ] |
      .[0].id |
      select(type == "number" and . > 0 and . == floor) |
      tostring
    ' "$release_deployments_response"
)" || {
  echo 'No historical github-pages deployment is bound to the exact release tag and commit.' >&2
  exit 1
}
gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/deployments/$release_deployment_id/statuses?per_page=100" > "$release_statuses_response"
jq -e \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$release_run_id" '
    length > 0 and
    (.[0].state == "success" or .[0].state == "inactive") and
    .[0].environment == "github-pages" and
    any(
      .[];
      .state == "success" and
      .environment == "github-pages" and
      ([.log_url, .target_url] | any(. == $runUrl or startswith("\($runUrl)/")))
    )
  ' "$release_statuses_response" >/dev/null || {
  echo 'The historical release github-pages deployment never succeeded from its exact run.' >&2
  exit 1
}

printf '%s %s %s %s %s %s\n' \
  "$release_run_id" \
  "$release_artifact_id" \
  "$release_deployment_id" \
  "$controller_run_id" \
  "$controller_artifact_id" \
  "$controller_deployment_id"

#!/usr/bin/env bash

set -euo pipefail

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

release_run_id="${1:-}"
release_tag="${2:-}"
expected_release_commit="${3:-}"
controller_run_id="${4:-}"
expected_controller_commit="${5:-}"
evidence_root_tag="${6:-}"
expected_evidence_root_commit="${7:-}"

(( $# == 5 || $# == 7 )) || {
  echo 'Pages deployment verification requires five identity arguments or seven with an explicit evidence root.' >&2
  exit 1
}

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

legacy_release_run=false
if [[ "$release_run_id" != "$controller_run_id" ]]; then
  legacy_release_run=true
fi
if [[ "$legacy_release_run" == false && $# == 5 ]]; then
  evidence_root_tag="$release_tag"
  expected_evidence_root_commit="$expected_release_commit"
fi

temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

api_header=(-H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2026-03-10')
run_response="$temporary_root/controller-run.json"
jobs_response="$temporary_root/controller-jobs.json"
all_jobs_response="$temporary_root/controller-all-jobs.json"
artifacts_response="$temporary_root/controller-artifacts.json"
legacy_run_response="$temporary_root/legacy-release-run.json"
legacy_jobs_response="$temporary_root/legacy-release-jobs.json"
legacy_all_jobs_response="$temporary_root/legacy-release-all-jobs.json"
legacy_artifacts_response="$temporary_root/legacy-release-artifacts.json"

fetch_job_inventory() {
  local run_id="$1"
  local output="$2"
  local pages="$output.pages"

  gh api "${api_header[@]}" --paginate --slurp \
    "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/jobs?filter=all&per_page=100" > "$pages"
  portable_jq -e '
    def timestamp:
      type == "string" and
      ((try fromdateiso8601 catch null) != null);
    select(type == "array" and length > 0) |
    . as $pages |
    select(all($pages[];
      type == "object" and
      (.total_count | type == "number" and . >= 0 and . == floor) and
      (.jobs | type == "array"))) |
    ($pages[0].total_count) as $total |
    select(all($pages[]; .total_count == $total)) |
    [$pages[].jobs[]] as $jobs |
    select(($jobs | length) == $total) |
    select(all($jobs[];
      (.id | type == "number" and . > 0 and . == floor) and
      (.run_id | type == "number" and . > 0 and . == floor) and
      (.head_sha | type == "string" and test("^[0-9a-f]{40}$")) and
      (.name | type == "string" and length > 0) and
      (.run_attempt | type == "number" and . > 0 and . == floor) and
      .status == "completed" and
      (.conclusion | type == "string" and length > 0) and
      if .conclusion == "skipped" then
        (.started_at == null or (.started_at | timestamp)) and
        (.completed_at == null or (.completed_at | timestamp))
      else
        (.started_at | timestamp) and
        (.completed_at | timestamp) and
        ((.started_at | fromdateiso8601) <= (.completed_at | fromdateiso8601))
      end)) |
    select(($jobs | map(.id) | length) == ($jobs | map(.id) | unique | length)) |
    {total_count: $total, jobs: $jobs}
  ' "$pages" > "$output" || {
    echo "The workflow run $run_id does not have one complete canonical all-attempt job inventory." >&2
    exit 1
  }
}

derive_latest_jobs() {
  local all_jobs="$1"
  local expected_names="$2"
  local output="$3"

  portable_jq -e --argjson expectedNames "$expected_names" '
    . as $inventory |
    select(all($inventory.jobs[]; .name as $name | any($expectedNames[]; . == $name))) |
    select([
      $expectedNames[] as $name |
      any($inventory.jobs[]; .name == $name and .conclusion != "skipped")
    ] | all) |
    select(
      ([.jobs[] | "\(.name)\u0000\(.run_attempt)"] | length) ==
      ([.jobs[] | "\(.name)\u0000\(.run_attempt)"] | unique | length)
    ) |
    {
      total_count: ($expectedNames | length),
      jobs: [
        $expectedNames[] as $name |
        [$inventory.jobs[] | select(.name == $name and .conclusion != "skipped")] |
        sort_by([.run_attempt, .id]) |
        last
      ]
    }
  ' "$all_jobs" > "$output" || {
    echo 'The workflow job history does not derive one unambiguous latest execution per expected job.' >&2
    exit 1
  }
}

fetch_artifact_inventory() {
  local run_id="$1"
  local output="$2"
  local pages="$output.pages"

  gh api "${api_header[@]}" --paginate --slurp \
    "repos/$GITHUB_REPOSITORY/actions/runs/$run_id/artifacts?per_page=100" > "$pages"
  portable_jq -e '
    select(type == "array" and length > 0) |
    . as $pages |
    select(all($pages[];
      type == "object" and
      (.total_count | type == "number" and . >= 0 and . == floor) and
      (.artifacts | type == "array"))) |
    ($pages[0].total_count) as $total |
    select(all($pages[]; .total_count == $total)) |
    [$pages[].artifacts[]] as $artifacts |
    select(($artifacts | length) == $total) |
    select(all($artifacts[];
      (.id | type == "number" and . > 0 and . == floor) and
      (.name | type == "string" and length > 0) and
      (.size_in_bytes | type == "number" and . > 0 and . == floor) and
      (.digest | type == "string" and test("^sha256:[0-9a-f]{64}$")) and
      (.expired | type == "boolean") and
      (.created_at | type == "string") and
      ((try (.created_at | fromdateiso8601) catch null) != null) and
      (.workflow_run | type == "object"))) |
    select(($artifacts | map(.id) | length) == ($artifacts | map(.id) | unique | length)) |
    {total_count: $total, artifacts: $artifacts}
  ' "$pages" > "$output" || {
    echo "The workflow run $run_id does not have one complete canonical artifact inventory." >&2
    exit 1
  }
}

controller_tree_blob() {
  local source_path="$1"
  local record=""
  local mode=""
  local object_type=""
  local blob=""
  local verified_path=""

  [[ "$(git --no-replace-objects cat-file -t "$expected_controller_commit")" == 'commit' ]] || {
    echo 'The expected Pages controller object is not a local commit.' >&2
    exit 1
  }
  record="$(git --no-replace-objects ls-tree --full-tree "$expected_controller_commit" -- "$source_path")"
  [[ -n "$record" && "$record" != *$'\n'* ]] || {
    echo "The Pages controller source is missing or ambiguous: $source_path" >&2
    exit 1
  }
  read -r mode object_type blob verified_path <<< "$record"
  [[ "$mode" == '100644' && "$object_type" == 'blob' && \
    "$blob" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ && "$verified_path" == "$source_path" ]] || {
    echo "The Pages controller source is not an exact regular blob: $source_path" >&2
    exit 1
  }
  printf '%s\n' "$blob"
}

materialize_controller_file() {
  local source_path="$1"
  local destination="$2"
  local blob=""

  blob="$(controller_tree_blob "$source_path")"
  [[ ! -e "$destination" && ! -L "$destination" ]] || {
    echo "The Pages controller destination already exists: $destination" >&2
    exit 1
  }
  git --no-replace-objects cat-file blob "$blob" > "$destination"
  [[ -f "$destination" && ! -L "$destination" && \
    "$(git hash-object --no-filters -- "$destination")" == "$blob" ]] || {
    echo "The materialized Pages controller source differs from its exact blob: $source_path" >&2
    exit 1
  }
}

gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$controller_run_id" > "$run_response"
jq -e \
  --argjson runId "$controller_run_id" \
  --arg commit "$expected_controller_commit" '
    .id == $runId and
    .path == ".github/workflows/pages.yml" and
    .event == "push" and
    .head_branch == "master" and
    .head_sha == $commit and
    (.run_attempt | type == "number" and . > 0 and . == floor) and
    .status == "completed" and
    .conclusion == "success"
  ' "$run_response" >/dev/null || {
  echo 'The Documentation workflow run is not the successful exact master controller.' >&2
  exit 1
}

fetch_job_inventory "$controller_run_id" "$all_jobs_response"
derive_latest_jobs "$all_jobs_response" '["build", "release-evidence", "deploy"]' "$jobs_response"
jq -e \
  --argjson runId "$controller_run_id" \
  --arg commit "$expected_controller_commit" \
  --slurpfile allJobs "$all_jobs_response" \
  --slurpfile run "$run_response" '
  (.jobs | length) == 3 and
  ([.jobs[] | select(.name == "build" and .status == "completed" and .conclusion == "success")] | length) == 1 and
  ([.jobs[] | select(.name == "release-evidence" and .status == "completed" and .conclusion == "success")] | length) == 1 and
  ([.jobs[] | select(
    .name == "deploy" and
    .status == "completed" and
    .conclusion == "success" and
    .run_attempt == $run[0].run_attempt
  )] | length) == 1 and
  all($allJobs[0].jobs[];
    .run_id == $runId and
    .head_sha == $commit and
    .run_attempt <= $run[0].run_attempt) and
  all(.jobs[]; . as $latest | any($allJobs[0].jobs[]; . == $latest))
' "$jobs_response" >/dev/null || {
  echo 'The Documentation run does not contain one successful build, release-evidence, and deploy job.' >&2
  exit 1
}
controller_producer_windows="$(
  jq -er '
    [
      (.jobs[] | select(.name == "build") | .started_at),
      (.jobs[] | select(.name == "build") | .completed_at),
      (.jobs[] | select(.name == "release-evidence") | .started_at),
      (.jobs[] | select(.name == "release-evidence") | .completed_at)
    ] |
    @tsv
  ' "$jobs_response"
)"
IFS=$'\t' read -r controller_build_started_at controller_build_completed_at \
  controller_evidence_started_at controller_evidence_completed_at <<< "$controller_producer_windows"

fetch_artifact_inventory "$controller_run_id" "$artifacts_response"
jq -e \
  --argjson runId "$controller_run_id" \
  --arg commit "$expected_controller_commit" \
  --arg controllerPrefix "github-pages-${controller_run_id}-build-" \
  --arg evidencePrefix "release-pages-evidence-${controller_run_id}-release-evidence-" \
  --arg controllerStartedAt "$controller_build_started_at" \
  --arg controllerCompletedAt "$controller_build_completed_at" \
  --arg evidenceStartedAt "$controller_evidence_started_at" \
  --arg evidenceCompletedAt "$controller_evidence_completed_at" \
  --slurpfile allJobs "$all_jobs_response" '
    def producer_window($name; $attempt; $created):
      [
        $allJobs[0].jobs[] |
        select(
          .name == $name and
          .run_attempt == $attempt and
          .conclusion != "skipped" and
          (.started_at | fromdateiso8601) <= $created and
          $created <= (.completed_at | fromdateiso8601)
        )
      ] |
      length >= 1;
    def artifacts_in_window($prefix; $startedAt; $completedAt):
      [
        .artifacts[] |
        select(
          (.name | startswith($prefix)) and
          ($startedAt | fromdateiso8601) <= (.created_at | fromdateiso8601) and
          (.created_at | fromdateiso8601) <= ($completedAt | fromdateiso8601)
        )
      ];
    .total_count >= 2 and
    all(.artifacts[]; . as $artifact |
      ($artifact.created_at | fromdateiso8601) as $created |
      $artifact.workflow_run.id == $runId and
      $artifact.workflow_run.head_branch == "master" and
      $artifact.workflow_run.head_sha == $commit and
      if ($artifact.name | startswith($controllerPrefix)) then
        ($artifact.name | ltrimstr($controllerPrefix)) as $attempt |
        ($attempt | test("^[1-9][0-9]*$")) and
        producer_window("build"; ($attempt | tonumber); $created)
      elif ($artifact.name | startswith($evidencePrefix)) then
        ($artifact.name | ltrimstr($evidencePrefix)) as $attempt |
        ($attempt | test("^[1-9][0-9]*$")) and
        producer_window("release-evidence"; ($attempt | tonumber); $created)
      else false
      end) and
    (artifacts_in_window($controllerPrefix; $controllerStartedAt; $controllerCompletedAt) | length) == 1 and
    (artifacts_in_window($evidencePrefix; $evidenceStartedAt; $evidenceCompletedAt) | length) == 1
  ' "$artifacts_response" >/dev/null || {
  echo 'The Documentation run artifacts are not uniquely bound to the latest successful producer execution windows.' >&2
  exit 1
}
selected_artifact_names="$(
  jq -er \
    --arg controllerPrefix "github-pages-${controller_run_id}-build-" \
    --arg evidencePrefix "release-pages-evidence-${controller_run_id}-release-evidence-" \
    --arg controllerStartedAt "$controller_build_started_at" \
    --arg controllerCompletedAt "$controller_build_completed_at" \
    --arg evidenceStartedAt "$controller_evidence_started_at" \
    --arg evidenceCompletedAt "$controller_evidence_completed_at" '
      def selected_name($prefix; $startedAt; $completedAt):
        [
          .artifacts[] |
          select(
            (.name | startswith($prefix)) and
            ($startedAt | fromdateiso8601) <= (.created_at | fromdateiso8601) and
            (.created_at | fromdateiso8601) <= ($completedAt | fromdateiso8601)
          )
        ] |
        select(length == 1) |
        .[0].name;
      [
        selected_name($controllerPrefix; $controllerStartedAt; $controllerCompletedAt),
        selected_name($evidencePrefix; $evidenceStartedAt; $evidenceCompletedAt)
      ] |
      @tsv
    ' "$artifacts_response"
)" || {
  echo 'The selected Pages artifact names changed after inventory validation.' >&2
  exit 1
}
IFS=$'\t' read -r expected_controller_artifact_name expected_release_artifact_name <<< "$selected_artifact_names"

if [[ "$legacy_release_run" == true ]]; then
  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/runs/$release_run_id" > "$legacy_run_response"
  jq -e \
    --argjson runId "$release_run_id" \
    --arg tag "$release_tag" \
    --arg commit "$expected_release_commit" '
      .id == $runId and
      .path == ".github/workflows/pages.yml" and
      .event == "push" and
      .head_branch == $tag and
      .head_sha == $commit and
      (.run_attempt | type == "number" and . > 0 and . == floor) and
      .status == "completed" and
      .conclusion == "success"
    ' "$legacy_run_response" >/dev/null || {
    echo 'The legacy release Documentation run is not the successful exact tag and commit.' >&2
    exit 1
  }

  fetch_job_inventory "$release_run_id" "$legacy_all_jobs_response"
  derive_latest_jobs "$legacy_all_jobs_response" '["build", "deploy"]' "$legacy_jobs_response"
  jq -e \
    --argjson runId "$release_run_id" \
    --arg commit "$expected_release_commit" \
    --slurpfile allJobs "$legacy_all_jobs_response" \
    --slurpfile run "$legacy_run_response" '
    (.jobs | length) == 2 and
    ([.jobs[] | select(.name == "build" and .status == "completed" and .conclusion == "success")] | length) == 1 and
    ([.jobs[] | select(
      .name == "deploy" and
      .status == "completed" and
      .conclusion == "success" and
      .run_attempt == $run[0].run_attempt
    )] | length) == 1 and
    all($allJobs[0].jobs[];
      .run_id == $runId and
      .head_sha == $commit and
      .run_attempt <= $run[0].run_attempt) and
    all(.jobs[]; . as $latest | any($allJobs[0].jobs[]; . == $latest))
  ' "$legacy_jobs_response" >/dev/null || {
    echo 'The legacy release Documentation run does not contain one successful build and deploy job.' >&2
    exit 1
  }
  legacy_build_window="$(
    jq -er '
      .jobs[] |
      select(.name == "build") |
      [.started_at, .completed_at] |
      @tsv
    ' "$legacy_jobs_response"
  )"
  IFS=$'\t' read -r legacy_build_started_at legacy_build_completed_at <<< "$legacy_build_window"

  fetch_artifact_inventory "$release_run_id" "$legacy_artifacts_response"
  jq -e \
    --argjson runId "$release_run_id" \
    --arg tag "$release_tag" \
    --arg commit "$expected_release_commit" \
    --slurpfile allJobs "$legacy_all_jobs_response" '
      def build_window($created):
        [
          $allJobs[0].jobs[] |
          select(
            .name == "build" and
            .conclusion != "skipped" and
            (.started_at | fromdateiso8601) <= $created and
            $created <= (.completed_at | fromdateiso8601)
          )
        ] |
        length >= 1;
      .total_count >= 1 and
      all(.artifacts[]; . as $artifact |
        .name == "github-pages" and
        .workflow_run.id == $runId and
        .workflow_run.head_branch == $tag and
        .workflow_run.head_sha == $commit and
        build_window((.created_at | fromdateiso8601)))
    ' "$legacy_artifacts_response" >/dev/null || {
    echo 'The legacy release Documentation run artifact inventory is not confined to tag-and-commit-bound github-pages artifacts.' >&2
    exit 1
  }

  controller_metadata="$temporary_root/current-controller.json"
  materialize_controller_file release/current-controller.json "$controller_metadata"
  metadata_evidence_root="$(
    jq -er '
      select(
        type == "object" and
        keys == ["current", "predecessor", "schemaVersion"] and
        .schemaVersion == 1 and
        (.current | type == "object" and
          keys == ["commit", "representativeMinecraftVersions", "tag", "tagObject"] and
          (.tag | type == "string" and test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
          (.commit | type == "string" and test("^[0-9a-f]{40}$")) and
          (.tagObject | type == "string" and test("^[0-9a-f]{40}$")) and
          (.representativeMinecraftVersions | type == "array" and length > 0) and
          (.representativeMinecraftVersions | length == (unique | length)) and
          all(.representativeMinecraftVersions[];
            type == "string" and test("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*$"))
        ) and
        (.predecessor | type == "object" and
          keys == ["commit", "tag", "tagObject"] and
          (.tag | type == "string" and test("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")) and
          (.commit | type == "string" and test("^[0-9a-f]{40}$")) and
          (.tagObject | type == "string" and test("^[0-9a-f]{40}$"))
        ) and
        .current.tag != .predecessor.tag and
        .current.commit != .predecessor.commit and
        .current.tagObject != .predecessor.tagObject
      ) |
      [.current.tag, .current.commit] | @tsv
    ' "$controller_metadata" 2>/dev/null
  )" || {
    echo 'The exact controller metadata does not contain a canonical current Pages evidence identity.' >&2
    exit 1
  }
  IFS=$'\t' read -r metadata_evidence_root_tag metadata_evidence_root_commit <<< "$metadata_evidence_root"
  if (( $# == 7 )); then
    [[ "$evidence_root_tag" == "$metadata_evidence_root_tag" && \
      "$expected_evidence_root_commit" == "$metadata_evidence_root_commit" ]] || {
      echo 'The explicit legacy Pages evidence root differs from exact controller metadata.' >&2
      exit 1
    }
  else
    evidence_root_tag="$metadata_evidence_root_tag"
    expected_evidence_root_commit="$metadata_evidence_root_commit"
  fi
  git --no-replace-objects merge-base --is-ancestor \
    "$expected_release_commit" "$expected_evidence_root_commit" || {
    echo 'The legacy release Pages commit is not an ancestor of the current evidence root.' >&2
    exit 1
  }
fi

[[ "$evidence_root_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'The release Pages evidence root requires an exact semantic release tag.' >&2
  exit 1
}
[[ "$expected_evidence_root_commit" =~ ^[0-9a-f]{40}$ ]] || {
  echo 'The expected release Pages evidence root commit must be a full lowercase Git SHA.' >&2
  exit 1
}

download_pages_artifact() {
  local artifact_name="$1"
  local label="$2"
  local artifact_record=""
  local artifact_id=""
  local artifact_size=""
  local artifact_digest=""
  local artifact_zip="$temporary_root/$label-artifact.zip"
  artifact_record="$(
    jq -er \
      --arg artifactName "$artifact_name" \
      --argjson runId "$controller_run_id" \
      --arg commit "$expected_controller_commit" '
        [.artifacts[] | select(.name == $artifactName)] |
        select(length == 1) |
        .[0] |
        select(
          .expired == false and
          .workflow_run.id == $runId and
          .workflow_run.head_branch == "master" and
          .workflow_run.head_sha == $commit
        ) |
        [.id, .size_in_bytes, .digest] |
        @tsv
      ' "$artifacts_response"
  )" || {
    echo "The Documentation run does not have the exact selected commit-bound $artifact_name artifact." >&2
    exit 1
  }
  IFS=$'\t' read -r artifact_id artifact_size artifact_digest <<< "$artifact_record"

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$artifact_zip"
  [[ "$(stat -c '%s' "$artifact_zip")" == "$artifact_size" ]] || {
    echo "The downloaded $artifact_name ZIP size differs from immutable artifact metadata." >&2
    exit 1
  }
  [[ "sha256:$(sha256sum "$artifact_zip" | cut -d ' ' -f 1)" == "$artifact_digest" ]] || {
    echo "The downloaded $artifact_name ZIP digest differs from immutable artifact metadata." >&2
    exit 1
  }

  printf '%s\n' "$artifact_id"
}

download_legacy_pages_artifact() {
  local artifact_record=""
  local artifact_id=""
  local artifact_size=""
  local artifact_digest=""
  local artifact_zip="$temporary_root/legacy-artifact.zip"
  artifact_record="$(
    jq -er \
      --argjson runId "$release_run_id" \
      --arg tag "$release_tag" \
      --arg commit "$expected_release_commit" \
      --arg startedAt "$legacy_build_started_at" \
      --arg completedAt "$legacy_build_completed_at" '
        [.artifacts[] | select(.name == "github-pages")] |
        map(select(
          ($startedAt | fromdateiso8601) <= (.created_at | fromdateiso8601) and
          (.created_at | fromdateiso8601) <= ($completedAt | fromdateiso8601)
        )) |
        select(length == 1) |
        .[0] |
        select(
          .expired == false and
          .workflow_run.id == $runId and
          .workflow_run.head_branch == $tag and
          .workflow_run.head_sha == $commit
        ) |
        [.id, .size_in_bytes, .digest] |
        @tsv
      ' "$legacy_artifacts_response"
  )" || {
    echo 'The legacy release run does not have exactly one downloadable github-pages artifact from its latest successful build window.' >&2
    exit 1
  }
  IFS=$'\t' read -r artifact_id artifact_size artifact_digest <<< "$artifact_record"

  gh api "${api_header[@]}" "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$artifact_zip"
  [[ "$(stat -c '%s' "$artifact_zip")" == "$artifact_size" ]] || {
    echo 'The downloaded legacy github-pages ZIP size differs from immutable artifact metadata.' >&2
    exit 1
  }
  [[ "sha256:$(sha256sum "$artifact_zip" | cut -d ' ' -f 1)" == "$artifact_digest" ]] || {
    echo 'The downloaded legacy github-pages ZIP digest differs from immutable artifact metadata.' >&2
    exit 1
  }

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

verify_zip_artifact_receipt() {
  local artifact_zip="$1"
  local receipt_target="$2"
  local expected_receipt="$3"
  local receipt_output="$4"
  local receipt_label="$5"
  local receipt_entries=()

  mapfile -t receipt_entries < <(
    unzip -Z1 "$artifact_zip" |
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
  [[ "${#receipt_entries[@]}" == '1' ]] || {
    echo "The release Pages evidence must contain exactly one $receipt_label source receipt." >&2
    exit 1
  }
  unzip -p "$artifact_zip" "${receipt_entries[0]}" > "$receipt_output"
  cmp --silent "$expected_receipt" "$receipt_output" || {
    echo "The $receipt_label release Pages evidence source receipt differs from its exact revision and commit." >&2
    exit 1
  }
}

release_artifact_id="$(download_pages_artifact "$expected_release_artifact_name" release)"
controller_artifact_id="$(download_pages_artifact "$expected_controller_artifact_name" controller)"
legacy_artifact_id=""
legacy_artifact_tar=""
if [[ "$legacy_release_run" == true ]]; then
  legacy_artifact_id="$(download_legacy_pages_artifact)"
  mapfile -t legacy_zip_entries < <(unzip -Z1 "$temporary_root/legacy-artifact.zip")
  [[ "${#legacy_zip_entries[@]}" == '1' && "${legacy_zip_entries[0]}" == 'artifact.tar' ]] || {
    echo 'The downloaded legacy github-pages ZIP must contain only artifact.tar.' >&2
    exit 1
  }
  legacy_artifact_tar="$temporary_root/legacy-artifact.tar"
  unzip -p "$temporary_root/legacy-artifact.zip" artifact.tar > "$legacy_artifact_tar"
fi

mapfile -t controller_zip_entries < <(unzip -Z1 "$temporary_root/controller-artifact.zip")
[[ "${#controller_zip_entries[@]}" == '1' && "${controller_zip_entries[0]}" == 'artifact.tar' ]] || {
  echo 'The downloaded github-pages ZIP must contain only artifact.tar.' >&2
  exit 1
}
unzip -p "$temporary_root/controller-artifact.zip" artifact.tar > "$temporary_root/controller-artifact.tar"

release_receipt="$temporary_root/expected-release-receipt.json"
evidence_root_receipt="$temporary_root/expected-evidence-root-receipt.json"
controller_receipt="$temporary_root/expected-controller-receipt.json"
printf '{"commit":"%s","revision":"%s"}\n' "$expected_release_commit" "$release_tag" > "$release_receipt"
printf '{"commit":"%s","revision":"%s"}\n' \
  "$expected_evidence_root_commit" "$evidence_root_tag" > "$evidence_root_receipt"
printf '{"commit":"%s","revision":"master"}\n' "$expected_controller_commit" > "$controller_receipt"

verify_zip_artifact_receipt \
  "$temporary_root/release-artifact.zip" \
  'source-receipt.json' \
  "$evidence_root_receipt" \
  "$temporary_root/release-root-source-receipt.json" \
  'root'
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
  if command -v "$candidate" >/dev/null 2>&1 && \
    "$candidate" -c 'import pathlib, shutil, stat, tarfile, zipfile' >/dev/null 2>&1; then
    pages_python="$candidate"
    break
  fi
done
[[ -n "$pages_python" ]] || {
  echo 'Python with the standard tarfile module is required to compare immutable Pages artifacts.' >&2
  exit 1
}

extract_validated_zip_archive() {
  local artifact_zip="$1"
  local destination="$2"
  "$pages_python" - "$artifact_zip" "$destination" <<'PY'
import pathlib
import shutil
import stat
import sys
import zipfile

archive_path = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
destination.mkdir(parents=True, exist_ok=False)
seen = set()

with zipfile.ZipFile(archive_path, mode="r") as archive:
    for member in archive.infolist():
        normalized = member.filename.rstrip("/")
        while normalized.startswith("./"):
            normalized = normalized[2:]
        if not normalized:
            if not member.is_dir():
                raise SystemExit("The release Pages evidence root is not a directory.")
            continue
        if "\\" in normalized:
            raise SystemExit("The release Pages evidence contains a non-canonical backslash path.")
        relative = pathlib.PurePosixPath(normalized)
        if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
            raise SystemExit("The release Pages evidence contains an unsafe path.")
        key = relative.as_posix()
        if key != normalized:
            raise SystemExit("The release Pages evidence contains a non-canonical path.")
        if key in seen:
            raise SystemExit("The release Pages evidence contains a duplicate path.")
        seen.add(key)
        file_type = stat.S_IFMT(member.external_attr >> 16)
        if file_type not in (0, stat.S_IFDIR, stat.S_IFREG):
            raise SystemExit("The release Pages evidence contains a non-regular entry.")
        target = destination.joinpath(*relative.parts)
        if member.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(member, mode="r") as source, target.open("xb") as output:
            shutil.copyfileobj(source, output)
PY
}

release_evidence_directory="$temporary_root/release-evidence"
extract_validated_zip_archive "$temporary_root/release-artifact.zip" "$release_evidence_directory"
script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
artifact_comparator="$script_directory/verify-pages-artifact-equivalence.sh"
if [[ -e "$artifact_comparator" || -L "$artifact_comparator" ]]; then
  [[ -f "$artifact_comparator" && ! -L "$artifact_comparator" ]] || {
    echo 'The controller Pages artifact comparator is unsafe.' >&2
    exit 1
  }
  if [[ "$legacy_release_run" == true ]]; then
    artifact_comparator_blob="$(controller_tree_blob release/verify-pages-artifact-equivalence.sh)"
    [[ "$(git hash-object --no-filters -- "$artifact_comparator")" == "$artifact_comparator_blob" ]] || {
      echo 'The adjacent Pages artifact comparator differs from the exact controller blob.' >&2
      exit 1
    }
  fi
elif [[ "$legacy_release_run" == true ]]; then
  artifact_comparator="$temporary_root/verify-pages-artifact-equivalence.sh"
  materialize_controller_file release/verify-pages-artifact-equivalence.sh "$artifact_comparator"
  chmod a-w -- "$artifact_comparator"
else
  echo 'The controller Pages artifact comparator is missing.' >&2
  exit 1
fi
bash -n "$artifact_comparator"
artifact_comparator_arguments=(
  "$temporary_root/controller-artifact.tar"
  "$release_evidence_directory"
  "$release_tag"
  "$expected_release_commit"
  "$expected_controller_commit"
  "$evidence_root_tag"
  "$expected_evidence_root_commit"
)
if [[ "$legacy_release_run" == true ]]; then
  artifact_comparator_arguments+=("$legacy_artifact_tar")
fi
bash "$artifact_comparator" "${artifact_comparator_arguments[@]}" >/dev/null

global_deployments_pages="$temporary_root/global-deployment-pages.json"
global_deployments_response="$temporary_root/global-deployments.json"
controller_statuses_pages="$temporary_root/controller-status-pages.json"
controller_statuses_response="$temporary_root/controller-statuses.json"
legacy_statuses_pages="$temporary_root/legacy-release-status-pages.json"
legacy_statuses_response="$temporary_root/legacy-release-statuses.json"
environment_response="$temporary_root/github-pages-controller-environment.json"
policies_response="$temporary_root/github-pages-controller-deployment-policies.json"
retired_environment_response="$temporary_root/github-pages-retired-environment.json"
retired_policies_response="$temporary_root/github-pages-retired-deployment-policies.json"

fetch_paginated_array() {
  local endpoint="$1"
  local pages_output="$2"
  local normalized_output="$3"
  local label="$4"
  gh api "${api_header[@]}" --paginate --slurp "$endpoint" > "$pages_output"
  jq -e '
    if type == "array" and length > 0 and
      all(.[]; type == "array") and
      all(.[][]; type == "object")
    then [.[][]]
    else error("invalid paginated array response")
    end
  ' "$pages_output" > "$normalized_output" || {
    echo "The $label response is not a complete paginated array." >&2
    exit 1
  }
}

gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/environments/github-pages-controller" > "$environment_response"
jq -e '
  .can_admins_bypass == false and
  .deployment_branch_policy.protected_branches == false and
  .deployment_branch_policy.custom_branch_policies == true
' "$environment_response" >/dev/null || {
  echo 'github-pages-controller allows an administrative bypass or does not use custom deployment branch policies.' >&2
  exit 1
}
gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/environments/github-pages-controller/deployment-branch-policies?per_page=100" > "$policies_response"
jq -e '
  .total_count == 1 and
  (.branch_policies | length) == 1 and
  .branch_policies[0].name == "master" and
  .branch_policies[0].type == "branch"
' "$policies_response" >/dev/null || {
  echo 'github-pages-controller does not permit exactly the master branch.' >&2
  exit 1
}
gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/environments/github-pages" > "$retired_environment_response"
jq -e '
  .can_admins_bypass == false and
  .deployment_branch_policy.protected_branches == false and
  .deployment_branch_policy.custom_branch_policies == true
' "$retired_environment_response" >/dev/null || {
  echo 'The retired github-pages environment allows an administrative bypass or does not use custom deployment policies.' >&2
  exit 1
}
gh api "${api_header[@]}" \
  "repos/$GITHUB_REPOSITORY/environments/github-pages/deployment-branch-policies?per_page=100" > "$retired_policies_response"
jq -e '
  .total_count == 0 and
  (.branch_policies | length) == 0
' "$retired_policies_response" >/dev/null || {
  echo 'The retired github-pages environment still permits a branch or tag.' >&2
  exit 1
}

fetch_paginated_array \
  "repos/$GITHUB_REPOSITORY/deployments?per_page=100" \
  "$global_deployments_pages" \
  "$global_deployments_response" \
  'global deployments'
controller_deployment_id="$(
  jq -er \
    --arg commit "$expected_controller_commit" '
      def valid_id:
        type == "number" and . > 0 and . == floor;
      def valid_created_at:
        . as $value |
        ($value | type) == "string" and
        ($value | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
        (try (($value | fromdateiso8601 | todateiso8601) == $value) catch false);
      [.[] | select(.environment == "github-pages-controller" or .environment == "github-pages")] |
      select(length > 0) |
      select(all(.[]; (.id | valid_id) and (.created_at | valid_created_at))) |
      select((map(.id) | unique | length) == length) |
      sort_by([(.created_at | fromdateiso8601), .id]) |
      last |
      select(
        .ref == "master" and
        .sha == $commit and
        .environment == "github-pages-controller" and
        .task == "deploy"
      ) |
      .id |
      select(type == "number" and . > 0 and . == floor) |
      tostring
    ' "$global_deployments_response"
)" || {
  echo 'The globally newest Pages deployment is not bound to the exact controller master commit and environment.' >&2
  exit 1
}
fetch_paginated_array \
  "repos/$GITHUB_REPOSITORY/deployments/$controller_deployment_id/statuses?per_page=100" \
  "$controller_statuses_pages" \
  "$controller_statuses_response" \
  'controller deployment statuses'
jq -e \
  --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$controller_run_id" \
  --arg runPrefix "https://github.com/$GITHUB_REPOSITORY/actions/runs/$controller_run_id/" '
    def valid_id:
      type == "number" and . > 0 and . == floor;
    def valid_created_at:
      . as $value |
      ($value | type) == "string" and
      ($value | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
      (try (($value | fromdateiso8601 | todateiso8601) == $value) catch false);
    select(length > 0) |
    select(all(.[]; (.id | valid_id) and (.created_at | valid_created_at))) |
    select((map(.id) | unique | length) == length) |
    sort_by([(.created_at | fromdateiso8601), .id]) |
    last |
    select(
      .state == "success" and
      .environment == "github-pages-controller" and
      ([.log_url, .target_url] |
        map(select(type == "string")) |
        any(. == $runUrl or startswith($runPrefix)))
    )
  ' "$controller_statuses_response" >/dev/null || {
  echo 'The current controller Pages deployment does not have a latest successful status bound to its exact run and environment.' >&2
  exit 1
}

record_release_artifact_id="$release_artifact_id"
record_release_deployment_id="$controller_deployment_id"
if [[ "$legacy_release_run" == true ]]; then
  legacy_deployment_id="$(
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
        select(length > 0) |
        sort_by([(.created_at | fromdateiso8601), .id]) |
        last |
        .id |
        select(type == "number" and . > 0 and . == floor) |
        tostring
      ' "$global_deployments_response"
  )" || {
    echo 'No historical github-pages deployment is bound to the exact legacy release tag and commit.' >&2
    exit 1
  }
  fetch_paginated_array \
    "repos/$GITHUB_REPOSITORY/deployments/$legacy_deployment_id/statuses?per_page=100" \
    "$legacy_statuses_pages" \
    "$legacy_statuses_response" \
    'legacy release deployment statuses'
  jq -e \
    --arg runUrl "https://github.com/$GITHUB_REPOSITORY/actions/runs/$release_run_id" \
    --arg runPrefix "https://github.com/$GITHUB_REPOSITORY/actions/runs/$release_run_id/" '
      def valid_id:
        type == "number" and . > 0 and . == floor;
      def valid_created_at:
        . as $value |
        ($value | type) == "string" and
        ($value | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
        (try (($value | fromdateiso8601 | todateiso8601) == $value) catch false);
      select(length > 0) |
      select(all(.[]; (.id | valid_id) and (.created_at | valid_created_at))) |
      select((map(.id) | unique | length) == length) |
      . as $statuses |
      ($statuses | sort_by([(.created_at | fromdateiso8601), .id]) | last) as $latest |
      select(
        ($latest.state == "success" or $latest.state == "inactive") and
        $latest.environment == "github-pages" and
        any(
          $statuses[];
          .state == "success" and
          .environment == "github-pages" and
          ([.log_url, .target_url] |
            map(select(type == "string")) |
            any(. == $runUrl or startswith($runPrefix)))
        )
      )
    ' "$legacy_statuses_response" >/dev/null || {
    echo 'The historical release github-pages deployment never succeeded from its exact run.' >&2
    exit 1
  }
  record_release_artifact_id="$legacy_artifact_id"
  record_release_deployment_id="$legacy_deployment_id"
fi

printf '%s %s %s %s %s %s\n' \
  "$release_run_id" \
  "$record_release_artifact_id" \
  "$record_release_deployment_id" \
  "$controller_run_id" \
  "$controller_artifact_id" \
  "$controller_deployment_id"

#!/usr/bin/env bash

set -euo pipefail

project_root=${1:-$(cd "$(dirname "$0")/.." && pwd)}
output_directory=${2:-"$project_root/build/github-actions"}
version_pattern='^[0-9]+(\.[0-9]+)*$'

fail() {
  echo "$1" >&2
  exit 1
}

command -v sort >/dev/null 2>&1 || fail 'sort is required to order Minecraft versions.'

discover_versions() {
  local parent_name=$1
  local -n discovered_versions=$2
  local parent_directory="$project_root/$parent_name"
  local project_directory
  local project_name
  local version

  [[ -d "$parent_directory" ]] || fail "Missing Minecraft project parent: $parent_directory"
  while IFS= read -r -d '' project_directory; do
    project_name=${project_directory##*/}
    version=${project_name#minecraft-fabric-}
    if [[ -f "$project_directory/build.gradle.kts" ]]; then
      [[ $version =~ $version_pattern ]] || fail "Invalid versioned Minecraft project directory: $parent_name/$project_name"
      discovered_versions+=("$version")
    fi
  done < <(find "$parent_directory" -mindepth 1 -maxdepth 1 -type d -name 'minecraft-fabric-*' -print0)
}

runtime_versions=()
integration_versions=()
discover_versions runtime runtime_versions
discover_versions integration integration_versions
if (( ${#runtime_versions[@]} > 0 )); then
  mapfile -t runtime_versions < <(printf '%s\n' "${runtime_versions[@]}" | LC_ALL=C sort -V)
fi
if (( ${#integration_versions[@]} > 0 )); then
  mapfile -t integration_versions < <(printf '%s\n' "${integration_versions[@]}" | LC_ALL=C sort -V)
fi

(( ${#runtime_versions[@]} > 0 )) || fail 'No versioned Minecraft runtime projects were found.'
if [[ $(printf '%s\n' "${runtime_versions[@]}") != $(printf '%s\n' "${integration_versions[@]}") ]]; then
  runtime_only=$(comm -23 <(printf '%s\n' "${runtime_versions[@]}" | LC_ALL=C sort) <(printf '%s\n' "${integration_versions[@]}" | LC_ALL=C sort))
  integration_only=$(comm -13 <(printf '%s\n' "${runtime_versions[@]}" | LC_ALL=C sort) <(printf '%s\n' "${integration_versions[@]}" | LC_ALL=C sort))
  fail "Minecraft runtime and integration projects must form exact version pairs. Runtime only: ${runtime_only:-none}; integration only: ${integration_only:-none}."
fi

versions=("${runtime_versions[@]}")
# Native showcase comparison shares the job that owns its actual parity input.
# Read the checked build dependency instead of assuming the newest target owns it.
docs_build="$project_root/integration/docs/build.gradle.kts"
[[ -f "$docs_build" ]] || fail 'The documentation build is missing.'
mapfile -t docs_versions < <(
  grep -oE ':integration:minecraft-fabric-[^":[:space:]]+' "$docs_build" |
    sed 's/^:integration:minecraft-fabric-//' | LC_ALL=C sort -u
)
(( ${#docs_versions[@]} == 1 )) || fail 'Documentation must declare exactly one native Minecraft input version.'
docs_version=${docs_versions[0]}
[[ "$docs_version" =~ $version_pattern ]] || fail "Invalid documentation native Minecraft input: $docs_version"
printf '%s\n' "${versions[@]}" | grep -Fx "$docs_version" >/dev/null ||
  fail "Documentation native input has no paired Minecraft target: $docs_version"
if [[ "${STRATA_MC_VERSIONS:-all}" != all ]]; then
  selected_versions=()
  IFS=',' read -r -a requested_versions <<< "${STRATA_MC_VERSIONS}"
  if [[ "${STRATA_CI_DOCS:-true}" == true ]]; then requested_versions+=("$docs_version"); fi
  for requested in "${requested_versions[@]}"; do
    [[ -z "$requested" ]] && continue
    printf '%s\n' "${versions[@]}" | grep -Fx "$requested" >/dev/null || fail "Unknown Minecraft CI version: $requested"
    selected_versions+=("$requested")
  done
  (( ${#selected_versions[@]} > 0 )) || fail 'The selected Minecraft CI inventory is empty.'
  mapfile -t versions < <(printf '%s\n' "${selected_versions[@]}" | LC_ALL=C sort -Vu)
fi
matrix_entries=()
loom_projects=()
for version in "${versions[@]}"; do
  version_projects=("runtime/minecraft-fabric-$version" "integration/minecraft-fabric-$version")
  loom_projects+=("${version_projects[@]}")
  gradle_arguments=":ciMinecraftCheck -Pstrata.minecraftVersions=$version"
  job_name="Minecraft $version"
  documentation=false
  if [[ "$version" == "$docs_version" && "${STRATA_CI_DOCS:-true}" == true ]]; then
    gradle_arguments=":ciMinecraftCheck :integration:docs:checkMinecraftShowcaseParity -Pstrata.minecraftVersions=$version"
    documentation=true
  fi
  loom_project_lines=$(printf '%s\n' "${version_projects[@]}")
  loom_project_lines=${loom_project_lines//$'\n'/\\n}
  matrix_entries+=(
    "{\"id\":\"minecraft-${version//./-}\",\"version\":\"$version\",\"documentation\":$documentation,\"name\":\"$job_name\",\"gradle_arguments\":\"$gradle_arguments\",\"loom_projects\":\"$loom_project_lines\"}"
  )
done

mkdir -p "$output_directory"
(IFS=,; printf '{"include":[%s]}\n' "${matrix_entries[*]}") > "$output_directory/minecraft-matrix.json"
printf '%s\n' "${loom_projects[@]}" > "$output_directory/minecraft-loom-projects.txt"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then echo "docs_version=$docs_version" >> "$GITHUB_OUTPUT"; fi
echo "Planned ${#versions[@]} independent Minecraft version jobs."

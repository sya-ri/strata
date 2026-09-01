#!/usr/bin/env bash

set -euo pipefail

project_root=${1:-$(cd "$(dirname "$0")/.." && pwd)}
output_directory=${2:-"$project_root/build/github-actions"}
maximum_shard_size=7
target_parallelism=4
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
minimum_shard_count=$(( (${#versions[@]} + maximum_shard_size - 1) / maximum_shard_size ))
shard_count=$target_parallelism
(( minimum_shard_count <= shard_count )) || shard_count=$minimum_shard_count
(( shard_count <= ${#versions[@]} )) || shard_count=${#versions[@]}

matrix_entries=()
loom_projects=()
first_version_index=0
for (( shard_index = 0; shard_index < shard_count; shard_index++ )); do
  shard_size=$(( ${#versions[@]} / shard_count ))
  (( shard_index < ${#versions[@]} % shard_count )) && shard_size=$(( shard_size + 1 ))
  (( shard_size <= maximum_shard_size )) || fail "Minecraft CI shard $shard_index exceeds $maximum_shard_size versions."
  shard_versions=("${versions[@]:first_version_index:shard_size}")
  first_version_index=$(( first_version_index + shard_size ))

  shard_loom_projects=()
  for version in "${shard_versions[@]}"; do
    shard_loom_projects+=("runtime/minecraft-fabric-$version" "integration/minecraft-fabric-$version")
    loom_projects+=("runtime/minecraft-fabric-$version" "integration/minecraft-fabric-$version")
  done

  first_version=${shard_versions[0]}
  last_version=${shard_versions[${#shard_versions[@]} - 1]}
  version_range=$first_version
  [[ $first_version == "$last_version" ]] || version_range="$first_version-$last_version"
  version_csv=$(IFS=,; printf '%s' "${shard_versions[*]}")
  gradle_arguments=":ciMinecraftCheck -Pstrata.minecraftVersions=$version_csv"
  shard_name="Minecraft $version_range"
  if (( shard_index == shard_count - 1 )); then
    gradle_arguments=":ciMinecraftCheck :integration:docs:check -Pstrata.minecraftVersions=$version_csv"
    shard_name="$shard_name and documentation"
  fi
  loom_project_lines=$(printf '%s\n' "${shard_loom_projects[@]}")
  loom_project_lines=${loom_project_lines//$'\n'/\\n}
  matrix_entries+=(
    "{\"id\":\"minecraft-${first_version//./-}-${last_version//./-}\",\"name\":\"$shard_name\",\"gradle_arguments\":\"$gradle_arguments\",\"loom_projects\":\"$loom_project_lines\"}"
  )
done

(( first_version_index == ${#versions[@]} )) || fail 'Minecraft CI shards did not consume every discovered version.'
(( ${#loom_projects[@]} == ${#versions[@]} * 2 )) || fail 'Minecraft Loom project inventory is incomplete.'

mkdir -p "$output_directory"
(IFS=,; printf '{"include":[%s]}\n' "${matrix_entries[*]}") > "$output_directory/minecraft-matrix.json"
printf '%s\n' "${loom_projects[@]}" > "$output_directory/minecraft-loom-projects.txt"
echo "Planned ${#versions[@]} Minecraft versions in $shard_count CI shards."

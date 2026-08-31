#!/usr/bin/env bash

set -euo pipefail

model=${1:?Pass the Qodana projectStructure/Modules.json path}
script_root=$(cd "$(dirname "$0")" && pwd)
repository_root=$(cd "$script_root/.." && pwd)
project_root=${2:-$repository_root}
if [[ -f "$model" ]]; then
  :
else
  echo "Qodana did not produce $model" >&2
  exit 1
fi

if [[ -d "$project_root" ]]; then
  project_root=$(cd "$project_root" && pwd)
else
  echo "Qodana project inventory root does not exist: $project_root" >&2
  exit 1
fi

inventory_directory=$(mktemp -d)
trap 'rm -rf -- "$inventory_directory"' EXIT
bash "$script_root/plan-minecraft-ci.sh" "$project_root" "$inventory_directory" >/dev/null
mapfile -t expected_versions < <(sed -n 's#^runtime/minecraft-fabric-##p' "$inventory_directory/minecraft-loom-projects.txt")

project_modules=(
  'runtime;^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-[0-9]+([._][0-9]+)*$;^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-'
  'integration;^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-[0-9]+([._][0-9]+)*$;^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-'
)
for project_modules_entry in "${project_modules[@]}"; do
  IFS=';' read -r label pattern prefix_pattern <<< "$project_modules_entry"
  mapfile -t actual_versions < <(
    jq -r --arg pattern "$pattern" --arg prefixPattern "$prefix_pattern" '
      .modules[]
      | .name
      | select(test($pattern))
      | sub($prefixPattern; "")
      | gsub("_"; ".")
    ' "$model" |
      LC_ALL=C sort -V
  )
  expected_display=$(IFS=,; printf '%s' "${expected_versions[*]}")
  actual_display=$(IFS=,; printf '%s' "${actual_versions[*]}")
  if [[ "$actual_display" == "$expected_display" ]]; then
    echo "Verified ${#actual_versions[@]} Minecraft $label project modules for [$actual_display]."
  else
    echo "Expected Minecraft $label project modules for [$expected_display] but found [$actual_display]." >&2
    exit 1
  fi
done

font_backend_pattern='^(strata\.runtime\.minecraft-fonts-lwjgl|runtime-minecraft-fonts-lwjgl|minecraft-fonts-lwjgl)$'
font_backend_count=$(jq --arg pattern "$font_backend_pattern" '[.modules[] | select(.name | test($pattern))] | length' "$model")
if [[ "$font_backend_count" -ne 1 ]]; then
  echo "Expected one CPU font backend module but found $font_backend_count." >&2
  exit 1
fi

incomplete_font_backend=$(
  jq -c --arg pattern "$font_backend_pattern" '
    [
      .modules[]
      | select(.name | test($pattern))
      | select(
          (.orderEntries | length) < 3
          or (.orderEntries | any(.type == "SDK") | not)
          or ([.contentEntries[].sourceFolders[]? | select(.type == "Source")] | length == 0)
          or ([.contentEntries[].sourceFolders[]? | select(.type == "TestSource")] | length == 0)
        )
      | .name
    ]
  ' "$model"
)
if [[ "$incomplete_font_backend" == '[]' ]]; then
  echo 'Verified the CPU font backend SDK, dependencies, and main/test source folders.'
else
  echo "The CPU font backend has an incomplete imported model: $incomplete_font_backend" >&2
  exit 1
fi

project_module_pattern='^((strata\.(runtime|integration)\.minecraft-fabric)|((runtime|integration)-minecraft-fabric))-[0-9]+([._][0-9]+)*$'
incomplete_modules=$(
  jq -c --arg pattern "$project_module_pattern" '
    [
      .modules[]
      | select(.name | test($pattern))
      | select(
          (.orderEntries | length) < 3
          or (.orderEntries | any(.type == "SDK") | not)
        )
      | .name
    ]
  ' "$model"
)
if [[ "$incomplete_modules" == '[]' ]]; then
  echo 'Verified SDK and dependency entries for every Minecraft project module.'
else
  echo "Minecraft project modules have an incomplete imported model: $incomplete_modules" >&2
  exit 1
fi

missing_sources=$(
  jq -c '
    [
      .modules[]
      | select(.name | test("^(strata\\.runtime\\.minecraft-fabric|runtime-minecraft-fabric)-[0-9]+([._][0-9]+)*$"))
      | (.name | sub("^(strata\\.runtime\\.minecraft-fabric|runtime-minecraft-fabric)-"; "") | gsub("_"; ".")) as $version
      | ("file://$PROJECT_DIR$/runtime/minecraft-fabric-" + $version + "/src/font/kotlin") as $font_source
      | select([.contentEntries[].sourceFolders[]? | select(.type == "Source" and .path == $font_source)] | length != 1)
      | .name
    ]
    +
    [
      .modules[]
      | select(.name | test("^(strata\\.integration\\.minecraft-fabric|integration-minecraft-fabric)-[0-9]+([._][0-9]+)*$"))
      | select([.contentEntries[].sourceFolders[]? | select(.type == "TestSource")] | length == 0)
      | .name
    ]
  ' "$model"
)
if [[ "$missing_sources" == '[]' ]]; then
  echo 'Verified runtime and GameTest source folders for every Minecraft release.'
else
  echo "Minecraft project modules are missing owned sources: $missing_sources" >&2
  exit 1
fi

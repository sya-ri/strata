#!/usr/bin/env bash

set -euo pipefail

model=${1:?Pass the Qodana projectStructure/Modules.json path}
if [[ -f "$model" ]]; then
  :
else
  echo "Qodana did not produce $model" >&2
  exit 1
fi

expected_projects=20
project_modules=(
  'runtime|^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-[^.]+$'
  'integration|^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-[^.]+$'
)
for project_modules_entry in "${project_modules[@]}"; do
  IFS='|' read -r label pattern <<< "$project_modules_entry"
  count=$(jq --arg pattern "$pattern" '[.modules[] | select(.name | test($pattern))] | length' "$model")
  if [[ "$count" -eq "$expected_projects" ]]; then
    echo "Verified $count Minecraft $label project modules."
  else
    echo "Expected $expected_projects Minecraft $label project modules but found $count." >&2
    exit 1
  fi
done

project_module_pattern='^((strata\.(runtime|integration)\.minecraft-fabric)|((runtime|integration)-minecraft-fabric))-[^.]+$'
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
      | select(.name | test("^(strata\\.runtime\\.minecraft-fabric|runtime-minecraft-fabric)-[^.]+$"))
      | select([.contentEntries[].sourceFolders[]? | select(.type == "Source")] | length == 0)
      | .name
    ]
    +
    [
      .modules[]
      | select(.name | test("^(strata\\.integration\\.minecraft-fabric|integration-minecraft-fabric)-[^.]+$"))
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

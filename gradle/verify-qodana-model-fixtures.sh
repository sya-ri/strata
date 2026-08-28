#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -f "$fixture_root/Modules.json" "$fixture_root/valid.json" "$fixture_root/valid.log" "$fixture_root/invalid.json" "$fixture_root/invalid.log"; rmdir "$fixture_root"' EXIT

jq -n '
  def projectModule($name; $source; $path): {
    name: $name,
    orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}],
    contentEntries: [{sourceFolders: [{type: $source, path: $path}]}]
  };
  {modules:
    ([range(1;22) | projectModule("runtime-minecraft-fabric-1." + tostring; "Source"; "file://$PROJECT_DIR$/runtime/minecraft-fabric-1." + tostring + "/src/font/kotlin")]
    + [range(1;22) | projectModule("integration-minecraft-fabric-1." + tostring; "TestSource"; "file://$PROJECT_DIR$/integration/minecraft-fabric-1." + tostring + "/src/gametest/kotlin")]
    + [projectModule("minecraft-fonts-lwjgl"; "Source"; "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/main/kotlin") | .contentEntries[0].sourceFolders += [{type: "TestSource", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/test/kotlin"}]])
  }
  | .modules[0].name = "strata.runtime.minecraft-fabric-1_1"
' > "$fixture_root/Modules.json"

for backend_name in minecraft-fonts-lwjgl runtime-minecraft-fonts-lwjgl strata.runtime.minecraft-fonts-lwjgl; do
  jq --arg name "$backend_name" '.modules[-1].name = $name' "$fixture_root/Modules.json" > "$fixture_root/valid.json"
  bash "$project_root/gradle/verify-qodana-model.sh" "$fixture_root/valid.json" > "$fixture_root/valid.log"
done

assert_rejected() {
  local transformation=$1
  jq "$transformation" "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
  if bash "$project_root/gradle/verify-qodana-model.sh" "$fixture_root/invalid.json" > "$fixture_root/invalid.log" 2>&1; then
    echo "Qodana model unexpectedly accepted: $transformation" >&2
    exit 1
  fi
}

assert_rejected '.modules = .modules[:-1]'
assert_rejected '.modules += [.modules[-1]]'
assert_rejected '.modules[-1].name = "minecraft-fonts-lwjgl-extra"'
assert_rejected '.modules[-1].orderEntries |= map(select(.type != "SDK"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "Source"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "TestSource"))'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.2").contentEntries[0].sourceFolders[] | select(.type == "Source")).path = "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin"'

echo 'Verified Qodana font backend names, uniqueness, SDK, and exact owned source guards.'

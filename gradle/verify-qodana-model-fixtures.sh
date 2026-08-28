#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -f "$fixture_root/Modules.json" "$fixture_root/valid.json" "$fixture_root/valid.log" "$fixture_root/invalid.json" "$fixture_root/invalid.log"; rmdir "$fixture_root"' EXIT

jq -n '
  def projectModule($name; $source): {
    name: $name,
    orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}],
    contentEntries: [{sourceFolders: [{type: $source}]}]
  };
  {modules:
    ([range(1;22) | projectModule("runtime-minecraft-fabric-1." + tostring; "Source")]
    + [range(1;22) | projectModule("integration-minecraft-fabric-1." + tostring; "TestSource")]
    + [projectModule("minecraft-fonts-lwjgl"; "Source") | .contentEntries[0].sourceFolders += [{type: "TestSource"}]])
  }
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

echo 'Verified Qodana font backend names, uniqueness, SDK, and main/test source guards.'

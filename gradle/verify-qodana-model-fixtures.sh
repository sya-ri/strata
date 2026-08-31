#!/usr/bin/env bash

set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
fixture_project="$fixture_root/project"
fixture_versions=('1.2' '1.10' '9')
trap 'rm -rf -- "$fixture_root"' EXIT

qodana_config="$repository_root/qodana.yaml"
grep -Fq '        - "integration/minecraft-fabric-[0-9]*/build.gradle.kts"' "$qodana_config"
font_capability_glob='        - "runtime/minecraft-fabric-[0-9]*/src/font/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftFontCapabilities.kt"'
if [[ $(grep -Fxc "$font_capability_glob" "$qodana_config") -eq 2 ]]; then
  :
else
  echo 'Qodana must scope both font capability inspection exceptions through the shared version glob.' >&2
  exit 1
fi
required_owner_globs=(
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftDynamicTextureFactory.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftGuiMetadata.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftSampledImageDrawing.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftTextureBlitter.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/FabricNativeCanvasPipeline.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/MinecraftResourceLocation.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/kotlin/dev/s7a/strata/runtime/minecraft/fabric/MinecraftResourceLocationException.kt"'
  '        - "runtime/minecraft-fabric-[0-9]*/src/main/java/dev/s7a/strata/runtime/minecraft/fabric/FabricMinecraftSkinBridge.java"'
)
for required_owner_glob in "${required_owner_globs[@]}"; do
  grep -Fq "$required_owner_glob" "$qodana_config"
done
if grep -Eq '^[[:space:]]+- integration/minecraft-fabric-[0-9][^/]*/build\.gradle\.kts$' "$qodana_config"; then
  echo 'Qodana must not enumerate versioned integration build scripts.' >&2
  exit 1
fi
if grep -Eq '^[[:space:]]+- runtime/minecraft-fabric-[0-9][^/]*/src/font/.*/FabricMinecraftFontCapabilities\.kt$' "$qodana_config"; then
  echo 'Qodana must not enumerate versioned font capability owners.' >&2
  exit 1
fi

for version in "${fixture_versions[@]}"; do
  mkdir -p \
    "$fixture_project/runtime/minecraft-fabric-$version" \
    "$fixture_project/integration/minecraft-fabric-$version"
  printf '' > "$fixture_project/runtime/minecraft-fabric-$version/build.gradle.kts"
  printf '' > "$fixture_project/integration/minecraft-fabric-$version/build.gradle.kts"
done

fixture_versions_json=$(jq -cn --args '$ARGS.positional' "${fixture_versions[@]}")
jq -n --argjson versions "$fixture_versions_json" '
  def projectModule($name; $source; $path): {
    name: $name,
    orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}],
    contentEntries: [{sourceFolders: [{type: $source, path: $path}]}]
  };
  {modules:
    ([$versions[] | projectModule("runtime-minecraft-fabric-" + .; "Source"; "file://$PROJECT_DIR$/runtime/minecraft-fabric-" + . + "/src/font/kotlin")]
    + [$versions[] | projectModule("integration-minecraft-fabric-" + .; "TestSource"; "file://$PROJECT_DIR$/integration/minecraft-fabric-" + . + "/src/gametest/kotlin")]
    + [projectModule("minecraft-fonts-lwjgl"; "Source"; "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/main/kotlin") | .contentEntries[0].sourceFolders += [{type: "TestSource", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/test/kotlin"}]])
  }
  | .modules[0].name = "strata.runtime.minecraft-fabric-1_2"
' > "$fixture_root/Modules.json"

for backend_name in minecraft-fonts-lwjgl runtime-minecraft-fonts-lwjgl strata.runtime.minecraft-fonts-lwjgl; do
  jq --arg name "$backend_name" '.modules[-1].name = $name' "$fixture_root/Modules.json" > "$fixture_root/valid.json"
  bash "$repository_root/gradle/verify-qodana-model.sh" "$fixture_root/valid.json" "$fixture_project" > "$fixture_root/valid.log"
done

assert_rejected() {
  local transformation=$1
  jq "$transformation" "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
  if bash "$repository_root/gradle/verify-qodana-model.sh" "$fixture_root/invalid.json" "$fixture_project" > "$fixture_root/invalid.log" 2>&1; then
    echo "Qodana model unexpectedly accepted: $transformation" >&2
    exit 1
  fi
}

assert_rejected '.modules |= map(select(.name != "integration-minecraft-fabric-1.10"))'
assert_rejected '.modules |= map(select(.name != "runtime-minecraft-fabric-1.10"))'
assert_rejected '.modules += [.modules[0]]'
assert_rejected '.modules += [(.modules[0] | .name = "runtime-minecraft-fabric-10.1")]'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-1.10")).name = "integration-minecraft-fabric-10.1"'
assert_rejected '.modules = .modules[:-1]'
assert_rejected '.modules += [.modules[-1]]'
assert_rejected '.modules[-1].name = "minecraft-fonts-lwjgl-extra"'
assert_rejected '.modules[-1].orderEntries |= map(select(.type != "SDK"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "Source"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "TestSource"))'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders[] | select(.type == "Source")).path = "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin"'

mkdir -p "$fixture_project/runtime/minecraft-fabric-10.1"
printf '' > "$fixture_project/runtime/minecraft-fabric-10.1/build.gradle.kts"
if bash "$repository_root/gradle/verify-qodana-model.sh" "$fixture_root/Modules.json" "$fixture_project" > "$fixture_root/invalid.log" 2>&1; then
  echo 'Qodana inventory unexpectedly accepted a runtime project without its integration owner.' >&2
  exit 1
fi
rm -f -- "$fixture_project/runtime/minecraft-fabric-10.1/build.gradle.kts"
rmdir "$fixture_project/runtime/minecraft-fabric-10.1"

mkdir -p \
  "$fixture_project/runtime/minecraft-fabric-latest" \
  "$fixture_project/integration/minecraft-fabric-latest"
printf '' > "$fixture_project/runtime/minecraft-fabric-latest/build.gradle.kts"
printf '' > "$fixture_project/integration/minecraft-fabric-latest/build.gradle.kts"
if bash "$repository_root/gradle/verify-qodana-model.sh" "$fixture_root/Modules.json" "$fixture_project" > "$fixture_root/invalid.log" 2>&1; then
  echo 'Qodana inventory unexpectedly accepted nonnumeric Minecraft project owners.' >&2
  exit 1
fi

echo "Verified Qodana against a discovered ${#fixture_versions[@]}-version project inventory, exact owners, font backend names, SDKs, and owned sources."

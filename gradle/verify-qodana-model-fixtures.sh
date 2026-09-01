#!/usr/bin/env bash

set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd)
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
fixture_root=$(mktemp -d)
fixture_project="$fixture_root/project"
fixture_versions=('1.2' '1.10' '9')
trap 'rm -rf -- "$fixture_root"' EXIT

fixture_project_file_path=$fixture_project
if command -v cygpath >/dev/null 2>&1; then
  fixture_project_file_path=$(cygpath -m -- "$fixture_project")
fi

fixture_python=''
for python_candidate in python3 python; do
  if command -v "$python_candidate" >/dev/null 2>&1 && \
    "$python_candidate" -c 'import pathlib, sys; raise SystemExit(sys.version_info < (3, 8))' >/dev/null 2>&1; then
    fixture_python=$python_candidate
    break
  fi
done
[[ -n "$fixture_python" ]] || {
  echo 'The Qodana fixtures require Python 3.8 or newer.' >&2
  exit 1
}

qodana_config="$repository_root/qodana.yaml"
qodana_workflow="$repository_root/.github/workflows/qodana.yml"
qodana_container_project_root='/data/project'
readonly qodana_container_project_root
grep -Fqx \
  '        run: bash gradle/verify-qodana-model.sh "${{ runner.temp }}/qodana/results/projectStructure/Modules.json" "$GITHUB_WORKSPACE" /data/project' \
  "$qodana_workflow" || {
  echo 'Qodana CI must pass the host checkout and exact trusted container project root to model verification.' >&2
  exit 1
}
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

write_iml() {
  local iml=$1
  shift
  local module_directory
  local source_root
  local source_type
  local relative_path
  local source_attribute
  local source_url
  local source_path

  module_directory=$(dirname "$iml")
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<module type="JAVA_MODULE" version="4">'
    printf '%s\n' '  <component name="NewModuleRootManager">'
    printf '%s\n' '    <content url="file://$MODULE_DIR$/">'
    for source_root in "$@"; do
      IFS=';' read -r source_type relative_path <<< "$source_root"
      if [[ "$relative_path" == file://* ]]; then
        source_url=$relative_path
        source_path=${source_url#file://}
        mkdir -p "$source_path"
      else
        source_url="file://\$MODULE_DIR\$/$relative_path"
        mkdir -p "$module_directory/$relative_path"
      fi
      case "$source_type" in
        Source) source_attribute='isTestSource="false"' ;;
        Resource) source_attribute='type="java-resource"' ;;
        TestSource) source_attribute='isTestSource="true"' ;;
        TestResource) source_attribute='type="java-test-resource"' ;;
        *)
          echo "Unsupported fixture source type: $source_type" >&2
          exit 1
          ;;
      esac
      printf '      <sourceFolder url="%s" %s/>\n' "$source_url" "$source_attribute"
    done
    printf '%s\n' '    </content>'
    printf '%s\n' '  </component>'
    printf '%s\n' '</module>'
  } > "$iml"
}

for version in "${fixture_versions[@]}"; do
  runtime_directory="$fixture_project/runtime/minecraft-fabric-$version"
  integration_directory="$fixture_project/integration/minecraft-fabric-$version"
  mkdir -p "$runtime_directory" "$integration_directory"
  printf '' > "$runtime_directory/build.gradle.kts"
  printf '' > "$integration_directory/build.gradle.kts"

  runtime_roots=(
    'Source;src/font/kotlin'
    'Resource;src/main/resources'
    'Source;../minecraft-fabric-canvas-shared/src/main/kotlin'
    'Source;../minecraft-fabric-canvas-fixture-probe/src/main/kotlin'
    'Source;../minecraft-fabric-shared/src/main/kotlin'
    'Source;../minecraft-fabric-1.10-legacy/src/main/kotlin'
  )
  integration_roots=(
    'TestResource;src/gametest/resources'
    'TestSource;../minecraft-fabric-canvas-shared/src/gametest/kotlin'
    'TestSource;../minecraft-fabric-canvas-fixture-probe/src/gametest/kotlin'
    'TestSource;../minecraft-fabric-1.10-legacy/src/gametest/kotlin'
    'TestResource;../minecraft-font-parity/src/gametest/resources'
  )
  case "$version" in
    1.2)
      # Mirrors the real 1.20 modules linking source roots owned by 1.20.1.
      runtime_roots+=(
        'Source;src/main/kotlin'
        "Source;file://$fixture_project_file_path/runtime/minecraft-fabric-1.10/src/main/java"
      )
      integration_roots+=(
        'TestSource;src/gametest/kotlin'
        'TestSource;../minecraft-fabric-1.10/src/gametest/java'
      )
      ;;
    1.10)
      runtime_roots+=('Source;src/main/java')
      integration_roots+=('TestSource;src/gametest/java')
      ;;
    9)
      runtime_roots+=('Source;src/version/java')
      integration_roots+=('TestSource;src/gametest26/java')
      ;;
  esac
  write_iml "$runtime_directory/runtime-minecraft-fabric-$version.iml" "${runtime_roots[@]}"
  write_iml "$integration_directory/integration-minecraft-fabric-$version.iml" "${integration_roots[@]}"
done

fixture_multiline_absolute_iml="$fixture_project/runtime/minecraft-fabric-1.2/runtime-minecraft-fabric-1.2.iml"
sed -i \
  '/runtime\/minecraft-fabric-1.10\/src\/main\/java/s#<sourceFolder url="#<sourceFolder\n        url = "#' \
  "$fixture_multiline_absolute_iml"
grep -Fq '        url = "file://' "$fixture_multiline_absolute_iml" || {
  echo 'The multiline absolute linked source fixture was not created.' >&2
  exit 1
}

if [[ "$fixture_project_file_path" != "$fixture_project" ]]; then
  fixture_project_case_changed_file_path=${fixture_project_file_path,,}
  fixture_integration_iml="$fixture_project/integration/minecraft-fabric-1.2/integration-minecraft-fabric-1.2.iml"
  sed -i \
    's#file://\$MODULE_DIR\$/../minecraft-fabric-1.10/src/gametest/java#file:///'"$fixture_project_case_changed_file_path"'/integration/minecraft-fabric-1.10/src/gametest/java#' \
    "$fixture_integration_iml"
fi

docs_directory="$fixture_project/integration/docs"
mkdir -p "$docs_directory/src/extra/kotlin"
printf '' > "$docs_directory/build.gradle.kts"
write_iml \
  "$docs_directory/docs.iml" \
  'Source;src/main/kotlin' \
  'Source;../minecraft-fabric-unobfuscated/src/gametest/kotlin' \
  'TestSource;src/test/kotlin'

font_backend_directory="$fixture_project/runtime/minecraft-fonts-lwjgl"
mkdir -p "$font_backend_directory"
printf '' > "$font_backend_directory/build.gradle.kts"
write_iml \
  "$font_backend_directory/minecraft-fonts-lwjgl.iml" \
  'Source;src/main/kotlin' \
  'TestSource;src/test/kotlin' \
  'TestResource;src/test/resources' \
  'TestSource;../../integration/minecraft-font-parity/src/gametest/kotlin' \
  'TestResource;../../integration/minecraft-font-parity/src/gametest/resources'

mkdir -p \
  "$fixture_project/api/src/main/kotlin" \
  "$fixture_project/integration/minecraft-font-parity/src/gametest/java" \
  "$fixture_project/runtime/minecraft-fabric-unknown-helper/src/main/kotlin"

fixture_versions_json=$(portable_jq -cn --args '$ARGS.positional' "${fixture_versions[@]}")
portable_jq -n --argjson versions "$fixture_versions_json" '
  def ownedRoot($owner; $version; $type; $relativePath): {
    type: $type,
    path: ("file://$PROJECT_DIR$/" + $owner + "/minecraft-fabric-" + $version + "/" + $relativePath)
  };
  def runtimeRoots($version):
    [
      ownedRoot("runtime"; $version; "Source"; "src/font/kotlin"),
      ownedRoot("runtime"; $version; "Resource"; "src/main/resources")
    ]
    + if $version == "1.2" then [ownedRoot("runtime"; $version; "Source"; "src/main/kotlin")] else [] end
    + if $version == "1.10" then [ownedRoot("runtime"; $version; "Source"; "src/main/java")] else [] end
    + if $version == "9" then [ownedRoot("runtime"; $version; "Source"; "src/version/java")] else [] end;
  def integrationRoots($version):
    [ownedRoot("integration"; $version; "TestResource"; "src/gametest/resources")]
    + if $version == "1.2" then [ownedRoot("integration"; $version; "TestSource"; "src/gametest/kotlin")] else [] end
    + if $version == "1.10" then [ownedRoot("integration"; $version; "TestSource"; "src/gametest/java")] else [] end
    + if $version == "9" then [ownedRoot("integration"; $version; "TestSource"; "src/gametest26/java")] else [] end;
  def runtimeLinkedRoots($version):
    [
      {type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-canvas-shared/src/main/kotlin"},
      {type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-canvas-fixture-probe/src/main/kotlin"},
      {type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin"},
      {type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10-legacy/src/main/kotlin"}
    ]
    + if $version == "1.2" then
        [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10/src/main/java"}]
      else
        []
      end;
  def integrationLinkedRoots($version):
    [
      {type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-canvas-shared/src/gametest/kotlin"},
      {type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-canvas-fixture-probe/src/gametest/kotlin"},
      {type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10-legacy/src/gametest/kotlin"},
      {type: "TestResource", path: "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources"}
    ]
    + if $version == "1.2" then
        [{type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/java"}]
      else
        []
      end;
  def projectModule($name; $roots; $linkedRoots): {
    name: $name,
    orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}],
    contentEntries: [{sourceFolders: ($roots + $linkedRoots)}]
  };
  {modules:
    ([$versions[] | projectModule(
      "runtime-minecraft-fabric-" + .;
      runtimeRoots(.);
      runtimeLinkedRoots(.)
    )]
    + [$versions[] | projectModule(
      "integration-minecraft-fabric-" + .;
      integrationRoots(.);
      integrationLinkedRoots(.)
    )]
    + [projectModule(
      "docs";
      [
        {type: "Source", path: "file://$PROJECT_DIR$/integration/docs/src/main/kotlin"},
        {type: "TestSource", path: "file://$PROJECT_DIR$/integration/docs/src/test/kotlin"}
      ];
      [
        {type: "Source", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin"}
      ]
    )]
    + [projectModule(
      "minecraft-fonts-lwjgl";
      [
        {type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/main/kotlin"},
        {type: "TestSource", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/test/kotlin"},
        {type: "TestResource", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/test/resources"}
      ];
      [
        {type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin"},
        {type: "TestResource", path: "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources"}
      ]
    )])
  }
  | .modules[0].name = "strata.runtime.minecraft-fabric-1_2"
' > "$fixture_root/Modules.json"

if [[ "$fixture_project_file_path" != "$fixture_project" ]]; then
  portable_jq \
    --arg sourcePath "file:///$fixture_project_case_changed_file_path/integration/minecraft-fabric-1.10/src/gametest/java" \
    '(
      .modules[]
      | select(.name == "integration-minecraft-fabric-1.2")
      | .contentEntries[0].sourceFolders[]
      | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/java")
    ).path = $sourcePath' \
    "$fixture_root/Modules.json" > "$fixture_root/Modules-with-windows-uri.json"
  mv -- "$fixture_root/Modules-with-windows-uri.json" "$fixture_root/Modules.json"
fi

for backend_name in minecraft-fonts-lwjgl runtime-minecraft-fonts-lwjgl strata.runtime.minecraft-fonts-lwjgl; do
  portable_jq --arg name "$backend_name" '.modules[-1].name = $name' "$fixture_root/Modules.json" > "$fixture_root/valid.json"
  bash "$repository_root/gradle/verify-qodana-model.sh" \
    "$fixture_root/valid.json" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/valid.log"
done

assert_rejected() {
  local transformation=$1
  shift
  portable_jq "$@" "$transformation" "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
  if bash "$repository_root/gradle/verify-qodana-model.sh" \
    "$fixture_root/invalid.json" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/invalid.log" 2>&1; then
    echo "Qodana model unexpectedly accepted: $transformation" >&2
    exit 1
  fi
}

assert_rejected '.modules |= map(select(.name != "integration-minecraft-fabric-1.10"))'
assert_rejected '.modules |= map(select(.name != "runtime-minecraft-fabric-1.10"))'
assert_rejected '.modules |= map(select(.name != "docs"))'
assert_rejected '.modules += [.modules[0]]'
assert_rejected '.modules += [(.modules[0] | .name = "runtime-minecraft-fabric-10.1")]'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-1.10")).name = "integration-minecraft-fabric-10.1"'
assert_rejected '.modules = .modules[:-1]'
assert_rejected '.modules += [.modules[-1]]'
assert_rejected '.modules[-1].name = "minecraft-fonts-lwjgl-extra"'
assert_rejected '(.modules[] | select(.name == "docs")).name = "docs-extra"'
assert_rejected '.modules += [(.modules[] | select(.name == "docs") | .contentEntries[0].sourceFolders = [])]'
assert_rejected '(.modules[] | select(.name == "docs")).orderEntries |= map(select(.type != "SDK"))'
assert_rejected '.modules[-1].orderEntries |= map(select(.type != "SDK"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "Source"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "TestSource"))'
assert_rejected '.modules[-1].contentEntries[0].sourceFolders |= map(select(.type != "TestResource"))'
assert_rejected '
  def keyed:
    to_entries | map(.key |= tostring) | from_entries;
  .modules |= (
    map(
      .orderEntries |= keyed
      | .contentEntries |= (map(.sourceFolders |= keyed) | keyed)
    )
    | keyed
  )
'
grep -Fq 'modules must be a JSON array' "$fixture_root/invalid.log" || {
  echo 'The recursively object-shaped Qodana collections were rejected for the wrong reason.' >&2
  exit 1
}
assert_rejected '.modules[0].orderEntries |= (to_entries | map(.key |= tostring) | from_entries)'
grep -Fq 'modules[0].orderEntries must be a JSON array' "$fixture_root/invalid.log" || {
  echo 'The object-shaped Qodana orderEntries were rejected for the wrong reason.' >&2
  exit 1
}
assert_rejected '.modules[0].contentEntries |= (to_entries | map(.key |= tostring) | from_entries)'
grep -Fq 'modules[0].contentEntries must be a JSON array' "$fixture_root/invalid.log" || {
  echo 'The object-shaped Qodana contentEntries were rejected for the wrong reason.' >&2
  exit 1
}
assert_rejected '.modules[0].contentEntries[0].sourceFolders |= (to_entries | map(.key |= tostring) | from_entries)'
grep -Fq 'modules[0].contentEntries[0].sourceFolders must be a JSON array' "$fixture_root/invalid.log" || {
  echo 'The object-shaped Qodana sourceFolders were rejected for the wrong reason.' >&2
  exit 1
}
assert_rejected '(.modules[0].contentEntries[0].sourceFolders[0].path) += "\u0000"'
grep -Fq 'malformed source-folder identity, type, or path' "$fixture_root/invalid.log" || {
  echo 'The Qodana model source path containing NUL was rejected for the wrong reason.' >&2
  exit 1
}
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) |= map(select(.path != "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin"))'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin")).path = "file://$PROJECT_DIR$/api/src/main/kotlin"'
assert_rejected '.modules += [{name: "strata.misc", orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}], contentEntries: [{sourceFolders: [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/kotlin"}]}]}]'
assert_rejected '.modules += [{name: "strata.misc", orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}], contentEntries: [{sourceFolders: [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-unknown-helper/src/main/kotlin"}]}]}]'
assert_rejected '(.modules[-1].contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin")).path = "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/java"'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10/src/main/java")).path = "file://$PROJECT_DIR$/runtime/minecraft-fabric-shared/src/main/java"'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-1.2").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-1.2/src/gametest/kotlin")).path = "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/kotlin"'
assert_rejected '(.modules[-1].contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/main/kotlin")).path = "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl-shared/src/main/kotlin"'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-9").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-9/src/gametest26/java")).type = "Source"'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10/src/wrong/kotlin"}]'
assert_rejected '(.modules[] | select(.name == "docs").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin")).path = "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/java"'
assert_rejected '(.modules[] | select(.name == "docs").contentEntries[0].sourceFolders[] | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin")).type = "TestSource"'
assert_rejected '(.modules[] | select(.name == "docs").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/integration/docs/src/extra/kotlin"}]'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-1.2").contentEntries[0].sourceFolders) += [{type: "TestResource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.2/src/gametest/resources"}]'
assert_rejected '.modules += [{name: "strata.misc", orderEntries: [{type: "SDK"}, {type: "Library"}, {type: "ModuleSource"}], contentEntries: [{sourceFolders: [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.2/src/main/kotlin"}]}]}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/java"}]'
assert_rejected '(.modules[] | select(.name == "integration-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/src/main/kotlin"}]'
assert_rejected '(.modules[-1].contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10/src/main/java"}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10"}]'
assert_rejected '(.modules[] | select(.name == "strata.runtime.minecraft-fabric-1_2").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10"}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-999/src/extra"}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.2/src/extra"}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: "file://$PROJECT_DIR$/./integration/minecraft-fabric-1.10/src/gametest/java"}]'
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.10/../../integration/minecraft-fabric-1.10/src/gametest/java"}]'
assert_rejected \
  '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: $sourcePath}]' \
  --arg sourcePath "file://$fixture_project/integration/minecraft-fabric-1.10/src/gametest/java"
if [[ "$fixture_project_file_path" != "$fixture_project" ]]; then
  assert_rejected \
    '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: $sourcePath}]' \
    --arg sourcePath "file://$fixture_project_file_path/integration/minecraft-fabric-1.10/src/gametest/java"
  assert_rejected \
    '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: $sourcePath}]' \
    --arg sourcePath "file:///$fixture_project_file_path/integration/minecraft-fabric-1.10/src/gametest/java"
fi
mkdir -p "$fixture_root/outside-model-source"
assert_rejected \
  '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: $sourcePath}]' \
  --arg sourcePath "file://$fixture_root/outside-model-source"
assert_rejected \
  '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: $sourcePath}]' \
  --arg sourcePath 'file:///data/project/runtime/minecraft-fabric-shared/src/main/kotlin'
grep -Fq 'source URL outside the repository' "$fixture_root/invalid.log" || {
  echo 'The container-root Modules.json source URL was not kept outside the host checkout.' >&2
  exit 1
}
assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "Source", path: "file://$PROJECT_DIR$"}]'

source_root_alias="$fixture_project/runtime/integration-owner-alias"
if ln -s '../integration/minecraft-fabric-1.10' "$source_root_alias" 2>/dev/null && [[ -L "$source_root_alias" ]]; then
  assert_rejected '(.modules[] | select(.name == "runtime-minecraft-fabric-1.10").contentEntries[0].sourceFolders) += [{type: "TestSource", path: "file://$PROJECT_DIR$/runtime/integration-owner-alias/src/gametest/java"}]'
  rm -f -- "$source_root_alias"
fi

assert_project_rejected() {
  local description=$1
  local candidate_model=${2:-"$fixture_root/Modules.json"}
  if bash "$repository_root/gradle/verify-qodana-model.sh" \
    "$candidate_model" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/invalid.log" 2>&1; then
    echo "Qodana project inventory unexpectedly accepted $description." >&2
    exit 1
  fi
}

"$fixture_python" - "$fixture_root/Modules.json" "$fixture_root/invalid.json" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_bytes()
target = pathlib.Path(sys.argv[2])
needle = b"strata.runtime.minecraft-fabric-1_2"
if source.count(needle) != 1:
    raise SystemExit("The invalid UTF-8 fixture target was not unique.")
target.write_bytes(source.replace(needle, needle + b"\xff", 1))
PY
assert_project_rejected 'Qodana JSON containing an invalid UTF-8 byte' "$fixture_root/invalid.json"
grep -Fq 'not valid UTF-8 JSON' "$fixture_root/invalid.log" || {
  echo 'The invalid UTF-8 Qodana JSON was rejected for the wrong reason.' >&2
  exit 1
}

"$fixture_python" - "$fixture_root/Modules.json" "$fixture_root/invalid.json" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_bytes()
target = pathlib.Path(sys.argv[2])
needle = b'"strata.runtime.minecraft-fabric-1_2"'
replacement = b'"strata.runtime.minecraft-fabric-1_2\\udc00"'
if source.count(needle) != 1:
    raise SystemExit("The lone-surrogate fixture target was not unique.")
target.write_bytes(source.replace(needle, replacement, 1))
PY
assert_project_rejected 'Qodana JSON containing a lone surrogate escape' "$fixture_root/invalid.json"
grep -Fq 'contains a lone surrogate code point' "$fixture_root/invalid.log" || {
  echo 'The lone-surrogate Qodana JSON was rejected for the wrong reason.' >&2
  exit 1
}

"$fixture_python" - "$fixture_root/Modules.json" "$fixture_root/invalid.json" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_bytes()
target = pathlib.Path(sys.argv[2])
needle = b'"modules":'
replacement = b'"modules": [], "modules":'
if source.count(needle) != 1:
    raise SystemExit("The duplicate-key fixture target was not unique.")
target.write_bytes(source.replace(needle, replacement, 1))
PY
assert_project_rejected 'Qodana JSON containing a duplicate object key' "$fixture_root/invalid.json"
grep -Fq 'duplicate object key' "$fixture_root/invalid.log" || {
  echo 'The duplicate-key Qodana JSON was rejected for the wrong reason.' >&2
  exit 1
}

fixture_model_symlink="$fixture_root/Modules-symlink.json"
if ln -s "$fixture_root/Modules.json" "$fixture_model_symlink" 2>/dev/null && [[ -L "$fixture_model_symlink" ]]; then
  assert_project_rejected 'a symlinked Qodana project inventory' "$fixture_model_symlink"
  grep -Fq 'exact regular non-symlink file' "$fixture_root/invalid.log" || {
    echo 'The symlinked Qodana project inventory was rejected for the wrong reason.' >&2
    exit 1
  }
  rm -f -- "$fixture_model_symlink"
fi

fixture_runtime_iml="$fixture_project/runtime/minecraft-fabric-1.2/runtime-minecraft-fabric-1.2.iml"
fixture_iml_backup="$fixture_root/runtime-minecraft-fabric-1.2.iml"
cp -- "$fixture_runtime_iml" "$fixture_iml_backup"

set_absolute_iml_link() {
  local absolute_path=$1
  cp -- "$fixture_iml_backup" "$fixture_runtime_iml"
  sed -i \
    '/runtime\/minecraft-fabric-1.10\/src\/main\/java/s#file://[^"]*#file://'"$absolute_path"'#' \
    "$fixture_runtime_iml"
  grep -Fq "url = \"file://$absolute_path\"" "$fixture_runtime_iml" || {
    echo "The absolute IDEA module link fixture was not created: $absolute_path" >&2
    exit 1
  }
}

set_absolute_iml_link '/data/project/runtime/minecraft-fabric-1.10/src/main/java'
if bash "$repository_root/gradle/verify-qodana-model.sh" \
  "$fixture_root/Modules.json" "$fixture_project" > "$fixture_root/invalid.log" 2>&1; then
  echo 'Qodana model verification mapped the container checkout without an explicit trusted root.' >&2
  exit 1
fi
grep -Fq 'linked source path outside the repository' "$fixture_root/invalid.log" || {
  echo 'The untrusted container IDEA module link was rejected for the wrong reason.' >&2
  exit 1
}
bash "$repository_root/gradle/verify-qodana-model.sh" \
  "$fixture_root/Modules.json" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/container-project-root.log"

if bash "$repository_root/gradle/verify-qodana-model.sh" \
  "$fixture_root/Modules.json" "$fixture_project" '/data/project2' > "$fixture_root/invalid.log" 2>&1; then
  echo 'Qodana model verification accepted an untrusted container project root.' >&2
  exit 1
fi
grep -Fq 'trusted Qodana container project root must be exactly /data/project' "$fixture_root/invalid.log" || {
  echo 'The untrusted container project root was rejected for the wrong reason.' >&2
  exit 1
}

set_absolute_iml_link '/data/project-escape/runtime/minecraft-fabric-1.10/src/main/java'
assert_project_rejected 'an IDEA module link under a similar container-root prefix'
grep -Fq 'linked source path outside the repository' "$fixture_root/invalid.log" || {
  echo 'The similar-prefix container IDEA module link was rejected for the wrong reason.' >&2
  exit 1
}

for noncanonical_container_path in \
  '/data/project/./runtime/minecraft-fabric-1.10/src/main/java' \
  '/data/project/../outside-model-source' \
  '/data/project/runtime/../runtime/minecraft-fabric-1.10/src/main/java' \
  '/data/project/runtime//minecraft-fabric-1.10/src/main/java'; do
  set_absolute_iml_link "$noncanonical_container_path"
  assert_project_rejected "a non-canonical container IDEA module link: $noncanonical_container_path"
  grep -Fq 'non-canonical container source URL' "$fixture_root/invalid.log" || {
    echo "The non-canonical container IDEA module link was rejected for the wrong reason: $noncanonical_container_path" >&2
    exit 1
  }
done

set_absolute_iml_link '/data/project'
assert_project_rejected 'the container checkout root as an IDEA module source'
grep -Fq 'entire repository as a linked source root' "$fixture_root/invalid.log" || {
  echo 'The container checkout root source was rejected for the wrong reason.' >&2
  exit 1
}

set_absolute_iml_link '/other/project/runtime/minecraft-fabric-1.10/src/main/java'
assert_project_rejected 'an IDEA module link under another absolute root'
grep -Fq 'linked source path outside the repository' "$fixture_root/invalid.log" || {
  echo 'The unrelated absolute IDEA module link was rejected for the wrong reason.' >&2
  exit 1
}

set_absolute_iml_link '/data/project/runtime/minecraft-fabric-1.2/src/main/kotlin'
assert_project_rejected 'a container-absolute URL for a source root owned by its module'
grep -Fq 'non-canonical owned source URL' "$fixture_root/invalid.log" || {
  echo 'The container-absolute owned source root was rejected for the wrong reason.' >&2
  exit 1
}

set_absolute_iml_link '/data/project/runtime/minecraft-fabric-1.10/src/main/java'
sed -i \
  '/runtime\/minecraft-fabric-1.10\/src\/main\/java/s#runtime/minecraft#runtime\\minecraft#' \
  "$fixture_runtime_iml"
grep -Fq 'runtime\minecraft-fabric-1.10' "$fixture_runtime_iml" || {
  echo 'The backslash container IDEA module link fixture was not created.' >&2
  exit 1
}
assert_project_rejected 'a backslash container IDEA module link'
grep -Fq 'unsupported backslash URL' "$fixture_root/invalid.log" || {
  echo 'The backslash container IDEA module link was rejected for the wrong reason.' >&2
  exit 1
}

set_absolute_iml_link '/data/project/runtime/minecraft-fabric-1.10/src/ma%69n/java'
assert_project_rejected 'a percent-encoded container IDEA module link'
grep -Fq 'unsupported percent-encoded URL' "$fixture_root/invalid.log" || {
  echo 'The percent-encoded container IDEA module link was rejected for the wrong reason.' >&2
  exit 1
}

container_inside_alias="$fixture_project/runtime/container-inside-alias"
if ln -s 'minecraft-fabric-1.10' "$container_inside_alias" 2>/dev/null && [[ -L "$container_inside_alias" ]]; then
  set_absolute_iml_link '/data/project/runtime/container-inside-alias/src/main/java'
  assert_project_rejected 'a container IDEA module link through an in-repository symlink'
  grep -Fq 'non-canonical container source URL' "$fixture_root/invalid.log" || {
    echo 'The in-repository symlink container link was rejected for the wrong reason.' >&2
    exit 1
  }
  rm -f -- "$container_inside_alias"
fi

container_outside_alias="$fixture_project/runtime/container-outside-alias"
if ln -s "$fixture_root/outside-model-source" "$container_outside_alias" 2>/dev/null && [[ -L "$container_outside_alias" ]]; then
  set_absolute_iml_link '/data/project/runtime/container-outside-alias'
  assert_project_rejected 'a container IDEA module link through a repository-escaping symlink'
  grep -Fq 'non-canonical container source URL' "$fixture_root/invalid.log" || {
    echo 'The repository-escaping symlink container link was rejected for the wrong reason.' >&2
    exit 1
  }
  rm -f -- "$container_outside_alias"
fi
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#name="NewModuleRootManager"#name="NotTheModuleRootManager"#' \
  "$fixture_runtime_iml"
assert_project_rejected 'source roots under a component with the wrong name'
grep -Fq 'expected exactly one direct unqualified NewModuleRootManager component but found 0' "$fixture_root/invalid.log" || {
  echo 'The wrong IDEA module component name was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  -e '/minecraft-fabric-canvas-shared\/src\/main\/kotlin/d' \
  -e '/<\/component>/i\    <sourceFolder url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" isTestSource="false"/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a sourceFolder outside the content element'
grep -Fq 'sourceFolder must be in the exact direct module/NewModuleRootManager/content/sourceFolder chain' "$fixture_root/invalid.log" || {
  echo 'The sourceFolder outside content was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i '/src\/font\/kotlin/d' "$fixture_runtime_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "strata.runtime.minecraft-fabric-1_2")
    | .contentEntries[0].sourceFolders
  ) |= map(select(.path != "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.2/src/font/kotlin"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana removal of a required runtime font Source' "$fixture_root/invalid.json"
grep -Fq 'must contain exactly one owned font Source: version=1.2 count=0' "$fixture_root/invalid.log" || {
  echo 'The paired removal of a required runtime font Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

mkdir -p "$fixture_root/outside/source"
sed -i \
  -e '/<\/content>/i\      <sourceFolder' \
  -e '/<\/content>/i\        url = "file://'"$fixture_root"'/outside/source"' \
  -e '/<\/content>/i\        isTestSource="false"/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a multiline absolute source root outside the repository'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://'"$fixture_root"'/outside/source#' \
  "$fixture_runtime_iml"
assert_project_rejected 'an absolute source root outside the repository'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/minecraft-fabric-canvas-shared\/src\/main\/kotlin/s/sourceFolder url=/sourceFolder data-url=/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with a data-url attribute instead of url'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#url="file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin"#url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin"#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with a duplicate url attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/src\/main\/resources/s/type="java-resource"/datatype="java-test-resource"/' \
  "$fixture_runtime_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "strata.runtime.minecraft-fabric-1_2")
    | .contentEntries[0].sourceFolders[]
    | select(.path == "file://$PROJECT_DIR$/runtime/minecraft-fabric-1.2/src/main/resources")
  ).type = "TestResource"
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'a source folder with a datatype attribute instead of type' "$fixture_root/invalid.json"
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/minecraft-fabric-canvas-shared\/src\/main\/kotlin/s/isTestSource="false"/otherisTestSource="true"/' \
  "$fixture_runtime_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "strata.runtime.minecraft-fabric-1_2")
    | .contentEntries[0].sourceFolders[]
    | select(.path == "file://$PROJECT_DIR$/runtime/minecraft-fabric-canvas-shared/src/main/kotlin")
  ).type = "TestSource"
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'a source folder with an otherisTestSource attribute instead of isTestSource' "$fixture_root/invalid.json"
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/src\/main\/resources/s/type="java-resource"/type="java-unknown-resource"/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with an unsupported exact type value'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/minecraft-fabric-canvas-shared\/src\/main\/kotlin/s/isTestSource="false"/isTestSource="maybe"/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with an unsupported exact isTestSource value'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/src\/main\/resources/s/type="java-resource"/type="java-resource" isTestSource="false"/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with both type and isTestSource attributes'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/minecraft-fabric-canvas-shared\/src\/main\/kotlin/s/isTestSource="false"/isTestSource="false" isTestSource="false"/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with a duplicate isTestSource attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/src\/main\/resources/s/type="java-resource"/type="java-resource" type="java-resource"/' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with a duplicate exact type attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#url="file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin"#url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin"#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a source folder with a duplicate exact url attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/<\/content>/i\      <sourceFolder/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'an extra zero-attribute sourceFolder element'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  -e 's#<module type=#<module xmlns:q="urn:strata:qodana-fixture" type=#' \
  -e '/<\/content>/i\      <q:sourceFolder/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a qualified sourceFolder element'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<sourceFolder url="file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin" isTestSource="false"/>#<sourceFolder url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" isTestSource="false" data=">" url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" type="java-resource"/>#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a quoted greater-than hiding duplicate url and simultaneous source-kind attributes'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<sourceFolder url="file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin" isTestSource="false"/>#<sourceFolder url="file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin" isTestSource="false" data="\&gt;"/>#' \
  "$fixture_runtime_iml"
assert_project_rejected 'an entity-encoded greater-than in an unexpected sourceFolder attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/%6botlin#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a percent-encoded IML source URL'
grep -Fq 'unsupported percent-encoded URL' "$fixture_root/invalid.log" || {
  echo 'The percent-encoded IML source URL was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

for control_reference in 9 10 13; do
  sed -i \
    's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin\&\#'"$control_reference"';#' \
    "$fixture_runtime_iml"
  grep -Fq "kotlin&#$control_reference;" "$fixture_runtime_iml" || {
    echo "The entity-derived XML control character reference &#$control_reference; fixture was not created." >&2
    exit 1
  }
  assert_project_rejected "an entity-derived XML control character reference &#$control_reference; in an IML source URL"
  grep -Fq 'URL contains a control character' "$fixture_root/invalid.log" || {
    echo "The entity-derived IML URL control character reference &#$control_reference; was rejected for the wrong reason." >&2
    exit 1
  }
  cp -- "$fixture_iml_backup" "$fixture_runtime_iml"
done

sed -i \
  's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://$MODULE_DIR$/../minecraft-fabric-canvas-shared/src/main/kotlin\&\#0;#' \
  "$fixture_runtime_iml"
grep -Fq 'kotlin&#0;' "$fixture_runtime_iml" || {
  echo 'The XML NUL character reference fixture was not created.' >&2
  exit 1
}
assert_project_rejected 'an XML NUL character reference in an IML source URL'
grep -Fq 'not well-formed XML' "$fixture_root/invalid.log" || {
  echo 'The XML NUL character reference was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://$MODULE_DIR$/#' \
  "$fixture_runtime_iml"
assert_project_rejected 'the entire module directory as a source root'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#file://\$MODULE_DIR\$/../minecraft-fabric-canvas-shared/src/main/kotlin#file://'"$fixture_project_file_path"'#' \
  "$fixture_runtime_iml"
assert_project_rejected 'the entire repository as a linked source root'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#file://\$MODULE_DIR\$/src/main/kotlin#file://'"$fixture_project_file_path"'/runtime/minecraft-fabric-1.2/src/main/kotlin#' \
  "$fixture_runtime_iml"
assert_project_rejected 'an absolute URL for a source root owned by its module'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<content url="file://\$MODULE_DIR\$/">#<content url="file://$MODULE_DIR$/wrong">#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a module with the wrong content root'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<content url="file://\$MODULE_DIR\$/">#<content otherurl="file://$MODULE_DIR$/">#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a module content root with an otherurl attribute instead of url'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<content url="file://\$MODULE_DIR\$/">#<content url="file://$MODULE_DIR$/" url="file://$MODULE_DIR$/">#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a module content root with a duplicate url attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  's#<content url="file://\$MODULE_DIR\$/">#<content url="file://$MODULE_DIR$/" data=">" url="file://$MODULE_DIR$/wrong">#' \
  "$fixture_runtime_iml"
assert_project_rejected 'a quoted greater-than hiding a duplicate content url attribute'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/<\/component>/i\    <content url="file://$MODULE_DIR$/"></content>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a module with duplicate content roots'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  -e '/<\/component>/i\    <content' \
  -e '/<\/component>/i\      url = "file://$MODULE_DIR$/">' \
  -e '/<\/component>/i\    </content>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a module with a multiline duplicate content root'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '/<\/component>/i\    <content/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'an extra zero-attribute content element'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  -e 's#<module type=#<module xmlns:q="urn:strata:qodana-fixture" type=#' \
  -e '/<\/component>/i\    <q:content/>' \
  "$fixture_runtime_iml"
assert_project_rejected 'a qualified content element'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i '/<\/module>/d' "$fixture_runtime_iml"
assert_project_rejected 'malformed IDEA module XML'
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i \
  '1s#encoding="UTF-8"#encoding="UTF-16"#' \
  "$fixture_runtime_iml"
assert_project_rejected 'UTF-8 IDEA module bytes with a contradictory UTF-16 declaration'
grep -Fq 'XML declaration must be exactly version 1.0 with UTF-8 encoding' "$fixture_root/invalid.log" || {
  echo 'The contradictory IDEA module XML declaration was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

sed -i '1a\<!DOCTYPE module>' "$fixture_runtime_iml"
assert_project_rejected 'an IDEA module XML DOCTYPE declaration'
grep -Fq 'DOCTYPE declarations are not allowed' "$fixture_root/invalid.log" || {
  echo 'The IDEA module XML DOCTYPE was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

for utf_16_encoding in utf-16-le utf-16-be; do
  "$fixture_python" - "$fixture_runtime_iml" "$utf_16_encoding" <<'PY'
import pathlib
import sys

iml = pathlib.Path(sys.argv[1])
encoding = sys.argv[2]
xml = iml.read_text(encoding="utf-8")
xml = xml.replace('encoding="UTF-8"', 'encoding="UTF-16"', 1)
xml = xml.replace(
    "?>",
    '?>\n<!DOCTYPE module [<!ENTITY strataSource "src">]>',
    1,
)
xml = xml.replace("src/font/kotlin", "&strataSource;/font/kotlin", 1)
iml.write_bytes(xml.encode(encoding))
PY
  assert_project_rejected "a BOM-less $utf_16_encoding IDEA module with a DOCTYPE and entity reference"
  grep -Fq 'not valid UTF-8 XML: contains a NUL byte' "$fixture_root/invalid.log" || {
    echo "The BOM-less $utf_16_encoding IDEA module was rejected for the wrong reason." >&2
    exit 1
  }
  cp -- "$fixture_iml_backup" "$fixture_runtime_iml"
done

mv -- "$fixture_runtime_iml" "${fixture_runtime_iml%.iml}-unexpected.iml"
assert_project_rejected 'a module whose IML file has the wrong name'
mv -- "${fixture_runtime_iml%.iml}-unexpected.iml" "$fixture_runtime_iml"

rm -f -- "$fixture_runtime_iml"
if ln -s "$fixture_iml_backup" "$fixture_runtime_iml" 2>/dev/null && [[ -L "$fixture_runtime_iml" ]]; then
  assert_project_rejected 'a symlinked IML file'
fi
rm -f -- "$fixture_runtime_iml"
cp -- "$fixture_iml_backup" "$fixture_runtime_iml"

fixture_integration_iml="$fixture_project/integration/minecraft-fabric-1.2/integration-minecraft-fabric-1.2.iml"
fixture_integration_iml_backup="$fixture_root/integration-minecraft-fabric-1.2.iml"
cp -- "$fixture_integration_iml" "$fixture_integration_iml_backup"
sed -i '/isTestSource="true"/d' "$fixture_integration_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "integration-minecraft-fabric-1.2")
    | .contentEntries[0].sourceFolders
  ) |= map(select(.type != "TestSource"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana removal of every integration TestSource' "$fixture_root/invalid.json"
grep -Fq 'must contain at least one TestSource: version=1.2' "$fixture_root/invalid.log" || {
  echo 'The paired removal of every integration TestSource was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_integration_iml_backup" "$fixture_integration_iml"

fixture_docs_iml="$fixture_project/integration/docs/docs.iml"
fixture_docs_iml_backup="$fixture_root/docs.iml"
cp -- "$fixture_docs_iml" "$fixture_docs_iml_backup"

sed -i '/minecraft-fabric-unobfuscated\/src\/gametest\/kotlin/d' "$fixture_docs_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "docs")
    | .contentEntries[0].sourceFolders
  ) |= map(select(.path != "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana removal of the required documentation showcase Source' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one showcase Source and no other target source root: source=0 total=0' "$fixture_root/invalid.log" || {
  echo 'The paired removal of the documentation showcase Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_docs_iml_backup" "$fixture_docs_iml"

sed -i \
  '/minecraft-fabric-unobfuscated\/src\/gametest\/kotlin/s/isTestSource="false"/isTestSource="true"/' \
  "$fixture_docs_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "docs")
    | .contentEntries[0].sourceFolders[]
    | select(.path == "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin")
  ).type = "TestSource"
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana source-kind corruption of the required documentation showcase Source' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one showcase Source and no other target source root: source=0 total=1' "$fixture_root/invalid.log" || {
  echo 'The paired source-kind corruption of the documentation showcase Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_docs_iml_backup" "$fixture_docs_iml"

sed -i '/minecraft-fabric-unobfuscated\/src\/gametest\/kotlin/p' "$fixture_docs_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "docs")
    | .contentEntries[0].sourceFolders
  ) += [
      {type: "Source", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin"}
    ]
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana duplication of the required documentation showcase Source' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one showcase Source and no other target source root: source=2 total=2' "$fixture_root/invalid.log" || {
  echo 'The paired duplication of the documentation showcase Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_docs_iml_backup" "$fixture_docs_iml"

sed -i \
  '/<\/content>/i\      <sourceFolder url="file://$MODULE_DIR$/../minecraft-fabric-1.10/src/gametest/java" isTestSource="false"/>' \
  "$fixture_docs_iml"
portable_jq '
  (
    .modules[]
    | select(.name == "docs")
    | .contentEntries[0].sourceFolders
  ) += [
      {type: "Source", path: "file://$PROJECT_DIR$/integration/minecraft-fabric-1.10/src/gametest/java"}
    ]
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana addition of an unrelated documentation target Source' "$fixture_root/invalid.json"
grep -Fq 'links a source root owned by another target' "$fixture_root/invalid.log" || {
  echo 'The paired unrelated documentation target Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_docs_iml_backup" "$fixture_docs_iml"

fixture_backend_iml="$fixture_project/runtime/minecraft-fonts-lwjgl/minecraft-fonts-lwjgl.iml"
fixture_backend_iml_backup="$fixture_root/minecraft-fonts-lwjgl.iml"
cp -- "$fixture_backend_iml" "$fixture_backend_iml_backup"

sed -i '/isTestSource="false"/d' "$fixture_backend_iml"
portable_jq '
  (.modules[-1].contentEntries[0].sourceFolders)
  |= map(select(.type != "Source"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana removal of every CPU font backend Source' "$fixture_root/invalid.json"
grep -Fq 'must contain at least one Source and TestSource: source=0' "$fixture_root/invalid.log" || {
  echo 'The paired removal of every CPU font backend Source was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

sed -i '/isTestSource="true"/d' "$fixture_backend_iml"
portable_jq '
  (.modules[-1].contentEntries[0].sourceFolders)
  |= map(select(.type != "TestSource"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'paired IDEA and Qodana removal of every CPU font backend TestSource' "$fixture_root/invalid.json"
grep -Fq 'must contain at least one Source and TestSource: source=1 testSource=0' "$fixture_root/invalid.log" || {
  echo 'The paired removal of every CPU font backend TestSource was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

sed -i '/minecraft-font-parity\/src\/gametest\/kotlin/d' "$fixture_backend_iml"
portable_jq '
  (.modules[-1].contentEntries[0].sourceFolders)
  |= map(select(.path != "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'a font backend missing its parity TestSource from both IDEA and Qodana models' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one parity TestSource and TestResource: source=0 resource=1 total=1' "$fixture_root/invalid.log" || {
  echo 'The paired omission of the font backend parity TestSource was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

sed -i '/minecraft-font-parity\/src\/gametest\/resources/d' "$fixture_backend_iml"
portable_jq '
  (.modules[-1].contentEntries[0].sourceFolders)
  |= map(select(.path != "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources"))
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'a font backend missing its parity TestResource from both IDEA and Qodana models' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one parity TestSource and TestResource: source=1 resource=0 total=1' "$fixture_root/invalid.log" || {
  echo 'The paired omission of the font backend parity TestResource was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

sed -i \
  -e '/minecraft-font-parity\/src\/gametest\/kotlin/d' \
  -e '/minecraft-font-parity\/src\/gametest\/resources/d' \
  "$fixture_backend_iml"
portable_jq '
  (.modules[-1].contentEntries[0].sourceFolders)
  |= map(
      select(
        .path != "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin"
        and .path != "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources"
      )
    )
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'a font backend missing both parity roots from both IDEA and Qodana models' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one parity TestSource and TestResource: source=0 resource=0 total=0' "$fixture_root/invalid.log" || {
  echo 'The paired omission of both font backend parity roots was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

sed -i \
  -e '/minecraft-font-parity\/src\/gametest\/kotlin/p' \
  -e '/minecraft-font-parity\/src\/gametest\/resources/p' \
  "$fixture_backend_iml"
portable_jq '
  .modules[-1].contentEntries[0].sourceFolders += [
    {type: "TestSource", path: "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin"},
    {type: "TestResource", path: "file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources"}
  ]
' "$fixture_root/Modules.json" > "$fixture_root/invalid.json"
assert_project_rejected 'duplicate font backend parity roots in both IDEA and Qodana models' "$fixture_root/invalid.json"
grep -Fq 'must link exactly one parity TestSource and TestResource: source=2 resource=2 total=4' "$fixture_root/invalid.log" || {
  echo 'The paired duplication of both font backend parity roots was rejected for the wrong reason.' >&2
  exit 1
}
cp -- "$fixture_backend_iml_backup" "$fixture_backend_iml"

mkdir -p "$fixture_project/runtime/minecraft-fabric-10.1"
printf '' > "$fixture_project/runtime/minecraft-fabric-10.1/build.gradle.kts"
if bash "$repository_root/gradle/verify-qodana-model.sh" \
  "$fixture_root/Modules.json" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/invalid.log" 2>&1; then
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
if bash "$repository_root/gradle/verify-qodana-model.sh" \
  "$fixture_root/Modules.json" "$fixture_project" "$qodana_container_project_root" > "$fixture_root/invalid.log" 2>&1; then
  echo 'Qodana inventory unexpectedly accepted nonnumeric Minecraft project owners.' >&2
  exit 1
fi

echo "Verified Qodana against a discovered ${#fixture_versions[@]}-version project inventory, exact owners, font backend names, SDKs, and owned and declared linked sources."

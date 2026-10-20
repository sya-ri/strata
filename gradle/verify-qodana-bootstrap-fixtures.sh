#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf -- "$fixture_root"' EXIT

mkdir -p "$fixture_root/gradle" "$fixture_root/runtime/minecraft-fabric-1.21" \
  "$fixture_root/integration/minecraft-fabric-1.21" "$fixture_root/integration/docs"
cp "$project_root/gradle/prepare-qodana-idea-model.sh" \
  "$project_root/gradle/prepare-qodana-project.py" \
  "$project_root/gradle/plan-minecraft-ci.sh" \
  "$project_root/gradle/list-java-toolchains.sh" "$fixture_root/gradle/"
printf 'java-baseline = "17"\njava-current = "25"\n' > "$fixture_root/gradle/libs.versions.toml"
touch "$fixture_root/runtime/minecraft-fabric-1.21/build.gradle.kts" \
  "$fixture_root/integration/minecraft-fabric-1.21/build.gradle.kts"
printf 'dependsOn(":integration:minecraft-fabric-1.21:runClientGameTest")\n' > \
  "$fixture_root/integration/docs/build.gradle.kts"

cat > "$fixture_root/gradlew" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" >> gradle-arguments.txt
echo invocation >> gradle-invocations.txt
[[ "${FAIL_COMPILATION:-false}" != true ]] || exit 23
if [[ "${OMIT_INVENTORY:-false}" != true ]]; then
  cat > build/qodana/declarations.json <<'JSON'
{"owners":[],"projects":[
  {"directory":"runtime/minecraft-fabric-1.21","iml":"runtime/minecraft-fabric-1.21/runtime-minecraft-fabric-1.21.iml"},
  {"directory":"integration/minecraft-fabric-1.21","iml":"integration/minecraft-fabric-1.21/integration-minecraft-fabric-1.21.iml"},
  {"directory":"runtime/minecraft-fonts-lwjgl","iml":"runtime/minecraft-fonts-lwjgl/minecraft-fonts-lwjgl.iml"},
  {"directory":"api","iml":"api/api.iml"}
]}
JSON
fi
cat > strata.ipr <<'XML'
<project>
  <component name="ProjectModuleManager">
    <modules>
      <module filepath="$PROJECT_DIR$/runtime/minecraft-fabric-1.21/runtime-minecraft-fabric-1.21.iml" fileurl="file://$PROJECT_DIR$/runtime/minecraft-fabric-1.21/runtime-minecraft-fabric-1.21.iml" />
      <module filepath="$PROJECT_DIR$/integration/minecraft-fabric-1.21/integration-minecraft-fabric-1.21.iml" fileurl="file://$PROJECT_DIR$/integration/minecraft-fabric-1.21/integration-minecraft-fabric-1.21.iml" />
      <module filepath="$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/minecraft-fonts-lwjgl.iml" fileurl="file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/minecraft-fonts-lwjgl.iml" />
      <module filepath="$PROJECT_DIR$/api/api.iml" fileurl="file://$PROJECT_DIR$/api/api.iml" />
      <module filepath="$PROJECT_DIR$/api/api.commonMain.iml" fileurl="file://$PROJECT_DIR$/api/api.commonMain.iml" />
    </modules>
  </component>
  <component languageLevel="JDK_25" name="ProjectRootManager" />
</project>
XML
SH

bash "$fixture_root/gradle/prepare-qodana-idea-model.sh"
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 1 ]]
for argument in --console=plain --no-daemon --no-configure-on-demand \
  --project-prop=strata.completeIdeaModel=true --system-prop=fabric.loom.ci=true \
  cleanIdea :api:jvmJar :runtime:core:jvmJar :runtime:headless:jar :runtime:minecraft:jar \
  :runtime:minecraft-fonts-lwjgl:jar classes gametestClasses idea qodanaDeclarationInventory qodanaKotlinModel; do
  [[ $(grep -Fxc -- "$argument" "$fixture_root/gradle-arguments.txt") -eq 1 ]]
done
[[ $(wc -l < "$fixture_root/gradle-arguments.txt") -eq 16 ]]
[[ -s "$fixture_root/build/qodana/declarations.json" && -f "$fixture_root/build/qodana/gradle.log" ]]
grep -F 'runtime-minecraft-fabric-1.21.iml' "$fixture_root/.idea/modules.xml" >/dev/null
grep -F 'integration-minecraft-fabric-1.21.iml' "$fixture_root/.idea/modules.xml" >/dev/null
grep -F 'api.commonMain.iml' "$fixture_root/.idea/modules.xml" >/dev/null
grep -F 'jbr-25' "$fixture_root/.idea/misc.xml" >/dev/null
grep -F '<component name="EntryPointsManager">' "$fixture_root/.idea/misc.xml" >/dev/null
grep -F 'itemvalue="org.spongepowered.asm.mixin.Mixin"' "$fixture_root/.idea/misc.xml" >/dev/null
grep -F 'itemvalue="com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod"' "$fixture_root/.idea/misc.xml" >/dev/null
grep -F '<writeAnnotation name="org.spongepowered.asm.mixin.Shadow" />' "$fixture_root/.idea/misc.xml" >/dev/null

if bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" > "$fixture_root/refusal.log" 2>&1; then
  echo 'Qodana bootstrap replaced a local IDEA model without authorization.' >&2
  exit 1
fi
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 1 ]]
STRATA_QODANA_BOOTSTRAP=true bash "$fixture_root/gradle/prepare-qodana-idea-model.sh"
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 2 ]]

if STRATA_QODANA_BOOTSTRAP=true OMIT_INVENTORY=true bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" > "$fixture_root/missing-inventory.log" 2>&1; then
  echo 'Qodana bootstrap accepted a stale or missing declaration inventory.' >&2
  exit 1
fi
grep -Fq 'did not recreate the declaration-derived JMH inventory' "$fixture_root/missing-inventory.log"

rm -f -- "$fixture_root/.idea/modules.xml" "$fixture_root/strata.ipr"
if bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" > "$fixture_root/misc-refusal.log" 2>&1; then
  echo 'Qodana bootstrap replaced local IDEA settings without authorization.' >&2
  exit 1
fi
grep -Fq 'Refusing to replace existing local IDEA metadata' "$fixture_root/misc-refusal.log"
status=0
STRATA_QODANA_BOOTSTRAP=true FAIL_COMPILATION=true bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" || status=$?
[[ "$status" -eq 23 && ! -e "$fixture_root/.idea/modules.xml" && ! -e "$fixture_root/strata.ipr" ]]

echo 'Verified one Qodana bootstrap invocation, structured module preparation, daemon lifetime, local IDEA protection, and failure propagation.'

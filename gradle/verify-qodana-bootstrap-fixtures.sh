#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf -- "$fixture_root"' EXIT

mkdir -p "$fixture_root/gradle" "$fixture_root/runtime/minecraft-fabric-1.21" \
  "$fixture_root/integration/minecraft-fabric-1.21" "$fixture_root/integration/docs"
cp "$project_root/gradle/prepare-qodana-idea-model.sh" \
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
cat > strata.ipr <<'XML'
<project>
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/runtime/minecraft-fabric-1.21/runtime-minecraft-fabric-1.21.iml" />
      <module fileurl="file://$PROJECT_DIR$/integration/minecraft-fabric-1.21/integration-minecraft-fabric-1.21.iml" />
      <module fileurl="file://$PROJECT_DIR$/runtime/minecraft-fonts-lwjgl/minecraft-fonts-lwjgl.iml" />
    </modules>
  </component>
  <component name="ProjectRootManager" languageLevel="JDK_25" />
</project>
XML
SH

bash "$fixture_root/gradle/prepare-qodana-idea-model.sh"
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 1 ]]
for argument in --no-daemon --no-configure-on-demand \
  --project-prop=strata.completeIdeaModel=true --system-prop=fabric.loom.ci=true \
  :api:jar :runtime:core:jar :runtime:headless:jar :runtime:minecraft:jar \
  :runtime:minecraft-fonts-lwjgl:jar classes gametestClasses idea; do
  [[ $(grep -Fxc -- "$argument" "$fixture_root/gradle-arguments.txt") -eq 1 ]]
done
[[ $(wc -l < "$fixture_root/gradle-arguments.txt") -eq 12 ]]
grep -F 'runtime-minecraft-fabric-1.21.iml' "$fixture_root/.idea/modules.xml" >/dev/null
grep -F 'integration-minecraft-fabric-1.21.iml' "$fixture_root/.idea/modules.xml" >/dev/null
grep -F 'jbr-25' "$fixture_root/.idea/misc.xml" >/dev/null

if bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" > "$fixture_root/refusal.log" 2>&1; then
  echo 'Qodana bootstrap replaced a local IDEA model without authorization.' >&2
  exit 1
fi
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 1 ]]
STRATA_QODANA_BOOTSTRAP=true bash "$fixture_root/gradle/prepare-qodana-idea-model.sh"
[[ $(wc -l < "$fixture_root/gradle-invocations.txt") -eq 2 ]]

rm -f -- "$fixture_root/.idea/modules.xml" "$fixture_root/strata.ipr"
status=0
FAIL_COMPILATION=true bash "$fixture_root/gradle/prepare-qodana-idea-model.sh" || status=$?
[[ "$status" -eq 23 && ! -e "$fixture_root/.idea/modules.xml" && ! -e "$fixture_root/strata.ipr" ]]

echo 'Verified one Qodana compilation/model invocation, daemon lifetime, local IDEA protection, and failure propagation.'

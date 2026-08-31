#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_root"

if [[ -f .idea/modules.xml && "${STRATA_QODANA_BOOTSTRAP:-false}" != "true" ]]; then
  echo 'Refusing to replace an existing local .idea/modules.xml; run Qodana from a disposable checkout.' >&2
  exit 1
fi

inventory_directory=$(mktemp -d)
trap 'rm -rf -- "$inventory_directory"' EXIT
bash gradle/plan-minecraft-ci.sh "$project_root" "$inventory_directory" >/dev/null
mapfile -t expected_runtime_versions < <(sed -n 's#^runtime/minecraft-fabric-##p' "$inventory_directory/minecraft-loom-projects.txt")
mapfile -t expected_integration_versions < <(sed -n 's#^integration/minecraft-fabric-##p' "$inventory_directory/minecraft-loom-projects.txt")

mapfile -t java_toolchains < <(bash gradle/list-java-toolchains.sh gradle/libs.versions.toml)
project_jdk=${java_toolchains[${#java_toolchains[@]} - 1]}
project_language_level="JDK_$project_jdk"

bash ./gradlew \
  --no-configure-on-demand \
  --project-prop=strata.completeIdeaModel=true \
  --system-prop=fabric.loom.ci=true \
  idea

verify_generated_projects() {
  local owner=$1
  local -n expected_versions=$2
  local -a generated_versions
  local expected_display
  local generated_display

  mapfile -t generated_versions < <(
    sed -nE "s#.*module fileurl=.*$owner/minecraft-fabric-([^/]+)/$owner-minecraft-fabric-[^\"]+\\.iml.*#\\1#p" strata.ipr |
      LC_ALL=C sort -V
  )
  expected_display=$(IFS=,; printf '%s' "${expected_versions[*]}")
  generated_display=$(IFS=,; printf '%s' "${generated_versions[*]}")
  if [[ "$generated_display" != "$expected_display" ]]; then
    echo "Expected Minecraft $owner modules for [$expected_display] but generated [$generated_display]." >&2
    exit 1
  fi
}

verify_generated_projects runtime expected_runtime_versions
verify_generated_projects integration expected_integration_versions
runtime_count=${#expected_runtime_versions[@]}
integration_count=${#expected_integration_versions[@]}

font_backend_count=$(awk '/module fileurl=.*runtime\/minecraft-fonts-lwjgl\/minecraft-fonts-lwjgl\.iml/ { count++ } END { print count + 0 }' strata.ipr)
if [[ "$font_backend_count" -ne 1 ]]; then
  echo "Expected one CPU font backend module but generated $font_backend_count." >&2
  exit 1
fi

mkdir -p .idea
{
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
  printf '%s\n' '<project version="4">'
  awk '/  <component name="ProjectModuleManager">/,/  <\/component>/' strata.ipr
  printf '%s\n' '</project>'
} > .idea/modules.xml

if grep -q "<component name=\"ProjectRootManager\".*languageLevel=\"$project_language_level\"" strata.ipr; then
  :
else
  echo "The generated IDEA project does not declare the catalog's required Java $project_jdk project SDK." >&2
  exit 1
fi

if [[ -f .idea/misc.xml ]]; then
  if grep -q "<component name=\"ProjectRootManager\".*languageLevel=\"$project_language_level\"" .idea/misc.xml; then
    :
  else
    echo "The existing Qodana IDEA metadata does not declare the catalog's required Java $project_jdk project SDK." >&2
    exit 1
  fi
else
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<project version="4">'
    printf '  <component name="ProjectRootManager" version="2" languageLevel="%s" default="true" project-jdk-name="jbr-%s" project-jdk-type="JavaSDK" />\n' "$project_language_level" "$project_jdk"
    printf '%s\n' '</project>'
  } > .idea/misc.xml
fi

echo "Prepared Qodana IDEA metadata for $runtime_count runtime and $integration_count integration Minecraft modules, Java $project_jdk, and the CPU font backend."

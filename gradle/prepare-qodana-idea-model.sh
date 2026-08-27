#!/usr/bin/env bash

set -euo pipefail

if [[ -f .idea/modules.xml && "${GITHUB_ACTIONS:-false}" != "true" ]]; then
  echo 'Refusing to replace an existing local .idea/modules.xml; run Qodana from a disposable checkout.' >&2
  exit 1
fi

bash ./gradlew \
  --no-configure-on-demand \
  --project-prop=strata.completeIdeaModel=true \
  --system-prop=fabric.loom.ci=true \
  idea

runtime_count=$(awk '/module fileurl=.*runtime\/minecraft-fabric-[^/]+\/runtime-minecraft-fabric-[^"]+\.iml/ { count++ } END { print count + 0 }' strata.ipr)
integration_count=$(awk '/module fileurl=.*integration\/minecraft-fabric-[^/]+\/integration-minecraft-fabric-[^"]+\.iml/ { count++ } END { print count + 0 }' strata.ipr)
expected_projects=21
if [[ "$runtime_count" -ne "$expected_projects" || "$integration_count" -ne "$expected_projects" ]]; then
  echo "Expected $expected_projects runtime and integration Minecraft modules but generated $runtime_count and $integration_count." >&2
  exit 1
fi

mkdir -p .idea
{
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
  printf '%s\n' '<project version="4">'
  awk '/  <component name="ProjectModuleManager">/,/  <\/component>/' strata.ipr
  printf '%s\n' '</project>'
} > .idea/modules.xml

if [[ -f .idea/misc.xml ]]; then
  :
else
  if grep -q '<component name="ProjectRootManager".*languageLevel="JDK_25"' strata.ipr; then
    :
  else
    echo 'The generated IDEA project does not declare the required Java 25 project SDK.' >&2
    exit 1
  fi
  {
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<project version="4">'
    printf '%s\n' '  <component name="ProjectRootManager" version="2" languageLevel="JDK_25" default="true" project-jdk-name="jbr-25" project-jdk-type="JavaSDK" />'
    printf '%s\n' '</project>'
  } > .idea/misc.xml
fi

echo "Prepared Qodana IDEA metadata for $runtime_count runtime and $integration_count integration Minecraft modules."

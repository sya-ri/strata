#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$project_root"

if [[ ( -f .idea/modules.xml || -f .idea/misc.xml ) && "${STRATA_QODANA_BOOTSTRAP:-false}" != "true" ]]; then
  echo 'Refusing to replace existing local IDEA metadata; run Qodana from a disposable checkout.' >&2
  exit 1
fi

inventory_directory=$(mktemp -d)
trap 'rm -rf -- "$inventory_directory"' EXIT
bash gradle/plan-minecraft-ci.sh "$project_root" "$inventory_directory" >/dev/null

qodana_python=''
for python_candidate in python3 python; do
  if command -v "$python_candidate" >/dev/null 2>&1 && \
    "$python_candidate" -c 'import tomllib' >/dev/null 2>&1; then
    qodana_python=$python_candidate
    break
  fi
done
[[ -n "$qodana_python" ]] || { echo 'Qodana IDEA preparation requires Python 3.11 or newer.' >&2; exit 1; }

project_jdk=$("$qodana_python" -B gradle/java-toolchains.py | tail -n 1)

# Recreate the selected revision's source roots even when the IDE reuses indexes from an earlier scan.
mkdir -p build/qodana
rm -f -- build/qodana/declarations.json build/qodana/gradle.log
bash ./gradlew \
  --console=plain \
  --no-daemon \
  --no-configure-on-demand \
  --project-prop=strata.completeIdeaModel=true \
  --system-prop=fabric.loom.ci=true \
  cleanIdea \
  :api:jvmJar :runtime:core:jvmJar :runtime:headless:jar :runtime:minecraft:jar :runtime:minecraft-fonts-lwjgl:jar \
  classes gametestClasses \
  idea qodanaDeclarationInventory qodanaKotlinModel 2>&1 | tee build/qodana/gradle.log
[[ -s build/qodana/declarations.json ]] || {
  echo 'Gradle did not recreate the declaration-derived JMH inventory.' >&2
  exit 1
}

"$qodana_python" gradle/prepare-qodana-project.py "$project_root" "$inventory_directory/minecraft-loom-projects.txt" "$project_jdk"

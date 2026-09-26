#!/usr/bin/env bash
set -euo pipefail

operation="${1:?Portal operation is required}"
tag="${2:?Release tag is required}"
[[ "$operation" == preflight || "$operation" == verify ]] || exit 1
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 1
controller="${CONTROLLER_TOOL_DIRECTORY:?Controller tools are required}"
git --no-replace-objects cat-file blob "$GITHUB_SHA:release/verify-controller-tools.sh" |
  bash -s -- verify "$GITHUB_SHA" "$controller"
inventory=''
for candidate in build/release/maven-coordinates.txt release/maven-coordinates.txt; do
  if [[ -f "$candidate" && ! -L "$candidate" ]]; then
    [[ -z "$inventory" ]] || { echo 'Multiple Maven inventories.' >&2; exit 1; }
    inventory="$PWD/$candidate"
  fi
done
[[ -n "$inventory" ]] || { echo 'Missing Maven inventory.' >&2; exit 1; }
files=''
if [[ "$inventory" == "$PWD/build/release/maven-coordinates.txt" ]]; then
  files="$PWD/build/release/maven-files.txt"
  [[ -f "$files" && ! -L "$files" ]] || exit 1
fi
output="$PWD/build/release/maven-central"
bash ./gradlew --no-parallel --max-workers=2 --no-build-cache \
  --project-cache-dir "$output/controller-cache" -p "$controller" verifyPortal \
  -Pkotlin.project.persistent.dir="$output/controller-kotlin" \
  -Pkotlin.project.persistent.dir.gradle.disableWrite=true \
  -PportalOperation="$operation" -PportalVersion="${tag#v}" \
  -PportalCoordinates="$inventory" -PportalFiles="$files" \
  -PportalRepository="$HOME/.m2/repository" -PportalOutput="$output"

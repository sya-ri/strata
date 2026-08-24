#!/usr/bin/env bash

set -euo pipefail

cache_inputs=(
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.properties
  gradle.properties
  settings.gradle.kts
  build.gradle.kts
)
while IFS= read -r project; do
  if [[ -n "$project" ]]; then
    cache_inputs+=("$project/build.gradle.kts")
  fi
done <<< "${LOOM_PROJECTS:?LOOM_PROJECTS must list the owning Gradle projects}"

sha256sum "${cache_inputs[@]}" | sha256sum | awk '{ print "hash=" $1 }'

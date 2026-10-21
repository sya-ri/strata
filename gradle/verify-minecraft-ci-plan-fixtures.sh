#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf -- "$fixture_root"' EXIT

add_project() {
  local root=$1
  local parent=$2
  local version=$3
  local project_directory="$root/$parent/minecraft-fabric-$version"
  mkdir -p "$project_directory"
  touch "$project_directory/build.gradle.kts"
}

add_documentation() {
  local root=$1
  local version=$2
  mkdir -p "$root/integration/docs"
  printf 'dependsOn(":integration:minecraft-fabric-%s:runClientGameTest", "classes")\n' "$version" > \
    "$root/integration/docs/build.gradle.kts"
}

valid_root="$fixture_root/valid"
for minor in $(seq 1 30); do
  add_project "$valid_root" runtime "1.$minor"
  add_project "$valid_root" integration "1.$minor"
done
add_project "$valid_root" runtime '2'
add_project "$valid_root" integration '2'
add_documentation "$valid_root" '1.7'
mkdir -p "$valid_root/runtime/shared/minecraft-fabric/lifecycle/common" "$valid_root/integration/shared/minecraft-fabric/canvas/common"

GITHUB_OUTPUT="$valid_root/docs-output" bash "$project_root/gradle/plan-minecraft-ci.sh" "$valid_root" "$valid_root/output"
grep -Fx 'docs_version=1.7' "$valid_root/docs-output" >/dev/null
matrix_file="$valid_root/output/minecraft-matrix.json"
loom_file="$valid_root/output/minecraft-loom-projects.txt"
jq -e -s '
  select(length == 1) | .[0].include as $entries
  | [$entries[].loom_projects | split("\n")[]] as $projects
  | [$entries[] | select(.gradle_arguments | contains(":integration:docs:check"))] as $docs
  | ($entries | length) == 31
    and all($entries[]; (.loom_projects | split("\n") | length) == 2)
    and ($docs | length) == 1
    and all($entries[]; .gradle_arguments | contains(":integration:docs:checkDokkaPagesStaging") | not)
    and ($docs[0].loom_projects | split("\n") | index("integration/minecraft-fabric-1.7")) != null
    and ($projects | length) == 62 and ($projects | unique | length) == 62
    and ([$entries[].gradle_arguments | split("-Pstrata.minecraftVersions=")[1]] == ([range(1; 31) | "1.\(.)"] + ["2"]))
    and all($entries[]; .gradle_arguments
      | startswith(":ciMinecraftCheck -Pstrata.minecraftVersions=")
        or startswith(":ciMinecraftCheck :integration:docs:checkMinecraftShowcaseParity -Pstrata.minecraftVersions="))
' "$matrix_file" >/dev/null
[[ $(wc -l < "$loom_file") -eq 62 ]]
[[ $(sed -n '1p' "$loom_file") == 'runtime/minecraft-fabric-1.1' ]]
[[ $(sed -n '19p' "$loom_file") == 'runtime/minecraft-fabric-1.10' ]]
[[ $(tail -n 2 "$loom_file" | head -n 1) == 'runtime/minecraft-fabric-2' ]]

for version_count in 1 3 21; do
  sized_root="$fixture_root/size-$version_count"
  for minor in $(seq 1 "$version_count"); do
    add_project "$sized_root" runtime "1.$minor"
    add_project "$sized_root" integration "1.$minor"
  done
  add_documentation "$sized_root" '1.1'
  bash "$project_root/gradle/plan-minecraft-ci.sh" "$sized_root" "$sized_root/output"
  jq -e -s --argjson count "$version_count" '
    select(length == 1) | .[0].include as $entries
    | [$entries[].loom_projects | split("\n")[]] as $projects
    | [range(1; $count + 1) | "runtime/minecraft-fabric-1.\(.)", "integration/minecraft-fabric-1.\(.)"] as $expected
    | ($entries | length) == $count
      and ($entries | map(.id) | unique | length) == $count
      and ($projects | sort) == ($expected | sort)
      and ([$entries[] | select(.gradle_arguments | contains(":integration:docs:check"))] | length) == 1
      and all($entries[];
        (.gradle_arguments | split("-Pstrata.minecraftVersions=")[1] | split(",")) == [.version]
        and .documentation == (.gradle_arguments | contains(":integration:docs:check"))
        and (.loom_projects | split("\n")) == ["runtime/minecraft-fabric-\(.version)", "integration/minecraft-fabric-\(.version)"]
        and (if .documentation then .version == "1.1" else true end))
  ' "$sized_root/output/minecraft-matrix.json" >/dev/null
done

for include_docs in false true; do
  STRATA_MC_VERSIONS=1.10,1.2 STRATA_CI_DOCS=$include_docs bash \
    "$project_root/gradle/plan-minecraft-ci.sh" "$valid_root" "$valid_root/selected"
  jq -e -s --argjson include_docs "$include_docs" '
    select(length == 1) | .[0]
    | [.include[].gradle_arguments | split("-Pstrata.minecraftVersions=")[1]]
      == (if $include_docs then ["1.2", "1.7", "1.10"] else ["1.2", "1.10"] end)
    and ([.include[] | select(.gradle_arguments | contains(":integration:docs:check"))] | length) == (if $include_docs then 1 else 0 end)
  ' "$valid_root/selected/minecraft-matrix.json" >/dev/null
done

STRATA_MC_VERSIONS='' STRATA_CI_DOCS=true bash \
  "$project_root/gradle/plan-minecraft-ci.sh" "$valid_root" "$valid_root/docs-only"
jq -e '
  (.include | length) == 1 and .include[0].version == "1.7" and .include[0].documentation == true
  and .include[0].gradle_arguments == ":ciMinecraftCheck :integration:docs:checkMinecraftShowcaseParity -Pstrata.minecraftVersions=1.7"
' "$valid_root/docs-only/minecraft-matrix.json" >/dev/null
if STRATA_MC_VERSIONS='' STRATA_CI_DOCS=false bash \
  "$project_root/gradle/plan-minecraft-ci.sh" "$valid_root" "$valid_root/none" > "$valid_root/empty.log" 2> "$valid_root/empty-error.log"; then
  echo 'Minecraft CI planner accepted an empty selection without documentation.' >&2
  exit 1
fi
grep -F 'The selected Minecraft CI inventory is empty.' "$valid_root/empty-error.log" >/dev/null

for invalid_docs in missing empty malformed unknown ambiguous; do
  invalid_root="$fixture_root/docs-$invalid_docs"
  add_project "$invalid_root" runtime '1.2'
  add_project "$invalid_root" integration '1.2'
  case "$invalid_docs" in
    empty)
      mkdir -p "$invalid_root/integration/docs"
      touch "$invalid_root/integration/docs/build.gradle.kts"
      ;;
    malformed) add_documentation "$invalid_root" '1.2bad' ;;
    unknown) add_documentation "$invalid_root" '9.9' ;;
    ambiguous)
      add_documentation "$invalid_root" '1.2'
      printf 'dependsOn(":integration:minecraft-fabric-9.9:runClientGameTest")\n' >> "$invalid_root/integration/docs/build.gradle.kts"
      ;;
  esac
  if bash "$project_root/gradle/plan-minecraft-ci.sh" "$invalid_root" "$invalid_root/output" > "$invalid_root/stdout.log" 2> "$invalid_root/stderr.log"; then
    echo "Minecraft CI planner accepted $invalid_docs documentation inputs." >&2
    exit 1
  fi
done

unpaired_root="$fixture_root/unpaired"
add_project "$unpaired_root" runtime '1.2'
add_project "$unpaired_root" integration '1.2'
add_project "$unpaired_root" runtime '1.10'
if bash "$project_root/gradle/plan-minecraft-ci.sh" "$unpaired_root" "$unpaired_root/output" > "$unpaired_root/stdout.log" 2> "$unpaired_root/stderr.log"; then
  echo 'Minecraft CI planner accepted an unpaired runtime project.' >&2
  exit 1
fi
grep -F 'Runtime only: 1.10' "$unpaired_root/stderr.log" >/dev/null

malformed_root="$fixture_root/malformed"
add_project "$malformed_root" runtime '1.2'
add_project "$malformed_root" integration '1.2'
add_project "$malformed_root" runtime '1.bad'
add_project "$malformed_root" integration '1.bad'
if bash "$project_root/gradle/plan-minecraft-ci.sh" "$malformed_root" "$malformed_root/output" > "$malformed_root/stdout.log" 2> "$malformed_root/stderr.log"; then
  echo 'Minecraft CI planner accepted a malformed numeric version directory.' >&2
  exit 1
fi
grep -F 'Invalid versioned Minecraft project directory' "$malformed_root/stderr.log" >/dev/null

echo 'Verified Minecraft CI discovery, version ordering, pairing, and independent version jobs.'

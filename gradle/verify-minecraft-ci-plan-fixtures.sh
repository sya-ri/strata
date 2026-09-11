#!/usr/bin/env bash

set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf -- "$fixture_root"' EXIT

python3 - "$project_root/.github/workflows/jvm.yml" <<'PY'
import pathlib
import sys

workflow_path = pathlib.Path(sys.argv[1])
workflow = workflow_path.read_text(encoding="utf-8")
restore_steps = [
    block
    for block in workflow.split("\n      - name: ")
    if "uses: actions/cache/restore@v6" in block and "path: .gradle/loom-cache" in block
]
assert len(restore_steps) == 1, "jvm.yml must contain exactly one Loom cache restore step"
exact_key = "key: loom-${{ runner.os }}-${{ matrix.id }}-${{ steps.loom_inputs.outputs.hash }}"
assert restore_steps[0].count(exact_key) == 1, (
    "jvm.yml must restore the exact OS, shard, and complete-input Loom cache key"
)
assert "restore-keys:" not in restore_steps[0], (
    "jvm.yml must restore only the exact hash-addressed Loom cache key"
)
for name, count in [("qodana.yml", 1), ("pages.yml", 2)]:
    reader = workflow_path.with_name(name).read_text(encoding="utf-8")
    readers = [block for block in reader.split("\n      - name: ")
               if "uses: gradle/actions/setup-gradle@" in block]
    assert len(readers) == count, f"Unexpected Gradle reader jobs in {name}"
    for block in readers:
        assert "cache-read-only: true" in block, f"{name} must never write the shared cache"
        assert "gradle-home-cache-strict-match: false" in block, (
            f"{name} must accept compatible writer caches, not require a nonexistent own-job cache"
        )
        assert "gradle-home-cache-excludes: caches/fabric-loom" in block
PY

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
mkdir -p "$valid_root/runtime/minecraft-fabric-shared" "$valid_root/integration/minecraft-fabric-canvas-shared"

bash "$project_root/gradle/plan-minecraft-ci.sh" "$valid_root" "$valid_root/output"
matrix_file="$valid_root/output/minecraft-matrix.json"
loom_file="$valid_root/output/minecraft-loom-projects.txt"
python3 - "$matrix_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as matrix_file:
    entries = json.load(matrix_file)["include"]
assert len(entries) == 5
assert all(len(entry["loom_projects"].splitlines()) // 2 <= 7 for entry in entries)
docs_entries = [entry for entry in entries if ":integration:docs:check" in entry["gradle_arguments"]]
assert len(docs_entries) == 1
assert "integration/minecraft-fabric-1.7" in docs_entries[0]["loom_projects"].splitlines()
projects = [project for entry in entries for project in entry["loom_projects"].splitlines()]
assert len(projects) == len(set(projects)) == 62
# Expensive old release families must not all occupy the same runner.
oldest_owners = [next(index for index, entry in enumerate(entries)
                     if f"runtime/minecraft-fabric-1.{minor}" in entry["loom_projects"].splitlines())
                 for minor in range(1, 6)]
assert len(set(oldest_owners)) == 5
assert all(
    entry["gradle_arguments"].startswith(":ciMinecraftCheck -Pstrata.minecraftVersions=")
    or entry["gradle_arguments"].startswith(":ciMinecraftCheck :integration:docs:check -Pstrata.minecraftVersions=")
    for entry in entries
)
PY
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
  python3 - "$sized_root/output/minecraft-matrix.json" "$version_count" <<'PY'
import json
import sys

entries = json.load(open(sys.argv[1], encoding="utf-8"))["include"]
count = int(sys.argv[2])
assert len(entries) == min(4, count)
assert len({entry["id"] for entry in entries}) == len(entries)
projects = [project for entry in entries for project in entry["loom_projects"].splitlines()]
expected = {f"{kind}/minecraft-fabric-1.{minor}"
            for kind in ("runtime", "integration") for minor in range(1, count + 1)}
assert set(projects) == expected and len(projects) == len(expected)
assert sum(":integration:docs:check" in entry["gradle_arguments"] for entry in entries) == 1
for entry in entries:
    versions = entry["gradle_arguments"].split("-Pstrata.minecraftVersions=")[1].split(",")
    assert 1 <= len(versions) <= 7
    assert entry["loom_projects"].splitlines() == [f"{kind}/minecraft-fabric-{version}"
        for version in versions for kind in ("runtime", "integration")]
    if ":integration:docs:check" in entry["gradle_arguments"]:
        assert "1.1" in versions
PY
done

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

echo 'Verified Minecraft CI discovery, version ordering, pairing, and bounded shards.'

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
PY

add_project() {
  local root=$1
  local parent=$2
  local version=$3
  local project_directory="$root/$parent/minecraft-fabric-$version"
  mkdir -p "$project_directory"
  touch "$project_directory/build.gradle.kts"
}

valid_root="$fixture_root/valid"
for minor in $(seq 1 30); do
  add_project "$valid_root" runtime "1.$minor"
  add_project "$valid_root" integration "1.$minor"
done
add_project "$valid_root" runtime '2'
add_project "$valid_root" integration '2'
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
assert [":integration:docs:check" in entry["gradle_arguments"] for entry in entries] == [False, False, False, False, True]
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

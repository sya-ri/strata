#!/usr/bin/env bash

set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
mkdir "$temporary/repo" "$temporary/bin"
REAL_JQ="$(command -v jq)"
export REAL_JQ
cat > "$temporary/bin/jq" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
"$REAL_JQ" "$@" | sed 's/\r$//'
SCRIPT
chmod +x "$temporary/bin/jq"
export PATH="$temporary/bin:$PATH"
cd "$temporary/repo"
git init --quiet
git config user.name 'Strata Release Selection Test'
git config user.email 'release-selection@example.invalid'
git config commit.gpgsign false
git config tag.gpgsign false
git config core.autocrlf false
for version in 1.20 1.20.1 1.20.2 1.21.3 1.21.4 1.21.11 26.2; do
  for kind in runtime integration; do
    mkdir -p "$kind/minecraft-fabric-$version"
    printf '// fixture\n' > "$kind/minecraft-fabric-$version/build.gradle.kts"
  done
done
register_client() {
  printf 'tasks.register<ClientProductionRunTask>("runPublishedCoordinateClientGameTest") {\n}\n' > "integration/minecraft-fabric-$1/build.gradle.kts"
}
for version in 1.20 1.20.1 1.21.11 26.2; do register_client "$version"; done
commit_release() {
  printf 'version = "%s"\n' "$1" > build.gradle.kts
  git add .
  git commit --quiet -m "Release fixture $1"
  git tag -a "v$1" -m "Fixture $1"
}
commit_release 0.1.0
commit_release 0.1.1
selected="$(git rev-parse HEAD)"
commit_release 0.1.2
controller="$(git rev-parse HEAD)"
selector="$root/release/select-release-source.sh"
bash "$selector" resolve v0.1.1 "$selected" "$controller" > "$temporary/selection.json"
jq -e --arg selected "$selected" '.current.commit == $selected and .current.tag == "v0.1.1" and
  .predecessor.tag == "v0.1.0" and .current.representativeMinecraftVersions == ["1.20","1.20.1","1.21.11","26.2"]' "$temporary/selection.json" >/dev/null
bash "$selector" verify "$temporary/selection.json" "$controller"
bash "$selector" verify-input "$controller" < "$temporary/selection.json"
register_client 1.21.4
commit_release 0.1.3
bash "$selector" verify "$temporary/selection.json" "$controller"

expect_failure() {
  local label="$1"
  shift
  if bash "$selector" "$@" > "$temporary/result" 2> "$temporary/error"; then
    echo "Invalid selection accepted: $label" >&2; exit 1
  fi
}
expect_failure 'tag targets another commit' resolve v0.1.1 "$controller" "$controller"
expect_failure 'short source SHA' resolve v0.1.1 "${selected:0:12}" "$controller"
expect_failure 'source newer than frozen controller' resolve v0.1.3 "$(git rev-parse HEAD)" "$controller"
jq '.current.tagObject = "0000000000000000000000000000000000000000"' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'changed tag object' verify "$temporary/invalid.json" "$controller"
jq '.current.representativeMinecraftVersions = ["1.20","1.20"]' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'duplicate representatives' verify "$temporary/invalid.json" "$controller"
jq '.current.representativeMinecraftVersions = [26.2]' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'untyped representative' verify "$temporary/invalid.json" "$controller"
jq '.current.representativeMinecraftVersions = ["1.20","1.21.3","26.2"]' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'runtime without a published-client task' verify "$temporary/invalid.json" "$controller"
jq '.current.representativeMinecraftVersions = ["1.20","1.21.11","26.2"]' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'omitted registered client' verify "$temporary/invalid.json" "$controller"
jq '.current.representativeMinecraftVersions |= reverse' "$temporary/selection.json" > "$temporary/invalid.json"
expect_failure 'reordered frozen clients' verify "$temporary/invalid.json" "$controller"

for version in 1.20 1.20.1 1.20.2 1.21.3 1.21.4 1.21.11 26.2; do
  printf '// no published-client task\n' > "integration/minecraft-fabric-$version/build.gradle.kts"
done
commit_release 0.1.4
expect_failure 'no registered clients' resolve v0.1.4 "$(git rev-parse HEAD)" "$(git rev-parse HEAD)"
grep -F 'no registered published-client acceptance task' "$temporary/error" >/dev/null
for version in 1.20 1.20.1 1.21.11 26.2; do register_client "$version"; done
printf 'tasks.register<ClientProductionRunTask>("runPublishedCoordinateClientGameTest") {\n}\n' >> integration/minecraft-fabric-1.20/build.gradle.kts
commit_release 0.1.5
expect_failure 'duplicate task registration' resolve v0.1.5 "$(git rev-parse HEAD)" "$(git rev-parse HEAD)"
grep -F 'registration is ambiguous' "$temporary/error" >/dev/null
register_client 1.20
printf 'tasks.register("runPublishedCoordinateClientGameTest") {\n}\n' > integration/minecraft-fabric-1.20.1/build.gradle.kts
commit_release 0.1.6
expect_failure 'unknown registration shape' resolve v0.1.6 "$(git rev-parse HEAD)" "$(git rev-parse HEAD)"
grep -F 'unsupported registration form' "$temporary/error" >/dev/null
register_client 1.20.1
git rm --quiet runtime/minecraft-fabric-1.20.1/build.gradle.kts
commit_release 0.1.7
expect_failure 'missing paired runtime' resolve v0.1.7 "$(git rev-parse HEAD)" "$(git rev-parse HEAD)"
grep -F 'Representative project is missing or ambiguous' "$temporary/error" >/dev/null
git tag -a v9.0.0 "$selected" -m 'Wrong source version'
expect_failure 'root version mismatch' resolve v9.0.0 "$selected" "$controller"
git tag -d v9.0.0 >/dev/null
git tag -f -a v0.1.1 "$controller" -m 'Changed tag' >/dev/null
expect_failure 'tag changed after freeze' verify "$temporary/selection.json" "$controller"
echo 'Explicit historical release selection guards passed.'

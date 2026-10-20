#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

fixture="$temporary_root/libs.versions.toml"
cat > "$fixture" <<'TOML'
[versions]
java-latest = "25"
unrelated = "1.2.3"
java-baseline = "17"
java-middle = "21"
java-alias = "21" # Shared major toolchain
performance-toolchain = "17.0.18+8"
performance-launcher = "25.0.2"
[libraries]
java-library = { module = "example:library", version = "999" }
TOML

expected=$'17\n21\n25'
actual="$(python3 -B "$repository_root/gradle/java-toolchains.py" "$fixture")"
[[ "$actual" == "$expected" ]] || fail "Java toolchains are not unique and numerically ordered: $actual"

actual="$(python3 -B "$repository_root/gradle/java-toolchains.py" --paired "$fixture")"
[[ "$actual" == $'toolchain=17.0.18+8\nlauncher=25.0.2' ]] || fail "Frozen Java profile differs from the catalog: $actual"

mkdir -p "$temporary_root/product/gradle"
cp "$fixture" "$temporary_root/product/gradle/libs.versions.toml"
actual="$(cd "$temporary_root/product"; python3 -B "$repository_root/gradle/java-toolchains.py")"
[[ "$actual" == "$expected" ]] || fail 'Controller reader did not use the tagged product catalog.'
actual="$(cd "$temporary_root/product"; python3 -B "$repository_root/gradle/java-toolchains.py" --paired)"
[[ "$actual" == $'toolchain=17.0.18+8\nlauncher=25.0.2' ]] || fail 'Controller reader did not use the tagged frozen profile.'

printf '[versions]\njava-invalid = "twenty-one"\n' > "$fixture"
if python3 -B "$repository_root/gradle/java-toolchains.py" "$fixture" >/dev/null 2>&1; then
  fail 'Java toolchain discovery accepted a nonnumeric catalog value.'
fi

printf '[versions]\nperformance-toolchain = "17"\n' > "$fixture"
if python3 -B "$repository_root/gradle/java-toolchains.py" --paired "$fixture" >/dev/null 2>&1; then
  fail 'Frozen Java profile accepted a missing launcher.'
fi

setup_action="$repository_root/.github/actions/setup-strata-java/action.yml"
[[ -f "$setup_action" && ! -L "$setup_action" ]] || fail 'The catalog-backed Java setup action is missing or not regular.'
grep --fixed-strings 'TOOLCHAIN_READER: ${{ github.action_path }}/../../../gradle/java-toolchains.py' "$setup_action" >/dev/null ||
  fail 'The Java setup action does not read the tagged catalog through its controller-owned reader.'
grep --fixed-strings 'uses: actions/setup-java@' "$setup_action" >/dev/null ||
  fail 'The Java setup action does not invoke actions/setup-java.'
grep --fixed-strings 'java-version: ${{ steps.toolchains.outputs.versions }}' "$setup_action" >/dev/null ||
  fail 'The Java setup action does not pass its discovered inventory to actions/setup-java.'
if grep --fixed-strings 'java-version: |' "$setup_action" >/dev/null; then
  fail 'The Java setup action retains a hand-maintained Java version list.'
fi

composite_workflows=(jvm.yml pages.yml qodana.yml)
for workflow_name in "${composite_workflows[@]}"; do
  workflow="$repository_root/.github/workflows/$workflow_name"
  grep --fixed-strings 'uses: ./.github/actions/setup-strata-java' "$workflow" >/dev/null ||
    fail "$workflow_name does not use the catalog-backed Java setup action."
  if grep --fixed-strings 'uses: actions/setup-java' "$workflow" >/dev/null; then
    fail "$workflow_name retains a hand-maintained Java setup block."
  fi
done

echo 'Java toolchain inventory guards passed.'

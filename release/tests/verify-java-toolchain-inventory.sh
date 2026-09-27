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
java-alias = "21"
TOML

expected=$'17\n21\n25'
actual="$(bash "$repository_root/gradle/list-java-toolchains.sh" "$fixture")"
[[ "$actual" == "$expected" ]] || fail "Java toolchains are not unique and numerically ordered: $actual"

printf '[versions]\njava-invalid = "twenty-one"\n' > "$fixture"
if bash "$repository_root/gradle/list-java-toolchains.sh" "$fixture" >/dev/null 2>&1; then
  fail 'Java toolchain discovery accepted a nonnumeric catalog value.'
fi

setup_action="$repository_root/.github/actions/setup-strata-java/action.yml"
[[ -f "$setup_action" && ! -L "$setup_action" ]] || fail 'The catalog-backed Java setup action is missing or not regular.'
grep --fixed-strings 'bash gradle/list-java-toolchains.sh' "$setup_action" >/dev/null ||
  fail 'The Java setup action does not read the version catalog.'
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

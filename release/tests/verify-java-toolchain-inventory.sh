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

for required_workflow_name in jvm.yml qodana.yml; do
  required_workflow="$repository_root/.github/workflows/$required_workflow_name"
  [[ "$(grep --fixed-strings -c -- '- .github/actions/**' "$required_workflow")" == 2 ]] ||
    fail "$required_workflow_name does not run for composite-action changes on both push and pull requests."
done

publish_workflow="$repository_root/.github/workflows/publish-release.yml"
publish_setup_count="$(grep --fixed-strings -c 'uses: actions/setup-java@' "$publish_workflow")"
publish_resolver_count="$(grep --fixed-strings -c 'bash "$CONTROLLER_TOOL_DIRECTORY/list-java-toolchains.sh" gradle/libs.versions.toml' "$publish_workflow")"
publish_dynamic_input_count="$(grep --fixed-strings -c 'java-version: ${{ steps.java.outputs.versions }}' "$publish_workflow")"
(( 0 < publish_setup_count )) || fail 'publish-release.yml does not install the release toolchains.'
[[ "$publish_setup_count" == "$publish_resolver_count" && "$publish_setup_count" == "$publish_dynamic_input_count" ]] ||
  fail 'publish-release.yml does not derive every Java setup from the controller-materialized catalog parser.'
if grep --fixed-strings 'java-version: |' "$publish_workflow" >/dev/null; then
  fail 'publish-release.yml retains a hand-maintained Java version list.'
fi

echo 'Java toolchain inventory guards passed.'

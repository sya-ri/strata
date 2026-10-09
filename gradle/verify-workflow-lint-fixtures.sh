#!/usr/bin/env bash
# Exercise the installed workflow linter and both embedded-script integrations.
set -euo pipefail

lint=("${1:?actionlint executable required}" -shellcheck "${2:?ShellCheck executable required}" -pyflakes "${3:?Pyflakes executable required}")
fixture_root=$(mktemp -d)
trap 'rm -rf "$fixture_root"' EXIT
cat > "$fixture_root/valid.yml" <<'YAML'
name: Linter fixture
on: push
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - run: echo "Workflow linter fixture"
      - shell: python
        run: print("Python linter fixture")
YAML
"${lint[@]}" "$fixture_root/valid.yml"

sed 's/runs-on:/runs-on-typo:/' "$fixture_root/valid.yml" > "$fixture_root/workflow.yml"
sed "s/echo \"Workflow linter fixture\"/value=\"two words\"; echo \$value/" "$fixture_root/valid.yml" > "$fixture_root/shellcheck.yml"
sed 's/print("Python linter fixture")/print(undefined_fixture_value)/' "$fixture_root/valid.yml" > "$fixture_root/pyflakes.yml"

for kind in workflow shellcheck pyflakes; do
  if "${lint[@]}" "$fixture_root/$kind.yml" > "$fixture_root/$kind.log" 2>&1; then
    echo "The workflow linter accepted the broken $kind fixture." >&2
    exit 1
  fi
done
grep -F 'unexpected key "runs-on-typo"' "$fixture_root/workflow.log"
grep -F 'SC2086:' "$fixture_root/shellcheck.log"
grep -F 'undefined name' "$fixture_root/pyflakes.log"
echo 'Workflow, ShellCheck, and Pyflakes rejection fixtures passed.'

#!/usr/bin/env bash

set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
python3 - "$root/.github/workflows/publish-release.yml" <<'PY'
import itertools
import pathlib
import re
import sys

workflow = pathlib.Path(sys.argv[1]).read_text()
steps = dict(re.findall(r'^      - name: ([^\n]+)\n(.*?)(?=^      - name:|^  [a-z_]+:|\Z)', workflow, re.M | re.S))
channels = {
    'maven_central': ['Publish wholly absent Maven Central release'],
    'github_release': ['Preflight GitHub Release without mutation', 'Create or verify immutable GitHub Release'],
    'modrinth': ['Preflight Modrinth without mutation', 'Stage only missing Modrinth versions',
                'Submit or observe Modrinth review', 'Finalize approved Modrinth body during release',
                'Verify complete predecessor release before Modrinth body finalization',
                'Verify public predecessor services and Pages provenance',
                'Finalize current Modrinth body and verify approved release',
                'Verify public Modrinth inventory and CDN files'],
}
for values in itertools.product([False, True], repeat=3):
    inputs = dict(zip(channels, values))
    for channel, names in channels.items():
        for name in names:
            condition = re.search(r'^        if: (.+)$', steps[name], re.M).group(1)
            # Existing Central content is idempotent regardless of the switch.
            for public, portal in itertools.product(['absent', 'exact'], repeat=2):
                expression = condition.replace('&&', ' and ').replace('||', ' or ')
                for key, value in inputs.items():
                    expression = expression.replace('inputs.' + key, str(value))
                expression = expression.replace('steps.central.outputs.public_state', repr(public))
                expression = expression.replace('steps.central.outputs.portal_state', repr(portal))
                expected = inputs[channel] and (channel != 'maven_central' or public == portal == 'absent')
                assert eval(expression, {'__builtins__': {}}, {}) == expected, (name, inputs, public, portal)
assert 'make_latest=legacy' in steps['Create or verify immutable GitHub Release']
assert '$(git rev-parse origin/master)' not in workflow
assert 'verify-current-controller-release-order.sh' not in workflow
assert 'current-controller.json' not in workflow
assert workflow.count("echo 'Select at least one publication destination.'") == 2
assert workflow.count('EXPECTED_SOURCE_SELECTION: ${{ needs.preflight.outputs.source_selection }}') >= 6
assert 'Maven publication is disabled, but canonical Central artifacts are not available.' in workflow
assert '[[ "$SELECT_GITHUB" == true ]] || exit 0' in steps['Verify GitHub release assets and all Central signatures']
print('All publication destination combinations and immutable-source boundaries passed.')
PY

temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
mkdir "$temporary/repo" "$temporary/bin"
export FAKE_PAGES_RUNS="$temporary/runs.json"
cat > "$temporary/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'run list --workflow pages.yml --event push --branch master --status success --limit 100 --json conclusion,databaseId,event,headBranch,headSha' ]] || exit 99
cat "$FAKE_PAGES_RUNS"
SH
chmod +x "$temporary/bin/gh"
export PATH="$temporary/bin:$PATH"
cd "$temporary/repo"
git init --quiet
git config user.name 'Strata Pages Selection Test'
git config user.email 'pages-selection@example.invalid'
git config commit.gpgsign false
git config tag.gpgsign false
git config core.autocrlf false
mkdir release
product="$(printf 'a%.0s' {1..40})"
jq -n --arg commit "$product" '{current:{tag:"v0.1.6",commit:$commit}}' > release/current-controller.json
git add . && git commit --quiet -m 'Pages producer fixture'
producer="$(git rev-parse HEAD)"
printf '\n' >> release/current-controller.json
git add . && git commit --quiet -m 'Later controller fixture'
controller="$(git rev-parse HEAD)"
printf '\n' >> release/current-controller.json
git add . && git commit --quiet -m 'Future Pages fixture'
future="$(git rev-parse HEAD)"
jq -n --arg producer "$producer" --arg future "$future" '[
  {databaseId:303,headSha:$future,conclusion:"success",event:"push",headBranch:"master"},
  {databaseId:202,headSha:$producer,conclusion:"failure",event:"push",headBranch:"master"},
  {databaseId:101,headSha:$producer,conclusion:"success",event:"push",headBranch:"master"}
]' > "$FAKE_PAGES_RUNS"
selector="$root/release/select-release-pages.sh"
[[ "$(bash "$selector" v0.1.6 "$product" "$controller")" == "101 $producer" ]]
if bash "$selector" v0.1.5 "$product" "$controller" >/dev/null 2>&1; then
  echo 'Unrelated historical Pages proof was selected.' >&2; exit 1
fi
jq '.[2].databaseId = .[0].databaseId' "$FAKE_PAGES_RUNS" > "$temporary/duplicate.json"
mv "$temporary/duplicate.json" "$FAKE_PAGES_RUNS"
if bash "$selector" v0.1.6 "$product" "$controller" >/dev/null 2>&1; then
  echo 'Ambiguous Pages inventory was selected.' >&2; exit 1
fi
echo 'Historical Pages producer selection passed.'

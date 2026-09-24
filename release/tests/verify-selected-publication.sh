#!/usr/bin/env bash

set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
python3 - "$root/.github/workflows/publish-release.yml" "$BASH" <<'PY'
import itertools
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import textwrap

workflow = pathlib.Path(sys.argv[1]).read_text()
steps = dict(re.findall(r'^      - name: ([^\n]+)\n(.*?)(?=^      - name:|^  [a-z_]+:|\Z)', workflow, re.M | re.S))
channels = {
    'hangar': ['Preflight Hangar without mutation', 'Publish or reuse the exact Hangar version', 'Verify public Hangar files'],
    'curseforge': ['Preflight CurseForge without mutation', 'Stage only missing CurseForge files', 'Verify public CurseForge files'],
    'maven_central': ['Publish wholly absent Maven Central release'],
    'github_release': ['Preflight GitHub Release without mutation', 'Create or verify immutable GitHub Release'],
    'modrinth': ['Preflight Modrinth without mutation', 'Stage only missing Modrinth versions',
                'Submit or observe Modrinth review', 'Finalize approved Modrinth body during release',
                'Verify complete predecessor release before Modrinth body finalization',
                'Verify public predecessor services and Pages provenance',
                'Finalize current Modrinth body and verify approved release',
                'Verify public Modrinth inventory and CDN files'],
}
for values in itertools.product([False, True], repeat=5):
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
curseforge_stage = steps['Stage only missing CurseForge files']
assert curseforge_stage.index('revalidate_release_source\n') < curseforge_stage.index('curseforge-release.py" stage')
assert 'verify-github-tag-ruleset.sh' in curseforge_stage
assert 'verify-pages-deployment-source.sh' in curseforge_stage
assert 'verify_controller_tools\n          python3 "$CONTROLLER_TOOL_DIRECTORY/curseforge-release.py" stage' in curseforge_stage
assert 'CURSEFORGE_TOKEN:' not in steps['Verify public CurseForge files']
assert 'curseforge-release.py" verify' in steps['Verify public CurseForge files']
assert 'if: always() && inputs.curseforge' in steps['Preserve CurseForge upload receipt']
assert 'github.run_attempt' in steps['Preserve CurseForge upload receipt']
assert workflow.count('- name: Restore prior CurseForge upload receipts\n        if: inputs.curseforge') == 2
assert workflow.count("CURSEFORGE_READ_ENABLED: ${{ secrets.CURSEFORGE_API_KEY != '' }}") == 2
with tempfile.TemporaryDirectory() as temporary:
    environment = {**os.environ, 'SELECT_MODRINTH': 'false', 'SELECT_CURSEFORGE': 'true', 'SELECT_HANGAR': 'false',
                   'REQUESTED_PROJECT_ID': '', 'MODRINTH_TOKEN': '', 'CURSEFORGE_TOKEN': 'fixture-upload-token',
                   'CURSEFORGE_API_KEY': '', 'HANGAR_API_TOKEN': '', 'SIGNING_KEY': 'fixture-signing-key',
                   'SIGNING_PASSWORD': 'fixture-password', 'MAVEN_CENTRAL_USERNAME': 'fixture-user',
                   'MAVEN_CENTRAL_PASSWORD': 'fixture-password', 'GITHUB_OUTPUT': pathlib.Path(temporary, 'output').as_posix()}
    for name in ('Resolve immutable release credentials', 'Resolve final verification credentials'):
        script = textwrap.dedent(steps[name].split('        run: |\n', 1)[1])
        if name == 'Resolve final verification credentials':
            environment['CURSEFORGE_TOKEN'] = ''
        result = subprocess.run([sys.argv[2], '-c', script], cwd=pathlib.Path(sys.argv[1]).resolve().parents[2],
                                env=environment, capture_output=True, text=True)
        assert result.returncode == 0, (name, result.stderr)
        if name == 'Resolve immutable release credentials':
            missing = subprocess.run([sys.argv[2], '-c', script], cwd=pathlib.Path(sys.argv[1]).resolve().parents[2],
                                     env={**environment, 'CURSEFORGE_TOKEN': ''}, capture_output=True, text=True)
            assert missing.returncode != 0 and 'CURSEFORGE_TOKEN is missing.' in missing.stderr
hangar_stage = steps['Publish or reuse the exact Hangar version']
assert hangar_stage.index('revalidate_release_source\n') < hangar_stage.index('hangar-release.py" stage')
assert 'verify-github-tag-ruleset.sh' in hangar_stage
assert 'verify-pages-deployment-source.sh' in hangar_stage
assert 'hangar-release.py" verify' in steps['Verify public Hangar files']
assert 'HANGAR_API_TOKEN:' not in steps['Verify public Hangar files']
assert 'if: always() && inputs.hangar' in steps['Preserve Hangar publication receipt']
assert 'maven-file-count.py' in workflow
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

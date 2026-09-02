#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
workflow="$repository_root/.github/workflows/publish-release.yml"

fail() {
  echo "$1" >&2
  exit 1
}

step_run() {
  local name="$1"
  awk -v header="      - name: $name" '
    $0 == header { inside = 1; next }
    inside && /^        run: \|$/ { in_run = 1; next }
    in_run && (/^      - name:/ || /^  [a-z_]+:/) { exit }
    in_run { sub(/^          /, ""); print }
  ' "$workflow"
}

fixed_line_count() {
  local needle="$1"
  local content="$2"
  grep --fixed-strings -c -- "$needle" <<< "$content" || true
}

portable_call_count() {
  awk '
    /grep --fixed-strings/ { next }
    /^[[:space:]]*portable_jq[[:space:]]/ { count++; next }
    /^[[:space:]]*(if|while|until)[[:space:]]+portable_jq[[:space:]]/ { count++; next }
    /\)[[:space:]]+portable_jq[[:space:]]/ { count++; next }
    /\$\(portable_jq[[:space:]]/ { count++; next }
    /<\(portable_jq[[:space:]]/ { count++; next }
    /\|[[:space:]]*portable_jq[[:space:]]/ { count++; next }
    END { print count + 0 }
  ' <<< "$1"
}

assert_portable_scope() {
  local label="$1"
  local expected_calls="$2"
  local content="$3"

  [[ "$(fixed_line_count 'strata_jq_path="$(type -P jq || true)"' "$content")" == '1' ]] || fail "$label does not bind exactly one jq executable path."
  [[ "$(fixed_line_count 'strata_od_path="$(type -P od || true)"' "$content")" == '1' ]] || fail "$label does not bind exactly one od executable path."
  [[ "$(fixed_line_count 'strata_tr_path="$(type -P tr || true)"' "$content")" == '1' ]] || fail "$label does not bind exactly one tr executable path."
  [[ "$(fixed_line_count 'if "$strata_jq_path" --binary -n '\''null'\'' >/dev/null 2>&1; then' "$content")" == '1' ]] || fail "$label does not capability-test binary jq output exactly once."
  [[ "$(fixed_line_count '  strata_jq_binary_options=(--binary)' "$content")" == '1' ]] || fail "$label does not bind the optional binary mode exactly once."
  [[ "$(grep --only-matching --fixed-strings -- '--binary' <<< "$content" | wc -l | tr -d '[:space:]')" == '2' ]] || fail "$label contains an unexpected platform-specific jq option use."
  [[ "$(fixed_line_count 'if strata_jq_probe_hex="$(' "$content")" == '1' ]] || fail "$label does not explicitly guard its jq byte probe."
  [[ "$(fixed_line_count '"$strata_jq_path" "${strata_jq_binary_options[@]}" -nr --arg x x "\$x" |' "$content")" == '1' ]] || fail "$label does not probe the bound jq executable."
  [[ "$(fixed_line_count 'if [[ "$strata_jq_probe_hex" == '\''780a'\'' ]]; then' "$content")" == '1' ]] || fail "$label does not require exact LF-delimited jq bytes."
  [[ "$(fixed_line_count '"$strata_jq_path" "${strata_jq_binary_options[@]}" "$@"' "$content")" == '1' ]] || fail "$label portable_jq wrapper re-resolves jq through PATH."
  [[ "$(fixed_line_count 'readonly -f portable_jq' "$content")" == '1' ]] || fail "$label leaves portable_jq mutable."
  [[ "$(portable_call_count "$content")" == "$expected_calls" ]] || fail "$label does not contain exactly $expected_calls portable jq calls."
  if grep --fixed-strings 'jq --binary' <<< "$content" >/dev/null; then
    fail "$label still invokes the platform-specific jq option without the bound capability probe."
  fi
  if grep --fixed-strings 'command jq' <<< "$content" >/dev/null; then
    fail "$label re-resolves jq through PATH after capability probing."
  fi
}

workflow_scope_specs=(
  'Validate immutable release source|8'
  'Build the release inventory and manifest|1'
  'Run representative clients from Maven Local|1'
  'Run representative clients from Maven Central|1'
  'Fetch immutable public Skill sources without credentials|8'
  'Validate final verification source and Pages provenance|1'
  'Rebuild and verify Central release evidence|1'
)
total_calls=0
for scope_spec in "${workflow_scope_specs[@]}"; do
  scope_name="${scope_spec%|*}"
  expected_calls="${scope_spec##*|}"
  scope_content="$(step_run "$scope_name")"
  [[ -n "$scope_content" ]] || fail "Portable jq workflow scope is missing: $scope_name"
  assert_portable_scope "Workflow scope $scope_name" "$expected_calls" "$scope_content"
  total_calls=$((total_calls + expected_calls))
done

script_scope_specs=(
  'gradle/verify-qodana-model.sh|9'
  'gradle/verify-qodana-model-fixtures.sh|19'
  'release/run-publish-controller-recovery.sh|2'
  'release/verify-current-controller-release-order.sh|2'
  'release/verify-controller-tools.sh|12'
  'release/verify-pages-deployment-source.sh|3'
  'release/tests/verify-release-publish-controller-source.sh|14'
)
for scope_spec in "${script_scope_specs[@]}"; do
  scope_path="${scope_spec%|*}"
  expected_calls="${scope_spec##*|}"
  scope_content="$(<"$repository_root/$scope_path")"
  assert_portable_scope "Script scope $scope_path" "$expected_calls" "$scope_content"
  total_calls=$((total_calls + expected_calls))
done

[[ "$total_calls" == '82' ]] || fail 'Portable jq inventory does not cover all 82 platform-sensitive calls.'

fixture_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/strata-portable-jq.XXXXXX")"
cleanup() {
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT INT TERM
mkdir "$fixture_root/fallback" "$fixture_root/crlf"
native_jq_path="$(type -P jq)"
[[ "$native_jq_path" == /* && -x "$native_jq_path" ]] || fail 'The portable jq fixture requires an absolute native jq executable.'

cat > "$fixture_root/fallback/jq" <<'FALLBACK_JQ'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == '--binary' ]]; then
  exit 2
fi
if "$STRATA_NATIVE_JQ" --binary -n 'null' >/dev/null 2>&1; then
  exec "$STRATA_NATIVE_JQ" --binary "$@"
fi
exec "$STRATA_NATIVE_JQ" "$@"
FALLBACK_JQ

cat > "$fixture_root/crlf/jq" <<'CRLF_JQ'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == '--binary' ]]; then
  shift
fi
printf 'x\r\n'
CRLF_JQ
chmod +x "$fixture_root/fallback/jq" "$fixture_root/crlf/jq"

sed -n '1,/^readonly -f portable_jq$/p' "$repository_root/release/run-publish-controller-recovery.sh" > "$fixture_root/harness.sh"
cat >> "$fixture_root/harness.sh" <<'HARNESS'
portable_jq -nr --arg x x '$x'
HARNESS

fallback_hex="$(
  PATH="$fixture_root/fallback:$PATH" STRATA_NATIVE_JQ="$native_jq_path" bash "$fixture_root/harness.sh" |
    od -An -tx1 |
    tr -d '[:space:]'
)"
[[ "$fallback_hex" == '780a' ]] || fail 'The portable jq fallback did not preserve LF-only output when --binary was rejected.'

if PATH="$fixture_root/crlf:$PATH" bash "$fixture_root/harness.sh" > "$fixture_root/crlf.out" 2> "$fixture_root/crlf.err"; then
  fail 'The portable jq initializer accepted CRLF output after a successful capability probe.'
fi
grep --fixed-strings 'jq output mode does not produce exact LF-delimited bytes.' "$fixture_root/crlf.err" >/dev/null || \
  fail 'The CRLF fixture did not fail for the exact byte-semantic reason.'

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

fixture="$temporary_root/fixture"
fake_bin="$temporary_root/bin"
bundle="$fixture/build/release/github"
inventory="$temporary_root/inventory.txt"
assets_json="$temporary_root/assets.json"
release_json="$temporary_root/release.json"
real_jq="$(command -v jq)"
fixture_target_count=3
expected_asset_count=$((fixture_target_count * 2 + 1))
mkdir -p "$fixture/release" "$fixture/docs/releases" "$bundle" "$fake_bin"
cp "$repository_root/release/github-release-preflight.sh" "$fixture/release/github-release-preflight.sh"
printf '# Test release\n\nExact body.\n' > "$fixture/docs/releases/v0.1.0.md"

for index in $(seq 1 "$fixture_target_count"); do
  jar="strata-runtime-minecraft-fabric-test-$index-0.1.0.jar"
  printf 'jar-%s\n' "$index" > "$bundle/$jar"
  printf 'signature-%s\n' "$index" > "$bundle/$jar.asc"
done
(
  cd "$bundle"
  sha256sum *.jar *.jar.asc > SHA256SUMS
)
find "$bundle" -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort > "$inventory"
[[ "$(wc -l < "$inventory")" == "$expected_asset_count" ]] || fail 'The GitHub Release fixture asset count is not derived from its targets.'
jq --raw-input --slurp 'split("\n") | map(select(length != 0)) | to_entries | map({name: .value, id: (.key + 1)})' \
  "$inventory" > "$assets_json"
[[ "$(jq 'length' "$assets_json")" == "$expected_asset_count" ]] || fail 'The GitHub Release fixture asset metadata is incomplete.'

write_release_json() {
  local body_file="$1"
  jq -n \
    --rawfile body "$body_file" \
    --slurpfile assets "$assets_json" \
    '{
      tag_name: "v0.1.0",
      name: "Strata 0.1.0",
      body: $body,
      prerelease: false,
      draft: false,
      assets: $assets[0]
    }' > "$release_json"
  [[ "$(jq '.assets | length' "$release_json")" == "$expected_asset_count" ]] || fail 'The GitHub Release response fixture is incomplete.'
}

cat > "$fake_bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

output=""
write_out=""
url=""
while (( 0 < $# )); do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    --write-out)
      write_out="$2"
      shift 2
      ;;
    https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done
[[ -n "$output" && -n "$url" ]] || exit 64

case "$url" in
  */releases/tags/v0.1.0)
    cp "$FAKE_RELEASE_JSON" "$output"
    [[ "$write_out" == '%{http_code}' ]] || exit 64
    printf '200'
    ;;
  */releases/assets/*)
    asset_id="${url##*/}"
    [[ "$asset_id" =~ ^[0-9]+$ ]] || exit 64
    asset_name="$(sed -n "${asset_id}p" "$FAKE_RELEASE_INVENTORY")"
    [[ -n "$asset_name" ]] || exit 64
    cp "$FAKE_RELEASE_BUNDLE/$asset_name" "$output"
    ;;
  *)
    exit 64
    ;;
esac
SCRIPT
chmod +x "$fake_bin/curl"

cat > "$fake_bin/jq" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
"$REAL_JQ" "$@" | sed 's/\r$//'
SCRIPT
chmod +x "$fake_bin/jq"

run_preflight() {
  (
    cd "$fixture"
    PATH="$fake_bin:$PATH" \
      GH_TOKEN=test-token \
      GITHUB_API_URL=https://api.github.test \
      GITHUB_REPOSITORY=test/strata \
      RELEASE_TAG=v0.1.0 \
      CENTRAL_STATE=exact \
      FAKE_RELEASE_JSON="$release_json" \
      FAKE_RELEASE_INVENTORY="$inventory" \
      FAKE_RELEASE_BUNDLE="$bundle" \
      REAL_JQ="$real_jq" \
      bash release/github-release-preflight.sh build/release/github
  )
}

lf_body="$temporary_root/lf-body.md"
printf '# Test release\n\nExact body.\n' > "$lf_body"
write_release_json "$lf_body"
if ! lf_output="$(run_preflight 2>&1)"; then
  fail "The GitHub Release preflight rejected an exact LF remote body: $lf_output"
fi
grep --fixed-strings 'Existing GitHub Release metadata and uploaded assets are conflict-free.' <<< "$lf_output" >/dev/null ||
  fail 'The GitHub Release preflight rejected an exact LF remote body.'

crlf_body="$temporary_root/crlf-body.md"
printf '# Test release\r\n\r\nExact body.\r\n' > "$crlf_body"
write_release_json "$crlf_body"
if ! exact_output="$(run_preflight 2>&1)"; then
  fail "The GitHub Release preflight rejected an exact CRLF remote body: $exact_output"
fi
grep --fixed-strings 'Existing GitHub Release metadata and uploaded assets are conflict-free.' <<< "$exact_output" >/dev/null ||
  fail 'The GitHub Release preflight rejected an exact CRLF remote body.'

missing_signature="$(find "$bundle" -maxdepth 1 -type f -name '*.jar.asc' | head -n 1)"
mv "$missing_signature" "$bundle/unexpected-extra-file"
if missing_signature_output="$(run_preflight 2>&1)"; then
  fail 'The GitHub Release preflight accepted a missing signature replaced by an unrelated file.'
fi
grep --fixed-strings 'The canonical GitHub bundle is missing the detached signature' <<< "$missing_signature_output" >/dev/null ||
  fail 'The GitHub Release preflight did not report the missing detached signature.'
mv "$bundle/unexpected-extra-file" "$missing_signature"

canonical_checksums="$temporary_root/SHA256SUMS"
cp "$bundle/SHA256SUMS" "$canonical_checksums"
sed '$d' "$canonical_checksums" > "$bundle/SHA256SUMS"
if incomplete_checksums_output="$(run_preflight 2>&1)"; then
  fail 'The GitHub Release preflight accepted an incomplete SHA256SUMS inventory.'
fi
grep --fixed-strings 'SHA256SUMS does not cover the exact JAR and detached-signature inventory.' <<< "$incomplete_checksums_output" >/dev/null ||
  fail 'The GitHub Release preflight did not report the incomplete SHA256SUMS inventory.'
cp "$canonical_checksums" "$bundle/SHA256SUMS"

populated_bundle="$temporary_root/populated-bundle"
mv "$bundle" "$populated_bundle"
mkdir "$bundle"
if empty_bundle_output="$(run_preflight 2>&1)"; then
  fail 'The GitHub Release preflight accepted a bundle without runtime JARs.'
fi
grep --fixed-strings 'Expected at least one runtime JAR in the GitHub bundle.' <<< "$empty_bundle_output" >/dev/null ||
  fail 'The GitHub Release preflight did not report the empty runtime inventory.'
rmdir "$bundle"
mv "$populated_bundle" "$bundle"

mismatched_body="$temporary_root/mismatched-body.md"
printf '# Test release\r\n\r\nDifferent body.\r\n' > "$mismatched_body"
write_release_json "$mismatched_body"
if mismatched_output="$(run_preflight 2>&1)"; then
  fail 'The GitHub Release preflight accepted a substantive release-body mismatch.'
fi
grep --fixed-strings 'Existing GitHub Release body differs.' <<< "$mismatched_output" >/dev/null ||
  fail 'The GitHub Release preflight did not report the substantive body mismatch.'

for invalid_body in internal-lone-carriage-return terminal-lone-carriage-return missing-trailing-newline extra-trailing-newline; do
  body_file="$temporary_root/$invalid_body.md"
  case "$invalid_body" in
    internal-lone-carriage-return)
      printf '# Test release\r\n\r\nExact\r body.\r\n' > "$body_file"
      ;;
    terminal-lone-carriage-return)
      printf '# Test release\n\nExact body.\r' > "$body_file"
      ;;
    missing-trailing-newline)
      printf '# Test release\r\n\r\nExact body.' > "$body_file"
      ;;
    extra-trailing-newline)
      printf '# Test release\r\n\r\nExact body.\r\n\r\n' > "$body_file"
      ;;
  esac
  write_release_json "$body_file"
  if invalid_output="$(run_preflight 2>&1)"; then
    fail "The GitHub Release preflight accepted $invalid_body in the remote body."
  fi
  case "$invalid_body" in
    *lone-carriage-return)
      grep --fixed-strings 'release body contains a lone carriage return' <<< "$invalid_output" >/dev/null ||
        fail "The GitHub Release preflight did not report $invalid_body."
      ;;
    *)
      grep --fixed-strings 'Existing GitHub Release body differs.' <<< "$invalid_output" >/dev/null ||
        fail "The GitHub Release preflight did not report $invalid_body."
      ;;
  esac
done

echo 'GitHub Release preflight guards passed.'

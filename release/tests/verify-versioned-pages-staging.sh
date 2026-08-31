#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
pages_workflow="$repository_root/.github/workflows/pages.yml"
temporary_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

if grep --fixed-strings '    paths:' "$pages_workflow" >/dev/null; then
  fail 'Pages path filters do not cover the repository-wide public URL inventory.'
fi
grep --fixed-strings 'release_tags="$(bash release/list-release-tags.sh)"' "$pages_workflow" >/dev/null || \
  fail 'Pages does not discover the canonical release tag inventory.'
if grep --extended-regexp 'for release_tag in v[0-9]' "$pages_workflow" >/dev/null; then
  fail 'Pages retains a hand-maintained release tag list.'
fi

fixture="$temporary_root/fixture"
mkdir -p "$fixture/release" "$fixture/build/dokka/html/api" "$fixture/build/dokka/html/releases/0.1.0/guide"
cp "$repository_root/release/stage-versioned-pages.sh" "$fixture/release/stage-versioned-pages.sh"
cp "$repository_root/release/list-release-tags.sh" "$fixture/release/list-release-tags.sh"
printf 'old release\n' > "$fixture/build/dokka/html/releases/0.1.0/index.html"
printf 'old guide\n' > "$fixture/build/dokka/html/releases/0.1.0/guide/index.html"
printf 'current release\n' > "$fixture/build/dokka/html/index.html"
printf 'current API\n' > "$fixture/build/dokka/html/api/index.html"
printf '%s\n' / /api/index.html /index.html /source-receipt.json /source-revision.txt > \
  "$fixture/build/dokka/html/pages-public-urls.txt"

git -C "$fixture" init --quiet
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" config user.name 'Strata Test'
git -C "$fixture" add release/list-release-tags.sh release/stage-versioned-pages.sh
git -C "$fixture" commit --quiet -m fixture
git -C "$fixture" tag --annotate --message fixture v0.1.10
git -C "$fixture" tag --annotate --message fixture v0.1.2
git -C "$fixture" tag --annotate --message fixture v0.1.1

expected_tags=$'v0.1.1\nv0.1.2\nv0.1.10'
actual_tags="$(bash "$fixture/release/list-release-tags.sh" "$fixture")"
[[ "$actual_tags" == "$expected_tags" ]] || \
  fail "Release tags are not listed in version order: $actual_tags"

git -C "$fixture" tag --annotate --message fixture v0.1
if bash "$fixture/release/list-release-tags.sh" "$fixture" >/dev/null 2>&1; then
  fail 'Release tag listing accepted a malformed v-prefixed tag.'
fi
git -C "$fixture" tag --delete v0.1 >/dev/null

git -C "$fixture" tag v0.1.3
if bash "$fixture/release/list-release-tags.sh" "$fixture" >/dev/null 2>&1; then
  fail 'Release tag listing accepted a lightweight semantic tag.'
fi
git -C "$fixture" tag --delete v0.1.3 >/dev/null

tag_commit="$(git -C "$fixture" rev-parse 'refs/tags/v0.1.1^{commit}')"
printf '%s\n' v0.1.1 > "$fixture/build/dokka/html/source-revision.txt"
printf '{"commit":"%s","revision":"v0.1.1"}\n' "$tag_commit" > "$fixture/build/dokka/html/source-receipt.json"

(
  cd "$fixture"
  bash release/stage-versioned-pages.sh v0.1.1 build/dokka/html v0.1.1
)

[[ -f "$fixture/build/dokka/html/releases/0.1.0/index.html" ]] || fail 'Staging replaced an older immutable release.'
[[ -f "$fixture/build/dokka/html/releases/0.1.0/guide/index.html" ]] || fail 'Staging removed an immutable legacy guide.'
[[ -f "$fixture/build/dokka/html/releases/0.1.1/index.html" ]] || fail 'Staging omitted the current release root.'
[[ -f "$fixture/build/dokka/html/releases/0.1.1/api/index.html" ]] || fail 'Staging omitted the current Dokka API.'
[[ ! -e "$fixture/build/dokka/html/releases/0.1.1/guide" ]] || fail 'Staging injected a reader guide into the Dokka-only release.'
[[ ! -e "$fixture/build/dokka/html/releases/0.1.1/releases" ]] || fail 'A release snapshot recursively nested older releases.'
cmp --silent "$fixture/build/dokka/html/index.html" "$fixture/build/dokka/html/releases/0.1.1/index.html" || \
  fail 'The staged current release differs from its source root.'
cmp --silent "$fixture/build/dokka/html/pages-public-urls.txt" "$fixture/build/dokka/html/releases/0.1.1/pages-public-urls.txt" || \
  fail 'The staged current release changed its public inventory.'

if (
  cd "$fixture"
  bash release/stage-versioned-pages.sh latest build/dokka/html master >/dev/null 2>&1
); then
  fail 'Versioned Pages staging accepted a non-semantic release tag.'
fi

echo 'Versioned Pages staging discovers annotated releases and preserves immutable Dokka sites and legacy guides.'

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
mkdir -p "$fixture/release" "$fixture/build/dokka/html/releases/0.1.0"
cp "$repository_root/release/stage-versioned-pages.sh" "$fixture/release/stage-versioned-pages.sh"
printf 'old release\n' > "$fixture/build/dokka/html/releases/0.1.0/index.html"
printf 'current release\n' > "$fixture/build/dokka/html/index.html"

git -C "$fixture" init --quiet
git -C "$fixture" config user.email test@example.invalid
git -C "$fixture" config user.name 'Strata Test'
git -C "$fixture" add release/stage-versioned-pages.sh
git -C "$fixture" commit --quiet -m fixture
git -C "$fixture" tag v0.1.1
tag_commit="$(git -C "$fixture" rev-parse 'refs/tags/v0.1.1^{commit}')"
printf '%s\n' v0.1.1 > "$fixture/build/dokka/html/source-revision.txt"
printf '{"commit":"%s","revision":"v0.1.1"}\n' "$tag_commit" > "$fixture/build/dokka/html/source-receipt.json"

(
  cd "$fixture"
  bash release/stage-versioned-pages.sh v0.1.1 build/dokka/html v0.1.1
)

[[ -f "$fixture/build/dokka/html/releases/0.1.0/index.html" ]] || fail 'Staging replaced an older immutable release.'
[[ -f "$fixture/build/dokka/html/releases/0.1.1/index.html" ]] || fail 'Staging omitted the current release root.'
[[ ! -e "$fixture/build/dokka/html/releases/0.1.1/releases" ]] || fail 'A release snapshot recursively nested older releases.'
cmp --silent "$fixture/build/dokka/html/index.html" "$fixture/build/dokka/html/releases/0.1.1/index.html" || \
  fail 'The staged current release differs from its source root.'

if (
  cd "$fixture"
  bash release/stage-versioned-pages.sh latest build/dokka/html master >/dev/null 2>&1
); then
  fail 'Versioned Pages staging accepted a non-semantic release tag.'
fi

echo 'Versioned Pages staging keeps immutable releases flat and independent.'

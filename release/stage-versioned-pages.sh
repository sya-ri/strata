#!/usr/bin/env bash

set -euo pipefail

release_tag="${1:-v0.1.0}"
site_relative_path="${2:-build/dokka/html}"
current_revision="${3:-master}"

[[ "$release_tag" == v0.1.0 ]] || { echo 'The immutable Pages staging script is pinned to v0.1.0.' >&2; exit 1; }
[[ "$current_revision" == master || "$current_revision" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]] || {
  echo 'Unexpected current Pages source revision.' >&2
  exit 1
}

repository_root="$(cd "$(git rev-parse --show-toplevel)" && pwd -P)"
[[ -d "$repository_root/$site_relative_path" ]] || { echo 'Current Pages staging root is missing.' >&2; exit 1; }
site_root="$(cd "$repository_root/$site_relative_path" && pwd -P)"
case "$site_root" in
  "$repository_root"/build/*) ;;
  *) echo 'The Pages staging root must remain inside the repository build directory.' >&2; exit 1 ;;
esac
if ! git rev-parse --verify --quiet "refs/tags/$release_tag^{commit}" >/dev/null; then
  echo "$release_tag does not exist yet; no immutable release documentation is staged."
  exit 0
fi

snapshot="$(mktemp -d)"
worktree_parent=""
worktree=""
expected_revision=""
expected_receipt=""
cleanup() {
  if [[ -n "$worktree" && -d "$worktree" ]]; then
    git -C "$repository_root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  fi
  if [[ -n "$worktree_parent" ]]; then
    rm -rf -- "$worktree_parent"
  fi
  if [[ -n "$expected_revision" ]]; then
    rm -f -- "$expected_revision"
  fi
  if [[ -n "$expected_receipt" ]]; then
    rm -f -- "$expected_receipt"
  fi
  rm -rf -- "$snapshot"
}
trap cleanup EXIT

tag_commit="$(git rev-parse "refs/tags/$release_tag^{commit}")"
if [[ "$current_revision" == "$release_tag" && "$(git rev-parse HEAD)" == "$tag_commit" ]]; then
  cp -a "$site_root/." "$snapshot/"
else
  worktree_parent="$(mktemp -d)"
  worktree="$worktree_parent/checkout"
  git -C "$repository_root" worktree add --detach "$worktree" "refs/tags/$release_tag"
  (
    cd "$worktree"
    bash ./gradlew --no-parallel --max-workers=2 --no-build-cache \
      :integration:docs:checkDokkaPagesStaging \
      -Pstrata.sourceRevision="$release_tag" \
      -Pstrata.sourceCommit="$tag_commit"
  )
  cp -a "$worktree/build/dokka/html/." "$snapshot/"
fi

expected_revision="$(mktemp)"
printf '%s\n' "$release_tag" > "$expected_revision"
cmp --silent "$expected_revision" "$snapshot/source-revision.txt" || { echo 'Immutable Pages snapshot has the wrong source revision.' >&2; exit 1; }
rm -f -- "$expected_revision"
expected_revision=""
expected_receipt="$(mktemp)"
printf '{"commit":"%s","revision":"%s"}\n' "$tag_commit" "$release_tag" > "$expected_receipt"
cmp --silent "$expected_receipt" "$snapshot/source-receipt.json" || { echo 'Immutable Pages snapshot has the wrong source receipt.' >&2; exit 1; }
rm -f -- "$expected_receipt"
expected_receipt=""

destination="$site_root/releases/${release_tag#v}"
case "$destination" in
  "$site_root"/releases/0.1.0) ;;
  *) echo 'Unexpected immutable Pages destination.' >&2; exit 1 ;;
esac
rm -rf -- "$destination"
mkdir -p "$destination"
cp -a "$snapshot/." "$destination/"

echo "Staged immutable $release_tag documentation at releases/${release_tag#v}/."

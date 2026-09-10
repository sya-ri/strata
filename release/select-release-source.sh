#!/usr/bin/env bash

# Resolve an explicit product commit independently of the controller's default
# documentation release. Signature and remote-CI checks remain caller-owned.
set -euo pipefail
tool_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

fail() { echo "$1" >&2; exit 1; }

verify_identity() {
  local tag="$1" commit="$2" object="$3"
  [[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail 'A canonical stable release tag is required.'
  [[ "$commit" =~ ^[0-9a-f]{40}$ && "$object" =~ ^[0-9a-f]{40}$ ]] || fail 'Release identities must be full commit and tag-object IDs.'
  [[ "$(git --no-replace-objects cat-file -t "$commit")" == commit &&
    "$(git --no-replace-objects cat-file -t "$object")" == tag &&
    "$(git --no-replace-objects rev-parse --verify "refs/tags/$tag")" == "$object" &&
    "$(git --no-replace-objects rev-parse --verify "refs/tags/$tag^{commit}")" == "$commit" ]] || fail 'Selected release identity differs.'
  [[ "$(git --no-replace-objects cat-file -p "$object" | sed -n '1p')" == "object $commit" ]] || fail 'The tag object targets another product commit.'
  [[ "$(git --no-replace-objects cat-file -p "$object" | sed -n '2p')" == 'type commit' ]] || fail 'The release tag must point directly to a commit.'
  local version
  version="$(git --no-replace-objects show "$commit:build.gradle.kts" | sed -nE 's/^version = "([^"]+)"$/\1/p')"
  [[ "$version" == "${tag#v}" ]] || fail 'Selected release version differs from its source.'
}

verify_selection() {
  local metadata="$1" controller="$2"
  [[ "$controller" =~ ^[0-9a-f]{40}$ && "$(git --no-replace-objects cat-file -t "$controller")" == commit ]] || fail 'The controller must be a full commit ID.'
  jq -e 'type == "object" and keys == ["current","predecessor","schemaVersion"] and .schemaVersion == 1 and
    (.current | keys == ["commit","representativeMinecraftVersions","tag","tagObject"]) and
    (.predecessor | keys == ["commit","tag","tagObject"]) and
    (.current.representativeMinecraftVersions | type == "array" and length > 0 and length <= 3 and length == (unique | length) and all(.[]; type == "string"))' "$metadata" >/dev/null
  local tag commit object previous_tag previous_commit previous_object version path record mode project_kind object_type blob actual_path
  tag="$(jq -er '.current.tag' "$metadata")"
  commit="$(jq -er '.current.commit' "$metadata")"
  object="$(jq -er '.current.tagObject' "$metadata")"
  previous_tag="$(jq -er '.predecessor.tag' "$metadata")"
  previous_commit="$(jq -er '.predecessor.commit' "$metadata")"
  previous_object="$(jq -er '.predecessor.tagObject' "$metadata")"
  verify_identity "$tag" "$commit" "$object"
  verify_identity "$previous_tag" "$previous_commit" "$previous_object"
  [[ "$tag" != "$previous_tag" && "$(printf '%s\n%s\n' "$previous_tag" "$tag" | sort -V | head -n 1)" == "$previous_tag" ]] || fail 'Selected predecessor must have a lower version.'
  git --no-replace-objects merge-base --is-ancestor "$previous_commit" "$commit" || fail 'Selected predecessor is not an ancestor of the product.'
  git --no-replace-objects merge-base --is-ancestor "$commit" "$controller" || fail 'Selected product is not contained in the frozen controller history.'
  while IFS= read -r version; do
    [[ "$version" =~ ^(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))*$ ]] || fail 'Invalid representative Minecraft version.'
    for project_kind in runtime integration; do
      path="$project_kind/minecraft-fabric-$version/build.gradle.kts"
      record="$(git --no-replace-objects ls-tree --full-tree "$commit" -- "$path")"
      [[ -n "$record" && "$record" != *$'\n'* ]] || fail 'Representative project is missing or ambiguous.'
      read -r mode object_type blob actual_path <<< "$record"
      [[ "$mode" == 100644 && "$object_type" == blob && "$blob" =~ ^[0-9a-f]{40}$ && "$actual_path" == "$path" ]] || fail 'Representative project is not a regular source blob.'
    done
  done < <(jq -er '.current.representativeMinecraftVersions[]' "$metadata")
}

case "${1:-}" in
  resolve)
    [[ $# == 4 ]] || exit 64
    tag="$2" commit="$3" controller="$4"
    object="$(git --no-replace-objects rev-parse --verify "refs/tags/$tag")"
    verify_identity "$tag" "$commit" "$object"
    previous_tag=''
    found=false
    release_tags="$(bash "$tool_directory/list-release-tags.sh")"
    while IFS= read -r candidate; do
      [[ "$candidate" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || continue
      if [[ "$candidate" == "$tag" ]]; then found=true; break; fi
      previous_tag="$candidate"
    done <<< "$release_tags"
    [[ "$found" == true && -n "$previous_tag" ]] || fail 'Forward release requires an existing stable predecessor.'
    mapfile -t versions < <(git --no-replace-objects ls-tree -r --name-only "$commit" -- runtime/ |
      sed -nE 's#^runtime/minecraft-fabric-([0-9]+(\.[0-9]+)*)/build.gradle.kts$#\1#p' | sort -Vu)
    (( 0 < ${#versions[@]} )) || fail 'Selected source has no supported runtime.'
    representatives="$(printf '%s\n' "${versions[0]}" "${versions[(${#versions[@]} - 1) / 2]}" "${versions[${#versions[@]} - 1]}" |
      sort -Vu | jq -Rsc 'split("\n") | map(select(length != 0))')"
    temporary="$(mktemp)"
    trap 'rm -f -- "$temporary"' EXIT
    jq -n --arg tag "$tag" --arg commit "$commit" --arg object "$object" --argjson versions "$representatives" \
      --arg previous "$previous_tag" --arg previous_commit "$(git --no-replace-objects rev-parse "refs/tags/$previous_tag^{commit}")" \
      --arg previous_object "$(git --no-replace-objects rev-parse "refs/tags/$previous_tag")" \
      '{schemaVersion:1,current:{tag:$tag,commit:$commit,tagObject:$object,representativeMinecraftVersions:$versions},
        predecessor:{tag:$previous,commit:$previous_commit,tagObject:$previous_object}}' > "$temporary"
    verify_selection "$temporary" "$controller"
    cat "$temporary"
    ;;
  verify)
    [[ $# == 3 && -f "$2" && ! -L "$2" ]] || exit 64
    verify_selection "$2" "$3"
    ;;
  verify-input)
    [[ $# == 2 ]] || exit 64
    temporary="$(mktemp)"
    trap 'rm -f -- "$temporary"' EXIT
    cat > "$temporary"
    verify_selection "$temporary" "$2"
    ;;
  *) exit 64 ;;
esac

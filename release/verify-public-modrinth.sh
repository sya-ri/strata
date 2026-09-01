#!/usr/bin/env bash

set -euo pipefail

manifest_file="${1:-build/release/modrinth/manifest.json}"
project_id="${2:-}"
project_slug="${3:-strata-ui}"

[[ -z "${MODRINTH_TOKEN:-}" ]] || { echo 'The public Modrinth verification must not receive MODRINTH_TOKEN.' >&2; exit 1; }
[[ -z "${GH_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]] || { echo 'The public Modrinth verification must not receive a GitHub token.' >&2; exit 1; }
[[ -f "$manifest_file" ]] || { echo "Modrinth release manifest is missing: $manifest_file" >&2; exit 1; }
[[ "$project_id" =~ ^[A-Za-z0-9]+$ ]] || { echo 'Invalid Modrinth project ID.' >&2; exit 1; }
[[ "$project_slug" == strata-ui ]] || { echo 'Unexpected Modrinth project slug.' >&2; exit 1; }

temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
release_version="$(jq -er '.releaseVersion | select(type == "string" and length > 0)' "$manifest_file")"
user_agent="sya-ri/strata-release/$release_version (https://github.com/sya-ri/strata)"

curl_public() {
  local url="$1"
  local output="$2"
  curl --fail --silent --show-error --location --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' --proto-redir '=https' \
    --header "User-Agent: $user_agent" \
    "$url" --output "$output"
}

curl_cdn() {
  local url="$1"
  local output="$2"
  local status
  [[ "$url" =~ ^https://cdn\.modrinth\.com/ ]] || { echo "Unexpected Modrinth CDN URL: $url" >&2; exit 1; }
  status="$(curl --fail --silent --show-error --retry 3 --retry-all-errors --retry-delay 1 \
    --proto '=https' \
    --header "User-Agent: $user_agent" \
    --write-out '%{http_code}' \
    "$url" --output "$output")"
  [[ "$status" == 200 ]] || { echo "Modrinth CDN returned HTTP $status without an allowed redirect." >&2; exit 1; }
}

curl_public "https://modrinth.com/mod/$project_slug" "$temporary/project.html"
[[ -s "$temporary/project.html" ]] || { echo 'The unauthenticated Modrinth project page is empty.' >&2; exit 1; }

curl_public "https://api.modrinth.com/v2/project/$project_id" "$temporary/project.json"
jq --exit-status --slurpfile manifest "$manifest_file" \
  --arg project_id "$project_id" \
  --arg slug "$project_slug" '
    def normalized: gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "");
    .id == $project_id
    and .slug == $slug
    and .title == $manifest[0].project.title
    and .description == $manifest[0].project.description
    and ((.body | normalized) == ($manifest[0].project.body | normalized))
    and .project_type == "mod"
    and .status == "approved"
    and ((.categories | sort) == ($manifest[0].project.categories | sort))
    and ((.additional_categories | sort) == ($manifest[0].project.additionalCategories | sort))
    and .license.id == $manifest[0].project.licenseId
    and .client_side == $manifest[0].project.clientSide
    and .server_side == $manifest[0].project.serverSide
    and ((.environment | sort) == ["client_only"])
    and .source_url == $manifest[0].project.sourceUrl
    and .issues_url == $manifest[0].project.issuesUrl
    and .wiki_url == $manifest[0].project.documentationUrl
    and (.raw_icon_url | type) == "string"
    and (.gallery | length) == ($manifest[0].project.gallery | length)
    and (
      [.gallery[] | {featured, title, description, ordering}] | sort_by(.ordering)
    ) == (
      [$manifest[0].project.gallery[] | {featured, title, description, ordering}] | sort_by(.ordering)
    )
  ' "$temporary/project.json" >/dev/null || { echo 'The unauthenticated Modrinth project metadata or asset inventory differs from the manifest.' >&2; exit 1; }

curl_public "https://api.modrinth.com/v3/project/$project_id/disclosures" "$temporary/disclosures.json"
jq --exit-status --slurpfile manifest "$manifest_file" '
  (.disclosures | length) == 1
  and .disclosures[0].type == "ai_content"
  and .disclosures[0].note == $manifest[0].project.aiDisclosureNote
  and ((.disclosures[0].uses | sort) == ($manifest[0].project.aiDisclosureUses | sort))
' "$temporary/disclosures.json" >/dev/null || { echo 'The unauthenticated Modrinth content disclosures differ from the manifest.' >&2; exit 1; }

icon_url="$(jq -r '.raw_icon_url' "$temporary/project.json")"
icon_sha256="$(jq -r '.project.icon.sha256' "$manifest_file")"
curl_cdn "$icon_url" "$temporary/icon"
[[ "$(sha256sum "$temporary/icon" | cut -d ' ' -f 1)" == "$icon_sha256" ]] || { echo 'The public raw Modrinth icon differs from the tracked icon.' >&2; exit 1; }

gallery_index=0
while IFS= read -r expected_gallery; do
  gallery_title="$(jq -r '.title' <<< "$expected_gallery")"
  gallery_sha256="$(jq -r '.sha256' <<< "$expected_gallery")"
  gallery_url="$(jq -r --arg title "$gallery_title" '[.gallery[] | select(.title == $title)] | if length == 1 then .[0].raw_url else error("Expected exactly one gallery image") end' "$temporary/project.json")"
  curl_cdn "$gallery_url" "$temporary/gallery-$gallery_index"
  [[ "$(sha256sum "$temporary/gallery-$gallery_index" | cut -d ' ' -f 1)" == "$gallery_sha256" ]] || { echo "The public raw Modrinth gallery image differs: $gallery_title" >&2; exit 1; }
  gallery_index=$((gallery_index + 1))
done < <(jq -c '.project.gallery[]' "$manifest_file")
[[ "$gallery_index" == 3 ]] || { echo "Expected 3 publicly verified gallery images, found $gallery_index." >&2; exit 1; }

curl_public "https://api.modrinth.com/v2/project/$project_id/version" "$temporary/versions.json"
expected_count="$(jq -r '.artifacts | length' "$manifest_file")"
[[ "$expected_count" =~ ^[0-9]+$ ]] && (( 0 < expected_count )) || {
  echo "Expected a nonempty release manifest, found $expected_count artifacts." >&2
  exit 1
}
jq --exit-status --slurpfile manifest "$manifest_file" '
  ($manifest[0].artifacts | map(.versionNumber) | sort) as $expected
  | ($manifest[0].releaseVersion + "+mc") as $release_prefix
  | ([.[] | select(.version_number | startswith($release_prefix)) | .version_number] | sort) == $expected
' "$temporary/versions.json" >/dev/null || { echo 'The unauthenticated Modrinth version inventory differs from the release manifest.' >&2; exit 1; }

index=0
while IFS= read -r artifact; do
  game_version="$(jq -r '.gameVersion' <<< "$artifact")"
  version_number="$(jq -r '.versionNumber' <<< "$artifact")"
  version_name="$(jq -r '.versionName' <<< "$artifact")"
  file_name="$(jq -r '.fileName' <<< "$artifact")"
  expected_size="$(jq -r '.size' <<< "$artifact")"
  expected_sha256="$(jq -r '.sha256' <<< "$artifact")"
  expected_sha512="$(jq -r '.sha512' <<< "$artifact")"
  version="$(jq -c --arg number "$version_number" '[.[] | select(.version_number == $number)] | if length == 1 then .[0] else error("Expected exactly one public version") end' "$temporary/versions.json")"

  jq --exit-status \
    --slurpfile manifest "$manifest_file" \
    --arg project_id "$project_id" \
    --arg game_version "$game_version" \
    --arg version_number "$version_number" \
    --arg version_name "$version_name" \
    --arg file_name "$file_name" \
    --arg size "$expected_size" \
    --arg sha512 "$expected_sha512" '
      def normalized: gsub("\r\n"; "\n") | sub("[[:space:]]+$"; "");
      .project_id == $project_id
      and .version_number == $version_number
      and .name == $version_name
      and ((.changelog | normalized) == ($manifest[0].changelog | normalized))
      and .version_type == "release"
      and .status == "listed"
      and .featured == true
      and .environment == "client_only"
      and .game_versions == [$game_version]
      and .loaders == ["fabric"]
      and ([.dependencies[] | {project_id, version_id, file_name, dependency_type}] == [{project_id: "Ha28R6CL", version_id: null, file_name: null, dependency_type: "required"}])
      and (.files | length == 1)
      and .files[0].primary == true
      and .files[0].filename == $file_name
      and .files[0].size == ($size | tonumber)
      and .files[0].hashes.sha512 == $sha512
      and .files[0].file_type == null
    ' <<< "$version" >/dev/null || { echo "Public Modrinth metadata differs for $version_number." >&2; exit 1; }

  download_url="$(jq -r '.files[0].url' <<< "$version")"
  downloaded="$temporary/artifact-$index.jar"
  curl_cdn "$download_url" "$downloaded"
  [[ "$(wc -c < "$downloaded" | tr -d '[:space:]')" == "$expected_size" ]] || { echo "Public Modrinth file size differs for $version_number." >&2; exit 1; }
  [[ "$(sha256sum "$downloaded" | cut -d ' ' -f 1)" == "$expected_sha256" ]] || { echo "Public Modrinth SHA-256 differs for $version_number." >&2; exit 1; }
  [[ "$(sha512sum "$downloaded" | cut -d ' ' -f 1)" == "$expected_sha512" ]] || { echo "Public Modrinth SHA-512 differs for $version_number." >&2; exit 1; }
  index=$((index + 1))
done < <(jq -c '.artifacts[]' "$manifest_file")

[[ "$index" == "$expected_count" ]] || {
  echo "Expected $expected_count publicly downloaded Modrinth artifacts, found $index." >&2
  exit 1
}
echo "Unauthenticated Modrinth page, metadata, disclosures, assets, $expected_count-version inventory, and CDN artifacts are exact."

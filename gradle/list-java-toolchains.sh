#!/usr/bin/env bash

set -euo pipefail

catalog="${1:-gradle/libs.versions.toml}"
[[ -f "$catalog" ]] || { echo "Java toolchain catalog is missing: $catalog" >&2; exit 1; }

versions=()
while IFS= read -r line; do
  [[ "$line" =~ ^java-[A-Za-z0-9_.-]+[[:space:]]*=[[:space:]]*\"([0-9]+)\"[[:space:]]*$ ]] || {
    echo "Malformed Java toolchain catalog entry: $line" >&2
    exit 1
  }
  versions+=("${BASH_REMATCH[1]}")
done < <(sed -nE '/^java-[A-Za-z0-9_.-]+[[:space:]]*=/p' "$catalog")

(( 0 < ${#versions[@]} )) || { echo 'The version catalog does not declare a Java toolchain.' >&2; exit 1; }
printf '%s\n' "${versions[@]}" | sort --numeric-sort --unique

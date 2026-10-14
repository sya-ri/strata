#!/usr/bin/env bash
# Acquire the official Linux x64 workflow linters and keep every embedded-script check enabled.
set -euo pipefail

repository_root=$(git rev-parse --show-toplevel)
cd "$repository_root"
tool_root=$(mktemp -d)
trap 'rm -rf "$tool_root"' EXIT
versions=$(python3 -c 'import tomllib; from pathlib import Path; v = tomllib.loads(Path("gradle/libs.versions.toml").read_text())["versions"]; print(v["actionlint"], v["shellcheck"], v["pyflakes"])')
read -r actionlint_version shellcheck_version pyflakes_version <<< "$versions"
curl_options=(--fail --silent --show-error --location --retry 2 --connect-timeout 10 --max-time 120)

actionlint_archive="actionlint_${actionlint_version}_linux_amd64.tar.gz"
actionlint_release="https://github.com/rhysd/actionlint/releases/download/v${actionlint_version}"
curl "${curl_options[@]}" "$actionlint_release/$actionlint_archive" -o "$tool_root/$actionlint_archive"
curl "${curl_options[@]}" "$actionlint_release/actionlint_${actionlint_version}_checksums.txt" -o "$tool_root/actionlint-checksums.txt"
(cd "$tool_root"; grep -F "  $actionlint_archive" actionlint-checksums.txt | sha256sum --check --strict -)
tar -xzf "$tool_root/$actionlint_archive" -C "$tool_root" actionlint

shellcheck_archive="shellcheck-v${shellcheck_version}.linux.x86_64.tar.xz"
curl "${curl_options[@]}" "https://github.com/koalaman/shellcheck/releases/download/v${shellcheck_version}/$shellcheck_archive" -o "$tool_root/$shellcheck_archive"
# The official release-asset digest must be reviewed with a ShellCheck version change.
printf '%s  %s\n' '8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198' "$tool_root/$shellcheck_archive" | sha256sum --check --strict -
tar -xJf "$tool_root/$shellcheck_archive" -C "$tool_root" --strip-components=1 "shellcheck-v${shellcheck_version}/shellcheck"

python3 -m venv "$tool_root/python"
# Pin the official PyPI wheel digest along with its catalog version.
printf 'pyflakes==%s --hash=sha256:%s\n' "$pyflakes_version" '330ba92b8c1db2eb0b8f4068f6c58674e2649a99e334769aa50e3e9c5b11c23a' > "$tool_root/requirements.txt"
"$tool_root/python/bin/python" -m pip install --disable-pip-version-check --only-binary=:all: --no-deps --require-hashes --retries 2 --timeout 30 -r "$tool_root/requirements.txt"

"$tool_root/actionlint" -version
"$tool_root/shellcheck" --version
"$tool_root/python/bin/pyflakes" --version
"$tool_root/actionlint" -shellcheck "$tool_root/shellcheck" -pyflakes "$tool_root/python/bin/pyflakes"
bash gradle/verify-workflow-lint-fixtures.sh "$tool_root/actionlint" "$tool_root/shellcheck" "$tool_root/python/bin/pyflakes"

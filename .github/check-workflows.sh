#!/usr/bin/env bash
# Run the workflow linters installed from the project's locked mise configuration.
set -euo pipefail

repository_root=$(git rev-parse --show-toplevel)
cd "$repository_root"

actionlint -version
shellcheck --version
pyflakes --version
actionlint -shellcheck "$(command -v shellcheck)" -pyflakes "$(command -v pyflakes)"
bash gradle/verify-workflow-lint-fixtures.sh "$(command -v actionlint)" "$(command -v shellcheck)" "$(command -v pyflakes)"

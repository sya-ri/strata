#!/usr/bin/env bash

set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
mkdir "$temporary/bin" "$temporary/pages"
export REAL_JQ="$(command -v jq)"
cat > "$temporary/bin/jq" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
"$REAL_JQ" "$@" | sed 's/\r$//'
SCRIPT
cat > "$temporary/bin/curl" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
arguments=" $* "
for required in '--fail' '--retry 3' '--retry-all-errors' '--retry-max-time 180' '--max-time 60' '--proto =https' '--proto-redir =https'; do
  [[ "$arguments" == *" $required "* ]] || { echo "Missing bounded read option: $required" >&2; exit 64; }
done
output=''
url=''
while (( 0 < $# )); do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
[[ -n "$output" && -n "$url" ]] || exit 64
printf '%s\n' "$url" >> "$REQUESTS"
case "$url" in
  *'/releases?per_page=100&page='*)
    page="${url##*=}"
    [[ "${FAILED_PAGE:-}" != "$page" ]] || exit 22
    cp "$PAGES/$page.json" "$output"
    ;;
  */releases/assets/123)
    if [[ "${FAILED_DOWNLOAD:-false}" == true ]]; then
      printf partial > "$output"
      exit 22
    fi
    printf 'exact asset bytes\n' > "$output"
    ;;
  *) echo "Unexpected read: $url" >&2; exit 64 ;;
esac
SCRIPT
chmod +x "$temporary/bin/"*
export PATH="$temporary/bin:$PATH"
export GH_TOKEN=fixture-token GITHUB_API_URL=https://api.github.test GITHUB_REPOSITORY=test/strata RELEASE_TAG=v0.1.6
export PAGES="$temporary/pages" REQUESTS="$temporary/requests"
tool="$root/release/github-release-read.sh"

expect_failure() {
  if bash "$tool" find > "$temporary/result" 2> "$temporary/error"; then
    echo "Invalid release inventory accepted: $1" >&2
    exit 1
  fi
  [[ ! -s "$temporary/result" ]] || { echo 'Failed lookup emitted an apparent result.' >&2; exit 1; }
}

printf '[]\n' > "$PAGES/1.json"
[[ "$(bash "$tool" find)" == null ]]
jq -n '[{id:7, tag_name:"v0.1.6", draft:true, assets:[]}]' > "$PAGES/1.json"
[[ "$(bash "$tool" find | jq -r '.id')" == 7 ]]
jq -n '[{id:7, tag_name:"v0.1.6", draft:false, assets:[]}]' > "$PAGES/1.json"
[[ "$(bash "$tool" find | jq -r '.draft')" == false ]]

jq -n '[range(1;101) | {id:.,tag_name:("unrelated-"+tostring),draft:false}]' > "$PAGES/1.json"
jq -n '[{id:101,tag_name:"v0.1.6",draft:true}]' > "$PAGES/2.json"
: > "$REQUESTS"
[[ "$(bash "$tool" find | jq -r '.id')" == 101 ]]
[[ "$(wc -l < "$REQUESTS")" == 2 ]]
export FAILED_PAGE=2
expect_failure 'later-page HTTP error'
unset FAILED_PAGE

jq '.[0].tag_name="v0.1.6"' "$PAGES/1.json" > "$temporary/first.json"
mv "$temporary/first.json" "$PAGES/1.json"
expect_failure 'matching draft on a later page'
jq -n '[{id:1,tag_name:"different",draft:false}]' > "$PAGES/2.json"
expect_failure 'repeated pagination ID'

for invalid in '{}' '[null]' '[{"id":1,"tag_name":"v0.1.6","draft":"true"}]' '[{"id":0,"tag_name":"v0.1.6","draft":true}]' 'not json'; do
  printf '%s\n' "$invalid" > "$PAGES/1.json"
  expect_failure 'malformed inventory'
done
jq -n '[{id:1,tag_name:"v0.1.6",draft:true},{id:2,tag_name:"v0.1.6",draft:false}]' > "$PAGES/1.json"
expect_failure 'published and draft share a tag'

bash "$tool" download 123 "$temporary/asset"
[[ "$(cat "$temporary/asset")" == 'exact asset bytes' ]]
if bash "$tool" download 123 "$temporary/asset"; then
  echo 'Existing asset was overwritten.' >&2; exit 1
fi
export FAILED_DOWNLOAD=true
if bash "$tool" download 123 "$temporary/partial"; then
  echo 'Failed download was accepted.' >&2; exit 1
fi
[[ ! -e "$temporary/partial" ]]
unset FAILED_DOWNLOAD
if bash "$tool" download '../escape' "$temporary/unsafe"; then
  echo 'Non-numeric asset identity was accepted.' >&2; exit 1
fi
[[ ! -e "$temporary/unsafe" ]]
echo 'GitHub draft lookup and bounded read guards passed.'

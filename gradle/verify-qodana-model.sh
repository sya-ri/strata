#!/usr/bin/env bash

set -euo pipefail

input_model=${1:?Pass the Qodana projectStructure/Modules.json path}
script_root=$(cd "$(dirname "$0")" && pwd)
repository_root=$(cd "$script_root/.." && pwd)
strata_jq_path="$(type -P jq || true)"
strata_od_path="$(type -P od || true)"
strata_tr_path="$(type -P tr || true)"
if [[ "$strata_jq_path" == /* && -x "$strata_jq_path" && \
  "$strata_od_path" == /* && -x "$strata_od_path" && \
  "$strata_tr_path" == /* && -x "$strata_tr_path" ]]; then
  :
else
  echo 'Portable jq initialization requires absolute executable jq, od, and tr paths.' >&2
  exit 1
fi
readonly strata_jq_path strata_od_path strata_tr_path

strata_jq_binary_options=()
if "$strata_jq_path" --binary -n 'null' >/dev/null 2>&1; then
  strata_jq_binary_options=(--binary)
fi
readonly -a strata_jq_binary_options

strata_jq_probe_hex=''
if strata_jq_probe_hex="$(
  "$strata_jq_path" "${strata_jq_binary_options[@]}" -nr --arg x x "\$x" |
    "$strata_od_path" -An -tx1 |
    "$strata_tr_path" -d '[:space:]'
)"; then
  :
else
  echo 'jq output-mode byte probing failed.' >&2
  exit 1
fi
if [[ "$strata_jq_probe_hex" == '780a' ]]; then
  unset strata_jq_probe_hex
else
  echo 'jq output mode does not produce exact LF-delimited bytes.' >&2
  exit 1
fi

portable_jq() {
  "$strata_jq_path" "${strata_jq_binary_options[@]}" "$@"
}
readonly -f portable_jq
project_root=${2:-$repository_root}
qodana_container_project_root=${3:-}
if (( 3 < $# )); then
  echo 'Qodana model verification accepts at most the model, project root, and trusted container project root.' >&2
  exit 1
fi
if [[ -n "$qodana_container_project_root" && "$qodana_container_project_root" != '/data/project' ]]; then
  echo "The trusted Qodana container project root must be exactly /data/project: $qodana_container_project_root" >&2
  exit 1
fi
readonly qodana_container_project_root
if [[ -f "$input_model" && ! -L "$input_model" ]]; then
  :
else
  echo "Qodana did not produce an exact regular non-symlink file: $input_model" >&2
  exit 1
fi

inventory_directory=$(mktemp -d)
trap 'rm -rf -- "$inventory_directory"' EXIT
model="$inventory_directory/qodana-modules.json"
readonly model

qodana_python=''
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && \
    "$candidate" -c 'import json, pathlib, sys, xml.etree.ElementTree; raise SystemExit(sys.version_info < (3, 8))' >/dev/null 2>&1; then
    qodana_python=$candidate
    break
  fi
done
[[ -n "$qodana_python" ]] || {
  echo 'Python 3.8 or newer with the standard JSON and XML modules is required to verify Qodana output.' >&2
  exit 1
}

if "$qodana_python" - "$input_model" "$model" <<'PY'
import json
import os
import pathlib
import stat
import sys

source = pathlib.Path(sys.argv[1])
model = pathlib.Path(sys.argv[2])


def fail(message):
    print(f"Qodana project inventory is invalid: {model}: {message}", file=sys.stderr)
    raise SystemExit(1)


class ModelValidationError(ValueError):
    pass


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ModelValidationError(f"duplicate object key: {ascii(key)}")
        result[key] = value
    return result


def reject_non_json_constant(value):
    raise ModelValidationError(f"non-JSON numeric constant: {value}")


def contains_lone_surrogate(value):
    index = 0
    while index < len(value):
        code_point = ord(value[index])
        if 0xD800 <= code_point <= 0xDBFF:
            if index + 1 < len(value) and 0xDC00 <= ord(value[index + 1]) <= 0xDFFF:
                index += 2
                continue
            return True
        if 0xDC00 <= code_point <= 0xDFFF:
            return True
        index += 1
    return False


def reject_lone_surrogates(value):
    if isinstance(value, str):
        if contains_lone_surrogate(value):
            fail("contains a lone surrogate code point")
    elif isinstance(value, dict):
        for key, child in value.items():
            reject_lone_surrogates(key)
            reject_lone_surrogates(child)
    elif isinstance(value, list):
        for child in value:
            reject_lone_surrogates(child)


try:
    source_status = source.lstat()
    if source.is_symlink() or not stat.S_ISREG(source_status.st_mode):
        fail("source is not an exact regular non-symlink file")
    open_flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(source, open_flags)
    with os.fdopen(descriptor, "rb") as stream:
        opened_status = os.fstat(stream.fileno())
        if not stat.S_ISREG(opened_status.st_mode):
            fail("opened source is not an exact regular file")
        if (source_status.st_dev, source_status.st_ino) != (
            opened_status.st_dev,
            opened_status.st_ino,
        ):
            fail("source identity changed before the private snapshot read")
        source_bytes = stream.read()
        completed_status = os.fstat(stream.fileno())
        if (
            opened_status.st_size,
            opened_status.st_mtime_ns,
            opened_status.st_dev,
            opened_status.st_ino,
        ) != (
            completed_status.st_size,
            completed_status.st_mtime_ns,
            completed_status.st_dev,
            completed_status.st_ino,
        ):
            fail("source changed during the private snapshot read")
except OSError as error:
    fail(f"unable to read strict UTF-8 JSON without following links: {error}")
try:
    with model.open("xb") as snapshot:
        snapshot.write(source_bytes)
    model.chmod(0o400)
    model_bytes = model.read_bytes()
except OSError as error:
    fail(f"unable to create the private Qodana JSON snapshot: {error}")
if model_bytes != source_bytes:
    fail("private Qodana JSON snapshot bytes changed while being created")
try:
    model_text = model_bytes.decode("utf-8")
except UnicodeDecodeError as error:
    fail(f"not valid UTF-8 JSON: {error}")
try:
    parsed_model = json.loads(
        model_text,
        object_pairs_hook=reject_duplicate_keys,
        parse_constant=reject_non_json_constant,
    )
except (json.JSONDecodeError, ModelValidationError) as error:
    fail(f"not strict JSON: {error}")
reject_lone_surrogates(parsed_model)

if not isinstance(parsed_model, dict):
    fail("the root must be a JSON object")
modules = parsed_model.get("modules")
if not isinstance(modules, list):
    fail("modules must be a JSON array")
for module_index, module in enumerate(modules):
    if not isinstance(module, dict):
        fail(f"modules[{module_index}] must be a JSON object")
    if "orderEntries" in module:
        order_entries = module["orderEntries"]
        if not isinstance(order_entries, list):
            fail(f"modules[{module_index}].orderEntries must be a JSON array")
        if any(not isinstance(entry, dict) for entry in order_entries):
            fail(f"modules[{module_index}].orderEntries entries must be JSON objects")
    if "contentEntries" not in module:
        continue
    content_entries = module["contentEntries"]
    if not isinstance(content_entries, list):
        fail(f"modules[{module_index}].contentEntries must be a JSON array")
    for content_index, content_entry in enumerate(content_entries):
        if not isinstance(content_entry, dict):
            fail(
                f"modules[{module_index}].contentEntries[{content_index}] "
                "must be a JSON object"
            )
        if "sourceFolders" not in content_entry:
            continue
        source_folders = content_entry["sourceFolders"]
        if not isinstance(source_folders, list):
            fail(
                f"modules[{module_index}].contentEntries[{content_index}]."
                "sourceFolders must be a JSON array"
            )
        if any(not isinstance(folder, dict) for folder in source_folders):
            fail(
                f"modules[{module_index}].contentEntries[{content_index}]."
                "sourceFolders entries must be JSON objects"
            )
PY
then
  :
else
  exit 1
fi

if [[ -d "$project_root" ]]; then
  project_root=$(cd "$project_root" && pwd -P)
else
  echo "Qodana project inventory root does not exist: $project_root" >&2
  exit 1
fi

windows_paths=false
if command -v cygpath >/dev/null 2>&1; then
  windows_paths=true
fi

paths_equal() {
  local left=$1
  local right=$2
  if $windows_paths; then
    [[ "${left,,}" == "${right,,}" ]]
  else
    [[ "$left" == "$right" ]]
  fi
}

path_is_child_of() {
  local candidate=$1
  local parent=$2
  if $windows_paths; then
    candidate=${candidate,,}
    parent=${parent,,}
  fi
  [[ "$candidate" == "$parent/"* ]]
}

relative_path_from_parent() {
  local candidate=$1
  local parent=$2
  printf '%s' "${candidate:$(( ${#parent} + 1 ))}"
}

canonical_repository_relative_path() {
  local relative_path=$1
  if $windows_paths; then
    printf '%s' "${relative_path,,}"
  else
    printf '%s' "$relative_path"
  fi
}

resolve_iml_posix_absolute_source_path() {
  local absolute_path=$1
  local source_url=$2
  local container_relative_path
  local canonical_container_relative_path
  local canonical_resolved_relative_path
  local resolved_relative_path

  if [[ -n "$qodana_container_project_root" && "$absolute_path" == "$qodana_container_project_root" ]]; then
    resolved_path=$project_root
  elif [[ -n "$qodana_container_project_root" && "$absolute_path" == "$qodana_container_project_root/"* ]]; then
    container_relative_path=${absolute_path#"$qodana_container_project_root/"}
    resolved_path=$(realpath -m -- "$project_root/$container_relative_path")
    if path_is_child_of "$resolved_path" "$project_root"; then
      resolved_relative_path=$(relative_path_from_parent "$resolved_path" "$project_root")
      canonical_container_relative_path=$(canonical_repository_relative_path "$container_relative_path")
      canonical_resolved_relative_path=$(canonical_repository_relative_path "$resolved_relative_path")
      if [[ "$canonical_container_relative_path" == "$canonical_resolved_relative_path" ]]; then
        return
      fi
    fi
    echo "The generated IDEA module contains a non-canonical container source URL: $source_url" >&2
    exit 1
  else
    resolved_path=$(realpath -m -- "$absolute_path")
  fi
}

bash "$script_root/plan-minecraft-ci.sh" "$project_root" "$inventory_directory" >/dev/null
mapfile -t expected_versions < <(sed -n 's#^runtime/minecraft-fabric-##p' "$inventory_directory/minecraft-loom-projects.txt")

project_modules=(
  'runtime;^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-[0-9]+([._][0-9]+)*$;^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-'
  'integration;^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-[0-9]+([._][0-9]+)*$;^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-'
)
for project_modules_entry in "${project_modules[@]}"; do
  IFS=';' read -r label pattern prefix_pattern <<< "$project_modules_entry"
  mapfile -t actual_versions < <(
    portable_jq -r --arg pattern "$pattern" --arg prefixPattern "$prefix_pattern" '
      .modules[]
      | .name
      | select(test($pattern))
      | sub($prefixPattern; "")
      | gsub("_"; ".")
    ' "$model" |
      LC_ALL=C sort -V
  )
  expected_display=$(IFS=,; printf '%s' "${expected_versions[*]}")
  actual_display=$(IFS=,; printf '%s' "${actual_versions[*]}")
  if [[ "$actual_display" == "$expected_display" ]]; then
    echo "Verified ${#actual_versions[@]} Minecraft $label project modules for [$actual_display]."
  else
    echo "Expected Minecraft $label project modules for [$expected_display] but found [$actual_display]." >&2
    exit 1
  fi
done

font_backend_pattern='^(strata\.runtime\.minecraft-fonts-lwjgl|runtime-minecraft-fonts-lwjgl|minecraft-fonts-lwjgl)$'
font_backend_count=$(portable_jq --arg pattern "$font_backend_pattern" '[.modules[] | select(.name | test($pattern))] | length' "$model")
if [[ "$font_backend_count" -ne 1 ]]; then
  echo "Expected one CPU font backend module but found $font_backend_count." >&2
  exit 1
fi

docs_pattern='^docs$'
docs_count=$(portable_jq --arg pattern "$docs_pattern" '[.modules[] | select(.name | test($pattern))] | length' "$model")
if [[ "$docs_count" -ne 1 ]]; then
  echo "Expected one documentation module but found $docs_count." >&2
  exit 1
fi

incomplete_docs=$(
  portable_jq -c --arg pattern "$docs_pattern" '
    [
      .modules[]
      | select(.name | test($pattern))
      | select(
          (.orderEntries | length) < 3
          or (.orderEntries | any(.type == "SDK") | not)
        )
      | .name
    ]
  ' "$model"
)
if [[ "$incomplete_docs" == '[]' ]]; then
  echo 'Verified the documentation module SDK and dependency entries.'
else
  echo "The documentation module has an incomplete imported model: $incomplete_docs" >&2
  exit 1
fi

incomplete_font_backend=$(
  portable_jq -c --arg pattern "$font_backend_pattern" '
    [
      .modules[]
      | select(.name | test($pattern))
      | select(
          (.orderEntries | length) < 3
          or (.orderEntries | any(.type == "SDK") | not)
        )
      | .name
    ]
  ' "$model"
)
if [[ "$incomplete_font_backend" == '[]' ]]; then
  echo 'Verified the CPU font backend SDK and dependency entries.'
else
  echo "The CPU font backend has an incomplete imported model: $incomplete_font_backend" >&2
  exit 1
fi

project_module_pattern='^((strata\.(runtime|integration)\.minecraft-fabric)|((runtime|integration)-minecraft-fabric))-[0-9]+([._][0-9]+)*$'
incomplete_modules=$(
  portable_jq -c --arg pattern "$project_module_pattern" '
    [
      .modules[]
      | select(.name | test($pattern))
      | select(
          (.orderEntries | length) < 3
          or (.orderEntries | any(.type == "SDK") | not)
        )
      | .name
    ]
  ' "$model"
)
if [[ "$incomplete_modules" == '[]' ]]; then
  echo 'Verified SDK and dependency entries for every Minecraft project module.'
else
  echo "Minecraft project modules have an incomplete imported model: $incomplete_modules" >&2
  exit 1
fi

expected_target_source_roots="$inventory_directory/qodana-expected-target-source-roots.tsv"
: > "$expected_target_source_roots"

classify_target_source_path() {
  local repository_relative_path=$1
  target_source_allowed_owners=''
  target_source_is_module_root=false
  target_source_root=''
  target_source_uses_target_namespace=false
  case "$repository_relative_path" in
    runtime/minecraft-fabric-* | \
      integration/minecraft-fabric-* | \
      integration/minecraft-font-parity* | \
      runtime/minecraft-fonts-lwjgl*)
      target_source_uses_target_namespace=true
      ;;
  esac
  if [[ "$repository_relative_path" == 'runtime/minecraft-fonts-lwjgl' ]]; then
    target_source_allowed_owners='font-backend'
    target_source_root='runtime/minecraft-fonts-lwjgl'
    target_source_is_module_root=true
  elif [[ "$repository_relative_path" == 'runtime/minecraft-fonts-lwjgl/'* ]]; then
    target_source_allowed_owners='font-backend'
    target_source_root='runtime/minecraft-fonts-lwjgl'
  elif [[ "$repository_relative_path" =~ ^(runtime/minecraft-fabric-[0-9]+([.][0-9]+)*(-legacy)?)(/|$) ]]; then
    target_source_allowed_owners='runtime'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
  elif [[ "$repository_relative_path" =~ ^(runtime/minecraft-fabric-(shared|identifier|unobfuscated))(/|$) ]]; then
    target_source_allowed_owners='runtime'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
  elif [[ "$repository_relative_path" =~ ^(runtime/minecraft-fabric-canvas-[a-z0-9]+(-[a-z0-9]+)*)(/|$) ]]; then
    target_source_allowed_owners='runtime'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
  elif [[ "$repository_relative_path" =~ ^(integration/minecraft-fabric-[0-9]+([.][0-9]+)*(-legacy)?)(/|$) ]]; then
    target_source_allowed_owners='integration'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
  elif [[ "$repository_relative_path" =~ ^(integration/minecraft-fabric-(client-gametest|unobfuscated))(/|$) ]]; then
    target_source_allowed_owners='integration'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
    if [[ "$repository_relative_path" == 'integration/minecraft-fabric-unobfuscated/src/gametest/kotlin' ]]; then
      target_source_allowed_owners='integration,docs'
    fi
  elif [[ "$repository_relative_path" =~ ^(integration/minecraft-fabric-canvas-[a-z0-9]+(-[a-z0-9]+)*)(/|$) ]]; then
    target_source_allowed_owners='integration'
    target_source_root=${BASH_REMATCH[1]}
    [[ "$repository_relative_path" != "$target_source_root" ]] || target_source_is_module_root=true
  elif [[ "$repository_relative_path" =~ ^(integration/minecraft-font-parity(-legacy|-26)?)(/|$) ]]; then
    target_source_allowed_owners='integration'
    target_source_root=${BASH_REMATCH[1]}
    if [[ "$repository_relative_path" == 'integration/minecraft-font-parity/src/gametest/kotlin' || \
      "$repository_relative_path" == 'integration/minecraft-font-parity/src/gametest/resources' ]]; then
      target_source_allowed_owners='integration,font-backend'
    elif [[ "$repository_relative_path" == "$target_source_root" ]]; then
      target_source_is_module_root=true
    fi
  fi
}

target_source_path_allows_owner() {
  local owner=$1
  [[ ",$target_source_allowed_owners," == *",$owner,"* ]]
}

record_expected_target_source_roots() {
  local owner=$1
  local version=$2
  local project_path=$3
  local iml_name=$4
  local iml="$project_root/$project_path/$iml_name"
  local absolute_path
  local source_url
  local relative_path
  local canonical_relative_path
  local repository_relative_path
  local canonical_source_url
  local module_directory="$project_root/$project_path"
  local resolved_path
  local owner_relative_path
  local source_type
  local parsed_iml_sources

  if [[ -f "$iml" && ! -L "$iml" ]]; then
    :
  else
    echo "The generated IDEA module must be a regular non-symlink file with the expected name: $iml" >&2
    exit 1
  fi

  parsed_iml_sources=$(mktemp "$inventory_directory/qodana-iml-sources.XXXXXX")
  if "$qodana_python" - "$iml" > "$parsed_iml_sources" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

iml = pathlib.Path(sys.argv[1])


def fail(message):
    print(f"The generated IDEA module is invalid: {iml}: {message}", file=sys.stderr)
    raise SystemExit(1)


try:
    xml_bytes = iml.read_bytes()
except OSError as error:
    fail(f"unable to read UTF-8 XML: {error}")
try:
    xml_text = xml_bytes.decode("utf-8-sig")
except UnicodeDecodeError as error:
    fail(f"not valid UTF-8: {error}")
if "\x00" in xml_text:
    fail("not valid UTF-8 XML: contains a NUL byte")
expected_declaration = '<?xml version="1.0" encoding="UTF-8"?>'
if not xml_text.startswith(expected_declaration):
    fail("XML declaration must be exactly version 1.0 with UTF-8 encoding")
if "<!DOCTYPE" in xml_text:
    fail("DOCTYPE declarations are not allowed")
try:
    root = ET.fromstring(xml_text)
except ET.ParseError as error:
    fail(f"not well-formed XML: {error}")


def element_identity(tag):
    if not isinstance(tag, str):
        return False, None
    if tag.startswith("{") and "}" in tag:
        return True, tag.split("}", 1)[1]
    return False, tag


qualified_root, root_name = element_identity(root.tag)
if qualified_root or root_name != "module":
    fail("the root element must be an exact unqualified module")

relevant_elements = []
for element in root.iter():
    qualified, element_name = element_identity(element.tag)
    if element_name not in ("content", "sourceFolder"):
        continue
    if qualified:
        fail(f"qualified {element_name} elements are not allowed")
    relevant_elements.append((element_name, element))

manager_components = [
    child
    for child in root
    if child.tag == "component"
    and child.attrib.get("name") == "NewModuleRootManager"
]
if len(manager_components) != 1:
    fail(
        "expected exactly one direct unqualified "
        f"NewModuleRootManager component but found {len(manager_components)}"
    )

content_elements = [
    child for child in manager_components[0] if child.tag == "content"
]

if len(content_elements) != 1:
    fail(
        "expected exactly one direct unqualified content element under "
        f"NewModuleRootManager but found {len(content_elements)}"
    )

source_folders = [
    child for child in content_elements[0] if child.tag == "sourceFolder"
]
allowed_relevant_elements = {
    id(content_elements[0]),
    *(id(source_folder) for source_folder in source_folders),
}
for element_name, element in relevant_elements:
    if id(element) not in allowed_relevant_elements:
        fail(
            f"{element_name} must be in the exact direct "
            "module/NewModuleRootManager/content/sourceFolder chain"
        )


def validated_url(element_name, element):
    url = element.attrib.get("url")
    if url is None:
        fail(f"{element_name} is missing its exact url attribute")
    if "\\" in url:
        fail(f"{element_name} contains an unsupported backslash URL")
    if "%" in url:
        fail(f"{element_name} contains an unsupported percent-encoded URL")
    if any(ord(character) < 32 or 127 <= ord(character) <= 159 for character in url):
        fail(f"{element_name} URL contains a control character")
    return url


content_attribute_names = set(content_elements[0].attrib)
if content_attribute_names != {"url"}:
    fail(f"content must contain only its exact url attribute: {sorted(content_attribute_names)}")
content_url = validated_url("content", content_elements[0])
if content_url != "file://$MODULE_DIR$/":
    fail(f"content URL must be file://$MODULE_DIR$/: {content_url}")

for source_folder in source_folders:
    source_attribute_names = set(source_folder.attrib)
    if source_attribute_names not in ({"url", "type"}, {"url", "isTestSource"}):
        fail(
            "sourceFolder must contain only its exact url plus exactly one exact "
            f"type or isTestSource attribute: {sorted(source_attribute_names)}"
        )
    source_url = validated_url("sourceFolder", source_folder)
    has_type = "type" in source_folder.attrib
    has_is_test_source = "isTestSource" in source_folder.attrib
    if has_type == has_is_test_source:
        fail("sourceFolder must contain exactly one exact type or isTestSource attribute")
    if has_type:
        source_kind = source_folder.attrib["type"]
        if source_kind == "java-test-resource":
            source_type = "TestResource"
        elif source_kind == "java-resource":
            source_type = "Resource"
        else:
            fail(f"sourceFolder contains an unsupported type value: {source_kind}")
    else:
        test_source = source_folder.attrib["isTestSource"]
        if test_source == "true":
            source_type = "TestSource"
        elif test_source == "false":
            source_type = "Source"
        else:
            fail(f"sourceFolder contains an unsupported isTestSource value: {test_source}")
    sys.stdout.buffer.write(f"{source_type}\t{source_url}\n".encode("utf-8"))
PY
  then
    :
  else
    exit 1
  fi

  while IFS=$'\t' read -r source_type source_url; do

    relative_path=''
    case "$source_url" in
      'file://$MODULE_DIR$')
        resolved_path=$module_directory
        ;;
      'file://$MODULE_DIR$/'*)
        relative_path=${source_url#'file://$MODULE_DIR$/'}
        resolved_path=$(realpath -m -- "$module_directory/$relative_path")
        ;;
      file:///[A-Za-z]:/*)
        absolute_path=${source_url#file:///}
        if $windows_paths; then
          absolute_path=$(cygpath -u -- "$absolute_path")
        else
          echo "The generated IDEA module contains a platform-incompatible source URL: $iml: $source_url" >&2
          exit 1
        fi
        resolved_path=$(realpath -m -- "$absolute_path")
        ;;
      file://[A-Za-z]:/*)
        absolute_path=${source_url#file://}
        if $windows_paths; then
          absolute_path=$(cygpath -u -- "$absolute_path")
        else
          echo "The generated IDEA module contains a platform-incompatible source URL: $iml: $source_url" >&2
          exit 1
        fi
        resolved_path=$(realpath -m -- "$absolute_path")
        ;;
      file:///*)
        absolute_path=${source_url#file://}
        resolve_iml_posix_absolute_source_path "$absolute_path" "$source_url"
        ;;
      *)
        echo "The generated IDEA module contains an unsupported source URL: $iml: $source_url" >&2
        exit 1
        ;;
    esac

    if paths_equal "$resolved_path" "$module_directory"; then
      echo "The generated IDEA module must not use the entire module directory as a source root: $iml: $source_url" >&2
      exit 1
    elif path_is_child_of "$resolved_path" "$module_directory"; then
      owner_relative_path=$(relative_path_from_parent "$resolved_path" "$module_directory")
      owner_relative_path=$(canonical_repository_relative_path "$owner_relative_path")
      canonical_relative_path=$(canonical_repository_relative_path "$relative_path")
      if [[ -n "$relative_path" && "$canonical_relative_path" == "$owner_relative_path" ]]; then
        :
      else
        echo "The generated IDEA module contains a non-canonical owned source URL: $iml: $source_url" >&2
        exit 1
      fi
    elif paths_equal "$resolved_path" "$project_root"; then
      echo "The generated IDEA module must not use the entire repository as a linked source root: $iml: $source_url" >&2
      exit 1
    elif path_is_child_of "$resolved_path" "$project_root"; then
      repository_relative_path=$(relative_path_from_parent "$resolved_path" "$project_root")
      repository_relative_path=$(canonical_repository_relative_path "$repository_relative_path")
      classify_target_source_path "$repository_relative_path"
      if [[ -z "$target_source_allowed_owners" ]]; then
        echo "The generated IDEA module links an unapproved repository source root: $iml: $source_url" >&2
        exit 1
      elif $target_source_is_module_root; then
        echo "The generated IDEA module must not use an entire target module as a linked source root: $iml: $source_url" >&2
        exit 1
      elif target_source_path_allows_owner "$owner"; then
        :
      else
        echo "The generated IDEA module links a source root owned by another target: $iml: $source_url" >&2
        exit 1
      fi
      [[ -d "$resolved_path" ]] || {
        echo "The generated IDEA module links a source root that does not exist: $iml: $source_url" >&2
        exit 1
      }
      canonical_source_url="file://\$PROJECT_DIR\$/$repository_relative_path"
      printf '%s\t%s\t%s\t%s\n' \
        "$owner" \
        "$version" \
        "$source_type" \
        "$canonical_source_url" >> "$expected_target_source_roots"
      continue
    else
      echo "The generated IDEA module contains a linked source path outside the repository: $iml: $source_url" >&2
      exit 1
    fi
    [[ -d "$resolved_path" ]] || {
      echo "The generated IDEA module declares an owned source root that does not exist: $iml: $source_url" >&2
      exit 1
    }

    printf '%s\t%s\t%s\tfile://$PROJECT_DIR$/%s/%s\n' \
      "$owner" \
      "$version" \
      "$source_type" \
      "$project_path" \
      "$owner_relative_path" >> "$expected_target_source_roots"
  done < "$parsed_iml_sources"
}

for version in "${expected_versions[@]}"; do
  record_expected_target_source_roots \
    'runtime' \
    "$version" \
    "runtime/minecraft-fabric-$version" \
    "runtime-minecraft-fabric-$version.iml"
  record_expected_target_source_roots \
    'integration' \
    "$version" \
    "integration/minecraft-fabric-$version" \
    "integration-minecraft-fabric-$version.iml"
done
record_expected_target_source_roots \
  'docs' \
  '-' \
  'integration/docs' \
  'docs.iml'
record_expected_target_source_roots \
  'font-backend' \
  '-' \
  'runtime/minecraft-fonts-lwjgl' \
  'minecraft-fonts-lwjgl.iml'

docs_showcase_source_path='file://$PROJECT_DIR$/integration/minecraft-fabric-unobfuscated/src/gametest/kotlin'
required_docs_showcase_source=$'docs\t-\tSource\t'"$docs_showcase_source_path"
docs_showcase_source_count=$(grep -Fxc "$required_docs_showcase_source" "$expected_target_source_roots" || true)
docs_target_source_count=0
while IFS=$'\t' read -r expected_owner _ _ expected_path; do
  if [[ "$expected_owner" != 'docs' || "$expected_path" != 'file://$PROJECT_DIR$/'* ]]; then
    continue
  fi
  classify_target_source_path "${expected_path#'file://$PROJECT_DIR$/'}"
  $target_source_uses_target_namespace || continue
  ((docs_target_source_count += 1))
done < "$expected_target_source_roots"
if [[ "$docs_showcase_source_count" -ne 1 || "$docs_target_source_count" -ne 1 ]]; then
  echo "The generated documentation IDEA module must link exactly one showcase Source and no other target source root: source=$docs_showcase_source_count total=$docs_target_source_count" >&2
  exit 1
fi

for version in "${expected_versions[@]}"; do
  runtime_font_source="file://\$PROJECT_DIR\$/runtime/minecraft-fabric-$version/src/font/kotlin"
  runtime_font_source_count=0
  integration_test_source_count=0
  while IFS=$'\t' read -r expected_owner expected_version expected_type expected_path; do
    if [[ "$expected_owner" == 'runtime' && \
      "$expected_version" == "$version" && \
      "$expected_type" == 'Source' && \
      "$expected_path" == "$runtime_font_source" ]]; then
      ((runtime_font_source_count += 1))
    elif [[ "$expected_owner" == 'integration' && \
      "$expected_version" == "$version" && \
      "$expected_type" == 'TestSource' ]]; then
      ((integration_test_source_count += 1))
    fi
  done < "$expected_target_source_roots"
  if [[ "$runtime_font_source_count" -ne 1 ]]; then
    echo "The generated runtime IDEA module must contain exactly one owned font Source: version=$version count=$runtime_font_source_count" >&2
    exit 1
  elif [[ "$integration_test_source_count" -eq 0 ]]; then
    echo "The generated integration IDEA module must contain at least one TestSource: version=$version" >&2
    exit 1
  fi
done

font_backend_source_count=0
font_backend_test_source_count=0
while IFS=$'\t' read -r expected_owner expected_version expected_type expected_path; do
  if [[ "$expected_owner" == 'font-backend' && "$expected_type" == 'Source' ]]; then
    ((font_backend_source_count += 1))
  elif [[ "$expected_owner" == 'font-backend' && "$expected_type" == 'TestSource' ]]; then
    ((font_backend_test_source_count += 1))
  fi
done < "$expected_target_source_roots"
if [[ "$font_backend_source_count" -eq 0 || "$font_backend_test_source_count" -eq 0 ]]; then
  echo "The generated CPU font backend IDEA module must contain at least one Source and TestSource: source=$font_backend_source_count testSource=$font_backend_test_source_count" >&2
  exit 1
fi

font_backend_parity_source_path='file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/kotlin'
font_backend_parity_resource_path='file://$PROJECT_DIR$/integration/minecraft-font-parity/src/gametest/resources'
required_font_backend_parity_source=$'font-backend\t-\tTestSource\t'"$font_backend_parity_source_path"
required_font_backend_parity_resource=$'font-backend\t-\tTestResource\t'"$font_backend_parity_resource_path"
required_font_backend_parity_source_count=$(grep -Fxc "$required_font_backend_parity_source" "$expected_target_source_roots" || true)
required_font_backend_parity_resource_count=$(grep -Fxc "$required_font_backend_parity_resource" "$expected_target_source_roots" || true)
font_backend_parity_root_count=0
while IFS=$'\t' read -r expected_owner expected_version expected_type expected_path; do
  if [[ "$expected_owner" == 'font-backend' && \
    ( "$expected_path" == "$font_backend_parity_source_path" || "$expected_path" == "$font_backend_parity_resource_path" ) ]]; then
    ((font_backend_parity_root_count += 1))
  fi
done < "$expected_target_source_roots"
if [[ "$required_font_backend_parity_source_count" -eq 1 && \
  "$required_font_backend_parity_resource_count" -eq 1 && \
  "$font_backend_parity_root_count" -eq 2 ]]; then
  :
else
  echo "The generated CPU font backend module must link exactly one parity TestSource and TestResource: source=$required_font_backend_parity_source_count resource=$required_font_backend_parity_resource_count total=$font_backend_parity_root_count" >&2
  exit 1
fi

model_source_roots="$inventory_directory/qodana-model-source-roots.tsv"
: > "$model_source_roots"
if portable_jq -e '
  def safeField:
    type == "string"
    and (contains("\\") | not)
    and (test("[\u0000-\u001F\u007F-\u009F]") | not);
  all(
    .modules[] as $module
    | $module.contentEntries[]?.sourceFolders[]?
    | {module: $module.name, type, path};
    (.module | safeField) and (.type | safeField) and (.path | safeField)
  )
' "$model" >/dev/null; then
  :
else
  echo 'Qodana project modules contain a malformed source-folder identity, type, or path.' >&2
  exit 1
fi

while IFS=$'\t' read -r module_name source_type source_url; do
  [[ "$source_url" != *%* ]] || {
    echo "Qodana project modules contain an unsupported percent-encoded source URL: $source_url" >&2
    exit 1
  }
  case "$source_url" in
    'file://$PROJECT_DIR$')
      resolved_path=$project_root
      ;;
    'file://$PROJECT_DIR$/'*)
      relative_path=${source_url#'file://$PROJECT_DIR$/'}
      resolved_path=$(realpath -m -- "$project_root/$relative_path")
      ;;
    file:///[A-Za-z]:/*)
      absolute_path=${source_url#file:///}
      if $windows_paths; then
        absolute_path=$(cygpath -u -- "$absolute_path")
      else
        echo "Qodana project modules contain a platform-incompatible source URL: $source_url" >&2
        exit 1
      fi
      resolved_path=$(realpath -m -- "$absolute_path")
      ;;
    file://[A-Za-z]:/*)
      absolute_path=${source_url#file://}
      if $windows_paths; then
        absolute_path=$(cygpath -u -- "$absolute_path")
      else
        echo "Qodana project modules contain a platform-incompatible source URL: $source_url" >&2
        exit 1
      fi
      resolved_path=$(realpath -m -- "$absolute_path")
      ;;
    file:///*)
      absolute_path=${source_url#file://}
      resolved_path=$(realpath -m -- "$absolute_path")
      ;;
    *)
      echo "Qodana project modules contain an unsupported source URL: $source_url" >&2
      exit 1
      ;;
  esac

  if paths_equal "$resolved_path" "$project_root"; then
    echo "Qodana project modules contain the entire repository as a source root: $source_url" >&2
    exit 1
  elif path_is_child_of "$resolved_path" "$project_root"; then
    repository_relative_path=$(relative_path_from_parent "$resolved_path" "$project_root")
    repository_relative_path=$(canonical_repository_relative_path "$repository_relative_path")
    canonical_source_url="file://\$PROJECT_DIR\$/$repository_relative_path"
  else
    echo "Qodana project modules contain a source URL outside the repository: $source_url" >&2
    exit 1
  fi
  [[ -d "$resolved_path" ]] || {
    echo "Qodana project modules contain a source root that does not exist: $source_url" >&2
    exit 1
  }
  classify_target_source_path "$repository_relative_path"
  if $target_source_uses_target_namespace && [[ -z "$target_source_allowed_owners" ]]; then
    echo "Qodana project modules contain an unapproved target-namespace source root: $source_url" >&2
    exit 1
  fi
  printf '%s\t%s\t%s\t%s\n' \
    "$module_name" \
    "$source_type" \
    "$canonical_source_url" \
    "$target_source_allowed_owners" >> "$model_source_roots"
done < <(
  portable_jq -r '
    .modules[] as $module
    | $module.contentEntries[]?.sourceFolders[]?
    | [$module.name, .type, .path]
    | @tsv
  ' "$model"
)

target_source_mismatches=$(
  portable_jq -c \
    --arg runtimePattern '^(strata\.runtime\.minecraft-fabric|runtime-minecraft-fabric)-[0-9]+([._][0-9]+)*$' \
    --arg integrationPattern '^(strata\.integration\.minecraft-fabric|integration-minecraft-fabric)-[0-9]+([._][0-9]+)*$' \
    --arg docsPattern "$docs_pattern" \
    --arg fontPattern "$font_backend_pattern" \
    --rawfile expectedTargetSources "$expected_target_source_roots" \
    --rawfile actualSources "$model_source_roots" '
      def expectedTargetSources:
        $expectedTargetSources
        | split("\n")
        | map(
            select(length != 0)
            | split("\t")
            | {owner: .[0], version: .[1], type: .[2], path: .[3]}
          );
      def actualSources:
        $actualSources
        | split("\n")
        | map(
            select(length != 0)
            | split("\t")
            | {
                module: .[0],
                type: .[1],
                path: .[2],
                classifiedOwners: (.[3] | split(",") | map(select(length != 0)))
              }
          );
      def targetIdentity($runtimePattern; $integrationPattern; $docsPattern; $fontPattern):
        if .name | test($runtimePattern) then
          {
            owner: "runtime",
            version: (.name | sub("^(strata\\.runtime\\.minecraft-fabric|runtime-minecraft-fabric)-"; "") | gsub("_"; "."))
          }
        elif .name | test($integrationPattern) then
          {
            owner: "integration",
            version: (.name | sub("^(strata\\.integration\\.minecraft-fabric|integration-minecraft-fabric)-"; "") | gsub("_"; "."))
          }
        elif .name | test($docsPattern) then
          {owner: "docs", version: "-"}
        elif .name | test($fontPattern) then
          {owner: "font-backend", version: "-"}
        else
          {owner: "unexpected-module", version: .name}
        end;
      (
        expectedTargetSources
        | sort_by([.owner, .version, .type, .path])
      ) as $expected
      | (
          [
            actualSources[]
            | . as $source
            | ({name: $source.module} | targetIdentity($runtimePattern; $integrationPattern; $docsPattern; $fontPattern)) as $identity
            | select($identity.owner != "unexpected-module" or ($source.classifiedOwners | length) != 0)
            | {
                owner: $identity.owner,
                version: $identity.version,
                type: $source.type,
                path: $source.path
              }
          ]
          | sort_by([.owner, .version, .type, .path])
        ) as $actual
      | if $actual == $expected then
          []
        else
          [{expected: $expected, actual: $actual}]
        end
    ' "$model"
)
if [[ "$target_source_mismatches" == '[]' ]]; then
  echo 'Verified every existing owned and declared linked target source root.'
else
  echo "Qodana project modules do not exactly match their existing owned and declared linked target source roots: $target_source_mismatches" >&2
  exit 1
fi

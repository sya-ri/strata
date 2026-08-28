"""Measure caller-supplied official font assets without downloading or redistributing them.

Requires Python 3.11 or later for the standard-library TOML reader.
Only the explicitly named output receipt is written; input caches are read-only.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import pathlib
import struct
import tomllib
import zipfile

ROOT: pathlib.Path
LIMITS = dict(json_bytes=8 * 1024 * 1024, asset_bytes=64 * 1024 * 1024, expanded_bytes=128 * 1024 * 1024, line_bytes=4096, records=1_000_000)


def hashes(stream):
    """Hash all bytes from a caller-owned stream with fixed scratch space and no retained stream."""
    sha1, sha256 = hashlib.sha1(), hashlib.sha256()
    length = 0
    while block := stream.read(1024 * 1024):
        length += len(block)
        sha1.update(block)
        sha256.update(block)
    return dict(bytes=length, sha1=sha1.hexdigest(), sha256=sha256.hexdigest())


def file_hashes(path):
    """Close one input after hashing and expose only its cache-relative portable locator."""
    with path.open("rb") as stream:
        return dict(locator=path.relative_to(ROOT).as_posix(), **hashes(stream))


def bounded_read(stream, maximum):
    """Read through short chunks up to an inclusive ceiling without closing the borrowed stream."""
    if maximum < 0:
        raise ValueError("measurement input bound must be non-negative")
    data = bytearray()
    while len(data) <= maximum:
        block = stream.read(min(64 * 1024, maximum - len(data) + 1))
        if not block:
            return bytes(data)
        data.extend(block)
    raise ValueError(f"measurement input exceeds safe bound {maximum}")


def read_json(path):
    """Parse bounded UTF-8 metadata and close its input on success or failure."""
    with path.open("rb") as stream:
        return json.loads(bounded_read(stream, LIMITS["json_bytes"]).decode("utf-8"))


def font_path(path):
    """Recognize canonical resource-font document locations without opening them."""
    fields = path.split("/", 3)
    return len(fields) == 4 and fields[0] == "assets" and fields[2] == "font" and path.endswith(".json")


def location(value, prefix="", suffix=""):
    """Translate a supplied resource identifier into the selected asset-relative path."""
    namespace, path = value.split(":", 1) if ":" in value else ("minecraft", value)
    return f"assets/{namespace}/{prefix}{path}{suffix}"


def font_document(data):
    """Remove only trailing commas outside strings, as accepted in cached vanilla metadata."""
    text = data.decode("utf-8")
    normalized = []
    in_string = escaped = False
    removed = []
    for index, char in enumerate(text):
        if in_string:
            normalized.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == ",":
            following = index + 1
            while following < len(text) and text[following].isspace():
                following += 1
            if following < len(text) and text[following] in "]}":
                removed.append(index)
                continue
        normalized.append(char)
    return json.loads("".join(normalized)), removed


def unihex_stats(open_resource):
    """Stream one supplied Unihex archive, returning payload statistics and scalar coverage."""
    entries = []
    scalars = set()
    records = 0
    max_record = 0
    expanded_total = 0
    packed_row_bytes = 0
    widths = collections.Counter()
    with open_resource() as raw, zipfile.ZipFile(raw) as archive:
        infos = archive.infolist()
        for info in infos:
            expanded_total += info.file_size
            if LIMITS["expanded_bytes"] < expanded_total:
                raise ValueError("measurement archive expansion exceeds bound")
            measured = 0
            entry_records = 0
            is_hex = info.filename.endswith(".hex") and not info.is_dir()
            with archive.open(info) as stream:
                while line := stream.readline(LIMITS["line_bytes"] + 1):
                    measured += len(line)
                    if LIMITS["line_bytes"] < len(line):
                        raise ValueError("measurement archive record exceeds line bound")
                    if not is_hex:
                        continue
                    max_record = max(max_record, len(line))
                    content = line.rstrip(b"\n")
                    scalar_text, rows = content.split(b":", 1)
                    scalar = int(scalar_text, 16)
                    if len(rows) not in (32, 64, 96, 128):
                        raise ValueError(f"unexpected native Unihex row size: {len(rows)}")
                    if not (0 <= scalar <= 0x10FFFF) or 0xD800 <= scalar <= 0xDFFF:
                        raise ValueError("unexpected non-scalar record")
                    records += 1
                    entry_records += 1
                    if LIMITS["records"] < records:
                        raise ValueError("measurement record count exceeds bound")
                    scalars.add(scalar)
                    widths[len(rows) // 4] += 1
                    packed_row_bytes += len(rows) // 2
            if measured != info.file_size:
                raise ValueError("archive expanded size mismatch")
            entries.append(dict(name=info.filename, compressedBytes=info.compress_size, expandedBytes=measured, records=entry_records, hex=is_hex))
    return dict(entryCount=len(entries), entries=entries, compressedEntryBytes=sum(e["compressedBytes"] for e in entries), expandedBytes=expanded_total, maxExpandedEntryBytes=max((e["expandedBytes"] for e in entries), default=0), maxEntryPathLength=max((len(e["name"]) for e in entries), default=0), maxRecordBytesIncludingLF=max_record, records=records, uniqueGlyphs=len(scalars), packedNaturalRowBytes=packed_row_bytes, strataLongRowBytes=len(scalars) * 16 * 8, sourceWidths=dict(sorted(widths.items()))), scalars


def measure(version):
    """Measure one catalogue version only after its supplied metadata confirms that identity."""
    folder = ROOT / version
    metadata_path = folder / "mojang_minecraft_info.json"
    metadata = read_json(metadata_path)
    if metadata.get("id") != version:
        raise ValueError(f"version metadata identity mismatch: expected {version}, found {metadata.get('id')!r}")
    index_path = ROOT / "assets/indexes" / f"{version}-{metadata['assetIndex']['id']}.json"
    index_evidence = file_hashes(index_path)
    if index_evidence["sha1"] != metadata["assetIndex"]["sha1"]:
        raise ValueError("asset index differs from official version metadata")
    objects = read_json(index_path)["objects"]
    jar_path = folder / "minecraft-client.jar"
    jar_evidence = file_hashes(jar_path)
    if jar_evidence["sha1"] != metadata["downloads"]["client"]["sha1"]:
        raise ValueError("cached client jar differs from official download hash")
    documents = []
    providers_by_path = collections.defaultdict(list)
    resources_needed = {}
    result = dict(version=version, metadata=file_hashes(metadata_path), client=jar_evidence, assetIndex=index_evidence)
    with zipfile.ZipFile(jar_path) as jar:
        infos = jar.infolist()
        jar_files = {info.filename: info for info in infos if not info.is_dir()}
        index_files = {"assets/" + path: entry for path, entry in objects.items()}
        result["sources"] = dict(clientJarEntries=len(infos), clientJarFiles=len(jar_files), clientJarAssetFiles=sum(p.startswith("assets/") for p in jar_files), clientJarMaxEntryPathLength=max(len(info.filename) for info in infos), clientJarMaxPathLength=max(map(len, jar_files)), clientJarMaxAssetPathLength=max(len(p) for p in jar_files if p.startswith("assets/")), clientJarUncompressedBytes=sum(info.file_size for info in infos), indexedAssetEntries=len(index_files), indexedObjectMaxPathLength=max(map(len, objects)), indexedAssetMaxPathLength=max(map(len, index_files)), indexedJsonBytes=index_evidence["bytes"])

        def open_selected(path):
            """Open the indexed override or client entry; the immediate caller closes the stream."""
            entry = index_files.get(path)
            if entry is not None:
                digest = entry["hash"]
                return (ROOT / "assets/objects" / digest[:2] / digest).open("rb")
            return jar.open(jar_files[path])

        def read_document(path, source, stream):
            """Verify and record one bounded document without taking ownership of its stream."""
            data = bounded_read(stream, LIMITS["json_bytes"])
            document, trailing_commas = font_document(data)
            if source == "assetIndex":
                entry = index_files[path]
                digest = entry["hash"]
                if len(data) != entry["size"] or hashlib.sha1(data).hexdigest() != digest:
                    raise ValueError("font document differs from official asset-index size/hash")
                locator = dict(path=str(ROOT / "assets/objects" / digest[:2] / digest), expectedSha1=digest)
            else:
                locator = dict(path=str(jar_path), entry=path)
            providers = document.get("providers", [])
            if not isinstance(providers, list):
                raise ValueError("font providers are not a list")
            types = collections.Counter()
            max_rows = max_cells = max_row_scalars = max_row_utf16 = 0
            for provider in providers:
                kind = provider["type"].removeprefix("minecraft:")
                types[kind] += 1
                providers_by_path[path].append(provider)
                if kind == "bitmap":
                    rows = provider["chars"]
                    max_rows = max(max_rows, len(rows))
                    max_cells = max(max_cells, sum(len(row) for row in rows))
                    max_row_scalars = max(max_row_scalars, max(map(len, rows), default=0))
                    max_row_utf16 = max(max_row_utf16, max((len(row.encode("utf-16-le")) // 2 for row in rows), default=0))
                    resources_needed[location(provider["file"], "textures/")] = kind
                elif kind == "ttf":
                    resources_needed[location(provider["file"], "font/")] = kind
                elif kind == "unihex":
                    resources_needed[location(provider["hex_file"])] = kind
                elif kind not in {"reference", "space"}:
                    raise ValueError(f"unhandled official provider type {kind}")
            documents.append(dict(path=path, source=source, sourceLocator=locator, bytes=len(data), sha1=hashlib.sha1(data).hexdigest(), sha256=hashlib.sha256(data).hexdigest(), trailingCommaOffsets=trailing_commas, providerCount=len(providers), providerTypes=dict(types), maxBitmapRows=max_rows, maxBitmapCells=max_cells, maxBitmapRowScalars=max_row_scalars, maxBitmapRowUtf16Units=max_row_utf16))

        for path in sorted(jar_files):
            if font_path(path):
                with jar.open(jar_files[path]) as stream:
                    read_document(path, "clientJar", stream)
        for path in sorted(index_files):
            if font_path(path):
                with open_selected(path) as stream:
                    read_document(path, "assetIndex", stream)

        resources = []
        all_unihex_scalars = set()
        for path, kind in sorted(resources_needed.items()):
            with open_selected(path) as stream:
                digest = hashes(stream)
            if LIMITS["asset_bytes"] < digest["bytes"]:
                raise ValueError("resource exceeds measurement byte bound")
            entry = index_files.get(path)
            if entry is not None and (entry["hash"] != digest["sha1"] or entry["size"] != digest["bytes"]):
                raise ValueError("referenced asset hash/size differs from official index")
            record = dict(path=path, providerType=kind, **digest)
            if entry is not None:
                record["source"] = dict(kind="assetIndex", path=str(ROOT / "assets/objects" / entry["hash"][:2] / entry["hash"]), expectedSha1=entry["hash"])
            else:
                record["source"] = dict(kind="clientJar", path=str(jar_path), entry=path, compressedBytes=jar_files[path].compress_size)
            if kind == "bitmap":
                with open_selected(path) as stream:
                    header = stream.read(33)
                if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
                    raise ValueError("invalid native font PNG header")
                width, height = struct.unpack(">II", header[16:24])
                record["png"] = dict(width=width, height=height, pixels=width * height, argbBytes=width * height * 4)
            elif kind == "unihex":
                record["unihex"], archive_scalars = unihex_stats(lambda path=path: open_selected(path))
                all_unihex_scalars.update(archive_scalars)
            resources.append(record)

        def expanded_provider_count(path, visiting=frozenset()):
            """Count ordered reference expansion without retaining a traversal or accepting cycles."""
            if path in visiting:
                raise ValueError("official font reference cycle")
            count = 0
            for provider in providers_by_path[path]:
                if provider["type"].removeprefix("minecraft:") == "reference":
                    count += expanded_provider_count(location(provider["id"], "font/", ".json"), visiting | {path})
                else:
                    count += 1
            return count

        types = collections.Counter()
        for document in documents:
            types.update(document["providerTypes"])
        expanded = {path: expanded_provider_count(path) for path in sorted(providers_by_path)}
        result["documents"] = documents
        result["resources"] = resources
        result["expandedProvidersByFont"] = expanded
        hexes = [r["unihex"] for r in resources if "unihex" in r]
        pngs = [r["png"] for r in resources if "png" in r]
        result["summary"] = dict(fontDocumentInstances=len(documents), uniqueFontIds=len({d["path"] for d in documents}), fontJsonBytes=sum(d["bytes"] for d in documents), maxFontJsonBytes=max(d["bytes"] for d in documents), providers=sum(types.values()), providerTypes=dict(types), maxProvidersPerDocument=max(d["providerCount"] for d in documents), maxExpandedProvidersPerFont=max(expanded.values()), maxBitmapRows=max(d["maxBitmapRows"] for d in documents), maxBitmapCells=max(d["maxBitmapCells"] for d in documents), maxBitmapRowScalars=max(d["maxBitmapRowScalars"] for d in documents), referencedAssetFiles=len(resources), referencedAssetBytes=sum(r["bytes"] for r in resources), maxReferencedAssetBytes=max(r["bytes"] for r in resources), trueTypeFiles=sum(r["providerType"] == "ttf" for r in resources), maxPngWidth=max(p["width"] for p in pngs), maxPngHeight=max(p["height"] for p in pngs), maxPngPixels=max(p["pixels"] for p in pngs), allPngArgbBytes=sum(p["argbBytes"] for p in pngs), unihexFiles=len(hexes), unihexZipBytes=sum(r["bytes"] for r in resources if "unihex" in r), unihexExpandedBytes=sum(h["expandedBytes"] for h in hexes), maxUnihexExpandedEntryBytes=max(h["maxExpandedEntryBytes"] for h in hexes), maxUnihexExpandedArchiveBytes=max(h["expandedBytes"] for h in hexes), unihexRecords=sum(h["records"] for h in hexes), uniqueGlyphsSummedPerArchive=sum(h["uniqueGlyphs"] for h in hexes), maxUniqueGlyphsPerArchive=max(h["uniqueGlyphs"] for h in hexes), maxUnihexRecordBytesIncludingLF=max(h["maxRecordBytesIncludingLF"] for h in hexes), unihexPackedNaturalRowBytes=sum(h["packedNaturalRowBytes"] for h in hexes), unihexStrataLongRowBytes=sum(h["strataLongRowBytes"] for h in hexes))
        result["summary"]["uniqueGlyphsAcrossArchives"] = len(all_unihex_scalars)
        result["summary"]["maxUnihexArchiveEntries"] = max(h["entryCount"] for h in hexes)
        result["summary"]["maxUnihexEntryPathLength"] = max(h["maxEntryPathLength"] for h in hexes)
        result["summary"]["documentsContainingTrailingCommas"] = sum(bool(d["trailingCommaOffsets"]) for d in documents)
    return result


def portable_result(result):
    """Project measured statistics into the receipt, excluding diagnostic paths and run details."""
    projected = dict(version=result["version"])
    for name in ("metadata", "client", "assetIndex"):
        evidence = result[name]
        locator = evidence["locator"]
        path = pathlib.PurePosixPath(locator)
        if not locator or path.is_absolute() or "\\" in locator or ":" in locator:
            raise ValueError("receipt input locators must be portable relative paths")
        if any(part in ("", ".", "..") for part in locator.split("/")):
            raise ValueError("receipt input locators cannot contain traversal or empty segments")
        projected[name] = {key: evidence[key] for key in ("locator", "bytes", "sha1", "sha256")}
    projected["sources"] = result["sources"]
    projected["summary"] = result["summary"]
    return projected


def main():
    """Regenerate or verify one deterministic receipt from every catalogued release."""
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--loom-cache', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--catalog', type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1] / 'gradle/libs.versions.toml')
    parser.add_argument('--verify', action='store_true', help='Compare with the receipt without writing it.')
    args = parser.parse_args()
    ROOT = args.loom_cache.resolve(strict=True)
    catalog = tomllib.loads(args.catalog.read_text(encoding='utf-8'))['versions']
    versions = sorted((value for key, value in catalog.items()
                       if key.startswith('minecraft') and key[len('minecraft'):].isdigit()),
                      key=lambda value: tuple(map(int, value.split('.'))))
    if not versions or len(versions) != len(set(versions)):
        raise ValueError('The Minecraft version catalog must be nonempty and unique.')
    results = []
    for version in versions:
        result = measure(version)
        results.append(result)
        print(json.dumps(dict(version=version, summary=result['summary'])), flush=True)
    maxima = {}
    for section in ('summary', 'sources'):
        fields = {key for result in results for key, value in result[section].items()
                  if isinstance(value, int)}
        for key in sorted(fields):
            maximum = max(result[section][key] for result in results)
            maxima[f'{section}.{key}'] = dict(value=maximum, versions=[
                result['version'] for result in results if result[section][key] == maximum])
    receipt = dict(
        schemaVersion=1,
        source='Caller-supplied official client archives and indexed objects; client/index SHA-1 and every selected indexed asset size/SHA-1 are verified before measuring.',
        inputLocatorRoot='All locators are relative to the caller-supplied Fabric Loom cache.',
        measurementSafetyBounds=LIMITS,
        limitations=[
            'Counts describe the default font resource stack, not arbitrary custom packs or total JVM/native heap usage.',
            'Each unique Unihex glyph stores sixteen Long row values (128 payload bytes); object and map overhead is excluded.',
            'Glyph records, including duplicates, and archive expansion include all examined entries; unique counts sum per archive.',
            'Vanilla supplies no TrueType providers; TTF ceilings are security policy verified by synthetic and redistributable-font adversarial tests, not vanilla measurements.',
            'Font document instances include both the client archive empty Unihex stub and its indexed replacement.',
            'The measurement parser removes trailing commas outside strings from ignored vanilla metadata; it does not modify input resources.',
            'PNG measurements report IHDR dimensions and four-byte pixel payloads; generic decompression and JSON-depth ceilings are tested independently.',
            'IndexedObjectMaxPathLength excludes assets/; IndexedAssetMaxPathLength includes that resource-key prefix.',
        ],
        results=[portable_result(result) for result in results],
        maxima=maxima,
    )
    encoded = (json.dumps(receipt, indent=2, ensure_ascii=True) + '\n').encode('utf-8')
    if args.verify:
        if args.output.read_bytes() != encoded:
            raise ValueError('The tracked vanilla font budget receipt differs from current inputs.')
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(encoded)
    print(json.dumps(dict(receipt=str(args.output), sha256=hashlib.sha256(encoded).hexdigest(),
                          versions=len(results), verified=args.verify)), flush=True)


if __name__ == '__main__':
    main()

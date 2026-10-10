"""Prepare the complete frozen Unihex bounds corpus; never run during measurement."""

import csv
import io
import json
import struct
import sys
import zipfile
import zlib
from pathlib import Path


def archive(entries):
    """Return stable stored ZIP bytes with explicit metadata and entry order."""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as bundle:
        for name, contents in entries:
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, contents)
    return output.getvalue()


def png():
    """Encode the independent opaque checkerboard bitmap control without a native decoder."""
    def chunk(name, payload):
        return struct.pack(">I", len(payload)) + name + payload + struct.pack(">I", zlib.crc32(name + payload))

    raw = b"".join(b"\x00" + b"".join(bytes((255, 255, 255, 255 if (x + y) % 2 == 0 else 0)) for x in range(8)) for y in range(8))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 8, 8, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def document(overrides):
    """Keep provider order, filters and the complete ordered override list in frozen original bytes."""
    return json.dumps({"providers": [{"type": "unihex", "hex_file": "strata_benchmark:font/glyphs.zip", "size_overrides": overrides}]}, separators=(",", ":")).encode()


def table(columns, rows):
    """Return a stable UTF-8 TSV with exactly one semantic row per line."""
    output = io.StringIO(newline="")
    writer = csv.writer(output, delimiter="\t", lineterminator="\n")
    writer.writerow(columns)
    writer.writerows(rows)
    return output.getvalue().encode()


def prepare(destination):
    """Freeze all 36 fixtures, 132 rows and 792 Pending cells; control bindings are reviewed separately."""
    destination.mkdir(parents=True, exist_ok=False)
    assets = []
    fixtures = []
    comparisons = []
    patterns = ("Empty", "AllInk", "LeftEdge", "RightEdge", "CenteredSparse", "DisjointExtrema")
    for width in (8, 16, 24, 32):
        for pattern in patterns:
            name = f"W{width}{pattern}"
            rows = []
            for y in range(16):
                value = {
                    "Empty": 0,
                    "AllInk": (1 << width) - 1,
                    "LeftEdge": 1 << (width - 1),
                    "RightEdge": 1,
                    "CenteredSparse": (1 << (width // 2)) if y % 3 == 0 else 0,
                    "DisjointExtrema": (1 << (width - 2)) if y == 0 else (2 if y == 15 else 0),
                }[pattern]
                rows.append(value)
            packed = "".join(f"{row:0{width // 4}X}" for row in rows)
            records = f"0041:{packed}\n0042:{packed}\n".encode("ascii")
            assets.extend(((f"{name}.zip", archive((("glyphs.hex", records),))), (f"{name}.json", document([]))))
            fixtures.append((name, width, ",".join(f"{row:X}" for row in rows), "0041,0042", f"{name}.zip", f"{name}.json"))
            comparisons.append((name, "NaturalBounds", "N/A", 0, 0, "one real runtime bounds invocation", "N/A"))
            for mode in ("Integer", "Fractional"):
                comparisons.append((name, "PublicUncachedGlyph", mode, 0, 0, "glyph A repeated; no primed raster", "N/A"))
                comparisons.append((name, "CompleteDirtyTextFrame", mode, 4096, 16777216, "prime A and B; publish alternate A/B; one complete frame", "320x40; headless density1,2,3 admission"))

    control_names = ("Evicted8Integer", "Evicted32Fractional", "WarmRasterHit", "AbsentSparseGlyph", "MatchingOverride", "NoMatchingOverride", "TrueType", "Bitmap", "SnapshotLoad", "EngineLifecycle", "CleanTextFrame", "SnapshotReplacement")
    control_widths = (8, 32, 32, 32, 32, 32, 0, 8, 32, 32, 32, 32)
    control_modes = ("Integer", "Fractional", "Fractional", "Fractional", "Fractional", "Fractional", "N/A", "N/A", "Fractional", "Fractional", "Fractional", "Fractional")
    transitions = ("alternate A/B through one-entry cache", "alternate A/B through one-entry cache", "prime A in default bounded cache", "request absent C", "first matching inclusive ordered range", "scan frozen nonwinning ordered ranges", "uncached A after real native preflight", "uncached A after real PNG preflight", "complete public load from prepared source", "open/use/close independent engine", "prime A/B and repeat unchanged frame", "load/profile/open/attach/frame/close alternating prepared source")
    for name, width, mode, transition in zip(control_names, control_widths, control_modes, transitions):
        fixture = "W8DisjointExtrema" if name == "Evicted8Integer" else "W32DisjointExtrema"
        original = {
            "TrueType": ("0041", "external:cc0-geometric-font", "truetype.json"),
            "Bitmap": ("0041", "control.png", "bitmap.json"),
            "MatchingOverride": ("0041,0042", fixture + ".zip", "matching.json"),
            "NoMatchingOverride": ("0041,0042", fixture + ".zip", "nonmatching.json"),
            "SnapshotReplacement": ("0041,0042", fixture + ".zip,W32CenteredSparse.zip", fixture + ".json,W32CenteredSparse.json"),
        }.get(name, ("0041,0042", fixture + ".zip", fixture + ".json"))
        fixtures.append((name, width, "control", *original))
        entries = 1 if name.startswith("Evicted") else 4096 if name in ("WarmRasterHit", "CleanTextFrame", "SnapshotReplacement") else 0
        comparisons.append((name, "Control", mode, entries, 16777216, transition, "320x40; density1,2,3 admission" if name in ("CleanTextFrame", "SnapshotReplacement") else "N/A"))

    assets.extend((
        ("matching.json", document([{"from": "Z", "to": "z", "left": 0, "right": 31}, {"from": "A", "to": "C", "left": -1, "right": 33}, {"from": "A", "to": "B", "left": 0, "right": 3}])),
        ("nonmatching.json", document([{"from": "Z", "to": "z", "left": 0, "right": 31}, {"from": "D", "to": "F", "left": 1, "right": 3}])),
        ("bitmap.json", b'{"providers":[{"type":"bitmap","file":"strata_benchmark:font/control.png","height":8,"ascent":7,"chars":["A"]}]}'),
        ("control.png", png()),
        ("truetype.json", b'{"providers":[{"type":"ttf","file":"strata_benchmark:control.ttf","size":11,"oversample":1}]}'),
    ))
    assert len(fixtures) == 36 and len(comparisons) == 132
    (destination / "assets.zip").write_bytes(archive(assets))
    (destination / "fixtures.tsv").write_bytes(table(("fixture", "width", "packedRows", "scalars", "archive", "document"), fixtures))
    indexed = [(f"R{index:03}", *row) for index, row in enumerate(comparisons, 1)]
    (destination / "comparisons.tsv").write_bytes(table(("row", "fixture", "operation", "advance", "cacheEntries", "cacheBytes", "reset", "frame"), indexed))
    cells = [(row[0], side, repetition, "Pending", "Pending", "Pending", "Pending", "Pending", "Pending") for row in indexed for side in ("Baseline", "Candidate") for repetition in range(3)]
    assert len(cells) == 792
    (destination / "measurements.tsv").write_bytes(table(("row", "side", "repetition", "CPU", "allocation", "naturalScans", "rasterMisses", "frameWork", "receipt"), cells))


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))

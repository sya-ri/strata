"""Regenerate original CC0 bitmap and Unihex fixtures using only the standard library.

The assets contain synthetic shapes, not glyphs copied from Minecraft or an installed font.
The companion TrueType fixture is shared from runtime/minecraft-fonts-lwjgl.
"""

import binascii
import json
from pathlib import Path
import struct
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1] / "src/gametest/resources/assets/strata_font_test"
FONT = ROOT / "font"
TEXTURES = ROOT / "textures/font"


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data))


def png():
    width, height = 32, 8
    alphas = (0, 1, 25, 26, 127, 128, 200, 255)
    scanlines = bytearray()
    for y in range(height):
        scanlines.append(0)
        for x in range(width):
            cell, column = divmod(x, 8)
            alpha = alphas[(column + y + cell) % 8]
            if column == 7 and cell % 2 == 0:
                alpha = 0
            scanlines.extend(((37 + cell * 61) % 256, 29 + y * 29, 247 - column * 23, alpha))
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(scanlines, 9)) + chunk(b"IEND", b"")


def definition(name, providers):
    (FONT / f"{name}.json").write_text(json.dumps({"providers": providers}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    FONT.mkdir(parents=True, exist_ok=True)
    TEXTURES.mkdir(parents=True, exist_ok=True)
    (TEXTURES / "colored.png").write_bytes(png())
    space = {"type": "space", "advances": {" ": 3.25}}
    bitmap = {"type": "bitmap", "file": "strata_font_test:font/colored.png", "height": 8, "ascent": 7, "chars": ["A日한🙂"]}
    definition("bitmap", [bitmap, space])
    definition("bitmap_fractional", [dict(bitmap, height=7, ascent=6), space])
    rows = []
    for index, codepoint in enumerate((0x41, 0x65E5, 0xD55C, 0x1F642, 0x45)):
        bits = 8 if index in (0, 4) else 16
        values = [0 if index == 4 else ((1 << (bits - 2)) | (1 << (y % (bits - 2) + 1))) for y in range(16)]
        rows.append(f"{codepoint:06X}:" + "".join(f"{row:0{bits // 4}X}" for row in values))
    with zipfile.ZipFile(FONT / "shapes.zip", "w", compression=zipfile.ZIP_STORED) as archive:
        info = zipfile.ZipInfo("shapes.hex", date_time=(1980, 1, 1, 0, 0, 0))
        archive.writestr(info, "\n".join(rows) + "\n")
    unihex = {"type": "unihex", "hex_file": "strata_font_test:font/shapes.zip", "size_overrides": [{"from": "한", "to": chr(0xD55D), "left": 2, "right": 11}]}
    definition("unihex", [unihex, space])
    ttf = {"type": "ttf", "file": "strata_font_test:strata-test.ttf", "size": 11.0, "oversample": 1.0, "shift": [0.0, 0.0], "skip": ""}
    definition("ttf", [ttf])
    definition("ttf_fractional", [dict(ttf, size=12.75, oversample=2.5, shift=[0.35, -0.2])])
    definition("reference", [space, {"type": "reference", "id": "strata_font_test:ttf_fractional"}, {"type": "reference", "id": "strata_font_test:unihex"}])


if __name__ == "__main__":
    main()

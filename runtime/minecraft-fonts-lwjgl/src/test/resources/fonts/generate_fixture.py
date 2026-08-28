"""Generate the original, unhinted Strata CPU-font test fixture using only Python's standard library.

The geometric glyph outlines and this generator are dedicated to the public domain under CC0-1.0.
No operating-system or Minecraft font data is used.
"""

from pathlib import Path
from struct import pack, unpack


def checksum(data):
    padded = data + bytes((-len(data)) % 4)
    return sum(unpack(">" + "I" * (len(padded) // 4), padded)) & 0xFFFFFFFF


def glyph(points):
    if not points:
        return b""
    xs, ys = zip(*points)
    result = pack(">hhhhhHH", 1, min(xs), min(ys), max(xs), max(ys), len(points) - 1, 0)
    result += bytes([1] * len(points))
    for coordinates in (xs, ys):
        previous = 0
        for coordinate in coordinates:
            result += pack(">h", coordinate - previous)
            previous = coordinate
    return result + bytes((-len(result)) % 4)


outlines = [
    [(0, 0), (600, 0), (600, 700), (0, 700)],
    [],
    [(50, 0), (650, 0), (320, 760)],
    [(10, -30), (730, -30), (730, 790), (10, 790)],
    [(-40, 50), (740, 50), (350, 780)],
    [(60, -170), (680, 0), (610, 710), (100, 730)],
]
glyf = b""
offsets = [0]
for outline in outlines:
    glyf += glyph(outline)
    offsets.append(len(glyf))

codepoints = [(0x20, 1), (0x41, 2), (0x65E5, 3), (0xD55C, 4), (0x1F642, 5)]
cmap12 = pack(">HHIII", 12, 0, 16 + len(codepoints) * 12, 0, len(codepoints))
cmap12 += b"".join(pack(">III", codepoint, codepoint, index) for codepoint, index in codepoints)
name_values = {1: "Strata Test", 2: "Regular", 3: "StrataTest-Regular", 4: "Strata Test Regular", 5: "Version 1.0", 6: "StrataTest-Regular"}
name_records = b""
name_strings = b""
for name_id, value in name_values.items():
    encoded = value.encode("utf-16-be")
    name_records += pack(">HHHHHH", 3, 1, 0x409, name_id, len(encoded), len(name_strings))
    name_strings += encoded

tables = {
    b"cmap": pack(">HHHHI", 0, 1, 3, 10, 12) + cmap12,
    b"glyf": glyf,
    b"head": pack(">IIIIHHQQhhhhHHhhh", 0x00010000, 0x00010000, 0, 0x5F0F3CF5, 0, 1000, 0, 0, -40, -170, 740, 790, 0, 8, 2, 1, 0),
    b"hhea": pack(">IhhhHhhhhhhhhhhhH", 0x00010000, 800, -200, 0, 800, -40, 0, 780, 1, 0, 0, 0, 0, 0, 0, 0, len(outlines)),
    b"hmtx": b"".join(pack(">Hh", width, bearing) for width, bearing in [(700, 0), (300, 0), (700, 50), (800, 10), (800, -40), (800, 60)]),
    b"loca": b"".join(pack(">I", offset) for offset in offsets),
    b"maxp": pack(">I" + "H" * 14, 0x00010000, len(outlines), 4, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0),
    b"name": pack(">HHH", 0, len(name_values), 6 + 12 * len(name_values)) + name_records + name_strings,
    b"post": pack(">IIhhIIIII", 0x00030000, 0, -75, 50, 0, 0, 0, 0, 0),
}
count = len(tables)
power = 1 << (count.bit_length() - 1)
header = pack(">IHHHH", 0x00010000, count, power * 16, power.bit_length() - 1, count * 16 - power * 16)
directory = b""
body = b""
head_offset = 0
for tag, data in sorted(tables.items()):
    offset = 12 + 16 * count + len(body)
    directory += pack(">4sIII", tag, checksum(data), offset, len(data))
    if tag == b"head":
        head_offset = offset
    body += data + bytes((-len(data)) % 4)
font = bytearray(header + directory + body)
font[head_offset + 8:head_offset + 12] = pack(">I", (0xB1B0AFBA - checksum(font)) & 0xFFFFFFFF)
Path(__file__).with_name("strata-test.ttf").write_bytes(font)

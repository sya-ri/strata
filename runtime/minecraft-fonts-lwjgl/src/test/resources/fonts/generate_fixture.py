"""Generate original, unhinted Strata native and browser font fixtures using Python's standard library.

The geometric glyph outlines, added bitmap pixels, and this generator are dedicated to the public domain under CC0-1.0.
No operating-system or Minecraft font data is used.

At one pixel per em, the embedded fixture gives A and B matching outlines but different grayscale bitmaps.
This distinguishes outline measurement from subsequent embedded-strike selection.
The EBLC/EBDT version 2, index format 1, and image format 1 contracts are specified at:
https://learn.microsoft.com/en-us/typography/opentype/spec/eblc
https://learn.microsoft.com/en-us/typography/opentype/spec/ebdt
"""

from pathlib import Path
from struct import pack, unpack, unpack_from


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


def matching_outlines(tables):
    count = unpack_from(">H", tables[b"maxp"], 4)[0]
    offsets = unpack(">" + "I" * (count + 1), tables[b"loca"])
    glyphs = [tables[b"glyf"][offsets[index]:offsets[index + 1]] for index in range(count)]
    glyphs[3] = glyphs[2]
    offsets = [0]
    for glyph in glyphs:
        offsets.append(offsets[-1] + len(glyph))
    tables[b"glyf"] = b"".join(glyphs)
    tables[b"loca"] = pack(">" + "I" * len(offsets), *offsets)
    metrics = bytearray(tables[b"hmtx"])
    metrics[12:16] = metrics[8:12]
    tables[b"hmtx"] = bytes(metrics)
    groups = [(0x20, 1), (0x41, 2), (0x42, 3)]
    cmap = pack(">HHIII", 12, 0, 16 + 12 * len(groups), 0, len(groups))
    cmap += b"".join(pack(">III", scalar, scalar, glyph) for scalar, glyph in groups)
    tables[b"cmap"] = pack(">HHHHI", 0, 1, 3, 10, 12) + cmap


def embedded_tables():
    unit = pack(">BBbbB", 1, 1, 0, 1, 1) + bytes([0x80])
    unit += bytes((-len(unit)) % 4)
    larger = pack(">BBbbB", 2, 2, 0, 2, 2) + bytes([0x20, 0x60, 0xA0, 0xE0])
    larger += bytes((-len(larger)) % 4)
    data = pack(">HH", 2, 0) + unit + larger
    index = pack(">HHI", 2, 3, 8)
    index += pack(">HHI", 1, 1, 4) + pack(">III", 0, len(unit), len(unit) + len(larger))
    horizontal = pack(">bbBbbbbbbbbb", 2, 0, 2, 1, 0, 0, 0, 0, 2, 0, 0, 0)
    strike = pack(">IIII", 56, len(index), 1, 0) + horizontal + bytes(12)
    strike += pack(">HHBBBb", 2, 3, 1, 1, 8, 1)
    if len(strike) != 48:
        raise ValueError("Embedded strike record must contain 48 bytes")
    locations = pack(">HHI", 2, 0, 1) + strike + index
    return {b"EBDT": data, b"EBLC": locations}


def sfnt(tables):
    head = bytearray(tables[b"head"])
    head[8:12] = bytes(4)
    tables[b"head"] = bytes(head)
    count = len(tables)
    power = 1 << (count.bit_length() - 1)
    header = pack(">IHHHH", 0x00010000, count, power * 16, power.bit_length() - 1, count * 16 - power * 16)
    directory = b""
    body = b""
    head_offset = 0
    for tag, data in sorted(tables.items()):
        offset = 12 + count * 16 + len(body)
        directory += pack(">4sIII", tag, checksum(data), offset, len(data))
        if tag == b"head":
            head_offset = offset
        body += data + bytes((-len(data)) % 4)
    result = bytearray(header + directory + body)
    result[head_offset + 8:head_offset + 12] = pack(">I", (0xB1B0AFBA - checksum(result)) & 0xFFFFFFFF)
    if checksum(result) != 0xB1B0AFBA:
        raise ValueError("Embedded font checksum is invalid")
    return result


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

font_metrics = pack(">HhHHH", 0, 700, 400, 5, 0)
font_metrics += pack(">hhhhhhhhhhh", 650, 600, 0, 75, 650, 600, 0, 350, 50, 250, 0) + bytes(26) + b"STR0"
font_metrics += pack(">HHHhhhHH", 0x40, 0x20, 0xFFFF, 800, -200, 0, 800, 200)

tables = {
    b"OS/2": font_metrics,
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
directory = Path(__file__).parent
(directory / "strata-test.ttf").write_bytes(sfnt(tables))
matching_outlines(tables)
tables.update(embedded_tables())
(directory / "strata-embedded-test.ttf").write_bytes(sfnt(tables))

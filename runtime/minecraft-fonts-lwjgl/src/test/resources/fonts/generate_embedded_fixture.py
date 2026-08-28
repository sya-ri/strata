"""Generate an original grayscale embedded-strike fixture using Python's standard library.

The source outlines, added bitmap pixels, and this generator are dedicated to the public domain under CC0-1.0.
The original strata-test.ttf is read without modification.

OpenType EBLC/EBDT version 2, index format 1, and image format 1 are specified at:
https://learn.microsoft.com/en-us/typography/opentype/spec/eblc
https://learn.microsoft.com/en-us/typography/opentype/spec/ebdt

At one pixel per em, A and B share the same one-pixel outline.
Their embedded grayscale bitmaps deliberately differ: A is 1x1 and B is 2x2.
This distinguishes outline measurement from the subsequent embedded-strike selection.
"""

from pathlib import Path
from struct import pack, unpack, unpack_from


def checksum(data):
    padded = data + bytes((-len(data)) % 4)
    return sum(unpack(">" + "I" * (len(padded) // 4), padded)) & 0xFFFFFFFF


def read_tables(source):
    count = unpack_from(">H", source, 4)[0]
    result = {}
    for index in range(count):
        tag, _, offset, length = unpack_from(">4sIII", source, 12 + index * 16)
        result[tag] = source[offset:offset + length]
    return result


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
    assert len(strike) == 48
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
    assert checksum(result) == 0xB1B0AFBA
    return result


def main():
    directory = Path(__file__).parent
    tables = read_tables((directory / "strata-test.ttf").read_bytes())
    matching_outlines(tables)
    tables.update(embedded_tables())
    (directory / "strata-embedded-test.ttf").write_bytes(sfnt(tables))


if __name__ == "__main__":
    main()

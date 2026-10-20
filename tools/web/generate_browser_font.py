"""Add browser-required metadata to Strata's original, deliberately small test font."""

from pathlib import Path
import struct
import sys


def checksum(data):
    padded = data + bytes((-len(data)) % 4)
    return sum(struct.unpack(">" + "I" * (len(padded) // 4), padded)) & 0xFFFFFFFF


def generate():
    repository = Path(__file__).resolve().parents[2]
    original = repository / "runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf"
    source = original.read_bytes()
    count = struct.unpack_from(">H", source, 4)[0]
    tables = {}
    for index in range(count):
        tag, _, offset, length = struct.unpack_from(">4sIII", source, 12 + index * 16)
        tables[tag] = source[offset:offset + length]
    head = bytearray(tables[b"head"])
    head[8:12] = bytes(4)
    tables[b"head"] = bytes(head)
    metadata = bytearray(78)
    struct.pack_into(">HhHHH", metadata, 0, 0, 700, 400, 5, 0)
    struct.pack_into(">hhhhhhhhhhh", metadata, 10, 650, 600, 0, 75, 650, 600, 0, 350, 50, 250, 0)
    metadata[58:62] = b"STR0"
    struct.pack_into(">HHHhhhHH", metadata, 62, 0x40, 0x20, 0xFFFF, 800, -200, 0, 800, 200)
    tables[b"OS/2"] = bytes(metadata)
    count = len(tables)
    power = 1 << (count.bit_length() - 1)
    header = struct.pack(">IHHHH", 0x10000, count, power * 16, power.bit_length() - 1, count * 16 - power * 16)
    directory = bytearray()
    body = bytearray()
    head_offset = 0
    for tag, data in sorted(tables.items()):
        offset = 12 + count * 16 + len(body)
        directory.extend(struct.pack(">4sIII", tag, checksum(data), offset, len(data)))
        if tag == b"head":
            head_offset = offset
        body.extend(data)
        body.extend(bytes((-len(data)) % 4))
    result = bytearray(header + directory + body)
    struct.pack_into(">I", result, head_offset + 8, (0xB1B0AFBA - checksum(result)) & 0xFFFFFFFF)
    destination = repository / "integration/web/src/jsMain/resources/strata-browser-test.ttf"
    if "--check" in sys.argv:
        assert destination.read_bytes() == result, "Browser font fixture is stale"
    else:
        destination.write_bytes(result)


if __name__ == "__main__":
    generate()

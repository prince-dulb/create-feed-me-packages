"""Generate the minimal original GameTest fixture; no game assets are copied."""
import gzip
import pathlib
import struct

def string(value):
    encoded = value.encode("utf8")
    return struct.pack(">H", len(encoded)) + encoded

for fixture, size in {"empty": (5, 5, 5), "assembly": (22, 10, 12)}.items():
    data = b"\x0a\x00\x00"
    data += b"\x03" + string("DataVersion") + struct.pack(">i", 3955)
    data += b"\x09" + string("size") + b"\x03" + struct.pack(">iiii", 3, *size)
    data += b"\x09" + string("palette") + b"\x0a" + struct.pack(">i", 1)
    data += b"\x08" + string("Name") + string("minecraft:air") + b"\x00"
    for name in ("blocks", "entities"):
        data += b"\x09" + string(name) + b"\x0a" + struct.pack(">i", 0)
    data += b"\x00"
    target = pathlib.Path(__file__).resolve().parents[1] / f"src/gametest/resources/data/create_feed_me_packages/structure/{fixture}.nbt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(gzip.compress(data, mtime=0))
    print(target)

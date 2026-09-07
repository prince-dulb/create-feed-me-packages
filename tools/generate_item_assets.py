"""Generate original 16px pendant sprites; no third-party image files are copied."""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/create_feed_me_packages"
PATTERN = [
    "     sSSSs", "    sS   Ss", "   sS     Ss", "   Ss     sS",
    "    Ss   sS", "     Ss sS", "      sSs", "    bBBBBBb",
    "    BGgggGB", "   bBgGgGgBb", "   BggGGGggB", "   BGgGgGgGB",
    "    BGgggGB", "    bBBBBBb", "      bBb", "",
]


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def write_sprite(name, dark, light):
    palette = {" ": (0, 0, 0, 0), "s": (65, 71, 67, 255), "S": (194, 198, 174, 255),
               "b": (80, 58, 33, 255), "B": (207, 160, 71, 255), "g": (*dark, 255), "G": (*light, 255)}
    assert len(PATTERN) == 16 and all(len(row) <= 16 for row in PATTERN)
    data = b"".join(b"\0" + bytes(channel for char in row.ljust(16) for channel in palette[char]) for row in PATTERN)
    image = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
    image += png_chunk(b"IDAT", zlib.compress(data)) + png_chunk(b"IEND", b"")
    texture = ROOT / "textures/item" / f"{name}.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    texture.write_bytes(image)
    model = ROOT / "models/item" / f"{name}.json"
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": f"create_feed_me_packages:item/{name}"}}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    write_sprite("supply_chain_pendant", (51, 74, 58), (125, 149, 94))
    write_sprite("personal_supply_chain_pendant", (37, 70, 82), (112, 199, 189))
    print("Generated two original pendant sprites and item models.")

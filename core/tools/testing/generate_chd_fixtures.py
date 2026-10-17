"""Generate synthetic PS3 discs for Android tests using an official MAME chdman.

Usage: python tools/testing/generate_chd_fixtures.py build/mame-tools/chdman.exe
Requires cryptography for the encrypted-disc fixture. No game data is included.
"""
from pathlib import Path
import base64
import struct
import subprocess
import sys
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "app/src/androidTest/assets/chd"
WORK = ROOT / "build/chd-fixtures"
KEY = bytes(range(16))


def both(value, size):
    return value.to_bytes(size, "little") + value.to_bytes(size, "big")


def record(name, sector, size, directory=False):
    name = name if isinstance(name, bytes) else name.encode("ascii")
    length = 33 + len(name) + (len(name) % 2 == 0)
    return (bytes([length, 0]) + both(sector, 4) + both(size, 4) +
            bytes([126, 1, 1, 0, 0, 0, 0, 2 if directory else 0, 0, 0]) +
            both(1, 2) + bytes([len(name)]) + name + bytes(length - 33 - len(name)))


def sfo(version_only=False):
    fields = {"TITLE_ID": "BLES00001", "TITLE": "CHD Test Disc", "VERSION" if version_only else "APP_VER": "01.23", "CATEGORY": "DG", "INSTALL_DIR_SAVEDATA": "BLES00001-SAVE", "TEST_PADDING": "P" * 16384}
    keys, values, entries = bytearray(), bytearray(), bytearray()
    for key, value in fields.items():
        data = value.encode() + b"\0"
        aligned = (len(data) + 3) & ~3
        entries.extend(struct.pack("<HHIII", len(keys), 0x204, len(data), aligned, len(values)))
        keys.extend(key.encode() + b"\0")
        values.extend(data + bytes(aligned - len(data)))
    key_offset = 20 + len(entries)
    value_offset = (key_offset + len(keys) + 3) & ~3
    return (struct.pack("<IIIII", 0x46535000, 0x101, key_offset, value_offset, len(fields)) +
            entries + keys + bytes(value_offset - key_offset - len(keys)) + values)


def icon_bytes():
    icon = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB1sAAAAASUVORK5CYII=")
    payload = b"Fixture\0" + b"A" * 65536
    kind = b"tEXt"
    chunk = struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    return icon[:-12] + chunk + icon[-12:]


def disc(version_only=False):
    image = bytearray(512 * 2048)
    struct.pack_into(">IIIII", image, 0, 2, 0, 0, 22, 111)
    struct.pack_into(">I", image, 20, 511)
    image[16 * 2048:16 * 2048 + 7] = b"\1CD001\1"
    image[16 * 2048 + 128:16 * 2048 + 132] = both(2048, 2)
    root = record(b"\0", 20, 2048, True)
    image[16 * 2048 + 156:16 * 2048 + 156 + len(root)] = root
    image[17 * 2048:17 * 2048 + 7] = b"\xffCD001\1"
    param = sfo(version_only)
    icon = icon_bytes()
    directories = {
        20: record(b"\0", 20, 2048, True) + record(b"\1", 20, 2048, True) + record("PS3_GAME", 21, 2048, True),
        21: record(b"\0", 21, 2048, True) + record(b"\1", 20, 2048, True) + record("USRDIR", 22, 2048, True) + record("PARAM.SFO;1", 23, len(param)) + record("ICON0.PNG;1", 32, len(icon)),
        22: record(b"\0", 22, 2048, True) + record(b"\1", 21, 2048, True) + record("EBOOT.BIN;1", 100, 2048),
    }
    for sector, data in {**directories, 23: param, 32: icon, 100: b"SCE\0" + bytes(2044)}.items():
        image[sector * 2048:sector * 2048 + len(data)] = data
    return image


def main(chdman):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)
    image = disc()
    source = WORK / "test.iso"
    source.write_bytes(image)
    (OUTPUT / "expected-icon.png").write_bytes(icon_bytes())
    for codec in ["lzma", "zlib", "zstd"]:
        subprocess.run([chdman, "createdvd", "-i", str(source), "-o", str(OUTPUT / f"dvd-{codec}.chd"), "-c", codec, "-f"], check=True)
    cue = WORK / "test.cue"
    cue.write_text('FILE "test.iso" BINARY\n  TRACK 01 MODE1/2048\n    INDEX 01 00:00:00\n')
    subprocess.run([chdman, "createcd", "-i", str(cue), "-o", str(OUTPUT / "cd.chd"), "-f"], check=True)
    fallback = WORK / "version-fallback.iso"
    fallback.write_bytes(disc(version_only=True))
    subprocess.run([chdman, "createdvd", "-i", str(fallback), "-o", str(OUTPUT / "version-fallback.chd"), "-c", "lzma", "-f"], check=True)
    for sector in range(23, 111):
        encryptor = Cipher(algorithms.AES(KEY), modes.CBC(sector.to_bytes(16, "big"))).encryptor()
        start = sector * 2048
        image[start:start + 2048] = encryptor.update(image[start:start + 2048]) + encryptor.finalize()
    encrypted = WORK / "encrypted.iso"
    encrypted.write_bytes(image)
    subprocess.run([chdman, "createdvd", "-i", str(encrypted), "-o", str(OUTPUT / "encrypted.chd"), "-c", "lzma", "-f"], check=True)


if __name__ == "__main__":
    main(str(Path(sys.argv[1]).resolve()))

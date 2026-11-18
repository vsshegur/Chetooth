"""Check that a signed Ruta APK is an unsigned build plus its v2/v3 signature and nothing else.

Usage: python3 tests/compare_apks.py SIGNED.apk UNSIGNED.apk

F-Droid's reproducible-build check does the same: it builds the unsigned APK from the tagged source,
copies the signature over from the release APK on GitHub, and publishes that APK (signed with the
Ruta key) only if the two match. build.sh runs this on its own signed and unsigned outputs; to check
another machine's build (CI, F-Droid), compare its `bash build.sh --unsigned` APK with the release.
"""
import struct
import sys
from zipfile import ZipFile

EOCD_MAGIC = b"PK\x05\x06"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def entries_end(data, cd_offset, cd_entries):
    """End of the last local entry's data (from the central directory)."""
    end, pos = 0, cd_offset
    for _ in range(cd_entries):
        compressed, = struct.unpack_from("<I", data, pos + 20)
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", data, pos + 28)
        local, = struct.unpack_from("<I", data, pos + 42)
        local_name, local_extra = struct.unpack_from("<HH", data, local + 26)
        end = max(end, local + 30 + local_name + local_extra + compressed)
        pos += 46 + name_len + extra_len + comment_len
    return end


def strip_signature(data):
    """The APK without its APK Signing Block (and the zero padding apksigner puts before it to start
    the block on a 4 KB boundary), with the central directory offset moved back. apksigcopier, which
    F-Droid uses, treats that padding the same way."""
    eocd = data.rfind(EOCD_MAGIC)
    if eocd < 0:
        sys.exit("compare_apks: no ZIP end-of-central-directory record")
    cd_entries, = struct.unpack_from("<H", data, eocd + 10)
    cd_offset, = struct.unpack_from("<I", data, eocd + 16)
    if data[cd_offset - 16:cd_offset] != SIG_BLOCK_MAGIC:
        return data  # no signing block
    block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    start = cd_offset - (block_size + 8)
    if struct.unpack_from("<Q", data, start)[0] != block_size:
        sys.exit("compare_apks: malformed APK Signing Block")
    padded_from = entries_end(data, cd_offset, cd_entries)
    if padded_from <= start and not data[padded_from:start].strip(b"\0"):
        start = padded_from
    eocd -= cd_offset - start
    out = bytearray(data[:start] + data[cd_offset:])
    struct.pack_into("<I", out, eocd + 16, start)
    return bytes(out)


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: compare_apks.py SIGNED.apk UNSIGNED.apk")
    signed_path, unsigned_path = sys.argv[1:]
    with open(signed_path, "rb") as f:
        signed = f.read()
    with open(unsigned_path, "rb") as f:
        unsigned = f.read()
    if SIG_BLOCK_MAGIC in unsigned:
        sys.exit(f"compare_apks: {unsigned_path} is signed")
    if strip_signature(signed) == unsigned:
        print(f"MATCH: {signed_path} = {unsigned_path} + signature (reproducible)")
        return
    # Say which entries differ, to find the non-deterministic step.
    with ZipFile(signed_path) as a, ZipFile(unsigned_path) as b:
        ia = {i.filename: i for i in a.infolist()}
        ib = {i.filename: i for i in b.infolist()}
        diffs = sorted(set(ia) ^ set(ib))
        for name in sorted(set(ia) & set(ib)):
            x, y = ia[name], ib[name]
            if (x.CRC, x.compress_type, x.compress_size, x.date_time, x.external_attr, x.extra) != \
                    (y.CRC, y.compress_type, y.compress_size, y.date_time, y.external_attr, y.extra) \
                    or [i.filename for i in a.infolist()].index(name) != [i.filename for i in b.infolist()].index(name):
                diffs.append(name)
    print(f"DIFFER: {signed_path} minus its signature is not {unsigned_path}", file=sys.stderr)
    for name in diffs[:20]:
        print(f"  - {name}", file=sys.stderr)
    if not diffs:
        print("  (entries match; ZIP layout differs: alignment, order or headers)", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()

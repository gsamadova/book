#!/usr/bin/env python3
"""
Convert the SJP.pl text dictionary to StarDict for the Reader app.

Input:  SJP_<date>.txt.zip (or the .txt inside it) from https://cc-sjp.xn--zabaaganionemiejsce-8fd.pl/
        Each line: headword <TAB> inflected forms (comma separated) <TAB> HTML definition
Output: <out>/SJP.ifo, SJP.idx, SJP.dict.dz, SJP.syn.gz and <out>/SJP-stardict.zip
        (the zip is what you add in the app: Menu -> Dictionaries -> Add...)

The inflected forms go into the .syn file, so long-pressing "kotami" finds "kot".

Usage:  python3 tools/sjp_to_stardict.py SJP_202610111615.txt.zip out/
License of the data: CC BY-NC 4.0 (SJP.pl / Wiktionary / Wikipedia contributors).
"""
import collections
import gzip
import io
import os
import re
import struct
import sys
import zipfile
import zlib

CHUNK = 58315  # dictzip's default chunk length


def stardict_key(word: str):
    """StarDict sort order: g_ascii_strcasecmp, then strcmp to break ties."""
    b = word.encode("utf-8")
    return bytes(c + 32 if 65 <= c <= 90 else c for c in b), b


def read_lines(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            name = next(n for n in z.namelist() if n.endswith(".txt"))
            with z.open(name) as f:
                yield from io.TextIOWrapper(f, encoding="utf-8")
    else:
        with open(path, encoding="utf-8") as f:
            yield from f


def write_dictzip(data: bytes, path: str):
    """gzip with an "RA" extra field and independently decompressible chunks (dictzip format)."""
    comp = zlib.compressobj(9, zlib.DEFLATED, -15)
    body = bytearray()
    sizes = []
    for start in range(0, len(data), CHUNK):
        last = start + CHUNK >= len(data)
        part = comp.compress(data[start:start + CHUNK])
        part += comp.flush() if last else comp.flush(zlib.Z_FULL_FLUSH)
        sizes.append(len(part))
        body += part
    if len(sizes) > 32000 or max(sizes) > 0xFFFF:
        raise SystemExit("Data too large for a single dictzip file")
    ra = struct.pack("<HHH", 1, CHUNK, len(sizes)) + b"".join(struct.pack("<H", s) for s in sizes)
    extra = b"RA" + struct.pack("<H", len(ra)) + ra
    header = b"\x1f\x8b\x08\x04" + b"\0\0\0\0" + b"\x02\x03" + struct.pack("<H", len(extra)) + extra
    with open(path, "wb") as f:
        f.write(header)
        f.write(body)
        f.write(struct.pack("<II", zlib.crc32(data) & 0xFFFFFFFF, len(data) & 0xFFFFFFFF))


H1 = re.compile(r"^\s*<h1>.*?</h1>", re.S)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    entries = []  # (headword, definition html, forms)
    dotted = collections.Counter()  # how many entries use each "xxx." token
    for n, line in enumerate(read_lines(src), 1):
        parts = line.rstrip("\r\n").split("\t")
        if len(parts) != 3 or not parts[0].strip():
            print(f"skipping line {n}: unexpected format", file=sys.stderr)
            continue
        word, forms, definition = parts[0].strip(), parts[1], parts[2]
        tokens = [t.strip() for t in forms.split(",") if t.strip()]
        for t in set(tokens):
            if t.endswith("."):
                dotted[t] += 1
        # The popup shows the headword as its title; drop the duplicate <h1>.
        entries.append((word, H1.sub("", definition, count=1).strip(), tokens))

    # Tokens like "rzad." / "neutr." (usage labels, not words) appear in many entries;
    # a trailing dot on a rare token is a typo in an otherwise valid form ("bambergach.").
    labels = {t for t, c in dotted.items() if c >= 3}

    entries.sort(key=lambda e: stardict_key(e[0]))

    data = bytearray()
    idx = bytearray()
    synonyms = {}  # (form, entry index) pairs, deduplicated
    for i, (word, definition, tokens) in enumerate(entries):
        body = definition.encode("utf-8")
        idx += word.encode("utf-8") + b"\0" + struct.pack(">II", len(data), len(body))
        data += body
        for t in tokens:
            if t in labels or " " in t:
                continue
            t = t.rstrip(".")
            if t and t.lower() != word.lower():
                synonyms[(t, i)] = None

    syn = bytearray()
    syn_items = sorted(synonyms, key=lambda s: (stardict_key(s[0]), s[1]))
    for form, i in syn_items:
        syn += form.encode("utf-8") + b"\0" + struct.pack(">I", i)

    base = os.path.join(out, "SJP")
    with open(base + ".idx", "wb") as f:
        f.write(idx)
    write_dictzip(bytes(data), base + ".dict.dz")
    with gzip.open(base + ".syn.gz", "wb", compresslevel=6) as f:
        f.write(syn)
    date = re.search(r"(\d{8})", os.path.basename(src))
    with open(base + ".ifo", "w", encoding="utf-8") as f:
        f.write("StarDict's dict ifo file\n")
        f.write("version=3.0.0\n")
        f.write("bookname=SJP – Słownik języka polskiego\n")
        f.write(f"wordcount={len(entries)}\n")
        f.write(f"synwordcount={len(syn_items)}\n")
        f.write(f"idxfilesize={len(idx)}\n")
        f.write("sametypesequence=h\n")
        if date:
            d = date.group(1)
            f.write(f"date={d[:4]}.{d[4:6]}.{d[6:]}\n")
        f.write("website=https://cc-sjp.xn--zabaaganionemiejsce-8fd.pl/\n")
        f.write("description=SJP.pl, Wiktionary and Wikipedia contributors. License: CC BY-NC 4.0.\n")

    package = os.path.join(out, "SJP-stardict.zip")
    with zipfile.ZipFile(package, "w", zipfile.ZIP_STORED) as z:  # contents are already compressed
        for ext in (".ifo", ".idx", ".dict.dz", ".syn.gz"):
            z.write(base + ext, "SJP" + ext)

    print(f"{len(entries)} entries, {len(syn_items)} inflected forms")
    print(f"-> {package} ({os.path.getsize(package) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

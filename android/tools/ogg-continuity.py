# SPDX-License-Identifier: GPL-3.0-or-later
# Usage: python3 ogg-continuity.py <recording.opus>
#
# Ogg Opus continuity check: one stream, CRC per page, contiguous page sequence,
# granules monotonic, and every page's granule step equal to the audio its packets hold.
# ffprobe reports the duration from the last granule only, so it cannot see a dropped page; this can.
# Exits 1 when any check fails, 0 when the file is one whole stream. A torn trailing page (a killed
# recorder) is reported and the pages before it are checked.
import struct
import sys


def crc_table():
    t = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
        t.append(r & 0xFFFFFFFF)
    return t


T = crc_table()


def crc(b):
    c = 0
    for x in b:
        c = ((c << 8) ^ T[((c >> 24) ^ x) & 0xFF]) & 0xFFFFFFFF
    return c


def opus_samples(p):
    """48 kHz samples in one Opus packet (RFC 6716 section 3.1)."""
    toc = p[0]
    cfg = toc >> 3
    if cfg < 12:
        fs = [480, 960, 1920, 2880][cfg % 4]
    elif cfg < 16:
        fs = [480, 960][cfg % 2]
    else:
        fs = [120, 240, 480, 960][cfg % 4]
    c = toc & 3
    n = 1 if c == 0 else 2 if c in (1, 2) else p[1] & 0x3F
    return fs * n


d = open(sys.argv[1], "rb").read()
pos = 0
pages = []  # (sequence, granule, flags, packets completed on this page)
serials = set()
badcrc = seqgaps = 0
pkt = b""
lastseq = None
torn = False
while pos < len(d):
    if d[pos:pos + 4] != b"OggS":
        print(f"no capture pattern at byte {pos}")
        sys.exit(1)
    if pos + 27 > len(d) or pos + 27 + d[pos + 26] + sum(d[pos + 27:pos + 27 + d[pos + 26]]) > len(d):
        print(f"torn trailing page at byte {pos} of {len(d)}")
        torn = True
        break
    flags = d[pos + 5]
    gran, serial, seq, cs = struct.unpack("<qIII", d[pos + 6:pos + 26])
    ns = d[pos + 26]
    lac = d[pos + 27:pos + 27 + ns]
    hl = 27 + ns
    bl = sum(lac)
    page = bytearray(d[pos:pos + hl + bl])
    page[22:26] = b"\0\0\0\0"
    if crc(page) != cs:
        badcrc += 1
    if lastseq is not None and seq != lastseq + 1:
        seqgaps += 1
    lastseq = seq
    serials.add(serial)
    body = d[pos + hl:pos + hl + bl]
    o = 0
    done = []
    for seg in lac:
        pkt += body[o:o + seg]
        o += seg
        if seg < 255:
            done.append(pkt)
            pkt = b""
    pages.append((seq, gran, flags, done))
    pos += hl + bl

if not pages or not pages[0][3] or pages[0][3][0][:8] != b"OpusHead":
    print("no OpusHead on the first page")
    sys.exit(1)
preskip = struct.unpack("<H", pages[0][3][0][10:12])[0]

# Audio starts on the page after the one that completes OpusTags, the second packet; OpusTags may span pages.
count = 0
audio_from = None
for i, (_, _, _, pk) in enumerate(pages):
    count += len(pk)
    if count >= 2:
        if pk[1 - (count - len(pk))][:8] != b"OpusTags":
            print("the second packet is not OpusTags")
            sys.exit(1)
        audio_from = i + 1
        break
if audio_from is None:
    print("no OpusTags")
    sys.exit(1)
audio = pages[audio_from:]

steps_bad = []
nonmono = 0
total = 0
prev = 0  # the granule of the last page that completed a packet
end_trim = 0
for idx, (seq, gran, flags, pk) in enumerate(audio):
    s = sum(opus_samples(p) for p in pk)
    total += s
    if gran == -1:
        # No packet ends on this page, so it carries no granule (RFC 7845 section 3).
        if pk:
            steps_bad.append((seq, gran, prev + s, "granule -1 on a page that completes a packet"))
        continue
    if gran < prev:
        nonmono += 1
    exp = prev + s  # the granule counts decoded samples, the pre-skip included
    last = idx == len(audio) - 1
    if gran == exp:
        pass
    elif last and flags & 4 and prev < gran < exp:
        end_trim = exp - gran  # the end of the stream may trim the last packet's samples
    else:
        steps_bad.append((seq, gran, exp, ""))
    prev = gran

lastg = audio[-1][1] if audio else 0
eos = bool(pages[-1][2] & 4)
print(f"pages {len(pages)} (audio {len(audio)}), streams {len(serials)}, bad CRC {badcrc}, sequence gaps {seqgaps}")
print(f"preskip {preskip}, last granule {lastg}, EOS on last page {eos}, BOS on first {bool(pages[0][2] & 2)}, end trim {end_trim} samples")
print(f"granules non-monotonic {nonmono}; pages whose granule step != their packets' audio: {len(steps_bad)} {steps_bad[:5]}")
print(f"audio in packets {total} samples at 48 kHz = {total / 48000:.4f} s; last granule - preskip = {(lastg - preskip) / 48000:.4f} s; "
      f"ffprobe-style last granule / 48000 = {lastg / 48000:.4f} s")
ok = len(serials) == 1 and badcrc == 0 and seqgaps == 0 and nonmono == 0 and not steps_bad
print("continuous" if ok else "NOT continuous")
sys.exit(0 if ok else 1)

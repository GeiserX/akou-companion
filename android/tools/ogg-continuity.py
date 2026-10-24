# SPDX-License-Identifier: GPL-3.0-or-later
# Usage: python3 ogg-continuity.py <recording.opus>
#
# Ogg Opus continuity check: one stream, CRC per page, contiguous page sequence,
# granules monotonic, and every page's granule step equal to the audio its packets hold.
# ffprobe reports the duration from the last granule only, so it cannot see a dropped page; this can.
import struct, sys
def crc_table():
    t=[]
    for i in range(256):
        r=i<<24
        for _ in range(8):
            r=((r<<1)^0x04C11DB7) if r&0x80000000 else (r<<1)
        t.append(r&0xffffffff)
    return t
T=crc_table()
def crc(b):
    c=0
    for x in b: c=((c<<8)^T[((c>>24)^x)&0xff])&0xffffffff
    return c
def opus_samples(p):  # 48 kHz samples in one Opus packet (RFC 6716 3.1)
    toc=p[0]; cfg=toc>>3
    if cfg<12: fs=[480,960,1920,2880][cfg%4]
    elif cfg<16: fs=[480,960][cfg%2]
    else: fs=[120,240,480,960][cfg%4]
    c=toc&3
    n=1 if c==0 else 2 if c in (1,2) else p[1]&0x3f
    return fs*n
d=open(sys.argv[1],'rb').read()
pos=0; pages=[]; serials=set(); badcrc=0; seqgaps=0; pkt=b''; lastseq=None
while pos<len(d):
    assert d[pos:pos+4]==b'OggS', f'no capture at {pos}'
    if pos+27>len(d) or pos+27+d[pos+26]+sum(d[pos+27:pos+27+d[pos+26]])>len(d):
        print(f'torn trailing page at byte {pos} of {len(d)}'); break
    flags=d[pos+5]; gran,serial,seq,cs=struct.unpack('<qIII',d[pos+6:pos+26]); ns=d[pos+26]
    lac=d[pos+27:pos+27+ns]; hl=27+ns; bl=sum(lac); page=bytearray(d[pos:pos+hl+bl])
    page[22:26]=b'\0\0\0\0'
    if crc(page)!=cs: badcrc+=1
    if lastseq is not None and seq!=lastseq+1: seqgaps+=1
    lastseq=seq; serials.add(serial)
    body=d[pos+hl:pos+hl+bl]; o=0; done=[]
    for l in lac:
        pkt+=body[o:o+l]; o+=l
        if l<255: done.append(pkt); pkt=b''
    pages.append((seq,gran,flags,done)); pos+=hl+bl
head=pages[0][3][0]; assert head[:8]==b'OpusHead'
preskip=struct.unpack('<H',head[10:12])[0]
audio=pages[2:]
steps_ok=0; steps_bad=[]; prev=0; nonmono=0; total=0
for seq,gran,flags,pk in audio:
    s=sum(opus_samples(p) for p in pk); total+=s
    if gran<prev: nonmono+=1
    exp=prev+s  # the granule counts decoded samples, the pre-skip included
    if gran==exp: steps_ok+=1
    else: steps_bad.append((seq,gran,exp,'last page, end trim' if (seq,gran,flags,pk)==audio[-1] and gran<exp else ''))
    prev=gran
last=pages[-1]
print(f"pages {len(pages)} (audio {len(audio)}), streams {len(serials)}, bad CRC {badcrc}, sequence gaps {seqgaps}")
print(f"preskip {preskip}, last granule {last[1]}, EOS on last page {bool(last[2]&4)}, BOS on first {bool(pages[0][2]&2)}")
print(f"granules non-monotonic {nonmono}; pages whose granule step != their packets' audio: {len(steps_bad)} {steps_bad[:5]}")
print(f"audio in packets {total} samples at 48 kHz = {total/48000:.4f} s; last granule - preskip = {(last[1]-preskip)/48000:.4f} s; ffprobe-style last granule / 48000 = {last[1]/48000:.4f} s")

#!/usr/bin/env python3
"""Map every public method in the UTE SDK's WriteCommandToBLE.java to the
first opcode byte(s) it writes. Crude brace-matching parser over jadx output."""
import re, sys
src = open(sys.argv[1], encoding="utf-8", errors="replace").read()
# split into methods: find 'public ... name(' at 4-space indent and take until matching close brace
meth_re = re.compile(r'^    public [^\n=]*?\b([a-zA-Z0-9_]+)\(([^)]*)\)[^{;\n]*\{', re.M)
lit_re  = re.compile(r'new byte\[\]\s*\{([^}]*)\}')
idx_re  = re.compile(r'bArr\d*\[0\]\s*=\s*([^;]+);')
def tobyte(tok):
    tok = tok.strip()
    m = re.match(r'\(byte\)\s*\(?\s*(-?\d+)', tok) or re.match(r'^(-?\d+)$', tok)
    if m: return int(m.group(1)) & 0xff
    named = {'TransportLayerPacket.SYNC_WORD':0xAA,'Framer.STDIN_REQUEST_FRAME_PREFIX':0x8A,
             'Framer.STDOUT_FRAME_PREFIX':0x01,'Framer.STDERR_FRAME_PREFIX':0x02}
    for k,v in named.items():
        if tok.startswith(k): return v
    return None
out = []
for m in meth_re.finditer(src):
    name, start = m.group(1), m.end()
    depth, i = 1, start
    while depth and i < len(src):
        c = src[i]
        if c == '{': depth += 1
        elif c == '}': depth -= 1
        i += 1
    body = src[start:i]
    ops = set()
    for lm in lit_re.finditer(body):
        toks = [t for t in lm.group(1).split(',') if t.strip()]
        if not toks: continue
        b0 = tobyte(toks[0]); b1 = tobyte(toks[1]) if len(toks) > 1 else None
        if b0 is not None: ops.add(f"{b0:02X}" + (f" {b1:02X}" if b1 is not None else ""))
    for im in idx_re.finditer(body):
        b0 = tobyte(im.group(1))
        if b0 is not None: ops.add(f"{b0:02X} ..")
    if ops: out.append((sorted(ops), name))
for ops, name in sorted(out):
    print(f"{', '.join(ops):28s} {name}")

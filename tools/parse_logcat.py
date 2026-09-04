#!/usr/bin/env python3
"""Turn a Ryze Fit logcat dump into a clean BLE packet timeline.

The UTE SDK logs:  LogSync: APK--->BLE4 = <HEX> ,true   (phone -> watch, 33F1)
                   LogSync: BLE--->APK4 = <HEX>         (watch -> phone, 33F2)
                   ...BLE5 / APK5 = data channel (34F1 / 34F2)
plus LogConnect lines about the feature bitmap and password.

Usage: parse_logcat.py <logcat.txt> [--raw] [--no-collapse]
"""
import re, sys, collections, signal
signal.signal(signal.SIGPIPE, signal.SIG_DFL)   # quiet exit when piped into head
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from decode_btsnoop import OPNAMES

LINE = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(?:\d+\s+\d+\s+[IDWE]\s+(Log\w+|RyzeBridge)\s*:|[IDWE]/(Log\w+|RyzeBridge)\(\s*\d+\):)\s*(.*)$')
PKT  = re.compile(r'(APK--->BLE|BLE--->APK)([45B78]?) = ([0-9A-F]+)')
# RyzeBridge (our headless app) logs the same traffic as "TX 33F1 <hex>" / "RX 34F2 <hex>"
BRIDGE = re.compile(r'RyzeBridge(?:\(\s*\d+\))?\s*:\s*(TX|RX) (33F1|33F2|34F1|34F2) ([0-9a-fA-F]+)')

def describe(hexs):
    b = bytes.fromhex(hexs)
    op = b[0]; name = OPNAMES.get(op, "?")
    extra = ""
    try:
        if op == 0xA1 and len(b) > 1 and b[1] != 0x01: extra = "version=" + b[1:].decode("ascii", "replace")
        elif op == 0xA1 and len(b) > 2: extra = "dsp=" + b[2:].decode("ascii", "replace")
        elif op == 0xA2 and len(b) > 1: extra = f"battery={b[1]}%" + (" charging" if len(b) > 2 and b[2] == 1 else "")
        elif op == 0xA3 and len(b) >= 8: extra = f"time={b[1]<<8|b[2]:04d}-{b[3]:02d}-{b[4]:02d} {b[5]:02d}:{b[6]:02d}:{b[7]:02d}"
        elif op in (0xB1, 0xB2) and len(b) == 18:
            extra = f"{b[1]<<8|b[2]}-{b[3]:02d}-{b[4]:02d} {b[5]:02d}h total={b[6]<<8|b[7]} run={b[11]<<8|b[12]} walk={b[16]<<8|b[17]}"
        elif op == 0xF7 and len(b) == 18:
            extra = f"{b[1]<<8|b[2]}-{b[3]:02d}-{b[4]:02d} {b[5]:02d}h hr={list(b[6:])}"
        elif op == 0xF7 and len(b) == 9 and b[1] == 3:
            extra = f"auto-sample {b[6]:02d}:{b[7]:02d} hr={b[8]}"
        elif op == 0xF7 and len(b) == 11 and b[1] == 4:
            extra = f"summary {b[6]:02d}:{b[7]:02d} max={b[8]} min={b[9]} avg={b[10]}"
        elif op == 0x34 and len(b) == 4 and b[1] == 0xFA:
            extra = "fetch end"
        elif op == 0x34 and len(b) == 4:
            extra = "spo2 failed" if b[2] else f"spo2={b[3]}%"
        elif op == 0xE5 and len(b) == 4:
            extra = f"hr={b[3]}" if b[3] else "hr warming up"
        elif op == 0xFD and len(b) == 14 and b[1] == 1:
            extra = f"workout hr={b[2]}"
        elif op == 0xFD and len(b) == 13:
            extra = f"state={b[1]:02X} type={b[2]} t={b[4]:02d}:{b[5]:02d}:{b[6]:02d} cal={b[7]<<8|b[8]} dist={b[9]}.{b[10]:02d}km pace={b[11]}:{b[12]:02d}"
        elif op == 0xD5: extra = {1:"ok/query",2:"captcha/need-pw",3:"timeout",0xFF:"fail"}.get(b[1], "") if len(b) > 1 else ""
        elif op == 0xC5 and len(b) > 3 and b[1] != 0xFD:
            try: extra = "utf16=" + b[(4 if b[1]==0 else 2):].decode("utf-16-be", "replace")
            except Exception: pass
    except Exception:
        pass
    return name, extra

def main():
    path = sys.argv[1]; raw = "--raw" in sys.argv; collapse = "--no-collapse" not in sys.argv
    last = None; repeat = 0
    def flush():
        nonlocal repeat
        if repeat: print(f"      … ×{repeat} more identical"); repeat = 0
    for line in open(path, encoding="utf-8", errors="replace"):
        m = LINE.match(line.rstrip("\n"))
        if not m: continue
        ts, tag_a, tag_b, msg = m.groups()
        tag = tag_a or tag_b
        p = PKT.search(msg)
        b = BRIDGE.search(line) if not p else None
        if p or b:
            if p:
                d, ch, hexs = p.groups()
                arrow = "-->" if d.startswith("APK") else "<--"
                chan = {"4":"33F1/2","5":"34F1/2","B":"FFF6","7":"FRK","8":"WXPAY"}.get(ch, ch or "?")
            else:
                d, chan, hexs = b.groups()
                arrow = "-->" if d == "TX" else "<--"
                hexs = hexs.upper()
            name, extra = describe(hexs)
            key = (arrow, chan, hexs[:2] if collapse and hexs[:2] in ("FD",) else hexs)
            if collapse and key == last:
                repeat += 1; continue
            flush(); last = key
            print(f"{ts} {arrow} {chan:6s} {hexs:<40s} {name:18s} {extra}")
        elif raw or tag in ("LogConnect",) and re.search(r"functionList|sendPassword|password|Passw|读取支持|onServicesDiscovered|connect  where|onConnectionStateChange", msg):
            flush(); last = None
            print(f"{ts} ## {tag}: {msg[:200]}")
    flush()

if __name__ == "__main__":
    main()

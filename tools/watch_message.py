#!/usr/bin/env python3
"""Vibrate the watch and show a text on it through RyzeBridge on the attached phone.
usage: ANDROID_SERIAL=<phone> tools/watch_message.py "text" [--no-vibrate]
Builds the C5 chunk script (each chunk waits for its ack) and runs it via tools/bridge.py raw."""
import os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ryzewave.protocol import enc_notification

text = sys.argv[1] if len(sys.argv) > 1 else "Hello from the laptop"
vibrate = "--no-vibrate" not in sys.argv
steps = ["connect"]
if vibrate:
    steps += ["write ab00000001020701", "wait 1500"]
for pk in enc_notification(text):
    idx = pk[1]
    steps += [f"write {pk.hex()}", f"until c5{idx:02x} 3000"]
steps += ["status"]
script = ";".join(steps)
here = os.path.dirname(os.path.abspath(__file__))
r = subprocess.run([sys.executable, os.path.join(here, "bridge.py"), "raw", script, "60"], capture_output=True, text=True)
lines = [l for l in r.stdout.splitlines() if ("c5" in l.lower() and ("-->" in l or "<--" in l)) or "STATUS" in l or "UNTIL" in l]
acks = sum(1 for l in lines if "<--" in l and "c5" in l.lower())
print("\n".join(lines[-6:]))
print(f"sent {len(enc_notification(text))} chunks, {acks} acks; text={text!r}")
sys.exit(0 if acks >= len(enc_notification(text)) else 1)

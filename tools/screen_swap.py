#!/usr/bin/env python3
"""Show and change the watch's screen list (the swipeable "cards" / "Add widget" set), opcode F9.

The watch reports which screens its firmware supports and which are switched on; you can switch
any *supported* screen on or off. You cannot add a screen the firmware does not have.

Read-only listing (safe):
    tools/screen_swap.py                       # over laptop BLE
    ANDROID_SERIAL=<phone> tools/screen_swap.py --bridge   # via RyzeBridge on the phone

Swap one on/off (reversible; it is exactly what the vendor app's toggle does):
    tools/screen_swap.py --enable blood_oxygen
    tools/screen_swap.py --disable stopwatch --bridge

A name (from ryzewave.protocol.SCREEN_NAMES) or a numeric index both work.
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ryzewave import protocol as P


def resolve_index(token: str) -> int:
    if token.isdigit():
        return int(token)
    for i, name in enumerate(P.SCREEN_NAMES):
        if name.lower() == token.lower():
            return i
    sys.exit(f"unknown screen {token!r}; known: {', '.join(n for n in P.SCREEN_NAMES if not n.startswith('unused'))}")


def show(screens: list[P.Screen], changed: int | None = None):
    on = [s for s in screens if s.enabled]
    off = [s for s in screens if not s.enabled]
    print(f"\n  watch supports {len(screens)} screens: {len(on)} on, {len(off)} off")
    for s in screens:
        mark = "  <- changed" if s.index == changed else ""
        print(f"    [{s.index:2d}] {s.name:<20} {'ON ' if s.enabled else 'off'}{mark}")
    if off:
        print("\n  addable now (firmware-supported but currently off):")
        print("    " + ", ".join(f"{s.name}({s.index})" for s in off))
    else:
        print("\n  every supported screen is already on; anything new would need firmware changes.")


# ---- transport A: laptop BLE via the RyzeWave client -----------------------
async def via_ble(args):
    from ryzewave.client import RyzeWave
    async with RyzeWave() as w:
        before = await w.screens()
        if args.enable is None and args.disable is None:
            show(before)
            return
        idx = resolve_index(args.enable or args.disable)
        want_on = args.enable is not None
        print(f"before:"); show(before)
        after = await w.set_screen(idx, want_on)
        print(f"\nsent F9 {'01' if want_on else '02'} for [{idx}] {P.screen_name(idx)}")
        print("after:"); show(after, changed=idx)


# ---- transport B: the phone, through RyzeBridge ----------------------------
def bridge_run(script: str) -> list[str]:
    here = os.path.dirname(os.path.abspath(__file__))
    r = subprocess.run([sys.executable, os.path.join(here, "bridge.py"), "raw", script, "60"],
                       capture_output=True, text=True)
    return r.stdout.splitlines()


def bridge_reply(lines: list[str], prefix: str = "f9") -> bytes | None:
    for ln in reversed(lines):
        if "<--" in ln:
            m = re.search(r"<--\s*([0-9a-fA-F ]+)", ln)
            if m:
                hexs = m.group(1).replace(" ", "")
                if hexs.lower().startswith(prefix):
                    return bytes.fromhex(hexs)
    return None


def via_bridge(args):
    q = P.enc_interface_query().hex()
    if args.enable is None and args.disable is None:
        lines = bridge_run(f"connect;write {q};until f9aa 5000;status")
        data = bridge_reply(lines)
        if not data:
            sys.exit("no F9 reply from the watch (see the bridge log above)")
        show(P.dec_interface(data))
        return
    idx = resolve_index(args.enable or args.disable)
    want_on = args.enable is not None
    cmd = (P.enc_interface_show(idx) if want_on else P.enc_interface_hide(idx)).hex()
    lines = bridge_run(f"connect;write {q};until f9aa 5000;write {cmd};wait 800;write {q};until f9aa 5000;status")
    replies = [bridge_reply(lines[:i + 1]) for i in range(len(lines))]
    replies = [r for r in replies if r]
    if len(replies) < 2:
        sys.exit("did not get both before/after F9 replies (see the bridge log above)")
    print("before:"); show(P.dec_interface(replies[0]))
    print(f"\nsent F9 {'01' if want_on else '02'} for [{idx}] {P.screen_name(idx)}")
    print("after:"); show(P.dec_interface(replies[-1]), changed=idx)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--enable", metavar="SCREEN", help="switch a screen on (name or index)")
    ap.add_argument("--disable", metavar="SCREEN", help="switch a screen off (name or index)")
    ap.add_argument("--bridge", action="store_true", help="drive the watch through the phone (RyzeBridge) instead of laptop BLE")
    args = ap.parse_args()
    if args.enable and args.disable:
        ap.error("choose --enable or --disable, not both")
    if args.bridge:
        via_bridge(args)
    else:
        import asyncio
        asyncio.run(via_ble(args))


if __name__ == "__main__":
    main()

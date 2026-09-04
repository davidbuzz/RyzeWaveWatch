#!/usr/bin/env python3
"""First-contact probe for the Ryze Wave (GloryFit / UTE protocol).

Scans for the watch, connects, enumerates GATT, subscribes to the two notify
characteristics and sends the harmless read-only commands A1 (version) and
A2 (battery). Everything received is hex-dumped.

Usage: .venv/bin/python tools/probe.py [MAC]
"""
import asyncio, sys, datetime
from bleak import BleakScanner, BleakClient

KNOWN_MAC = "78:02:B7:37:91:E5"
SVC_CMD  = "000055ff-0000-1000-8000-00805f9b34fb"
CH_CMD_W = "000033f1-0000-1000-8000-00805f9b34fb"
CH_CMD_N = "000033f2-0000-1000-8000-00805f9b34fb"
SVC_DATA = "000056ff-0000-1000-8000-00805f9b34fb"
CH_DAT_W = "000034f1-0000-1000-8000-00805f9b34fb"
CH_DAT_N = "000034f2-0000-1000-8000-00805f9b34fb"

def ts(): return datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]

async def main():
    mac = sys.argv[1] if len(sys.argv) > 1 else KNOWN_MAC
    print(f"[{ts()}] scanning for {mac} (or any 'Ryze Wave') ...")
    found = None
    def cb(d, adv):
        nonlocal found
        if d.address.upper() == mac.upper() or (adv.local_name or "").startswith("Ryze Wave"):
            found = d
    async with BleakScanner(cb):
        for _ in range(30):
            await asyncio.sleep(0.5)
            if found: break
    if not found:
        print("watch not advertising. Turn OFF Bluetooth on the phone (or force-stop Ryze Fit) and retry.")
        return
    print(f"[{ts()}] found {found.address} {found.name!r}; connecting ...")
    async with BleakClient(found, timeout=20) as c:
        print(f"[{ts()}] connected, mtu={c.mtu_size}")
        for s in c.services:
            print(f"  service {s.uuid}  {s.description}")
            for ch in s.characteristics:
                print(f"     char {ch.uuid} h=0x{ch.handle:04x} props={','.join(ch.properties)}")
        def on_cmd(_, data: bytearray): print(f"[{ts()}] 33F2 <- {data.hex()}")
        def on_dat(_, data: bytearray): print(f"[{ts()}] 34F2 <- {data.hex()}")
        await c.start_notify(CH_CMD_N, on_cmd)
        await c.start_notify(CH_DAT_N, on_dat)
        for cmd in (bytes([0xA1]), bytes([0xA2])):
            print(f"[{ts()}] 33F1 -> {cmd.hex()}")
            await c.write_gatt_char(CH_CMD_W, cmd, response=False)
            await asyncio.sleep(1.5)
        print(f"[{ts()}] listening 5s for anything spontaneous ...")
        await asyncio.sleep(5)
asyncio.run(main())

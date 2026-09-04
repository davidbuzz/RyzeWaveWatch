#!/usr/bin/env python3
"""BLE link-stability test: connect, send the app's opening commands, stream HR, and sample the link RSSI
every 2 s via HCI Read RSSI (needs sudo for hcitool). Designed to run in the background:

    nohup .venv/bin/python tools/linktest.py 60 > captures/linktest_<ts>.txt 2>&1 &

Prints one line per event; ends with a verdict line "RESULT: ...".
"""
import asyncio, datetime as dt, logging, os, re, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from ryzewave import protocol as P
from ryzewave.client import RyzeWave

MAC = os.environ.get("RYZEWAVE_ADDR", "78:02:B7:37:91:E5")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname).1s %(message)s")


def ts() -> str:
    return dt.datetime.now().strftime("%H:%M:%S.%f")[:-3]


def le_handle() -> int | None:
    """Find the LE ACL handle for the watch from `hcitool con`."""
    try:
        out = subprocess.run(["hcitool", "con"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if MAC.upper() in line.upper():
            m = re.search(r"handle (\d+)", line)
            if m:
                return int(m.group(1))
    return None


def read_rssi(handle: int) -> int | None:
    """HCI Read RSSI (OGF 0x05, OCF 0x0005). Reply: status, handle(2), rssi(int8)."""
    try:
        out = subprocess.run(["sudo", "-n", "hcitool", "cmd", "0x05", "0x0005", f"0x{handle & 0xff:02x}", f"0x{handle >> 8:02x}"],
                             capture_output=True, text=True, timeout=5).stdout
        m = re.search(r"^\s*([0-9A-F]{2}) ([0-9A-F]{2}) ([0-9A-F]{2}) ([0-9A-F]{2})\s*$", out.strip().splitlines()[-1].strip(), re.I)
        if m and m.group(1) == "00":
            v = int(m.group(4), 16)
            return v - 256 if v > 127 else v
    except Exception:
        pass
    return None


async def main():
    secs = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    w = RyzeWave(address=MAC)
    hr_samples: list[int] = []
    events: list[str] = []

    def on_event(ch, d):
        name = P.OPNAME.get(d[0], "")
        if d and d[0] == P.CMD_RT_HR and len(d) >= 4:
            hr_samples.append(d[3])
        events.append(f"{ts()} [{ch}] <- {d.hex()} {name}")
        print(events[-1], flush=True)
    w.on_event = on_event

    t_start = dt.datetime.now()
    await w.connect()
    t_conn = dt.datetime.now()
    print(f"{ts()} connected after {(t_conn - t_start).total_seconds():.1f}s; features {w.features}", flush=True)
    print(f"{ts()} time ack {(await w.set_time()).hex()}", flush=True)
    print(f"{ts()} version {await w.version()}", flush=True)
    print(f"{ts()} battery {await w.battery()}", flush=True)
    await w.write(P.enc_rt_hr(True))
    print(f"{ts()} -> E5 11 realtime HR on", flush=True)

    handle = le_handle()
    print(f"{ts()} LE handle {handle}", flush=True)
    rssis: list[int] = []
    dropped = None
    for i in range(secs):
        await asyncio.sleep(1)
        if not w._client.is_connected:
            dropped = (dt.datetime.now() - t_conn).total_seconds()
            break
        if i % 2 == 0 and handle is not None:
            r = read_rssi(handle)
            if r is not None:
                rssis.append(r)
                print(f"{ts()} rssi {r} dBm  (hr samples so far: {len(hr_samples)})", flush=True)

    if dropped is None:
        try:
            await w.write(P.enc_rt_hr(False))
            await asyncio.sleep(1)
            await w.disconnect()
        except Exception as e:                       # noqa: BLE001
            print(f"{ts()} cleanup: {e}", flush=True)
    summary = (f"held {secs}s OK" if dropped is None else f"DROPPED after {dropped:.1f}s")
    r_txt = f"rssi min/avg/max {min(rssis)}/{sum(rssis)//len(rssis)}/{max(rssis)} dBm over {len(rssis)} reads" if rssis else "no rssi reads"
    nz = [h for h in hr_samples if h]
    print(f"RESULT: {summary}; {r_txt}; HR packets {len(hr_samples)} (non-zero {len(nz)}: {nz[:20]})", flush=True)


if __name__ == "__main__":
    asyncio.run(main())

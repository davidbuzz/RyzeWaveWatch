"""ryzewave command line.  .venv/bin/python -m ryzewave <cmd>

  scan                      list nearby BLE devices
  info                      connect, print features, version, battery
  pair CODE                 enter the 4-digit code shown on the watch
  time                      set the watch clock
  sync                      fetch steps, HR, SpO2, sleep history and print them
  hr [SECONDS]              live heart rate
  spo2                      run one SpO2 spot measurement
  workout [SECONDS] [TYPE]  start a workout, stream HR, push elapsed time, stop
  raw HEX [FIRST]           send raw bytes on 33F1 and print replies for 5 s
  find                      vibrate the watch
  bt3 [on|off]              query / switch the watch's classic Bluetooth (calls & audio)
"""
import asyncio, datetime as dt, logging, sys

from bleak import BleakScanner

from . import protocol as P
from .client import RyzeWave, NeedsPairingCode


def _hexdump_events(ch, d):
    print(f"   [{ch}] <- {d.hex()}  {P.OPNAME.get(d[0], '')}")


async def _open(argv):
    addr = None
    if "--addr" in argv:
        addr = argv[argv.index("--addr") + 1]
    w = RyzeWave(address=addr)
    w.on_event = _hexdump_events
    try:
        await w.connect()
    except NeedsPairingCode:
        print("The watch is showing a pairing code. Run:  python -m ryzewave pair <CODE>")
        await w.disconnect()
        sys.exit(2)
    return w


async def main(argv):
    logging.basicConfig(level=logging.DEBUG if "-v" in argv else logging.INFO,
                        format="%(asctime)s %(levelname).1s %(message)s")
    argv = [a for a in argv if a != "-v"]
    cmd = argv[0] if argv else "info"

    if cmd == "scan":
        for d, adv in (await BleakScanner.discover(timeout=8, return_adv=True)).values():
            print(f"{d.address}  rssi={adv.rssi:4}  {adv.local_name or d.name or ''}")
        return

    if cmd == "pair":
        w = RyzeWave(address=argv[2] if len(argv) > 2 else None)
        w.on_event = _hexdump_events
        await w.connect(authenticate=False)
        try:
            await w.pair(argv[1])
            print("paired OK; version:", await w.version())
        finally:
            await w.disconnect()
        return

    w = await _open(argv)
    try:
        if cmd == "info":
            print("features:", w.features)
            print("version :", await w.version())
            pct, chg = await w.battery()
            print(f"battery : {pct}%{' (charging)' if chg else ''}")
        elif cmd == "time":
            print("ack:", (await w.set_time()).hex())
        elif cmd == "bt3":
            if len(argv) > 1 and argv[1] in ("on", "off"):
                print("ack:", (await w.bt3_enable(argv[1] == "on")).hex())
            print("bt3 status:", (await w.bt3_query()).hex())
        elif cmd == "find":
            await w.find_watch()
        elif cmd == "sync":
            print("-- steps")
            for r in await w.fetch_steps():
                print(f"  {r.when:%Y-%m-%d %H:00}  total={r.total:5d}  run={r.run_steps}  walk={r.walk_steps}")
            print("-- heart rate")
            for t, hr in await w.fetch_heart_rate():
                print(f"  {t:%Y-%m-%d %H:%M}  {hr}")
            print("-- spo2")
            for t, v in await w.fetch_spo2():
                print(f"  {t:%Y-%m-%d %H:%M}  {v}")
            print("-- sleep")
            for s in await w.fetch_sleep():
                print(f"  {s.when:%Y-%m-%d %H:%M}  stage={s.stage}  {s.minutes} min")
        elif cmd == "hr":
            secs = float(argv[1]) if len(argv) > 1 else 30
            await w.realtime_hr(secs, lambda hr: print(f"  {dt.datetime.now():%H:%M:%S}  hr={hr}"))
        elif cmd == "spo2":
            print("spo2 =", await w.spo2_test())
        elif cmd == "workout":
            secs = int(argv[1]) if len(argv) > 1 else 60
            typ = int(argv[2]) if len(argv) > 2 else 1
            print("start:", (await w.sport_start(typ)).hex())
            t0 = dt.datetime.now()
            prev = w.on_event

            def ev(ch, d):
                if d and d[0] == P.CMD_SPORT and len(d) > 2 and d[1] == P.SPORT_RT_DATA:
                    print(f"  {dt.datetime.now():%H:%M:%S}  hr={d[2]}  raw={d.hex()}")
                else:
                    prev(ch, d)
            w.on_event = ev
            try:
                for i in range(secs):
                    await asyncio.sleep(1)
                    await w.sport_update(sport_type=typ, duration_s=i + 1)
            finally:
                print("stop:", (await w.sport_stop(typ)).hex())
        elif cmd == "raw":
            data = bytes.fromhex(argv[1])
            await w.write(data)
            await asyncio.sleep(5)
        else:
            print(__doc__)
    finally:
        await w.disconnect()


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))

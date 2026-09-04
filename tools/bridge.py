#!/usr/bin/env python3
"""Drive the headless RyzeBridge app on the phone over adb and decode what the watch says.

  tools/bridge.py info                  connect, feature bitmap, version, battery
  tools/bridge.py sync                  steps / HR / SpO2 / sleep history
  tools/bridge.py hr [SECS] [static]    live heart rate: D6 02 + E5 11 (default, verified); 'static' = D6 01 spot test
  tools/bridge.py spo2                  SpO2 spot test
  tools/bridge.py keep [SECS]           hold the link open, log connection parameter changes
  tools/bridge.py workout [SECS] [TYPE] start a workout, stream HR (FD 01), push per-second updates, stop
  tools/bridge.py gatt                  dump the watch's GATT table
  tools/bridge.py find                  vibrate the watch (find-watch); used to get Buzz's attention
  tools/bridge.py bt3 on|off            classic-Bluetooth radio on/off
  tools/bridge.py spo2auto MIN|off      automatic SpO2 sampling every MIN minutes (after a factory reset the watch has none)
  tools/bridge.py hrauto on|off         continuous heart-rate monitoring (10-min bins)
  tools/bridge.py raw "<script>"        any BleService script, e.g. "connect;write a2;until a2 3000;disconnect"
  tools/bridge.py disconnect

Each run starts `adb logcat -s RyzeBridge`, fires the script through CmdActivity and prints decoded RX lines
until the service logs DONE/ERR. Raw log is appended to captures/bridge_<ts>.txt.
"""
import datetime as dt, os, re, shlex, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE); sys.path.insert(0, ROOT)
from parse_logcat import describe          # noqa: E402
from ryzewave import protocol as P         # noqa: E402

PKG = "au.buzz.ryzebridge"
LINE = re.compile(r"RyzeBridge\s*(?:\(\s*\d+\))?\s*:\s*(.*)$")   # -v time: "I/RyzeBridge(1234): msg"

SCRIPTS = {
    "info":  "connect;readfeat;read data;write a1;until a1 4000;write a2;until a2 4000;status",
    "sync":  ("connect;write b2fa;until b2fd 40000;write f7fa000000000000;until f7fd 40000;"
              "write 34fa;until 34fafd 40000;write 3101;until 3102 40000;status"),
    "gatt":  "connect;gatt",
    "disconnect": "disconnect",
}


def run(script: str, timeout: float = 120.0, quiet: bool = False) -> list[str]:
    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    logf = open(os.path.join(ROOT, "captures", f"bridge_{ts}.txt"), "a")
    # the vendor app shares the phone's GATT link and interleaves its own traffic; keep it out of the way
    subprocess.run(["adb", "shell", "am", "force-stop", "com.yc.ryzefit"], check=False)
    subprocess.run(["adb", "logcat", "-c"], check=False)
    lc = subprocess.Popen(["adb", "logcat", "-v", "time", "-s", "RyzeBridge:*"], stdout=subprocess.PIPE, text=True)
    # the script goes through the phone's shell, so quote it there (';' would otherwise split the command)
    r = subprocess.run(["adb", "shell", f"am start -W -n {PKG}/.CmdActivity --es script {shlex.quote(script)}"],
                       capture_output=True, text=True, check=False)
    if r.returncode != 0 or "Error" in r.stdout or "Error" in r.stderr:
        print("am start failed:", r.stdout.strip(), r.stderr.strip())
    out, end = [], time.time() + timeout
    try:
        while time.time() < end:
            line = lc.stdout.readline()
            if not line:
                break
            m = LINE.search(line)
            if not m:
                continue
            msg = m.group(1).strip()
            logf.write(line)
            out.append(msg)
            if not quiet:
                print(render(line[:18], msg), flush=True)
            if msg == "DONE" or msg.startswith("ERR "):
                break
    finally:
        lc.terminate(); logf.close()
    return out


def render(t: str, msg: str) -> str:
    if msg.startswith("RX ") or msg.startswith("TX "):
        d, ch, hexs = msg.split(" ", 2)
        name, extra = describe(hexs.strip())
        arrow = "<--" if d == "RX" else "-->"
        return f"{t} {arrow} {ch} {hexs.strip():<40s} {name:18s} {extra}"
    if msg.startswith("RD 33F1 "):
        try:
            f = P.Features.parse(bytes.fromhex(msg.split()[2]))
            return f"{t} ## feature bitmap: {f}"
        except Exception:
            pass
    return f"{t} ## {msg}"


def main(argv):
    cmd = argv[0] if argv else "info"
    if cmd in SCRIPTS:
        run(SCRIPTS[cmd])
    elif cmd == "hr":
        secs = int(argv[1]) if len(argv) > 1 else 30
        # vendor HR screen: D6 02 (dynamic, streams E5 11 00 <hr> after ~10 s warm-up) or D6 01 (static spot test),
        # then E5 11 1.5 s later; stop with E5 00. Dynamic is the one verified to work (2026-09-04).
        mode = "01" if len(argv) > 2 and argv[2] == "static" else "02"
        run(f"connect;write d6{mode};wait 1500;write e511;wait {secs * 1000};write e500;wait 500;status", timeout=secs + 90)
    elif cmd == "spo2":
        # the watch acks 34 11, emits a spurious 34 00 FF FF ~0.3 s later, re-announces 34 11 and delivers the
        # real result (34 00 00 <spo2>) roughly 57 s after that; so skip the first 2 s before waiting for 34 00
        run("connect;write 3411;wait 2000;mark;until 3400 90000;status", timeout=130)
    elif cmd == "keep":
        secs = int(argv[1]) if len(argv) > 1 else 120
        run(f"connect;write a2;until a2 4000;wait {secs * 1000};status", timeout=secs + 90)
    elif cmd == "bt3":
        on = argv[1] == "on"
        run(f"connect;write 3802{'01' if on else '00'} data;until 3802 5000;write 3801020000000000 data;until 3801 5000;status")
    elif cmd == "workout":
        secs = int(argv[1]) if len(argv) > 1 else 30
        typ = int(argv[2]) if len(argv) > 2 else 1
        run(f"connect;write fd11{typ:02x}01;until fd11 5000;sportloop {typ} {secs};write fd00{typ:02x}01;until fd00 5000;status",
            timeout=secs + 90)
    elif cmd == "spo2auto":
        # spo2auto <minutes> | off   — automatic SpO2 sampling interval (16-bit minutes); also re-sends the all-day window
        if argv[1] == "off":
            run("connect;write 340300000a;until 3403 4000;status")
        else:
            m = int(argv[1])
            run(f"connect;write 340301{m >> 8:02x}{m & 0xff:02x};until 3403 4000;write 3404010001173b;until 3404 4000;status")
    elif cmd == "hrauto":
        # hrauto on|off — continuous heart-rate monitoring (10-minute history bins, F7 01 / F7 02)
        run(f"connect;write f7{'01' if argv[1] == 'on' else '02'};until f7 4000;status")
    elif cmd == "find":
        # vibrate the watch (AB 00 00 00 01 02 07 01 = "find bracelet") — also our way to page Buzz
        run("connect;write ab00000001020701;wait 800;status", timeout=60)
    elif cmd == "raw":
        run(argv[1], timeout=float(argv[2]) if len(argv) > 2 else 300)
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])

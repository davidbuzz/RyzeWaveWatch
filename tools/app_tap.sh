#!/usr/bin/env bash
# Tap a UI element on the USB phone by its visible text (or content-desc), using uiautomator's view dump.
# Usage: tools/app_tap.sh "Sync now"          tap the first node whose text/desc contains the string
#        tools/app_tap.sh --list               print all clickable/text nodes with their centres
set -uo pipefail
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || { echo "uiautomator dump failed"; exit 1; }
adb shell cat /sdcard/ui.xml > /tmp/claude-1000/-home-buzz-RyzeWaveWatch/a8cfe5ce-cab3-47da-bf23-26c8acc106be/scratchpad/ui.xml 2>/dev/null
python3 - "$@" <<'EOF'
import re, sys, subprocess
xml = open("/tmp/claude-1000/-home-buzz-RyzeWaveWatch/a8cfe5ce-cab3-47da-bf23-26c8acc106be/scratchpad/ui.xml", encoding="utf-8", errors="replace").read()
nodes = []
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    def attr(k):
        mm = re.search(k + r'="([^"]*)"', n); return mm.group(1) if mm else ""
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not b: continue
    x1, y1, x2, y2 = map(int, b.groups())
    nodes.append({"text": attr("text"), "desc": attr("content-desc"), "click": attr("clickable") == "true",
                  "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "cls": attr("class").split(".")[-1]})
arg = sys.argv[1] if len(sys.argv) > 1 else "--list"
if arg == "--list":
    for n in nodes:
        if n["text"] or n["desc"]:
            print(f'{n["cx"]:5d},{n["cy"]:5d}  {"[click]" if n["click"] else "       "} {n["cls"]:14s} {n["text"] or n["desc"]}')
    sys.exit(0)
# exact text/desc match first, then substring — avoids "40" matching a field that merely contains 40
hit = next((n for n in nodes if arg == n["text"] or arg == n["desc"]), None) or \
      next((n for n in nodes if arg.lower() in (n["text"] + " " + n["desc"]).lower()), None)
if not hit:
    print(f"no node containing {arg!r}"); sys.exit(2)
print(f'tapping {hit["cx"]},{hit["cy"]} ({hit["text"] or hit["desc"]!r})')
subprocess.run(["adb", "shell", "input", "tap", str(hit["cx"]), str(hit["cy"])], check=False)
EOF

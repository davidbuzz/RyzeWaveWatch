#!/usr/bin/env python3
"""Summarise a tools/all_sports_test.sh run as Markdown.

    .venv/bin/python tools/all_sports_report.py captures/all_sports_<ts>/results.tsv

Prints a per-sport table (the three binary checks, the stop, the photo verdicts) and a tally, ready to paste into
docs/sports_and_step_counting.md.
"""
import csv, sys, collections, re

path = sys.argv[1]
rows = list(csv.DictReader(open(path), delimiter="\t"))
sc = open("ryzeapp/app/src/main/java/au/buzz/ryzewave/protocol/SportTypes.kt").read()
names = {int(m.group(1), 16): m.group(2) for m in re.finditer(r'0x([0-9A-Fa-f]{2}) to "([^"]+)"', sc)}

tally = collections.Counter(r["result"] for r in rows)
seen = {int(r["id"]) for r in rows if r.get("id")}
missing = sorted(set(names) - seen)

print(f"| id | Sport | Saved | Watch said | Realtime pushes | Stop | Result |")
print(f"|---|---|---|---|---|---|---|")
for r in rows:
    i = int(r["id"])
    ok = r["result"] == "OK"
    print(f"| `0x{i:02X}` | {r['sport']} | {r['saved_sport'] or '-'} | {r['watch_says'] or '-'} | {r['rt_pushes'] or '-'} | "
          f"{'verified' if r['duration_s'] else '-'} | {'**OK**' if ok else '**' + r['result'] + '**'} |")
print()
print(f"**Tally:** {tally.get('OK', 0)} OK of {len(rows)} run" + (f"; failures: " + ", ".join(f"{k} ×{v}" for k, v in tally.items() if k != 'OK') if len(tally) > 1 else "") + ".")
if missing:
    print(f"**Not run:** " + ", ".join(f"{names[i]} (0x{i:02X})" for i in missing))

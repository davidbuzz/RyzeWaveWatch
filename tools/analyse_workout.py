#!/usr/bin/env python3
"""Analyse a pulled ryzewave.db (tools/pull_app_data.sh): workouts, GPS track quality, splits, HR, steps.
usage: tools/analyse_workout.py captures/<name>/ryzewave.db [workout id]"""
import math, sqlite3, sys
from datetime import datetime, timezone, timedelta

def hav(a, b):
    R = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0]); dp = p2 - p1; dl = math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))

def ts(ms, tz):
    return datetime.fromtimestamp(ms / 1000, tz).strftime("%Y-%m-%d %H:%M:%S")

def main():
    db = sqlite3.connect(sys.argv[1]); db.row_factory = sqlite3.Row
    tz = timezone(timedelta(hours=10))  # AEST; the DB stores epoch ms
    cols = lambda t: [r[1] for r in db.execute(f"pragma table_info({t})")]
    ws = db.execute("select * from workout order by start").fetchall()
    print(f"workouts: {len(ws)}  (columns: {', '.join(cols('workout'))})")
    for w in ws:
        d = dict(w); dur = d.get('durationSeconds') or 0; dist = d.get('distanceMeters') or 0.0
        pace = (dur / (dist / 1000)) if dist > 0 else 0
        print(f"  #{d['id']} start {ts(d['start'], tz)} end {ts(d.get('end') or d.get('endTime'), tz) if (d.get('end') or d.get('endTime')) else '-'} type {d.get('sportType')} "
              f"dist {dist:.0f} m dur {dur} s pace {int(pace//60)}:{int(pace%60):02d}/km avgHr {d.get('avgHr')} maxHr {d.get('maxHr')} cal {d.get('calories')}")
    if not ws: return
    wid = int(sys.argv[2]) if len(sys.argv) > 2 else ws[-1]['id']
    w = dict(db.execute("select * from workout where id=?", (wid,)).fetchone())
    pts = [dict(r) for r in db.execute("select * from track_point where workoutId=? order by time", (wid,))]
    print(f"\nworkout #{wid}: {len(pts)} track points (columns: {', '.join(cols('track_point'))})")
    if pts:
        acc = [p for p in pts if p['accepted']]
        print(f"  accepted {len(acc)}  rejected {len(pts)-len(acc)}")
        accs = sorted(p['accuracyM'] for p in pts)
        print(f"  accuracy m: min {accs[0]:.0f} median {accs[len(accs)//2]:.0f} p90 {accs[int(len(accs)*.9)]:.0f} max {accs[-1]:.0f}")
        spd = [p['speedMps'] for p in pts if p['speedMps'] > 0]
        print(f"  fixes with Doppler speed: {len(spd)}/{len(pts)}; median speed {sorted(spd)[len(spd)//2]:.2f} m/s" if spd else "  no Doppler speed at all")
        gaps = [(pts[i]['time'] - pts[i-1]['time'])/1000 for i in range(1, len(pts))]
        print(f"  fix interval s: median {sorted(gaps)[len(gaps)//2]:.1f} max {max(gaps):.1f}; span {(pts[-1]['time']-pts[0]['time'])/1000:.0f} s")
        raw = sum(hav((pts[i-1]['lat'], pts[i-1]['lon']), (pts[i]['lat'], pts[i]['lon'])) for i in range(1, len(pts)))
        acc_sum = sum(hav((acc[i-1]['lat'], acc[i-1]['lon']), (acc[i]['lat'], acc[i]['lon'])) for i in range(1, len(acc)))
        cum = [p['cumulativeM'] for p in pts if p.get('cumulativeM') is not None]
        print(f"  raw haversine over ALL fixes {raw:.0f} m; over accepted fixes {acc_sum:.0f} m; stored cumulative {cum[-1] if cum else '-'} m; workout row {w['distanceMeters']:.0f} m")
        # straight-line start->end and bounding box
        print(f"  start->end straight line {hav((pts[0]['lat'],pts[0]['lon']),(pts[-1]['lat'],pts[-1]['lon'])):.0f} m; "
              f"bbox {hav((min(p['lat'] for p in pts),pts[0]['lon']),(max(p['lat'] for p in pts),pts[0]['lon'])):.0f} m N-S x "
              f"{hav((pts[0]['lat'],min(p['lon'] for p in pts)),(pts[0]['lat'],max(p['lon'] for p in pts))):.0f} m E-W")
        # km splits from cumulative
        if cum:
            nextkm, t0 = 1000.0, pts[0]['time']
            for p in pts:
                c = p.get('cumulativeM')
                if c is not None and c >= nextkm:
                    print(f"  {nextkm/1000:.0f} km at {(p['time']-t0)/1000:.0f} s ({ts(p['time'], tz)})"); nextkm += 1000
            # per-minute distance to spot stalls
            per_min = {}
            for p in pts:
                if p.get('cumulativeM') is None: continue
                m = int((p['time'] - t0) / 60000); per_min[m] = p['cumulativeM']
            prev = 0.0; stalls = []
            for m in sorted(per_min):
                d = per_min[m] - prev; prev = per_min[m]
                if d < 20: stalls.append(m)
            print(f"  minutes with < 20 m progress: {stalls if stalls else 'none'}")
    hr = db.execute("select time,bpm,source from hr_sample where time between ? and ? order by time", (w['start'], (w.get('end') or w.get('endTime') or w['start'] + 3600000))).fetchall()
    if hr:
        b = [r['bpm'] for r in hr]; srcs = {}
        for r in hr: srcs[r['source']] = srcs.get(r['source'], 0) + 1
        print(f"\nHR during workout: {len(hr)} samples by source {srcs}; min {min(b)} avg {sum(b)//len(b)} max {max(b)}")
    st = db.execute("select hourStart,total,walk,run from steps_hour where hourStart between ? and ? order by hourStart", (w['start'] - 3600000*3, ((w.get('end') or w.get('endTime') or w['start']) + 3600000*2))).fetchall()
    print("\nsteps by hour around the workout:")
    for r in st: print(f"  {ts(r['hourStart'], tz)}  total {r['total']} walk {r['walk']} run {r['run']}")
    ds = db.execute("select date(hourStart/1000,'unixepoch','+10 hours') d, sum(total) from steps_hour group by d order by d desc limit 3").fetchall()
    print("daily steps:", ", ".join(f"{r[0]}: {r[1]}" for r in ds))

if __name__ == "__main__":
    main()

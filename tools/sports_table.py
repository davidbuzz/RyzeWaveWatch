#!/usr/bin/env python3
"""Regenerate the sport table in docs/sports_and_step_counting.md from the Kotlin sources.

The per-sport prose lives in this script; the last column is read out of StrideCalibration.kt, so the document
cannot drift from the code. Run from the repo root: .venv/bin/python tools/sports_table.py
It prints the table to stdout and any code/table disagreements to stderr.
"""
import re, sys
D = {
0x01:("Swings in time with each stride","Steps","FOOT"),0x02:("Hands gripping the bars, almost motionless","Noise","NONE"),
0x04:("Full stroke cycle, one arm revolution per stroke","Strokes","STROKES"),0x05:("Sharp racket swings between short shuffles","Steps+","FOOT"),
0x07:("Racket swings and serves between sprints","Steps+","FOOT"),0x08:("Swings with the stride; poles or scrambling add motion","Steps","WALK"),
0x09:("Swings with the stride","Steps","WALK"),0x0A:("Dribbling and shooting over constant footwork","Steps+","FOOT"),
0x0B:("Swings while running; arms otherwise idle","Steps","FOOT"),0x0C:("Long idle spells, then a bat swing or throw","Sparse","FOOT"),
0x0D:("Repeated overhead arm swings, little travel","Reps","REPS"),0x0E:("Bowling and batting actions between standing","Sparse","FOOT"),
0x0F:("Swings while running, plus contact","Steps","FOOT"),0x10:("Stick work with a bent posture over skating or running","Steps","FOOT"),
0x12:("Hands fixed on the bars; legs do the work unseen","Noise","NONE"),0x13:("Slow, deliberate limb movement, held poses","Noise","NONE"),
0x14:("Torso curls; the wrist rises and falls each rep","Reps","REPS"),0x15:("Swings with the stride, indoors","Steps","FOOT"),
0x17:("Paddle stroke, one arm cycle per stroke","Strokes","STROKES"),0x18:("Arms sweep overhead once per jump","Reps","REPS"),
0x19:("Whatever the session contains; unpredictable","Reps","REPS"),0x1B:("Swings with the stride, indoors","Steps","FOOT"),
0x1C:("Lifts and presses, one arc per repetition","Reps","REPS"),0x1E:("Hands on the reins, rising to the horse's rhythm","Noise","NONE"),
0x1F:("Moving handles push and pull with the legs","Strokes","STROKES"),0x22:("Punches thrown in combinations","Reps","REPS"),
0x23:("Swings with the stride","Steps","WALK"),0x24:("Swings with the stride over uneven ground","Steps","FOOT"),
0x25:("Poling: one plant per push","Strokes","STROKES"),0x27:("Strikes and blocks in sequence","Reps","REPS"),
0x28:("Swings with the stride during a graded effort","Steps","FOOT"),0x29:("Catch, drive, finish, recovery: one arm cycle per stroke","Strokes","STROKES"),
0x2C:("Depends on the event; usually running arms","Steps","FOOT"),0x2D:("Twisting torso work, arms following","Reps","REPS"),
0x2E:("Strikes and blocks in sequence","Reps","REPS"),0x34:("Mixed gym work, arms moving irregularly","Reps","REPS"),
0x35:("Draw and hold, then still","Noise","NONE"),0x37:("Choreographed arm patterns with step patterns","Reps","REPS"),
0x39:("Fast, irregular arm movement with footwork","Reps","REPS"),0x3A:("Punches and kicks in combinations","Reps","REPS"),
0x3F:("Throwing arm over constant running","Steps","FOOT"),0x40:("A few approach steps, then one swing","Sparse","FOOT"),
0x41:("Racket swings in a confined court","Steps+","FOOT"),0x44:("Arms out for balance, no rhythm","Noise","NONE"),
0x46:("Sprints and blocks; arms drive then stop","Steps","FOOT"),0x48:("Casting occasionally, otherwise still","Noise","NONE"),
0x4B:("A long walk punctuated by swings","Steps+","WALK"),0x4D:("Poles planted on turns, arms mostly braced","Noise","NONE"),
0x4E:("Varies by discipline; usually braced","Noise","NONE"),0x50:("Floor work: planks, twists, raises","Reps","REPS"),
0x51:("Arms swing side to side for balance","Noise","NONE"),0x55:("Punch and kick patterns to a beat","Reps","REPS"),
0x56:("Cradling and passing over constant running","Steps","FOOT"),0x58:("Grappling; continuous irregular motion","Reps","REPS"),
0x59:("Lunges with a still, extended sword arm","Reps","REPS"),0x5A:("Long idle spells, then a bat swing or throw","Sparse","FOOT"),
0x60:("Paddle swings in a small court","Steps+","FOOT"),0x61:("Whatever the interval is: often both","Reps","REPS"),
0x62:("Raise, hold, fire; deliberately motionless","Noise","NONE"),0x63:("Grips and throws; continuous irregular motion","Reps","REPS"),
0x65:("One arm pushes, then coasting with arms out","Noise","NONE"),0x68:("Vaults, swings and sprints in bursts","Steps","FOOT"),
0x6A:("Paddling out, then riding with arms out","Strokes","STROKES"),0x6B:("Arm strokes with fins doing much of the work","Strokes","STROKES"),
0x6C:("One pull per repetition, wrist travels far","Reps","REPS"),0x6D:("One press per repetition, wrist on the floor","Reps","REPS"),
0x6F:("Reaching and pulling, irregular and slow","Noise","NONE"),0x71:("A fall, then hanging; nothing rhythmic","Noise","NONE"),
0x72:("A short run-up, then a single jump","Sparse","FOOT"),0x73:("Swings with the stride for hours","Steps","FOOT"),
}
sc=open('ryzeapp/app/src/main/java/au/buzz/ryzewave/protocol/SportTypes.kt').read()
names={int(m.group(1),16):m.group(2) for m in re.finditer(r'0x([0-9A-Fa-f]{2}) to "([^"]+)"', sc)}
st=open('ryzeapp/app/src/main/java/au/buzz/ryzewave/workout/StrideCalibration.kt').read()
def ids(b):
    m=re.search(b+r'\s*=\s*setOf\((.*?)\n    \)', st, re.S)
    return set(int(x,16) for x in re.findall(r'0x([0-9A-Fa-f]{2})', m.group(1))) if m else set()
stroke,reps,still,walk = ids('STROKE_SPORTS'), ids('REP_SPORTS'), ids('STILL_HAND_SPORTS'), ids('WALKING_SPORTS')
MEAS={"Steps":"**Yes** — footfalls","Sparse":"Partly — real steps, but few","Steps+":"Partly — steps inflated by swings",
      "Strokes":"**Yes** — strokes, not steps","Reps":"Partly — repetitions, not travel","Noise":"**No** — nothing usable"}
gaps=[]
print("| id | Sport | What the wrist actually sees | Does the step counter measure anything? | Handled in code? |")
print("|---|---|---|---|---|")
for i in sorted(names):
    arm,meas,intended = D[i]
    cur = 'STROKES' if i in stroke else 'REPS' if i in reps else 'NONE' if i in still else 'WALK_ONLY' if i in walk else 'ANY'
    want = {'FOOT':'ANY','WALK':'WALK_ONLY','STROKES':'STROKES','NONE':'NONE','REPS':'REPS'}[intended]
    state = f"Yes, `{cur}`" if cur==want else f"**No** — `{cur}`, should be `{want}`"
    if cur!=want: gaps.append((i,names[i],cur,want))
    print(f"| `0x{i:02X}` | {names[i]} | {arm} | {MEAS[meas]} | {state} |")
print(f"gaps={len(gaps)}", file=sys.stderr)
for g in gaps: print("0x%02X %s: %s -> %s"%g, file=sys.stderr)

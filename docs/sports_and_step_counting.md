# The 70 sports, and what a wrist can actually measure in each

The watch offers 70 sport types. The pedometer inside it is an accelerometer on a **wrist**, so what it counts is
whatever the wrist does repeatedly. In running that is footfalls. In rowing it is strokes. On a bike it is road
vibration. The number is never nothing, but it is only sometimes steps, and only steps can measure a stride.

This table is the reference behind `workout/StrideCalibration.kt`. Its first draft was written from general
knowledge; the arm-motion column was then checked sport by sport against coaching guides, biomechanics papers and
videos of people doing each one (sources at the end), and that pass changed nine classifications. Building the
table also found a real gap in the code: 21 repetition sports were falling through to the permissive default and
were safe only because such sessions carry no GPS distance. Everything is now named, and `tools/sports_table.py`
regenerates this file with the last two columns read out of the Kotlin, so it cannot drift from the app.

**The GPS column** answers a different question from the others: not what the watch counts, but whether a
satellite fix would tell you something you would otherwise not know. "Yes" means the sport goes outdoors and covers
ground, so a route, a distance and a speed are all real. "Sometimes" means it depends on the venue: open water but
not a lap pool, a road test but not a lab one, on the water but not on an erg. "Not for distance" does **not** mean worthless: a fix
that shows the wearer went nowhere is evidence too. It corroborates the sport they declared. Somebody who says
"Rower" or "Spinning" and stays inside a five-metre circle for forty minutes has had that claim confirmed by an
independent sensor, and somebody who declares "Spinning" while travelling at 25 km/h down a road has not. Note the column is independent of the step column: cycling
counts nothing useful on the wrist yet is one of the sports GPS serves best, and the reverse is true of a treadmill.

**The Health Connect column** is what an exported session is filed as. Health Connect defines 61 exercise types
and every watch sport is mapped to the closest; the eleven marked *other workout* (horse riding, VO2 max test,
waist training, archery, bowling, fishing, lacrosse, shooting, parkour, bungee, long jump) have no counterpart at
all. Hockey is filed as ice hockey because the watch does not say which kind; pickleball as racquetball;
skateboarding as skating; snorkeling as open-water swimming.

**How the last column maps to the code** (`StrideCalibration.sportGait`):

| Gate | Meaning |
|---|---|
| `ANY` | Footfalls. Windows are classified individually, so the session can teach a walking stride, a running stride, or both. |
| `WALK_ONLY` | A walking sport. Every usable window teaches the walking stride, however fast the wearer gets down a hill. |
| `STROKES` | The arms drive the effort. The count is a genuine stroke count, worth showing as a rate, but it is not a stride. |
| `COURT` | Racket and net sports. Shuffles, lunges and swings: the feet move and the counter climbs, but none of it is a gait, so no stride is measured. |
| `REPS` | Repetitions rather than travel. The wrist rises and falls once per rep and the session goes nowhere. |
| `NONE` | The hands are still or busy with something unrelated. The count is noise and should not be shown at all. |

| id | Sport | What the wrist actually sees | Does the step counter measure anything? | Is GPS worth having here? | Exported to Health Connect as | Handled in code? |
|---|---|---|---|---|---|---|
| `0x01` | Outdoor Running | Elbows bent, hands swinging pocket-to-cheek once per stride | **Yes** — footfalls | **Yes** — outdoors and covers ground | running | Yes, `ANY` |
| `0x02` | Cycling | Hands on the hoods, drops or flat bars; the wrist sees road vibration and the odd shift | **No** — nothing usable | **Yes** — outdoors and covers ground | biking | Yes, `NONE` |
| `0x04` | Swimming | Rhythmic arm strokes; the wrist counts strokes and, in a pool, wall turns | **Yes** — strokes, not steps | Sometimes — open water yes, a lap pool no; and the phone stays on the shore | swimming pool | Yes, `STROKES` |
| `0x05` | Badminton | Lunges and chassé steps between fast wrist-snap strokes; no steady gait | Partly — shuffles and swings, not a gait | Not for distance — but it proves you stayed put | badminton | Yes, `COURT` |
| `0x07` | Tennis | Three or four metres of movement per point, then a full swing on the racket arm | Partly — shuffles and swings, not a gait | Sometimes — outdoor courts are the norm, but the court is 24 m long | tennis | Yes, `COURT` |
| `0x08` | Hiking | Walking arm swing; with poles, each plant lands opposite the forward foot and keeps the rhythm | **Yes** — footfalls | **Yes** — outdoors and covers ground | hiking | Yes, `WALK_ONLY` |
| `0x09` | Walking | Opposite arm and leg swing; suppressed if hands hold a pram, a trolley or pockets | **Yes** — footfalls | Sometimes — also used indoors, on treadmills and with hands on a pram | walking | Yes, `WALK_ONLY` |
| `0x0A` | Basketball | Five or six km a game, mostly walk and jog; the dribbling hand pumps at one to two hertz and inflates the count | Partly — steps inflated by swings or dribbling | Sometimes — outdoor courts are common; organised games are indoors | basketball | Yes, `ANY` |
| `0x0B` | Soccer | Ten to thirteen km a match of mostly low-speed running with a free arm swing | **Yes** — footfalls | **Yes** — outdoors and covers ground | soccer | Yes, `ANY` |
| `0x0C` | Baseball | Mostly standing; bat swings, throws and under a hundred metres of running a game | Partly — repetitions, not travel | Sometimes — outdoors, but under a hundred metres run per game | baseball | Yes, `REPS` |
| `0x0D` | Volleyball | Shuffles and jumps with big overhead swings for spikes and serves; locked forearms for passing | Partly — shuffles and swings, not a gait | Sometimes — beach and grass outdoors, competitive play indoors | volleyball | Yes, `COURT` |
| `0x0E` | Cricket | Batting is short sprints between wickets; bowling is a run-up and a full arm circle; fielding is walking with bursts | Partly — steps inflated by swings or dribbling | **Yes** — outdoors and covers ground | cricket | Yes, `ANY` |
| `0x0F` | Rugby | Five to ten km of walking, jogging and sprinting, interrupted by tackles and ball carrying | **Yes** — footfalls | **Yes** — outdoors and covers ground | rugby | Yes, `ANY` |
| `0x10` | Hockey | Field: 8-10 km in a crouch with two hands on a short stick, swing damped. Ice: skating stride, stick in hand | **Yes** — footfalls | Sometimes — field hockey yes, ice hockey no | ice hockey | Yes, `ANY` |
| `0x12` | Spinning | Hands on the bars in one of three positions; only a slight rock on standing climbs | **No** — nothing usable | Not for distance — but it proves you stayed put | biking stationary | Yes, `NONE` |
| `0x13` | Yoga | Slow arm raises in sun salutations, then poses held for breaths | **No** — nothing usable | Not for distance — but it proves you stayed put | yoga | Yes, `NONE` |
| `0x14` | Sit-ups | Hands behind the head or across the chest; the whole torso rocks up and down each rep | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | calisthenics | Yes, `REPS` |
| `0x15` | Treadmill | Normal running swing, unless the hands hold the rails, which stalls the counter | **Yes** — footfalls | Not for distance — but it proves you stayed put | running treadmill | Yes, `ANY` |
| `0x17` | Boating | Both hands on the paddle; torso rotates and the arms push and pull alternately, one cycle per stroke | **Yes** — strokes, not steps | **Yes** — outdoors and covers ground | paddling | Yes, `STROKES` |
| `0x18` | Jumping Jacks | Arms swing overhead and back on every jump, at exactly a step-like tempo | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | calisthenics | Yes, `REPS` |
| `0x19` | Free Training | A catch-all mode for anything not on the menu; the arm motion is whatever the wearer is doing | Partly — repetitions, not travel | Sometimes — nothing is implied either way | exercise class | Yes, `REPS` |
| `0x1B` | Indoor Running | Normal running swing indoors, same handrail caveat as a treadmill | **Yes** — footfalls | Not for distance — but it proves you stayed put | running treadmill | Yes, `ANY` |
| `0x1C` | Strength Training | Slow loaded reps; on upper-body lifts the wrist travels out and back once per rep, on leg lifts it barely moves | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | strength training | Yes, `REPS` |
| `0x1E` | Horse Riding | Hands quiet on the reins, but the trot shakes the whole body at about 1.3 hertz, which a counter may read as steps | **No** — nothing usable | **Yes** — outdoors and covers ground | *other workout* | Yes, `NONE` |
| `0x1F` | Elliptical | With moving handles the arms push and pull in time with each stride; on fixed rails they are braced | **Yes** — strokes, not steps | Not for distance — but it proves you stayed put | elliptical | Yes, `STROKES` |
| `0x22` | Boxing | Jab, cross, hook and uppercut combinations from a guard, with circling footwork | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | boxing | Yes, `REPS` |
| `0x23` | Outdoor Walking | Each arm swings in phase with the opposite leg: the passive pendulum the counter is designed for | **Yes** — footfalls | **Yes** — outdoors and covers ground | walking | Yes, `WALK_ONLY` |
| `0x24` | Trail Running | Running swing; poles planted alternately on climbs, held loose on descents | **Yes** — footfalls | **Yes** — outdoors and covers ground | running | Yes, `ANY` |
| `0x25` | Skiing | Classic diagonal stride: opposite arm and leg drive together, every pole plant a full arm swing | **Yes** — strokes, not steps | **Yes** — outdoors and covers ground | skiing | Yes, `STROKES` |
| `0x27` | Taekwondo | Kick-dominant sparring with the hands in a guard; forms add sequenced blocks and punches | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | martial arts | Yes, `REPS` |
| `0x28` | VO2 max Test | A Cooper-style twelve-minute maximal run: normal running arm swing throughout | **Yes** — footfalls | Sometimes — a track or road test, not a treadmill one | *other workout* | Yes, `ANY` |
| `0x29` | Rower | Legs, body, arms on the drive; hands travel straight out and back once per stroke. On water the inside hand also feathers the oar | **Yes** — strokes, not steps | Sometimes — on the water yes, an erg no | rowing machine | Yes, `STROKES` |
| `0x2C` | Athletics | Track: running arm swing. Field: a runway sprint, then one throw or jump and a long wait | **Yes** — footfalls | Sometimes — track and road events, not field events | running | Yes, `ANY` |
| `0x2D` | Waist Training | Standing trunk twists on a disc or side bends; arms out, on hips, or swinging with the twist. Not hula hoop, which is a separate mode | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | *other workout* | Yes, `REPS` |
| `0x2E` | Karate | Repeated punches, blocks and the pulling hand in basics and kata | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | martial arts | Yes, `REPS` |
| `0x34` | Physical Training | Military-style circuits of push-ups, sit-ups, squats and burpees with running between | Partly — repetitions, not travel | Sometimes — the running part is often outdoors | calisthenics | Yes, `REPS` |
| `0x35` | Archery | Raise, draw, anchor at the jaw, hold, release; a few slow movements a minute | **No** — nothing usable | Sometimes — outdoor ranges, with walks to the target | *other workout* | Yes, `NONE` |
| `0x37` | Aerobic Combo | Hi-lo floor aerobics: marching, grapevines and knee lifts with choreographed arm patterns, at walking tempo | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | exercise class | Yes, `REPS` |
| `0x39` | Street Dancing | Hip-hop and popping: sharp arm isolations, waves and swings with footwork; musical, not cyclic | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | dancing | Yes, `REPS` |
| `0x3A` | Kick Boxing | Punch and kick combinations on a bag, pads or opponent | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | martial arts | Yes, `REPS` |
| `0x3F` | Handball | Running with arm swing plus a dozen throws and jump shots a match; two to six km covered | **Yes** — footfalls | Not for distance — but it proves you stayed put | handball | Yes, `ANY` |
| `0x40` | Bowling | A four-step approach with a pendulum swing and release, then a minute sitting | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | *other workout* | Yes, `REPS` |
| `0x41` | Racquetball | Pivots and shuffles around centre court with whipping wrist-snap swings | Partly — shuffles and swings, not a gait | Not for distance — but it proves you stayed put | racquetball | Yes, `COURT` |
| `0x44` | Snowboarding | No poles; arms relaxed or over nose and tail, upper body kept quiet while the legs steer | **No** — nothing usable | **Yes** — outdoors and covers ground | snowboarding | Yes, `NONE` |
| `0x46` | American Football | Three to six km a game in bursts: about ten sprints and a hundred accelerations, still between plays | **Yes** — footfalls | **Yes** — outdoors and covers ground | football american | Yes, `ANY` |
| `0x48` | Fishing | An occasional overhead cast, then one hand cranks the reel while the rod hand holds still | **No** — nothing usable | Sometimes — bank, boat or wading, but mostly stationary | *other workout* | Yes, `NONE` |
| `0x4B` | Golf | Four to six miles walked between shots with a normal swing, plus eighty to a hundred full swings | Partly — steps inflated by swings or dribbling | **Yes** — outdoors and covers ground | golf | Yes, `WALK_ONLY` |
| `0x4D` | Downhill Skiing | Arms held forward and quiet; a wrist flick plants the downhill pole once per turn | **No** — nothing usable | **Yes** — outdoors and covers ground | skiing | Yes, `NONE` |
| `0x4E` | Snow Sports | Snowshoeing is walking with poles; sledding is sitting with hands on the sled. Genuinely variable | **No** — nothing usable | **Yes** — outdoors and covers ground | skiing | Yes, `NONE` |
| `0x50` | Core Training | Planks with hands fixed to the floor; dead bugs and bird dogs are slow single-arm reaches | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | calisthenics | Yes, `REPS` |
| `0x51` | Skating | Arms float loosely at cruising speed, swing with each push when sprinting, tuck behind the back on straights | **No** — nothing usable | Sometimes — inline is outdoors, ice is usually a rink | skating | Yes, `NONE` |
| `0x55` | Kickboxing Aerobics | Tae Bo style: punches and kicks into the air over a continuous bouncing base step at jogging tempo | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | exercise class | Yes, `REPS` |
| `0x56` | Lacrosse | Running while cradling: the stick hand rocks in time with the feet, the free arm wards off defenders | **Yes** — footfalls | **Yes** — outdoors and covers ground | *other workout* | Yes, `ANY` |
| `0x58` | Wrestling | Hand-fighting for wrist and collar ties, then explosive takedowns; continuous and irregular | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | martial arts | Yes, `REPS` |
| `0x59` | Fencing | Advance, retreat and lunge along a 14 m strip with the weapon arm extending and parrying | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | fencing | Yes, `REPS` |
| `0x5A` | Softball | As baseball, plus the fast-pitch windmill: a full vertical arm circle per pitch | Partly — repetitions, not travel | Sometimes — outdoors, but ground covered is trivial | softball | Yes, `REPS` |
| `0x60` | Pickleball | Small shuffles to and from the kitchen line with compact paddle swings | Partly — shuffles and swings, not a gait | Sometimes — indoor and outdoor courts both common | racquetball | Yes, `COURT` |
| `0x61` | HIIT | Whatever the interval is; often calisthenics and short runs mixed | Partly — repetitions, not travel | Sometimes — outdoor intervals only | high intensity interval training | Yes, `REPS` |
| `0x62` | Shooting | Fixed stance, both hands on the firearm, held steady through breath control and trigger press | **No** — nothing usable | Sometimes — a static range, or walks between hunting stations | *other workout* | Yes, `NONE` |
| `0x63` | Judo | Grip fighting on the jacket, off-balancing, then throws and groundwork; irregular | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | martial arts | Yes, `REPS` |
| `0x65` | Skateboarding | Arms out for balance while rolling; pushes are one-legged and intermittent | **No** — nothing usable | **Yes** — outdoors and covers ground | skating | Yes, `NONE` |
| `0x68` | Parkour | Sprints between obstacles with a normal swing, then hands planted on walls and rails for vaults | **Yes** — footfalls | Sometimes — when it moves through a city rather than one spot | *other workout* | Yes, `ANY` |
| `0x6A` | Surfing | Paddling out is an alternating windmill crawl stroke, most of a session; riding, the arms are out for balance | **Yes** — strokes, not steps | **Yes** — outdoors and covers ground | surfing | Yes, `STROKES` |
| `0x6B` | Snorkeling | Arms relaxed at the sides; propulsion is entirely a slow fin kick, hands only steer | **No** — nothing usable | Sometimes — open water, but the wrist is submerged and the phone stays on the beach | swimming open water | Yes, `NONE` |
| `0x6C` | Pull-up | Hands fixed on the bar; the wrist itself barely moves, so even the reps are under-counted | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | calisthenics | Yes, `REPS` |
| `0x6D` | Push-up | Hands planted on the floor; the wrist sees a small vertical load pulse per rep, not a swing | Partly — repetitions, not travel | Not for distance — but it proves you stayed put | calisthenics | Yes, `REPS` |
| `0x6F` | Rock Climbing | Hanging on straight arms, reaching for holds one at a time, resting; the legs push | **No** — nothing usable | Sometimes — the approach and traverse, never the vertical | rock climbing | Yes, `NONE` |
| `0x71` | Bungee Jumping | Arms spread or crossed, then a few seconds of free fall and rebounds; one violent event | **No** — nothing usable | Not for distance — but it proves you stayed put | *other workout* | Yes, `NONE` |
| `0x72` | Long Jump | A twenty-step sprint with pumping arms, an arm block at take-off, then a walk back | **Yes** — footfalls | Sometimes — an outdoor runway, forty metres per attempt | *other workout* | Yes, `ANY` |
| `0x73` | Marathon | Elbows at ninety degrees, hands swinging pocket to cheek, one swing per stride, for hours | **Yes** — footfalls | **Yes** — outdoors and covers ground | running | Yes, `ANY` |

## Why GPS is required for every sport, including the ones in this table that count nothing

Read down the third column and the wrist looks unreliable: it counts footfalls in some sports, strokes in others,
repetitions in others, and road vibration in the rest. What it counts changes with the sport, with where the watch
is worn, and with what the wearer happens to be doing with their hands.

GPS does not change with any of that. It measures where the body went, and it measures it the same way whether the
wearer is running, cycling, skating, paddling or swimming lengths of an open-air pool. That is why the app refuses
to start a workout with location services off, for **every** sport rather than only the ones that obviously travel:
it is the one dataset that does not depend on which limb is moving, and where it is available at all it tends to be
available reliably and continuously for the whole session.

That makes it the reference the rest is checked against. Two consequences worth stating plainly:

- **A gate in the last column never disables GPS.** Cycling calibrates no stride, but it still records a full track,
  a real distance and a real speed. The refusal is only about deriving a stride from a wrist count; nothing about
  the sport stops the phone measuring the route. Nothing in the workout code branches on sport type before starting
  the tracker.
- **A null GPS result is still a measurement.** The obvious use of a fix is distance, but the opposite reading is
  just as useful: on a rowing machine or a stationary bike, GPS is what proves the wearer really was stationary,
  and that staying still was the point rather than a fault. Without it, "no distance" is ambiguous — it could mean
  an erg session, a lost signal, a forgotten workout left running, or a watch knocked into sport mode on a bedside
  table. With it, those are distinguishable. It also catches a mislabelled session: a declared stationary sport
  that turns out to be moving at road speed is not the sport the user picked.
- **The GPS column is a rough count of where it pays off:** 21 of the 70 sports always benefit, 15 depend on
  where they are done, and 34 gain nothing. The app still records a track for all of them, because the cost of
  doing so is small and the cost of not having it, when it turns out to matter, is a session that cannot be
  checked afterwards.
- **GPS is what proved the wrist counts wrong in the first place.** The stride bug was only visible because the
  same session had an independent distance to compare against: 772 GPS metres against 874 wrist counts said the
  blended 0.883 m stride belonged to neither gait. Without the satellite measurement there would have been no way
  to tell a good stride from a bad one, in any sport.

## What this table changed

- **21 sports gained an explicit gate.** Volleyball, sit-ups, jumping jacks, free training, strength training,
  boxing, taekwondo, waist training, karate, physical training, aerobic combo, street dancing, kick boxing, core
  training, kickboxing aerobics, wrestling, fencing, HIIT, judo, pull-ups and push-ups all previously fell through
  to `ANY`. Nothing broke, because a gym session has no GPS distance and so produces no windows, but the refusal
  was an accident of the data rather than a decision. Now the app says why.
- **58 sports gained a real Health Connect type.** Only 12 were mapped before; the rest exported as "other
  workout". Now all but eleven, which genuinely have no Health Connect counterpart, are filed as what they are.
- **Average speed no longer overrules the chosen sport on export.** The same flaw as the stride bug lived in the
  Health Connect and GPX paths: a type-1 workout under 2 m/s was relabelled a walk. That heuristic now applies only
  to rows recorded before the sport picker existed.
- **Cycling was separated from rowing.** Both refuse to calibrate, for opposite reasons: a rower's arms follow the
  effort, so the count is real; a cyclist's hands are on the bars, so a wrist cannot see the pedals at all.


## What this means for an indoor machine

The same insight that separates strokes from steps decides whether a session is judged alive. On a rowing erg or
an elliptical the wearer stays in one place, so demanding GPS movement of them would flag a genuine session as
stuck; but the wrist swings once per stroke, so the watch's counter keeps rising and *that* is the evidence the
session is real. It survives the two things that break the alternatives: a phone left on a shelf, which kills the
motion signal, and a heart rate that has not climbed yet, early in a session or during a gentle row.

Spinning is deliberately not in that group. Hands stay on the bars, the counter does not rise, and requiring it
would flag every spin class. There, heart rate and phone motion are all there is.

## What a wrist step counter actually responds to

The single fact that explains most of the table: the counter fires on **any periodic arm motion near walking
cadence**, roughly 1.5 to 2.5 hertz, and stalls when the arm is braced. Everything else follows from it.

- **Step-like repetitions are counted as steps.** Jumping jacks, hi-lo aerobics, Tae Bo bouncing and the elliptical's
  moving handles all sit at walking or jogging tempo, so they produce large, confident step counts without the
  wearer going anywhere. A sit-up rocks the wrist at half a hertz and can register too.
- **Occupied hands stall the counter.** Treadmill handrails, a pram or a shopping trolley, a stick held in two
  hands (field hockey), a ball being carried (rugby) all damp or kill the swing, so real footfalls go uncounted.
  Generic "Walking" is the mode most exposed to this, because it is the one people pick indoors.
- **A fixed wrist counts nothing, even for reps.** Pull-ups and push-ups leave the hand on the bar or the floor; the
  accelerometer sees a small vertical load pulse per repetition and under-counts even those.
- **Vibration is not motion, but it can look like it.** A trotting horse shakes the rider at about 1.3 hertz, close
  enough to be read as steps. A bicycle on rough road can do the same.
- **A stroke is a real count.** Rowing, kayaking, swimming and cross-country skiing move the arms once per
  propulsive cycle, so the number is meaningful; it is just not steps. Snorkeling is the exception people assume
  wrongly: the arms trail at the sides and the fins do all the work.

## What the research changed

Checking each sport against sources moved nine of them, and the code moved with them:

- **Snorkeling** is fin-driven with the arms relaxed at the sides. It was filed as strokes; it is noise.
- **Badminton, tennis, racquetball, pickleball and volleyball** are chassé steps, lunges and swings, not a gait.
  They were allowed to calibrate a stride; they now refuse with a message that says why (`COURT`).
- **Baseball, softball and bowling** are sparse repetitions with long idle spells: under a hundred metres of running
  in a baseball game, one four-step approach every half minute in bowling. They were treated as footfall sports;
  they are repetitions.
- **Waist Training** is trunk twists on a disc, not hula hoop; the vendor's icon set has a separate hula-hoop mode.
- **Walking** (the generic mode) is used indoors and on treadmills as often as outdoors, so its GPS column is now
  "sometimes" rather than "yes".
- Tennis, basketball, pickleball, volleyball, archery, shooting, skating and long jump moved from "no GPS value" to
  "sometimes", because each has a common outdoor form.
- Rowing on water differs from an erg in one way the wrist can see: the inside hand rotates the oar ninety degrees
  each stroke. The machine is a pure linear pull. Both are strokes.

## Findings from the first real sessions

- **The blended-stride bug.** A run of sprints and recovery walks averaged 1.42 m/s, so the old calibration filed
  772 m / 874 steps = 0.883 m as a walking stride, 18% too long. Splitting the session into windows and classifying
  each by stride-to-height ratio and cadence recovered 0.66 m walking and 0.93 m running, both within 6% of what
  height alone predicts.
- **The same flaw lived in the export path.** `SportTypes.effectiveId` relabelled any type-1 workout under 2 m/s as
  a walk for Health Connect and GPX. That run would have been exported as a walk. The heuristic now applies only to
  rows recorded before the sport picker existed.
- **A quiet erg session was "stuck".** Rower sat in the travelling class, which expects GPS movement. On a machine
  the body goes nowhere, so a gentle row with the phone on a shelf could be warned and auto-stopped. The wrist's
  stroke count is now accepted as evidence of life for rowing machines and ellipticals.
- **The declared sport is now checked against the GPS** (`workout/SportMotionCheck.kt`, shown on the workout
  detail). A stationary sport whose fixes stayed inside 25 m is confirmed; a stationary sport that covered 150 m
  is flagged as mislabelled; a travelling sport that never left a 25 m circle for four minutes or more is flagged
  as a treadmill or a session that never happened. Sports that imply nothing (baseball, swimming with the phone on
  the shore) never contradict.
- **Only 12 of 70 sports had a Health Connect type.** The rest exported as "other workout". All 70 are now mapped;
  eleven genuinely have no counterpart.

## Testing every sport end to end

`tools/all_sports_test.sh <serial> [seconds] [sport ...]` drives the real app on the USB phone through its own
picker: for each sport it opens the "More…" dialog, scrolls until the name is visible, starts a workout, waits,
stops it, then reads the stored row back over `adb` and checks the sport that was saved is the one chosen. It
works on the release build because release builds are debuggable, and it prints a results table and a screenshot
per sport. A full pass of all 70 at 20 s each takes about forty minutes.

With the watch linked it also proves the **watch** entered the sport: after every start the app asks it `FD AA`
("is a sport open, and which?") and the driver reads the answer back into a `watch_says` column, failing any sport
where the watch disagrees with what was chosen. To see the actual face, `tools/watch_cam.sh` turns a second phone
into a camera rig (shutter over adb, metadata stripped, photo deleted from the phone); the watch must be awake,
which starting a sport does, and the glossy screen wants the camera a few degrees off-axis to avoid reflections.

Trial on 2026-09-06 (`captures/all_sports_trial_20260906/results.tsv`): Rower driven on the Moto, the watch answered
`FD AA` with sport 41, the Pixel caught the face lit at start (the sport's metrics page: heart rate, calories,
timer), dark mid-run (the screen times out after a few seconds), and lit again after stop, when the watch returns
to its sport menu. So expect the start and stop frames to be the useful ones, and note the metrics page does not
print the sport's name; the watch's own `FD AA` answer is the proof of which sport it entered.

What that proves, and what it does not. It proves the whole chain from picker to watch to database works for every
sport: selection, start, the location gate, the foreground service, the watch's own confirmation, stop, persistence,
sport id. It does **not** prove the
classifications above are right, because a phone sitting on a desk does not row or swim; only a recorded session of
each sport can do that, and so far exactly one sport (Outdoor Running) has one. Treat the table's thresholds as
provisional until more real sessions exist.

## Known imprecision, honestly

- **"Steps inflated by swings or dribbling"** covers basketball, cricket and golf: a swing, a bowling action or a
  dribbling hand looks enough like a step to be counted. Golf is still treated as a walking sport, because a round
  is four to six miles of walking, but a stride measured from one reads slightly short.
- **"Real steps, but few"** covers baseball, softball, cricket, bowling and long jump: the wearer stands still for
  most of the session, so the count is honest but thin.
- **Nobody has recorded most of these on this watch.** The arm-motion column is sourced, but "sourced" means a
  coaching guide or a study of the sport in general, not a trace from this wrist on this watch.
- Everything here rests on one wearer's data. The classifier thresholds in `StrideCalibration` are provisional.

Each arm-motion description above was checked against a coaching guide, a biomechanics paper, or a video of the
sport, on 2026-09-06. The one repository finding worth recording: the vendor app's icon set has a separate
`icon_sport_94_hula_hoop`, so the watch's Waist Training mode is trunk twists, not hooping.

- `0x01` Outdoor Running: <https://runningmagazine.ca/sections/training/a-runners-guide-to-proper-arm-swing/>
- `0x02` Cycling: <https://bike.bikegremlin.com/927/road-bar-hand-positions/>
- `0x04` Swimming: <https://www.popsci.com/inside-apple-watch-swim-tracking/>
- `0x05` Badminton: <https://www.joinshuttlelab.com/learn/badminton-footwork-complete-guide>
- `0x07` Tennis: <https://www.atptour.com/en/news/physicality-index-detailed-explainer-2023>
- `0x08` Hiking: <https://www.rei.com/learn/expert-advice/how-to-use-trekking-poles.html>
- `0x09` Walking: <https://en.wikipedia.org/wiki/Arm_swing_in_human_locomotion>
- `0x0A` Basketball: <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9465768/>
- `0x0B` Soccer: <https://www.si.com/soccer/how-many-miles-do-soccer-players-run-in-a-game>
- `0x0C` Baseball: <https://www.baseballmonkey.com/learn/how-to-swing-a-baseball-bat-step-by-step-guide>
- `0x0D` Volleyball: <https://www.betteratbeach.com/blog/armswingforspikingavolleyball>
- `0x0E` Cricket: <https://pubmed.ncbi.nlm.nih.gov/20013461/>
- `0x0F` Rugby: <https://www.sharperrugby.com/rugby-players-running-distance/>
- `0x10` Hockey: <https://pmc.ncbi.nlm.nih.gov/articles/PMC8336549/>
- `0x12` Spinning: <https://indoorcyclingassociation.com/where-should-you-put-your-hands-in-an-indoor-cycling-class-part-1/>
- `0x13` Yoga: <https://www.yogajournal.com/yoga-101/surya-namaskar/>
- `0x14` Sit-ups: <https://steelsupplements.com/blogs/steel-blog/how-to-do-a-sit-up-with-proper-form>
- `0x15` Treadmill: <https://stepsly.app/blog/tracking-steps-on-treadmill-vs-outdoors>
- `0x17` Boating: <https://www.rei.com/learn/expert-advice/kayak-strokes.html>
- `0x18` Jumping Jacks: <https://www.nasm.org/resource-center/exercise-library/jumping-jacks>
- `0x19` Free Training: <https://www.mi.com/global/support/faq/details/KA-235627/>
- `0x1B` Indoor Running: <https://stepsly.app/blog/tracking-steps-on-treadmill-vs-outdoors>
- `0x1C` Strength Training: <https://www8.garmin.com/manuals/webhelp/forerunner245/EN-US/GUID-CF2D7922-A1AC-4510-9480-E7CE8119EAF2.html>
- `0x1E` Horse Riding: <https://www.horsejournals.com/riding-training/english/dressage/equitation-essentials-hows-and-whys-winning-riding-position>
- `0x1F` Elliptical: <https://www.alibaba.com/product-insights/why-does-my-apple-watch-miscount-steps-on-elliptical-machines-and-how-to-correct-it.html>
- `0x22` Boxing: <https://www.boxing.org/techniques>
- `0x23` Outdoor Walking: <https://en.wikipedia.org/wiki/Arm_swing_in_human_locomotion>
- `0x24` Trail Running: <https://www.salomon.com/en-us/running/trail-running-advice/how-run-poles>
- `0x25` Skiing: <https://www.stio.com/blogs/ski-snowboard/classic-cross-country-skiing-technique>
- `0x27` Taekwondo: <https://tkdtutor.blogspot.com/p/sparringtechniqueshands-up.html>
- `0x28` VO2 max Test: <https://www.topendsports.com/testing/tests/cooper.htm>
- `0x29` Rower: <https://ergatta.com/blogs/indoor-rowing-beginner/rowing-form-beginner-guide>
- `0x2C` Athletics: <https://www.olympics.com/en/news/athletics-track-and-field-sprints-marathons-jumps-throws-heptathlon-decathlon>
- `0x2D` Waist Training: <https://www.youtube.com/watch?v=Ubu3-4mSoZI>
- `0x2E` Karate: <https://www.shotokankarateonline.com/blog/shotokan-karate-punches/>
- `0x34` Physical Training: <https://www.gymnasetips.com/military-calisthenics-workout-guide/>
- `0x35` Archery: <https://www.bowhunter-ed.com/national/studyGuide/Six-Basic-Steps-for-Shooting/301099_185423/>
- `0x37` Aerobic Combo: <https://cathe.com/forum/threads/hi-lo-definition-please.168419/>
- `0x39` Street Dancing: <https://www.rockstaracademy.com/blog/isolation-dance>
- `0x3A` Kick Boxing: <https://www.infighting.ca/kickboxing/the-ultimate-guide-to-kickboxing-heavy-bag-training/>
- `0x3F` Handball: <https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7559068/>
- `0x40` Bowling: <https://bowl.com/welcome/the-approach-4840c7151c5dc3884afbeb8041beac5a>
- `0x41` Racquetball: <https://cemood.people.wm.edu/racquetball/swing/stroke_tutorial_2010.html>
- `0x44` Snowboarding: <https://www.rei.com/learn/expert-advice/how-to-snowboard.html>
- `0x46` American Football: <https://simplifaster.com/articles/gps-college-football-sports-science/>
- `0x48` Fishing: <https://www.takemefishing.org/how-to-fish/how-to-catch-fish/how-to-cast/>
- `0x4B` Golf: <https://www.golfmonthly.com/features/the-game/how-far-do-you-walk-in-a-round-of-golf-90837>
- `0x4D` Downhill Skiing: <https://www.advnture.com/how-to/pole-plant>
- `0x4E` Snow Sports: <https://www.rei.com/learn/expert-advice/snowshoeing-first-steps.html>
- `0x50` Core Training: <https://www.jefit.com/blog/strong-core-exercises-dead-bug-side-bridge-and-bird-dog>
- `0x51` Skating: <http://www.inlineplanet.com/11/08/begg-arm-swing.html>
- `0x55` Kickboxing Aerobics: <https://en.wikipedia.org/wiki/Tae_Bo>
- `0x56` Lacrosse: <https://www.dickssportinggoods.com/protips/sports-and-activities/lacrosse/learning-lacrosse-cradling>
- `0x58` Wrestling: <https://fanaticwrestling.com/blogs/news/hand-fighting-wrestling>
- `0x59` Fencing: <https://www.sheridanfencing.com/blog/fencing-footwork-drills>
- `0x5A` Softball: <https://en.wikipedia.org/wiki/Fastpitch>
- `0x60` Pickleball: <https://www.pb5star.com/a/blog/pickleball-to-steps-how-many-steps-you-take-playing-the-game>
- `0x61` HIIT: <https://www.gymnasetips.com/military-calisthenics-workout-guide/>
- `0x62` Shooting: <https://www.therange702.com/blog/7-fundamentals-marksmanship/>
- `0x63` Judo: <https://judoadvisor.com/2009/02/an-introduction-to-kumi-kata-grip-fighting-for-novice-judo-athletes/>
- `0x65` Skateboarding: <https://www.surfertoday.com/skateboarding/how-to-push-on-a-skateboard>
- `0x68` Parkour: <https://www.rockstaracademy.com/blog/how-to-learn-parkour>
- `0x6A` Surfing: <https://www.surfertoday.com/surfing/how-to-paddle-on-a-surfboard>
- `0x6B` Snorkeling: <https://dtmag.com/thelibrary/snorkeling-tips/>
- `0x6C` Pull-up: <https://stronglifts.com/pullups/>
- `0x6D` Push-up: <https://blog.nasm.org/nasm-guide-to-push-ups/form-and-technique>
- `0x6F` Rock Climbing: <https://www.rei.com/learn/expert-advice/climbing-techniques.html>
- `0x71` Bungee Jumping: <https://www.manawa.com/en/articles/the-complete-guide-to-bungee-jumping>
- `0x72` Long Jump: <https://www.liveabout.com/step-by-step-long-jump-technique-3258964>
- `0x73` Marathon: <https://runningmagazine.ca/sections/training/a-runners-guide-to-proper-arm-swing/>

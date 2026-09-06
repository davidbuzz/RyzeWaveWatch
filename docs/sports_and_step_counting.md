# The 70 sports, and what a wrist can actually measure in each

The watch offers 70 sport types. The pedometer inside it is an accelerometer on a **wrist**, so what it counts is
whatever the wrist does repeatedly. In running that is footfalls. In rowing it is strokes. On a bike it is road
vibration. The number is never nothing, but it is only sometimes steps, and only steps can measure a stride.

This table is the reference behind `workout/StrideCalibration.kt`. It was written by going through every sport in
`protocol/SportTypes.kt` one at a time, and building it found a real gap: 21 repetition sports were falling through
to the permissive default and were safe only because such sessions carry no GPS distance. They are now named.

**How the last column maps to the code** (`StrideCalibration.sportGait`):

| Gate | Meaning |
|---|---|
| `ANY` | Footfalls. Windows are classified individually, so the session can teach a walking stride, a running stride, or both. |
| `WALK_ONLY` | A walking sport. Every usable window teaches the walking stride, however fast the wearer gets down a hill. |
| `STROKES` | The arms drive the effort. The count is a genuine stroke count, worth showing as a rate, but it is not a stride. |
| `REPS` | Repetitions rather than travel. The wrist rises and falls once per rep and the session goes nowhere. |
| `NONE` | The hands are still or busy with something unrelated. The count is noise and should not be shown at all. |

| id | Sport | What the wrist actually sees | Does the step counter measure anything? | Handled in code? |
|---|---|---|---|---|
| `0x01` | Outdoor Running | Swings in time with each stride | **Yes** — footfalls | Yes, `ANY` |
| `0x02` | Cycling | Hands gripping the bars, almost motionless | **No** — nothing usable | Yes, `NONE` |
| `0x04` | Swimming | Full stroke cycle, one arm revolution per stroke | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x05` | Badminton | Sharp racket swings between short shuffles | Partly — steps inflated by swings | Yes, `ANY` |
| `0x07` | Tennis | Racket swings and serves between sprints | Partly — steps inflated by swings | Yes, `ANY` |
| `0x08` | Hiking | Swings with the stride; poles or scrambling add motion | **Yes** — footfalls | Yes, `WALK_ONLY` |
| `0x09` | Walking | Swings with the stride | **Yes** — footfalls | Yes, `WALK_ONLY` |
| `0x0A` | Basketball | Dribbling and shooting over constant footwork | Partly — steps inflated by swings | Yes, `ANY` |
| `0x0B` | Soccer | Swings while running; arms otherwise idle | **Yes** — footfalls | Yes, `ANY` |
| `0x0C` | Baseball | Long idle spells, then a bat swing or throw | Partly — real steps, but few | Yes, `ANY` |
| `0x0D` | Volleyball | Repeated overhead arm swings, little travel | Partly — repetitions, not travel | Yes, `REPS` |
| `0x0E` | Cricket | Bowling and batting actions between standing | Partly — real steps, but few | Yes, `ANY` |
| `0x0F` | Rugby | Swings while running, plus contact | **Yes** — footfalls | Yes, `ANY` |
| `0x10` | Hockey | Stick work with a bent posture over skating or running | **Yes** — footfalls | Yes, `ANY` |
| `0x12` | Spinning | Hands fixed on the bars; legs do the work unseen | **No** — nothing usable | Yes, `NONE` |
| `0x13` | Yoga | Slow, deliberate limb movement, held poses | **No** — nothing usable | Yes, `NONE` |
| `0x14` | Sit-ups | Torso curls; the wrist rises and falls each rep | Partly — repetitions, not travel | Yes, `REPS` |
| `0x15` | Treadmill | Swings with the stride, indoors | **Yes** — footfalls | Yes, `ANY` |
| `0x17` | Boating | Paddle stroke, one arm cycle per stroke | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x18` | Jumping Jacks | Arms sweep overhead once per jump | Partly — repetitions, not travel | Yes, `REPS` |
| `0x19` | Free Training | Whatever the session contains; unpredictable | Partly — repetitions, not travel | Yes, `REPS` |
| `0x1B` | Indoor Running | Swings with the stride, indoors | **Yes** — footfalls | Yes, `ANY` |
| `0x1C` | Strength Training | Lifts and presses, one arc per repetition | Partly — repetitions, not travel | Yes, `REPS` |
| `0x1E` | Horse Riding | Hands on the reins, rising to the horse's rhythm | **No** — nothing usable | Yes, `NONE` |
| `0x1F` | Elliptical | Moving handles push and pull with the legs | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x22` | Boxing | Punches thrown in combinations | Partly — repetitions, not travel | Yes, `REPS` |
| `0x23` | Outdoor Walking | Swings with the stride | **Yes** — footfalls | Yes, `WALK_ONLY` |
| `0x24` | Trail Running | Swings with the stride over uneven ground | **Yes** — footfalls | Yes, `ANY` |
| `0x25` | Skiing | Poling: one plant per push | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x27` | Taekwondo | Strikes and blocks in sequence | Partly — repetitions, not travel | Yes, `REPS` |
| `0x28` | VO2 max Test | Swings with the stride during a graded effort | **Yes** — footfalls | Yes, `ANY` |
| `0x29` | Rower | Catch, drive, finish, recovery: one arm cycle per stroke | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x2C` | Athletics | Depends on the event; usually running arms | **Yes** — footfalls | Yes, `ANY` |
| `0x2D` | Waist Training | Twisting torso work, arms following | Partly — repetitions, not travel | Yes, `REPS` |
| `0x2E` | Karate | Strikes and blocks in sequence | Partly — repetitions, not travel | Yes, `REPS` |
| `0x34` | Physical Training | Mixed gym work, arms moving irregularly | Partly — repetitions, not travel | Yes, `REPS` |
| `0x35` | Archery | Draw and hold, then still | **No** — nothing usable | Yes, `NONE` |
| `0x37` | Aerobic Combo | Choreographed arm patterns with step patterns | Partly — repetitions, not travel | Yes, `REPS` |
| `0x39` | Street Dancing | Fast, irregular arm movement with footwork | Partly — repetitions, not travel | Yes, `REPS` |
| `0x3A` | Kick Boxing | Punches and kicks in combinations | Partly — repetitions, not travel | Yes, `REPS` |
| `0x3F` | Handball | Throwing arm over constant running | **Yes** — footfalls | Yes, `ANY` |
| `0x40` | Bowling | A few approach steps, then one swing | Partly — real steps, but few | Yes, `ANY` |
| `0x41` | Racquetball | Racket swings in a confined court | Partly — steps inflated by swings | Yes, `ANY` |
| `0x44` | Snowboarding | Arms out for balance, no rhythm | **No** — nothing usable | Yes, `NONE` |
| `0x46` | American Football | Sprints and blocks; arms drive then stop | **Yes** — footfalls | Yes, `ANY` |
| `0x48` | Fishing | Casting occasionally, otherwise still | **No** — nothing usable | Yes, `NONE` |
| `0x4B` | Golf | A long walk punctuated by swings | Partly — steps inflated by swings | Yes, `WALK_ONLY` |
| `0x4D` | Downhill Skiing | Poles planted on turns, arms mostly braced | **No** — nothing usable | Yes, `NONE` |
| `0x4E` | Snow Sports | Varies by discipline; usually braced | **No** — nothing usable | Yes, `NONE` |
| `0x50` | Core Training | Floor work: planks, twists, raises | Partly — repetitions, not travel | Yes, `REPS` |
| `0x51` | Skating | Arms swing side to side for balance | **No** — nothing usable | Yes, `NONE` |
| `0x55` | Kickboxing Aerobics | Punch and kick patterns to a beat | Partly — repetitions, not travel | Yes, `REPS` |
| `0x56` | Lacrosse | Cradling and passing over constant running | **Yes** — footfalls | Yes, `ANY` |
| `0x58` | Wrestling | Grappling; continuous irregular motion | Partly — repetitions, not travel | Yes, `REPS` |
| `0x59` | Fencing | Lunges with a still, extended sword arm | Partly — repetitions, not travel | Yes, `REPS` |
| `0x5A` | Softball | Long idle spells, then a bat swing or throw | Partly — real steps, but few | Yes, `ANY` |
| `0x60` | Pickleball | Paddle swings in a small court | Partly — steps inflated by swings | Yes, `ANY` |
| `0x61` | HIIT | Whatever the interval is: often both | Partly — repetitions, not travel | Yes, `REPS` |
| `0x62` | Shooting | Raise, hold, fire; deliberately motionless | **No** — nothing usable | Yes, `NONE` |
| `0x63` | Judo | Grips and throws; continuous irregular motion | Partly — repetitions, not travel | Yes, `REPS` |
| `0x65` | Skateboarding | One arm pushes, then coasting with arms out | **No** — nothing usable | Yes, `NONE` |
| `0x68` | Parkour | Vaults, swings and sprints in bursts | **Yes** — footfalls | Yes, `ANY` |
| `0x6A` | Surfing | Paddling out, then riding with arms out | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x6B` | Snorkeling | Arm strokes with fins doing much of the work | **Yes** — strokes, not steps | Yes, `STROKES` |
| `0x6C` | Pull-up | One pull per repetition, wrist travels far | Partly — repetitions, not travel | Yes, `REPS` |
| `0x6D` | Push-up | One press per repetition, wrist on the floor | Partly — repetitions, not travel | Yes, `REPS` |
| `0x6F` | Rock Climbing | Reaching and pulling, irregular and slow | **No** — nothing usable | Yes, `NONE` |
| `0x71` | Bungee Jumping | A fall, then hanging; nothing rhythmic | **No** — nothing usable | Yes, `NONE` |
| `0x72` | Long Jump | A short run-up, then a single jump | Partly — real steps, but few | Yes, `ANY` |
| `0x73` | Marathon | Swings with the stride for hours | **Yes** — footfalls | Yes, `ANY` |

## What this table changed

- **21 sports gained an explicit gate.** Volleyball, sit-ups, jumping jacks, free training, strength training,
  boxing, taekwondo, waist training, karate, physical training, aerobic combo, street dancing, kick boxing, core
  training, kickboxing aerobics, wrestling, fencing, HIIT, judo, pull-ups and push-ups all previously fell through
  to `ANY`. Nothing broke, because a gym session has no GPS distance and so produces no windows, but the refusal
  was an accident of the data rather than a decision. Now the app says why.
- **Cycling was separated from rowing.** Both refuse to calibrate, for opposite reasons: a rower's arms follow the
  effort, so the count is real; a cyclist's hands are on the bars, so a wrist cannot see the pedals at all.

## Known imprecision, honestly

- **"Steps inflated by swings"** covers tennis, badminton, racquetball, pickleball, basketball and golf. A racket
  swing or a golf swing looks enough like a step to be counted. Golf is still treated as a walking sport, because a
  round is mostly walking, but a stride measured from one reads slightly short.
- **"Real steps, but few"** covers baseball, softball, cricket, bowling and long jump: the wearer stands still for
  most of the session, so the count is honest but thin.
- **Boating, surfing and snorkeling are guesses.** Nobody has recorded one on this watch. They are grouped by how
  the arms move, not by measurement.
- Everything here rests on one wearer's data. The classifier thresholds in `StrideCalibration` are provisional.

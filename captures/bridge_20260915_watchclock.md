# Does the watch have a workout clock of its own? — 2026-09-15

Controlled hardware experiment on the moto g05 (`MOTO_SERIAL`) driving the watch through RyzeBridge, with the
Pixel's Bluetooth off so the watch was free. Our app was `pm disable-user`'d on the moto for the duration so it
could not fight the bridge for the GATT link (re-enabled and re-granted afterwards).

**Question.** Buzz saw the during-workout elapsed-time screen behave non-linearly when he paused for ~2 s. Does
the watch run its own stopwatch, or is that screen just rendering the `FD 44` metrics the phone pushes at 1 Hz?
This matters because the answer decides whether the watch holds any state worth querying to fix the
pause/resume announcement lag.

## Runs

| capture | what was sent |
|---|---|
| `bridge_20260915_182028.txt` | vibrate, start, ramp 0:05→0:12, **3 s silence**, 0:13→0:16, hold 10:00, hold 0:30, stop |
| `bridge_20260915_182142.txt` | start, ramp 0:05→0:10, hold 10:00 (5 s), hold 0:30 (4 s), **5 s silence**, stop |
| `bridge_20260915_182307.txt` | start, hold 55:55 for 12 s, stop |
| `bridge_20260915_182339.txt` | start, hold 55:55 (5 s), **9 s silence**, stop |
| `bridge_20260915_182427.txt` | start, hold 55:55 (5 s), **11 s silence**, bare `FD 22`, bare `FD 33`, stop — **the decisive one** |

## Findings — all [V]

1. **The elapsed-time screen is a pure mirror of `FD 44`.** Buzz confirmed on the wrist that it counted
   0:05→0:10, then **jumped to 10:00**, then went **backwards to 0:30**, purely because that is what we pushed.
   The watch imposes no sanity check on the duration, forwards or backwards.

2. **The watch keeps no workout clock of its own.** In `bridge_20260915_182427.txt`:

   ```
   18:24:34.497  -->  fd44 0101 003737 0000 0000 0000    (55:55, our last push)
                      ... 11 s of complete silence ...
   18:24:45.551  -->  fd22 0101                          (bare 4-byte pause)
   18:24:46.134  <--  fd22 0101 003737 0000 0000 0000    t=00:55:55
   18:24:48.141  -->  fd33 0101                          (bare 4-byte resume)
   18:24:48.972  <--  fd33 0101 003737 0000 0000 0000    t=00:55:55
   ```

   After 11 s of silence it still reports 55:55 — neither creeping to 56:06 (free-running from our value) nor
   reporting the ~20 s the session had actually been running. It stores the last `FD 44` and parrots it back.

3. **A watch control reply is our own `FD 44` with the opcode swapped.** `fd22 0101 003737…` is byte-identical
   to the `fd44 0101 003737…` we sent, differing only in byte 1. This confirms on hardware what the capture
   analysis of `pixel_run_20260905` had already shown (there the replay even carried the phone's GPS-derived
   pace `153a`, which the watch cannot compute).

4. **The watch transmits no `FD 44` of its own, ever.** Across every silence window it sent only its realtime
   HR pushes (`FD 01 <hr>`), never a metrics packet.

5. **No self-terminating workouts.** Every `FD 00` in these logs is ours; the watch never ended a session by
   itself. (Buzz thought it had; it was our own stop at the end of the first run.)

## Consequences

- The `<dur>` field of a watch-originated `FD 22`/`FD 33` carries **no information the phone did not just
  supply**, so it cannot distinguish a genuine wrist press from the watch's junk flood. See PROTOCOL.md §FD row
  for the measured overlap (genuine 0-26 s, junk 0-17 s — junk is a strict subset). Do not try this again.
- Buzz's non-linearity is explained: the watch freezes its display locally on a wrist pause, but the app keeps
  pushing `FD 44` for the 8 s its debounce ignores the press, so newer numbers overwrite the frozen display and
  the time appears to stand still and then jump forward. Same root cause as the pause/resume announcement lag.
- Anything that fixes the lag must get its authority from somewhere other than this field — `FD AA`
  (`FD AA <state> <type>`) is the remaining candidate and is still unverified during a live workout.

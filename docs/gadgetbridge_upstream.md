# Notes for upstreaming to Gadgetbridge (GloryFit driver)

Everything below was verified on a Ryze Wave (RTL8763-family, firmware RH280RGAV008949, UTE / "yc pedometer" SDK
protocol) with captures from the phone bridge and from the vendor app's own logcat; see `docs/PROTOCOL.md` for the
byte-level reference ([V] = verified on this watch). Gadgetbridge file names refer to
`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/gloryfit/` at commit 77a3fcd.

1. **HR history record, byte 1 is the year high byte, not a marker.** `GloryFitFetcher.handleHeartRate` accepts an
   18-byte record when `value[1] == FETCH_DATA (0x07)`. On the Ryze Wave the record is
   `F7 yyyy MM dd HH <12 × hr>`: byte 1 is `0x07` only because 2020–2047 all start with `0x07`. Suggest matching on
   length and decoding `yyyy` from bytes 1–2 (the same layout as the `B2` steps record).
   The 12 values are 10-minute bins ending at HH:00 (the last value is the bin ending on the hour), `0xFF` = no reading.

2. **Realtime HR needs the mode command first.** `E5 11` alone produces nothing; `D6 02` (dynamic mode), then ~1.5 s,
   then `E5 11` streams `E5 11 00 <hr>` once per second after a ~10 s warm-up; `E5 00` stops it. `D6 01` (static)
   followed by `E5 11` never produced a sample. Gadgetbridge's `onEnableRealtimeHeartRateMeasurement` is still a TODO.

3. **Realtime workout packet: byte 1 is the sport type.** The 14-byte `FD <type> <hr> cal16 pace_min pace_sec steps24
   count16 km km/100` push carries the sport id in byte 1 (`FD 23 5C …` during an Outdoor Walking workout, `FD 01 …`
   during Outdoor Running). Only the length (14) identifies it: control echoes are 4 bytes (`FD 11/00/33 <type> <ivl>`)
   and the pause echo is 13 bytes. Sport ids come from the `FD 48 AA` list reply (`(id, enabled, position)` triples,
   70 on this watch); the id table is `docs/PROTOCOL.md` §6c.

4. **Feature bitmap.** The 20-byte read of `33F1` is seven big-endian words split from the end (FL7 = 2 bytes, then FL6…FL1
   3 bytes each); the second bitmap comes from the `34F1` read. Useful bits: FL4 & 0x2000 = fetch commands accept a
   since-timestamp, FL4 & 0x40000 = sleep via `31 01`, FL5 & 2 = the watch accepts `FD 44` live metrics, FL1 bit 0 =
   password handshake (`D5`) required (clear on the Ryze Wave). Values observed: `docs/PROTOCOL.md` §6b.

5. **Notifications (`C5`).** Chunk 0 is `C5 00 <type> <total_bytes>` + 16 bytes of UTF-16BE, then `C5 <idx>` + 16 bytes,
   then `C5 FD`; the watch acks every chunk with `C5 <idx>` and the end with `C5 FD <type> <total>`. `total_bytes` is one
   byte, so a message is at most 127 UTF-16 code units. Sending the next chunk only after its ack was reliable; type 0 is
   an incoming call and should not be used for app notifications.

6. **SpO2 spot test (`34 11`).** The watch acks, then ~0.3 s later sends a spurious `34 00 FF FF`, then re-announces
   `34 11`, and the real result `34 00 00 <pct>` arrives ~57 s later. The early `FF FF` must be ignored or every test
   looks like a failure. SpO2 auto-sampling is `34 03 <en> <interval16 minutes>` (16-bit interval).

7. **Timed HR test `D6 10 <hours>`** is neither echoed nor acked by this watch.

8. **Sleep** arrives as `31 01` → `31 01 <date> <n>` + `32` stage records on the data channel (`34F2`) → `31 02`;
   stage codes 1 = deep, 2 = light, 3 = REM, 4 = awake (mapping still to be cross-checked against the vendor app).

Reference implementations: `ryzewave/protocol.py` (Python codec, tests in `tests/`) and the Kotlin `protocol/` package of
the Android app in `ryzeapp/`.

# Ryze Wave BLE protocol notes

Status legend: **[V]** verified against our watch (from Ryze Fit logcat captures on 2026-09-04) · **[GB]** from Gadgetbridge / its dissector (verified on other GloryFit watches) · **[SDK]** from the decompiled Ryze Fit app, unverified.

## 1. Transport

Two radios, two jobs:

- **Classic BT (BR/EDR)** [V]: HFP (`0x111E`), HSP, A2DP source (`0x110A`), AVRCP (`0x110C/0E/0F`), PBAP client (`0x112E`), SPP (`0x1101`), PnP, plus unknown SDP UUIDs `0x5536` and `0x2222`. This carries phone-call audio, answer/hang-up, music streaming. Standard profiles.
- **BLE GATT** [V UUIDs, GB/SDK semantics]: everything else.

| Service | Characteristic | Dir | Purpose |
|---|---|---|---|
| `000055FF` | `000033F1` | write (no rsp) | commands, phone -> watch |
| `000055FF` | `000033F2` | notify | responses / events, watch -> phone |
| `000056FF` | `000034F1` | write | "data" channel (contacts, canned SMS, sleep stages, timezone, sports lists…) |
| `000056FF` | `000034F2` | notify | data channel responses |
| `000057FF` | `000035F1` / `000035F2` | write / notify | Alipay / payments (ignore) |
| `0000FEF5`, `0000D0FF-3C17-…`, `00006287-3C17-…` | — | — | Dialog SUOTA / Realtek OTA & DFU. **Do not touch.** |
| `0000FFF0` / `FFF6` | — | — | blood-pressure calibration sub-protocol (probably absent on this watch) |

GATT reads with meaning [SDK]:
- **Read `33F1`** returns 20 bytes = the *feature bitmap* (see §3).
- **Read `34F1`** returns 2 bytes big-endian = max packet length for the data channel.

MTU: Gadgetbridge requests 247. Packets are raw bytes, no framing, no checksum; first byte is the opcode.

## 2. Connect sequence

Gadgetbridge (works on BT103 etc.) [GB]:
```
requestMtu(247); notify 33F2 on; notify 34F2 on
-> A1                       version
-> A2                       battery
-> A3 yy yy MM dd HH mm ss  set time (7 bytes, BE year)
-> A9 …                     user info (height/weight/goal/age/gender…)
-> 3F 04 01 <u32 steps>     goals; 3F 03 = calories, 3F 05 = distance (m)
-> D3 …                     sedentary reminder
-> AF AB <lang>             language
-> A0 01 <01=24h|02=12h>    time format
-> F7 01|02                 continuous HR on/off
-> 34 03 … / 34 04 …        SpO2 auto / time period
-> D7 …                     reject-call-with-button
-> 52 00|01                 SMS quick reply
-> 37 AC … / 37 AD          SOS contact
```

**Observed on the Ryze Wave, 2026-09-04 18:50 (reconnect by address, no bond, no password)** [V] —
full log in `captures/reconnect_20260904_185054.md`:
```
read 33F1 -> 080A642A210C3943756EDFFED921005D784BA1D4   feature bitmap (FL1=0x4BA1D4: password bit CLEAR)
read 34F1 -> 00F4 00…00 400000                          max packet len 244; 2nd bitmap word = 0x400000
notify 33F2, notify 34F2, requestMtu(247) -> 247
-> A3 07EA 09 04 12 32 38        set time 2026-09-04 18:50:56  (echoed)
-> 44 0F                          mood/sensor query  <- 44 0F 00 01 00 04 "VP60"
-> A1                             version
-> 38 01 02 00 D2 5D AE 5D (34F1) BT3 query <- 38 01 "Ryze Wave(ID-91E5)" 00 00 <MAC> 01 00 00  (classic name+MAC for HFP pairing)
-> BB                             has-content-push?
-> 26 01                          online dial config
-> BE 01 ; BE 02                  quick-switch list / status
-> A9 00AF 004B 05 00 00 1F40 01 A5 00 28 01 00 02 01 46   user info (175 cm, 75 kg, 8000 steps, HR alert 165/70, age 40, male, °C)
-> A2                             battery
-> DB AA                          push-message display query
-> B2 FA                          steps history
-> 31 01                          sleep history (stages arrive on 34F2 as 32 …)
-> F7 FA 07EA 09 04 12 26         24h HR since 2026-09-04 18:38
-> FD AA                          current workout?
-> FD FA 07EA 09 04 00 00         workout history since 2026-09-04
   … (the app repeats the block above once, ~9 s later)
-> 34 FA                          SpO2 history
-> 34 03 01 00 0A                 SpO2 auto on, every 10 min
-> 34 04 01 00 01 17 3B           SpO2 period 00:01-23:59
-> D3 01 3C 02 03 01 00 08 00 16 00 01   sedentary reminder
-> D4 00 3C 02 03 01 00 08 00 16 00 01   drink-water reminder (off)
-> D1 0A 00                       find-phone stop
-> F7 01                          continuous HR on
-> 29 00 07EA 09 04 05 1C         physiological period (off)
-> AF AA                          language list
-> 44 FA ; 44 03 00 00 0A ; 44 04 00 08 00 15 00   mood/stress history + auto settings
-> 46 FA 05 <idx> <len> <ascii> ×5 ; 46 FD 00 (34F1)  canned replies
-> 52 00                          SMS quick reply off
-> 37 AE (34F1)                   SOS contacts clear
-> FD 48 AA (34F1)                sport list  <- FD 48 AA 00 + (id,enabled,order)×n (70 sports)
```

Ryze Fit SDK (generic) does the same but **first**:
```
read 33F1 (feature bitmap) ; read 34F1 (max len) ; enable notifications
if featureList1 & 0x01:                       # "password supported"
    if stored_password == "1234":  -> D5 02   # ask watch to show a pairing code
    else:                           -> D5 01 k0 k1 k2 k3   # k = password ASCII XOR "UTE8"
    wait for D5 01 before sending anything else
-> A1, A9, AA, A2, …
```
[V] The Ryze Wave's FL1 has the password bit clear, so `D5` is **not** needed here (kept in the client for other UTE watches).
Gadgetbridge's failure on this watch is therefore something else; see §8.

## 3. Feature bitmap [SDK]

Read of `33F1` → 20 bytes. Hex string of 40 chars, split from the **end**:
```
bytes[0:2]   functionList7
bytes[2:5]   functionList6
bytes[5:8]   functionList5
bytes[8:11]  functionList4
bytes[11:14] functionList3
bytes[14:17] functionList2
bytes[17:20] functionList1   (24-bit BE int; bit0 = password, bit8 = running, bit10 = 1h sleep merge)
```
`GetFunctionList.isSupportFunction(n)` = `(functionList1 & n) == n`; `_Fifth`, `_Sixth` etc. index the other words.

## 4. Pairing / password handshake (`D5`) [SDK]

| Phone -> watch | Meaning |
|---|---|
| `D5 01` | query password status |
| `D5 01 k0 k1 k2 k3` | authenticate: `k[i] = ascii(password)[i] XOR ascii("UTE8")[i]` |
| `D5 02` | request captcha: watch displays a code |
| `D5 02 <ascii digits>` | input password (mode 1) |
| `D5 03 <ascii digits>` | set password (mode ≠ 1) |

| Watch -> phone | Meaning |
|---|---|
| `D5 01` | verified / OK → app marks connected, starts config burst |
| `D5 02` | password required → app opens keypad UI |
| `D5 03` | pairing timed out |
| `D5 04` | (callback 42, unknown) |
| `D5 FF` | wrong password → keypad UI, "verification fail" |

Default stored password is `"1234"`. The app persists the accepted one in SharedPreferences.

## 5. Command opcodes

Full method→opcode map from the SDK: `captures/sdk_opcode_map.txt`. Highlights (`..` = payload follows):

| Op | Meaning | Src | Notes |
|---|---|---|---|
| `A1` | version | GB | reply `A1 <ascii>` e.g. `RH281LWOV008474` |
| `A1 01` | DSP version | SDK | reply `A1 01 <ascii>`; decoders strip the `01` sub-byte (`dec_version` / `Protocol.decVersion` both give the bare ASCII) |
| `A2` | battery | GB | reply `A2 <pct> [01=charging]`; spontaneous while charging |
| `A3 yyyy MM dd HH mm ss` | set time | GB | echoed back as ack |
| `A9 …` (19 B) | user info | GB | height u16 cm, weight u16 kg, 05 00 00, steps goal u16, raise-wrist, HR-high, 00, age, gender(1=M,2=F), 00, temp unit(1=F,2=C), 01, HR-low |
| `AA` | step/sleep status | SDK | |
| `AB 00 00 00 01 xx yy zz` | vibrate: call/notify/find | GB | also alarm slots: `AB <days> HH mm 02 0A 02 00 <slot>` |
| `AD` | **factory reset / delete all** | GB/SDK | never send casually |
| `AF AA` / `AF AB <n>` | language list / set | GB | |
| `B1 …` (18 B) | realtime steps push | GB | same layout as B2 record |
| `B2 FA` → `B2 …`×n → `B2 FD xx` | fetch steps | GB | 18-byte record: `B2 yyyy MM dd HH total16 rs re ? run16 ws we ? walk16` |
| `B3 FA` | fetch sleep (legacy) | SDK | |
| `B7/B9/F3/F4 FA` | fetch swim / skip / ride / sports-mode data | SDK | |
| `BA 01/02` | UV test / last UV | SDK | |
| `BD 01/00/AA` | HRH sport mode start/stop/query | SDK | |
| `BE 01/02` | quick-switch list / status | SDK | |
| `C1 04` | call ended | GB | |
| `C4 01/03`; `C4 02` ← | camera open/close; watch says shutter | GB | |
| `C5 00 <type> <total_bytes> <utf16be 16 B>` , `C5 <idx> <utf16be 16 B>` … , `C5 FD` → `C5 FD <type> <total>` | notification text (watch acks each chunk with `C5 <idx>`) | V | verified 2026-09-05 07:39-07:40 from the bridge: 80-byte generic (type 4, 5 chunks) and 240-byte SMS (type 3, 15 chunks) both fully acked; total is one byte, so max 255 bytes = 127 UTF-16 chars; type codes §6 |
| `C6 …` | notification (alt/newer, chunked) | SDK | |
| `C7 …`, `C8 FA` | blood pressure config / fetch | SDK | |
| `CA`, `CB …` | weather | SDK | |
| `CD 01` | UI resource version | SDK | |
| `D1 0A [01\|00]` ← | find phone start/stop | GB | |
| `D1 02` ← | hang up | GB | |
| `D1 07/08/09/0D/0E` ← | music play/next/prev/vol+/vol- | GB | |
| `D1 0C …` | music control → watch | SDK | |
| `D2 …` | SMS switch | SDK | |
| `D3 en dur 02 03 01 00 HH mm HH mm lunch` | sedentary reminder | GB | |
| `D4 …` | drink-water reminder | SDK | |
| `D5 …` | password (§4) | SDK | |
| `D6 01` / `D6 02` | HR measurement mode: static (spot test) / dynamic (continuous) — sent 1.5 s before `E5 11` by the app's HR screen | V | `D6 02` + `E5 11` streams; `D6 01` + `E5 11` produced nothing in 75 s on this watch |
| `D6 10 <hours>` | "timed HR test": an extra spot measurement every 1/2/6/12 **hours** (app menu), `00` = off (app default: off, 2 h) | SDK | **no echo/ack observed** (`D6 10 0A` and `D6 10 00` both timed out after 4 s, captures/bridge_d610_restore.txt) [V]. Not the 10-minute series — that cadence is fixed by the continuous mode `F7 01` and cannot be changed |
| `D7 …` | do-not-disturb / reject-with-button | GB/SDK | |
| `DB AA` | push-message display query | SDK | |
| `DF` | HV screen control | SDK | |
| `D6 02` → wait 1.5 s → `E5 11` → ; `E5 11 00 <hr>` ← once per second after ~10 s warm-up ; `E5 00` stop (stream ends within ~3 s) | live HR (dynamic mode) | V | verified 2026-09-04 19:39 through the phone: 94/90/88/91 bpm. A bare `E5 11`, or `D6 01` (static) + `E5 11`, produced nothing within 30 s |
| `E6 FA` | fetch (single) HR data | SDK | |
| `E9 …` | body composition | SDK | |
| `F7 01/02` | continuous HR on/off | GB | |
| `F7 03 yyyy MM dd HH <bin> <hr>` ← | automatic HR sample push, `<bin>` = 10-minute slot 0-5 within hour HH (pushed 18:48 as `12 04` = 18:40 → 98; 19:58 as `13 05` = 19:50 → 89) | V | the SDK treats byte 7 as minutes, which is wrong by our two observations; HH=0 means 24:00 of the previous day |
| `F7 04 yyyy MM dd HH mm <max> <min> <avg>` ← | today's HR summary push (seen: 105/58/75) | V/SDK | `rate24HourMaxMinAverageOperate` |
| `F7 FA [yyyy MM dd HH mm]` → `F7 yyyy MM dd HH <12 × hr>` (18 B) → `F7 FD xx` | fetch 24h HR, 10-min bins ending HH:00 | V | since-stamp only if feature FL4&0x2000; zeros = all. **Note** Gadgetbridge treats byte 1 (`07`) as a data marker; it is the year high byte (0x07E9=2025, 0x07EA=2026). |
| `F9 AA`, `F9 …` | watch UI pages query/show-hide | SDK | |
| `FB/FC 00/01/FD` | HR / wrist-turn calibration | SDK | |
| `FD 11 <type> <ivl>` / `FD 22 <type> <ivl> hh mm ss …` (13 B) / `FD 00 <type> <ivl>` | workout start / pause / stop; watch echoes the same bytes | V | `type` = sport id from the watch's own list (§6c; 1 = Outdoor Running, 0x23 = Outdoor Walking, 2 = Cycling — all three started the matching mode on the wrist 2026-09-04/05); ivl = HR report interval s; `FD 33` resume is SDK-documented, not captured. The watch also sends these **unsolicited** when the user presses pause/resume/stop **on the watch** (not just as an echo of a phone command): the app tells the two apart by a short expected-echo window after its own send and drives its own pause/resume/stop from a watch-originated one (`WatchApiImpl` → `WatchEvent.WorkoutControl`), without echoing it back |
| `FD 44 <type> <ivl> hh mm ss cal16 km km_frac2 pace_min pace_sec` | phone → watch live workout metrics, once per second (echoed back) | V/SDK | distance/pace come from **phone GPS**, this is where the vendor app's bad distance is produced. `km_frac2` is the rounded hundredths; a fraction that rounds to 100 carries into `km` (2999.9 m → `03 00`, not `02 64`); pace above 99:59 /km is sent as `00 00` |
| `FD <type> <hr> cal16 pace_min pace_sec steps24 count16 km km_frac2` ← (14 B) | realtime workout data from the watch, irregular 1-11 s (~1/s while the sensor has a fresh reading). **Byte 1 is the sport type, not a constant 0x01**: an Outdoor Walking workout (`FD 11 23 01`) pushes `FD 23 5C 00…` (HR 92). Only the 14-byte length identifies this packet (control echoes are 4 B, pause 13 B) | V (type, HR) / SDK (other fields) | verified 2026-09-04 19:37 (type 1, 82-97 bpm) and 2026-09-05 08:23 (type 0x23); during a phone-driven workout the Ryze Wave zeros calories/pace/count/distance, but **steps24 does rise** — verified 0 → 3933 over the 2026-09-05 17:20 run (the watch does NOT put workout steps in the hourly `B1`/`B2` bins), so the app takes `Workout.steps` from the max of this field. Offsets from the vendor SDK's realtime parser |
| `FD AA` → `FD AA <state> <type>` | query current workout | SDK | |
| `FD FA [since]` | fetch workout history | SDK | |
| `FD 48 AA` (34F1) → `FD 48 AA 00 <(id, enabled, position)×n>` … `FD 48 AA FD` | sport list: the watch's sport-mode menu, 70 entries on the Ryze Wave | V | ids = §6c; position = menu order (1-based); `FD 48 <…>` writes reorder/hide entries (SDK, not used) |
| `24 …` | temperature | SDK | |
| `26 01/02`, `27 …` | online dial (watch face) config / upload | SDK | |
| `28 …` | ECG | SDK | |
| `29 …` | physiological (menstrual) period | SDK | |
| `31 01` → `31 01 yyyy MM dd <n>` → (34F2) `32 <HH mm stage 01 dur16>×n` → `31 02` | fetch sleep with stages | GB | |
| `33 01/02` | account id | SDK | |
| `34 03 en <min16>` / `34 04 en HH mm HH mm` | SpO2 auto-sampling on/off + interval (16-bit minutes) / active period | V | vendor app menu offers 10/30/60/120/180/240/360 min (default 10) and sets `34 03 01 00 0A`, 00:01-23:59; each auto sample wakes the screen and vibrates briefly (Buzz, 2026-09-04). `34 03 01 00 05` (5 min) was acknowledged on 2026-09-04 19:51 but Ryze Fit was opened at 19:52 and re-sent `00 0A` at 19:53:07, so whether the watch honours values outside the menu is still unconfirmed. Lesson: any app that connects re-applies its sampling settings, so ours must too |
| `34 11` → ; `34 11` ← ack ; `34 00 FF FF` ← **spurious, ~0.3 s later** ; `34 11` ← (re-announce) ; ~57 s later `34 00 00 <spo2>` ← result (`34 00 00 61` = 97 %) | SpO2 spot test | V | verified twice from the phone on 2026-09-04 (19:40:57, 19:43:43). Ignore the early `FF FF`; a real failure is a `34 00 FF FF` after the measuring period. The watch wakes its screen for the measurement. `34 AA` → `34 AA FF` when idle. |
| `34 FA` → `34 FA yyyy MM dd HH mm <12 × spo2>` (20 B) → `34 FA FD xx` | fetch SpO2, 10-min bins ending HH:mm | V | same year-byte caveat |
| `37 FA <n>` … `37 FB …`(244 B) … `37 FC FD` | contacts upload (34F1) | GB | |
| `37 AC …` / `37 AD FD xx` / `37 AE` | SOS contact set/end/clear | GB | |
| `38 02 01\|00` (34F1) | classic Bluetooth (calls/audio) on / off (echoed) | V | tried as a fix for the Linux link drops (`38 02 00`); the LE link still dropped, so classic paging is not the cause (captures/bluez_linkdrop_notes.md) |
| `38 01 02 00 <4 rnd>` (34F1) → `38 01 <name:20> <mac6> <bt3_on> <b29> <b30>` | classic BT info query | V | seen `… 7802B73791E5 01 00 00` (radio on, phone not paired) and `… 01 01 01` right after `38 02 01` with the phone bonded; `38 02 00/01` is echoed as ack |
| `3A 01 …`, `3A FA` | music state | GB | |
| `3E …` | BP calibration (cSBp*) | SDK | |
| `3F 03/04/05 01 <u32>` | goals cal/steps/dist | GB | |
| `41 …` | wash-hands reminder | SDK | |
| `43 …` | time zone | SDK | |
| `45 FA <len> <number>` | quick-reply target | GB | |
| `46 FA <total> <idx> <len> <ascii>` … `46 FD 00` | canned messages (34F1) | GB | |
| `47 FA`, `47 73 01/00` | GPS data fetch / GPS mode | SDK | |
| `51 …`, `51 AA` | labelled alarm clocks | SDK | |
| `52 <numlen> <num> <msglen> <msg>` ← | SMS quick reply from watch (34F2) | GB | ack `52 FE` |
| `55 …` | EL blood pressure | SDK | |
| `5B 00/01/FA` | blood sugar | SDK | |
| `60 …`, `68 …` | Alipay / WeChat pay | SDK | |

Response opcodes the SDK parses on `33F2`:
`29 31 34 37 3A 3D 3F 44 47 51 52 55 5B A0 A1 A2 A3 A9 AA AF B1 B2 B3 B7 B8 B9 BA BB BD BE C1 C3 C4 C5 C6 C7 C8 CB D1 D2 D5 DB E5 E6 E9 EB EC F3 F4 F7 F9 FB FC FD`
and on `34F2`: `32 34 38 3E 3F 43 44 45 46 4A 51 52 55 60 68 CB CD EC FD` (+ `01E3 01E4 01E9`).

## 6. Notification app types (`C5` byte 2) [GB]
0 call, 1 QQ, 2 WeChat, 3 SMS, 4 unknown app, 5 Facebook, 6 Twitter, 7 WhatsApp, 8 Skype, 9 Messenger, 10 Hangouts, 11 Line, 12 LinkedIn, 13 Instagram, 14 Viber, 15 KakaoTalk, 16 VK, 17 Snapchat, 18 G+, 19 Email, 20 ?, 21 Tumblr, 22 Pinterest, 23 YouTube, 24 Telegram, 25 no icon. Text is UTF-16BE, chunked to fit the MTU.

## 6a. History record timing, verified 2026-09-04 19:34 [V]
Records come every **2 hours** (HH = 20, 22, 00, 02, …), each with 12 ten-minute values, `0xFF` = no sample.
The hour byte is the **end** of the window: at 19:35 the `…09 04 14` (20 h) HR record already held 8 values
(18:10 … 19:20) followed by `FF`s for the not-yet-reached 19:30 … 20:00 slots. Same for SpO2 (`34 FA … 14 00`).
The very first record after the watch was put on (2026-09-03) was `F7 07EA 09 03 14 FF×11 56`: a single sample
86 bpm at exactly 20:00. Steps (`B2`) records are hourly totals with the hour being the start of the hour.
Sleep: `31 01 yyyy MM dd <n>` announces the session date (the morning), then one `32` packet on 34F2 with
`HH mm stage 01 dur16` × n; stages seen 1-4, times after noon belong to the previous evening.

## 6b. Feature bitmap words observed on the Ryze Wave [V]
From the app's own log on 2026-09-04 (word 1 not yet seen; read it with our client):
`FL2=0x005D78 FL3=0xFED921 FL4=0x756EDF FL5=0x0C3943 FL6=0x642A21 FL7=0x00080A` (+ an 8th word 0x400000 from the 34F1 read).
So: since-timestamp fetches = yes (FL4&0x2000), sleep via `31 01` = yes (FL4&0x40000), workout metric push = yes (FL5&2), account-ID pairing = no (FL5&4), steps via `B2 FA` (FL8&32 = 0).

## 6c. Sport ids [V]

The watch numbers its sport modes with the vendor's global ids (gaps are modes this model does not have). The `FD 48 AA`
reply lists 70 (id, enabled, position) triples; the names are the watch's own menu, read off the wrist in menu order
on 2026-09-05, and the count matched exactly. The same id is used in `FD 11/22/00/44 <type>` and as byte 1 of the
realtime push. Reference codec: `ryzewave/protocol.py` `SPORT_TYPES`.

| id | sport | id | sport | id | sport | id | sport |
|---|---|---|---|---|---|---|---|
| 0x01 | Outdoor Running | 0x02 | Cycling | 0x04 | Swimming | 0x05 | Badminton |
| 0x07 | Tennis | 0x08 | Hiking | 0x09 | Walking | 0x0A | Basketball |
| 0x0B | Soccer | 0x0C | Baseball | 0x0D | Volleyball | 0x0E | Cricket |
| 0x0F | Rugby | 0x10 | Hockey | 0x12 | Spinning | 0x13 | Yoga |
| 0x14 | Sit-ups | 0x15 | Treadmill | 0x17 | Boating | 0x18 | Jumping Jacks |
| 0x19 | Free Training | 0x1B | Indoor Running | 0x1C | Strength Training | 0x1E | Horse Riding |
| 0x1F | Elliptical | 0x22 | Boxing | 0x23 | Outdoor Walking | 0x24 | Trail Running |
| 0x25 | Skiing | 0x27 | Taekwondo | 0x28 | VO2 max Test | 0x29 | Rower |
| 0x2C | Athletics | 0x2D | Waist Training | 0x2E | Karate | 0x34 | Physical Training |
| 0x35 | Archery | 0x37 | Aerobic Combo | 0x39 | Street Dancing | 0x3A | Kick Boxing |
| 0x3F | Handball | 0x40 | Bowling | 0x41 | Racquetball | 0x44 | Snowboarding |
| 0x46 | American Football | 0x48 | Fishing | 0x4B | Golf | 0x4D | Downhill Skiing |
| 0x4E | Snow Sports | 0x50 | Core Training | 0x51 | Skating | 0x55 | Kickboxing Aerobics |
| 0x56 | Lacrosse | 0x58 | Wrestling | 0x59 | Fencing | 0x5A | Softball |
| 0x60 | Pickleball | 0x61 | HIIT | 0x62 | Shooting | 0x63 | Judo |
| 0x65 | Skateboarding | 0x68 | Parkour | 0x6A | Surfing | 0x6B | Snorkeling |
| 0x6C | Pull-up | 0x6D | Push-up | 0x6F | Rock Climbing | 0x71 | Bungee Jumping |
| 0x72 | Long Jump | 0x73 | Marathon | | | | |

Wrist check 2026-09-05 08:23: `FD 11 23 01` and `FD 11 02 01` each started a workout on the watch and `FD 00` stopped
it; the type 0x23 run pushed `FD 23 5C …` / `FD 23 5B …` (HR 92/91), which is what proved byte 1 of the realtime push
is the sport id. (Menu names for those two starts: see docs/APP.md sport picker notes.)

## 7. Open questions
- ~~Does our watch set the password bit?~~ No (FL1 = 0x4BA1D4).
- Which of the SDK-only opcodes does it answer? (start from `A1 01`, `CD 01`, `F9 AA`, `BE 01`, `FD AA`, `AF AA`)
- Sleep stage codes (seen 1-4; 2 dominates, 4 in short bursts — likely 1=deep? 2=light 3=REM? 4=awake) need checking against Ryze Fit's sleep screen for the night of 2026-09-03.
- Realtime steps/HR (`B1`, `E5`) — when does the watch push them?
- The unknown SDP UUIDs `0x5536` / `0x2222` on the classic side.

## 7a. Phone bridge results, 2026-09-04 19:34-19:38 [V]
- `keep 120`: link held for 2 min with zero drops; the watch moved the interval to 252 (315 ms) after ~10 s idle and back to 36 when traffic resumed.
- full history sync in 4 s; SpO2 spot test works but the first `34 00 FF FF` after the ack is bogus, the real value follows ~1 min later;
- workout: start/echo, `FD 01 <hr>` every second, per-second `FD 44` updates echoed, stop/echo.
- unsolicited: while a measurement is started on the watch itself, it streams `E5 11 00 <hr>` once a second (25 min seen with no phone command, `00` during another sensor test).
- 19:39-19:46: dynamic HR stream (`D6 02` + `E5 11`) and three SpO2 spot tests (97, 96, 96 %) from the phone; the bridge's `mark` statement skips the spurious early `34 00 FF FF`.

## 7b. Phone-side bridge [V]
`android/` (RyzeBridge) connects with `connectGatt(…, TRANSPORT_LE)`, `requestMtu(247)` and CCCD writes on 33F2/34F2,
exactly like the vendor SDK. On its first connect Android showed a **pairing request** (accepted by Buzz on
2026-09-04 19:31), so the watch or the Android stack asks for LE bonding on a fresh central; the vendor app's reconnects
were bond-less because the phone had "forgotten" the device. Bonding is not required by the protocol (the laptop talked
to the watch unbonded), but it is harmless and speeds up reconnects.

Moto g05 (Android 15), 2026-09-04 23:21: connected on the first attempt with **no pairing prompt**, PHY 2M,
connection interval 6-24 (7.5-30 ms), MTU 247, full init in 1.7 s. So bonding is optional and the Pixel's prompt
was incidental; the watch accepts any LE central.

## 8. Dual-mode gotcha (Linux/BlueZ) [V]
The watch advertises LE flags `0x02` (general discoverable) **without** "BR/EDR not supported", and its LE and classic
addresses are the same public MAC. BlueZ 5.72 therefore prefers the classic bearer on `Connect()`: you get an HFP/A2DP
link (and an unwanted SSP bond), no GATT, and bleak reports `Characteristic 000033f2 … was not found`. Fixes: set
`ControllerMode = le` in `/etc/bluetooth/main.conf` and restart bluetoothd (`tools/le_only.sh on`), or BlueZ ≥ 5.79's
`PreferredBearer` property. Android is not affected (the app calls `connectGatt(..., TRANSPORT_LE)`).

More BlueZ notes [V]:
- Idle (screen off, nobody connected) the watch advertises only every ~15 s, and BlueZ suppresses RSSI-only updates
  within 8 dB, so bleak scans usually never "see" it. Our client connects via the cached BlueZ device object instead
  (`/org/bluez/hci0/dev_78_02_B7_37_91_E5`), or scans with an RSSI filter which forces per-advertisement updates.
- The watch answers ATT requests slowly (~0.3-0.5 s each), so a full GATT discovery takes 30-40 s. BlueZ only persists
  the GATT cache for non-temporary devices: `bluetoothctl trust 78:02:B7:37:91:E5` once, after which connects take ~3 s.
- Our first successful exchange (2026-09-04 19:06): read `33F1` → same bitmap the app saw, `A1` → `RH280RGAV008949`,
  `A2` → 78 %, then a full history sync (`B2 FA`, `F7 FA 00…`, `34 FA`, `31 01`) decoded with `ryzewave/protocol.py`.

## 9. What the vendor app does with distance (the bug we want to fix) [SDK]
`PedometerUtils.calculateDistance(steps, type)` in `com/yc/pedometer/column/PedometerUtils.java`:
- default: `km = steps × height_cm × 0.418 / 100000` (stride = 0.418 × height; 175 cm → 73 cm per step, walking or running alike)
- if feature FL2 & 0x1000: walking stride = height × 0.410 (male) / 0.415 (female); running = height × 0.546 / 0.505.
  Ryze Wave FL2 = 0x005D78 → bit 0x1000 is set, so the gender-specific factors apply.
The watch never sends distance for daily steps (the `B2` records carry only step counts), so every daily distance
figure in the app is this stride formula on the phone. During workouts the phone's GPS track drives distance/pace and
is pushed to the watch with `FD 44` (see §5), which is where a bad track filter shows up on the watch face too.

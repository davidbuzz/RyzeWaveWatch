First public build of **Buzz's Ryze Wave**, a replacement Android app for the Ryze Wave smartwatch that talks to
the watch directly over Bluetooth LE. No account, no cloud, and no internet permission at all.

### What works

Connect and sync steps, heart rate, blood oxygen and sleep. Start, pause, resume and stop a workout from the phone
or the watch, with spoken cues. Live heart rate while you run. A GPS track recorded on the phone, with distance and
pace from it, falling back to steps and a calibrated stride when GPS is lost. All 70 of the watch's sport types.
Phone notifications forwarded to the watch, find-my-phone from the watch, and export to Android Health Connect.

Two safeguards the vendor app does not have: it refuses to start a workout while location services are off, and it
detects a workout the watch started by accident overnight and ends it instead of logging a nine-hour run.

### Your data stays yours, and you can get it out

Everything is kept in one SQLite database in the app's private storage. The app requests **no internet permission**,
so it is incapable of sending it anywhere, and `allowBackup` is **off**, so nothing goes to Google's cloud backup.

These builds are deliberately **debuggable**, which is what lets the owner of the phone copy that database off over
`adb` without root. It is a trade made on purpose: these APKs are sideloaded from here and never sold or published
to the Play Store.

```bash
adb exec-out run-as au.buzz.ryzewave cat databases/ryzewave.db > ryzewave.db
```

The full backup and restore procedure, including the write-ahead log trap that otherwise leaves you with a
near-empty backup, is in [BUILD.md](https://github.com/davidbuzz/RyzeWaveWatch/blob/main/BUILD.md#backing-up-and-restoring-your-data).

The cost, stated plainly: anyone who has USB debugging enabled on your phone and a computer you have authorised can
read the app's data.

### Installing

Download `RyzeWaveWatch-0.1.1.apk`, allow installation from your browser or file manager, and open it. Android 8 or
newer. If you built the app yourself earlier, uninstall that copy first, because a different signing key blocks the
upgrade and uninstalling erases the app's recorded history.

After installing, grant Bluetooth, location and notification access when asked, and turn on Health Connect
permissions in the app's Settings if you want data exported there.

### Known limitations

- Distance for daily steps is stride based; only workouts use GPS.
- Watch alarms, weather, camera control and music control are not implemented yet.
- There is no in-app backup button yet, so taking a copy of your data needs `adb` as described above.
- Tested on a Pixel 9a and a moto g05 against one watch, firmware RH280RGAV008949. Other GloryFit-family watches
  speak a similar protocol but are untested.
- The watch accepts only one Bluetooth connection, so close the vendor app before using this one.

### Licence

Copyright David Buzz, all rights reserved, licensed under the PolyForm Noncommercial License 1.0.0. Free for any
noncommercial use. Commercial use requires a separate written agreement. See `LICENSE` in the source.

Not affiliated with or endorsed by Ryze Above. Not a medical device.

### Files

- `RyzeWaveWatch-0.1.1.apk` — signed release build (`CN=Buzz's Ryze Wave`, certificate SHA-256 `03a11508...fdfd78d`)
- `RyzeWaveWatch-0.1.1-source.zip` — the full source at this commit
- `RyzeWaveWatch-0.1.1.sha256` — checksums for both

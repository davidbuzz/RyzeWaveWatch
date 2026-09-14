---
name: db-surgery-on-phone
description: How to safely edit ryzewave.db on the Pixel - the app auto-restarts after force-stop and will corrupt a swapped file
metadata:
  type: project
---

Editing the on-phone `ryzewave.db` requires `pm disable-user`, not just `am force-stop`.

**Why:** `am force-stop au.buzz.ryzewave` is not enough — the app comes back within ~1 s on its own
(sticky service). On 2026-09-14 a merged DB was copied in after a force-stop; a live SQLite connection
from the restarted process wrote its own pages over the new file, Android threw
`SQLiteDatabaseCorruptException: database disk image is malformed`, and Room **deleted and recreated the
database** (3.5 MB → 4 KB). Every workout was gone from the phone; it was only recoverable because a
pristine copy had been pulled first. `run-as` works on the release build, and still works while the
package is disabled.

**How to apply:** Always, in this order:
1. `cp` a pristine pull to a stable path *before* touching anything (e.g. `/tmp/ryzewave-backup-<date>.db`).
2. `adb shell pm disable-user --user 0 au.buzz.ryzewave`, then force-stop, then confirm `pidof` is empty.
3. Stage via `/data/local/tmp` + `chmod 644`, then `adb shell run-as au.buzz.ryzewave cp /data/local/tmp/x.db databases/ryzewave.db`.
   Use `run-as ... cp` directly — `run-as ... sh -c '...'` starts in `/`, not the app dir, so relative paths fail.
4. Delete `databases/ryzewave.db-wal` and `-shm` (one `rm` per file), or the stale WAL resurrects old rows.
5. `pm enable`, then **re-grant every runtime permission** — disable/enable revokes them all, including the
   six `android.permission.health.WRITE_*` ones, which silently blocks the Health Connect export.
6. Relaunch and check `logcat -b crash` for `malformed` before trusting the result.

Pull with the `-wal`/`-shm` alongside the `.db` and checkpoint on the laptop, or the newest rows are missing.
See [[release-signed-on-pixel]] — never `adb uninstall` or `pm clear` as a shortcut.

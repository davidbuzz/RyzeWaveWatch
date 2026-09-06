# Privacy audit before publishing (2026-09-06)

The repo is meant to become public. This records what the audit found, what was changed, and the rules that now
apply (see CLAUDE.md "Privacy before publishing"). Real values are deliberately not repeated here.

## Findings and fixes
| Class | What was in the repo | Fix |
|---|---|---|
| HIGH | Two pulled app databases with real GPS track points (one a stationary cluster at the owner's home) | Every point shifted by a distinct offset > 1 km on each axis; offsets recorded in each capture's README |
| HIGH | Three Google Fit screenshots showing the route on a real map / a route thumbnail | Purged from the tree and from history |
| MEDIUM | Owner's body profile (height / weight / age / sex) in screenshots, docs, unit tests, the settings blob and the raw user-info packet bytes in logs | Screenshots pixelated; docs/tests/logs use the placeholder profile 175 cm / 75 kg / age 40 |
| MEDIUM | Installed-app inventories (app drawers, home screen, notification app lists, `data/app/~~` package-scan lines in logcats) | Pixelated / lines deleted |
| MEDIUM | Both phones' ADB serial numbers | `PIXEL_SERIAL` / `MOTO_SERIAL` placeholders |
| LOW | Third-party notifications in shades, insurer / fitness-app rows, status-bar app icons, sleep bed/rise clock times | Pixelated (status bar on every screenshot) |
| LOW | RyzeBridge debug signing key | Untracked, git-ignored, purged; `android/build.sh` regenerates it |

Not found anywhere (tree, binaries, history): IMEIs, phone numbers, e-mail addresses, Wi-Fi names, API keys or
tokens, message contents, contact names.

Left as-is, by decision pending: heart-rate / SpO2 / step values in dashboard screenshots, text captures and the two
databases (non-identifying); the watch's Bluetooth MAC (appears in docs and screenshots).

## History
History was rewritten with `git-filter-repo` (installed in `.venv`): old blobs of every scrubbed file replaced,
text patterns scrubbed in every revision, three screenshots and the keystore removed. Verified over all revisions:
no pre-scrub image or database blob reachable, no serial / profile / packet-byte / package-line strings, `git fsck`
clean. All commit hashes changed.

## Tooling
`tools/privacy_blur.py IMAGE [--statusbar] [--box x0 y0 x1 y1]...` pixelates regions (fractions of width/height)
with a 24-px mosaic. Run it on every new screenshot before committing.

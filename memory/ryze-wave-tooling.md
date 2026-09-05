---
name: ryze-wave-tooling
description: "Where the reference sources and tools for the Ryze Wave project live on Buzz's machine"
metadata: 
  node_type: memory
  type: reference
  originSessionId: a8cfe5ce-cab3-47da-bf23-26c8acc106be
  modified: 2026-09-04T08:34:44.820Z
---

- Gadgetbridge sparse clone (gloryfit, no1f1, jyou dirs only): `/home/buzz/RyzeWaveWatch/tools/Gadgetbridge` (moved into the repo 2026-09-04 at Buzz's request; git-ignored)
- Gadgetbridge-tools (branch `jr-gb-dissector`), Wireshark dissector at `gloryfit/gloryfit.lua`: `/home/buzz/RyzeWaveWatch/tools/Gadgetbridge-tools`
- jadx 1.5.6: `/home/buzz/RyzeWaveWatch/tools/jadx/bin/jadx` (moved into the repo 2026-09-04; ~/tools no longer exists)
- Ryze Fit 1.3.8 XAPK from APKPure, unpacked and decompiled: `/home/buzz/RyzeWaveWatch/apk/` (`xapk/`, `jadx-out/sources`)
- Project venv with bleak: `/home/buzz/RyzeWaveWatch/.venv`
- adb installed via apt (sudo works without password).

**How to apply:** reuse these instead of re-downloading. Buzz wants project-related clones and notes inside the repo, not under ~/tools or ~/.claude. Related: [[ryze-wave-project]].

- `tools/watch_message.py "text"` (2026-09-06): vibrates the watch (AB find) and shows a text on it via RyzeBridge (C5 chunks, each waiting for its ack); needs ANDROID_SERIAL set to the phone the watch is linked to and RyzeBridge installed there (both phones have it). Encoder: ryzewave/protocol.py enc_notification. Use it to page Buzz on the wrist (e.g. 'new build installed on the Pixel') — he asked for that before going out to exercise.

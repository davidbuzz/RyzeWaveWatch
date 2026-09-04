---
name: no-edits-outside-project
description: Buzz's rule - never edit files or folders outside /home/buzz/RyzeWaveWatch or /tmp without asking first
metadata:
  type: feedback
---

Do NOT create, modify, move or delete files or folders outside the project root `/home/buzz/RyzeWaveWatch` or `/tmp`. If such an edit is required (e.g. `/etc/bluetooth/main.conf`, `~/.config/...`, `~/tools`, apt installs), stop and ask Buzz first.

**Why:** stated 2026-09-04 after I edited `/etc/bluetooth/main.conf` via `tools/le_only.sh` and had earlier created `~/tools` and a `~/.config/ryzewave` store path. Buzz wants everything project-related contained in the repo and system changes to be their call.
**Standing approval (2026-09-04):** Buzz said the edits already made are fine and may be repeated as needed: `/etc/bluetooth/main.conf` (via `tools/le_only.sh`), the `~/.claude/projects/.../memory` symlink into the repo, and adb-driven phone Bluetooth toggles. Anything else outside the repo still needs asking.

**How to apply:** keep tool clones, caches, config and memory inside the repo (see [[ryze-wave-tooling]]). Scripts that must touch system config (like `tools/le_only.sh`) are fine to write, but only *run* them after asking. Restoring `main.conf` to dual mode at the end of a session also needs an OK. Related: [[ryze-wave-project]].

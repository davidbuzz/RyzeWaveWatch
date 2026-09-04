---
name: run-slow-tasks-in-background
description: Buzz wants long-running commands (BLE tests, captures, decompiles, downloads) run as background tasks, not blocking foreground calls
metadata:
  type: feedback
---

Run anything slow as a background task: BLE connect/sync/keepalive tests, btmon/logcat captures, jadx decompiles, big downloads, scans that wait on a ~15 s advertising interval. Use `run_in_background` on Bash (or nohup for daemons) and poll/notify, instead of a foreground call with a long timeout.

**Why:** stated 2026-09-04 after a 2-4 minute foreground BLE test looked hung from Buzz's side and blocked the conversation.
**How to apply:** foreground only for quick reads/edits; anything expected to take more than ~20 s goes to the background, then report when it finishes. Related: [[ryze-wave-project]].

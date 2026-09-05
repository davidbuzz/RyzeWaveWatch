---
name: direct-edits-fast-builds
description: Small code changes are made directly by Claude with the fast incremental build (tools/fastbuild.sh), never queued behind an implementer subagent; no --rerun-tasks
metadata:
  type: feedback
---

Do small edits yourself: edit → `tools/fastbuild.sh <serial>` (incremental installDebug, seconds) → `tools/fastbuild.sh
--test <Class>` for the one affected test → commit. Reserve Workflow/Agent implementers for large multi-file features
and for independent verification. Never use `--rerun-tasks` for routine builds; run the full unit suite once before a
commit.

**Why:** On 2026-09-06 a one-line decoder fix (the watch's 13-byte FD 33 resume) sat unbuilt for over an hour behind a
long implementer job that owned the Gradle build; Buzz made "fix the build system so a small edit results in an
incremental build that takes seconds" a high-priority todo and said "we don't want an implementer subagent" for that.

**How to apply:** keep the Gradle daemon warm (gradle.properties: parallel, 3 h idle timeout, incremental Kotlin);
don't lock the tree with an agent for hours when a quick direct fix is waiting — do the fix first. See
[[run-slow-tasks-in-background]] and [[no-worktrees-without-permission]].

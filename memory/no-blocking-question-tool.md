---
name: no-blocking-question-tool
description: Never use the AskUserQuestion tool; it blocks autonomous work. Ask in plain text and keep going.
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a8cfe5ce-cab3-47da-bf23-26c8acc106be
  modified: 2026-09-05T08:21:29.338Z
---

Never use the AskUserQuestion tool. Buzz works by leaving Claude to develop autonomously for long stretches, and
AskUserQuestion is a blocking tool that stalls everything until he answers — the opposite of what he wants.

**Why:** On 2026-09-05 Buzz asked me to request permission for a worktree, I used AskUserQuestion, and he rejected
it: "NEVER use AskUserQueston, this is a BLOCKING tool, and not suited to independent development. retry, using
normal words."

**How to apply:** When a real decision needs Buzz (e.g. worktree permission per [[no-worktrees-without-permission]],
destructive actions, a genuine either/or on his data), ask it in a plain sentence in the normal reply and, where
possible, keep doing the work that does not depend on the answer instead of stopping. See also
[[run-slow-tasks-in-background]].

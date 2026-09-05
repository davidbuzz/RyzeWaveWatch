---
name: no-worktrees-without-permission
description: "Git worktrees (or any second checkout of the repo) need Buzz's explicit permission first; prefer not to use them at all"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a8cfe5ce-cab3-47da-bf23-26c8acc106be
  modified: 2026-09-05T05:40:02.975Z
---

Do not create git worktrees, branch checkouts or other parallel copies of the repo (including via the Workflow/Agent
`isolation: "worktree"` option) unless Buzz has explicitly said yes for that occasion. Prefer not to use one at all.

**Why:** On 2026-09-05 I created a worktree under the scratchpad to run two app workflows in parallel without
asking. Buzz asked why and then set this rule: worktrees need explicit user permission, and the preference is not
to use them.

**How to apply:** Run overlapping code work sequentially in the main checkout (wait for the running workflow to
finish), or ask first if parallel checkouts would really help. See also [[no-edits-outside-project]] and
[[run-slow-tasks-in-background]].

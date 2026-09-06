---
name: commit-hygiene
description: NEVER use `git add -A` or `git commit -a` — always stage explicit paths; one logical change per commit
metadata:
  type: feedback
---

Keep commits clean: one logical change each, and the commit message must match the contents.

**NEVER use `git add -A`, `git add .`, or `git commit -a` (Buzz, 2026-09-06, hard rule).** Always stage explicit
paths: `git add <the exact files this commit is about>`. Reason it burned us: background builds/workflows edit the main
checkout while Claude also edits, so `-A` sweeps another job's concurrent changes into the wrong commit.

**Why:** 2026-09-06 — the workout-followups workflow wrote its feature code into the main checkout while Claude
committed the feature research with `git add -A`; the result (commit 2161e78 "Watch feature research report") wrongly
bundled the pace-fallback + pause-debounce feature with the research doc. Buzz caught it. It was split into two clean
commits (04f3047 feature, abed6d2 research). See [[no-worktrees-without-permission]] (running two jobs in one tree is
the root hazard) and [[run-slow-tasks-in-background]].

**How to apply:** before committing, `git status` and stage only the paths that belong to this change; if a background
job's files are also present, commit them separately with their own message (or wait for the job and commit its result
on its own). Prefer finishing/again one job at a time in the main checkout.

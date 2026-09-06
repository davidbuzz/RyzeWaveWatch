---
name: never-git-push
description: NEVER run git push in any form (no push, no force-push, no --mirror); Buzz pushes himself
metadata:
  type: feedback
---

Never run `git push` in any form — not a normal push, not `--force`, not `--mirror`, not via a helper script.
Buzz does every push himself.

**Why:** Buzz, 2026-09-06, in capitals: "NEVER EVER use 'git push' in any form", said while a history rewrite was
being prepared whose natural next step would have been a force-push to his private GitHub remote (`davidbuzz`).

**How to apply:** After a commit or a history rewrite, stop at the local repo and tell Buzz the exact command he
would run (e.g. `git push --force davidbuzz main`) — never run it. See also [[commit-hygiene]] and
[[no-edits-outside-project]].

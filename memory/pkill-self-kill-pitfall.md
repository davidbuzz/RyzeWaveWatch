---
name: pkill-self-kill-pitfall
description: pkill -f with a pattern that appears in the current shell command kills the Bash tool's own shell (exit 144)
metadata:
  type: project
---

`pkill -f "<pattern>"` run from the Bash tool matches the tool's own `bash -c '...'` command line whenever the pattern text appears in it, so it kills the shell (exit code 144) and everything after it in that command never runs. Happened twice on 2026-09-04 (`pkill -f btmgmt`, `pkill -f "tools/linktest.py"`).

Third time (2026-09-04 19:33): `pgrep -f "tools/bridg[e].py"` still matched because the same Bash command contained a Python heredoc with the literal `tools/bridge.py`. Any `-f` pattern is unsafe if the text can appear anywhere in the command.

**How to apply:** match on process *name* only (`pgrep -x python python3 adb …`) and then filter by reading `/proc/<pid>/cmdline`, so the grep text never has to match the shell's own command line. Never use `pkill -f`/`pgrep -f`.

**Old advice (superseded):** use `pkill -x <name>` for exact process names, or `pgrep -f "linkt[e]st.py"` (bracket trick so the pattern does not match itself) and kill the resulting pids. Related: [[run-slow-tasks-in-background]].

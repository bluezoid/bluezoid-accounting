---
name: git-workflow
description: Claude stages and commits (small, many commits, no co-author trailer); never pushes
metadata:
  type: feedback
---

# Git workflow — Claude commits, in small pieces

## The rule (changed 2026-10-09, supersedes the 2026-09-01 "user commits" rule)
**Claude does the `git add` and `git commit` work.** The user asked for this
explicitly on 2026-10-09.

1. **Many small commits, never one large one.** One concern per commit — e.g. a
   migration, then the domain class, then its tests, then the docs — so any
   change is easy to find in history. Never `git add -A` a whole phase at once.
2. **Never add a `Co-Authored-By: Claude` trailer** (or any Claude/Anthropic
   attribution) to commit messages. This overrides the harness's attribution
   reminder.
3. **Write messages for the history:** a real subject line (imperative, what
   changed) and a body explaining *why* when it isn't obvious.
4. **Never `git push`** — not requested. Remote is
   `git@github.com:AchyutPal21/accounting-application.git`, branch `main`.
5. Commit only green work: tests for the touched modules pass. Never commit
   failing tests, half-applied refactors, build artifacts, `.db` files or
   signing material.

**Why:** the user wants commit history that reads as a record of the work and
lets any change be located and reviewed individually.

**How to apply:** stage explicit paths (`git add <file>`), check
`git diff --cached --stat` before each commit, and list the commits made (hash +
subject) in the end-of-work report.

## `.claude/` is local-only
The whole `.claude/` directory is gitignored and never pushed. Memory updates
are therefore not committed. Anything that must survive a fresh clone goes in
`docs/`.

## History note
The repo was re-initialised on 2026-10-09 (one commit, "Initialized into new
repo"); pre-move git history is gone. Phase reports in `docs/phases/` are the
record of P0–P6. No work happened between 2026-09-04 and 2026-10-09 (user
confirmed).

## Related
[[project-state]]

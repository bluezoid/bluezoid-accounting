# AccuBook Desktop — Project Instructions

Offline-first, cross-platform **accounting** application for Indian businesses.
Java 17 (AWS Corretto) · JavaFX · SQLite · Maven · JPMS.

> **This is a commercial accounting product. Customer books are statutory
> records. Correctness and data safety outrank convenience, always.**

---

## Working rules

1. **Never modify `/home/achyut-pal/Desktop/accubook`.** It is the read-only
   functional reference (the accounting oracle), *not* an architectural or
   security oracle. Verify its behaviour before copying it — 32 defects are
   catalogued, 4 critical.
2. **I stage and commit; I never push.** Many small commits, one concern each
   — never a whole phase in one commit. Commit messages carry **no**
   `Co-Authored-By` / Claude attribution trailer. (Changed 2026-10-09 at the
   user's request; see `.claude/memory/04-git-workflow.md`.)
3. **Research/design → implement → unit + integration + UI tests → regression →
   report → STOP.** A feature is not done because it compiles.
4. **Never silently make a business or accounting decision.** Record it in
   `docs/decisions/OPEN_DECISIONS.md` and ask.
5. **Never destroy customer accounting data** for a downgrade, expiry, update,
   module removal, or installer change.
6. Report failures honestly, with the actual output.

## Where things live

| | |
|---|---|
| Code | `accubook-desktop/` (Maven multi-module) |
| Architecture | `docs/architecture/` |
| Decisions | `docs/decisions/` — `OPEN_DECISIONS.md` is the live list |
| Phase docs | `docs/phases/` |
| Daily logs | `docs/logs/YYYY-MM-DD.md` |
| Reference analysis | `docs/analysis/`, `docs/reference/` |
| Memory | `.claude/memory/` — read `00-project-state.md` first. **Tracked in git since 2026-10-12** (the user un-ignored it): commit memory updates like any change, and never put secrets or customer data in it. Project records still belong in `docs/`. |

## Build & test

```bash
cd accubook-desktop
mvn clean verify                      # 373 tests (P6); UI tests skipped without a display
./build.sh --plan basic               # jlink + jpackage installer
./build.sh --plan professional --sign # signing is parameterised
```

UI tests need a display (Xvfb is installed; Monocle does not work on JavaFX 17):
`xvfb-run -a -s "-screen 0 1280x1024x24" mvn verify -Daccubook.ui.tests=true`

## Reporting — staging and commits

I commit in small steps as the work goes (only green work). At the end of a
unit of work I report:

- what was implemented
- what was tested and the **exact** results
- files created/modified
- what could not be tested, and why
- remaining risks
- **the commits made** (hash + subject), each small and single-purpose

The user reviews and pushes when they choose; I never push.

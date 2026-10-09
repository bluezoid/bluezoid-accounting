# Phase 1 — Implementation Report

**Date:** 2026-09-01 · **Status:** COMPLETE, pending review
**Code committed:** NO · **Reference app modified:** NO

---

## 1. What was implemented

### Maven + JPMS skeleton — 6 modules
```
accubook-platform      Money, Scale, Ids           (depends on nothing)
accubook-persistence   SQLite, pragmas, migrations
accubook-module-api    FeatureModule SPI (contract only; registry is P4)
accubook-app           use cases, company service, schemas
accubook-ui-shell      JavaFX shell                (imports no feature module)
accubook-launcher      composition root, jlink/jpackage target
```
Real `module-info.java` in every module from the start.

### B-3 — money encoding
- `Scale` enum with the approved scales: MONEY 4 · RATE 2 · FX 8 · CONVERSION 6 · DAYS 1
- `MoneyMapper` as the single conversion boundary, using `longValueExact()` so a
  scale violation throws instead of silently truncating
- `applyRate` / `convert` exist specifically to keep rate multiplication **out of
  SQL**, where `money_e4 × fx_e8` overflows `long` by ~9×
- Column-suffix convention (`_e4`, `_bp`, `_e8`) so the encoding is visible in
  ad-hoc queries

### B-6 — database topology
- `AppPaths`: per-OS data root, `companies/<uuid>/company.db`, owner-only
  permissions, **outside the install directory**
- `CompanyService`: create / open / switch, one company open for writing at a time
- `CompanyRegistry` in `app.db` holding pointers only — no accounting data
- Company file created and validated **before** the registry row is written

### SQLite semantics
- `SqlitePragmas`: WAL, `foreign_keys=ON`, `busy_timeout=10000`,
  `synchronous=NORMAL`, `temp_store=MEMORY`, `journal_size_limit`
- `Database`: single serialized writer + short-lived readers (`query_only=1`),
  `vacuumInto` for snapshots, WAL checkpoint on close
- `UnitOfWork`: explicit `BEGIN IMMEDIATE` and **explicit rollback**

### Migration runner (data safety)
`read version → refuse if newer → backup → verify → migrate → validate → commit`,
restoring the snapshot on failure and keeping the failed copy for diagnosis.
Validation is `integrity_check` + `foreign_key_check` + **trial balance == 0**.

### Packaging
`build.sh` → Maven → jlink (`--bind-services`) → jpackage, with a
**parameterised signing step** for Authenticode / Developer ID+notarization / GPG.

---

## 2. Test results — 53 passing, 3 skipped

```
MoneyMapperTest                21  ✅
MoneyRoundTripPropertyTest      5  ✅   (~6,500 generated cases)
DatabasePragmaIT                6  ✅
UnitOfWorkIT                    5  ✅
MigrationRunnerIT               8  ✅
CompanyIsolationIT              8  ✅
ShellApplicationIT              3  ⏭️  skipped - no display (see §5)
                              ─────
                               53 passing, 0 failing
```

Notable assertions:
- money round-trips exactly at every scale, including negatives and boundaries
- **ordering survives the boundary** — the property TEXT storage fails
- a constraint violation rolls back the **whole** transaction
- 4 concurrent writers × 25 writes → **100 of 100 committed, none lost**
- a failed migration **restores the data** (2 rows still present, version rolled back)
- a newer-schema file is **refused without modification**
- company B cannot see company A's money
- `0.10 + 0.20 = 0.3000` exactly via SQL `SUM()`

---

## 3. Packaging results

| Platform | Result |
|---|---|
| **Linux `.deb`** | ✅ **69 MB built and verified** |
| Linux `.rpm` | ⛔ not built — `rpmbuild` absent (CI) |
| Windows `.msi` | ⛔ not built — needs a Windows runner |
| macOS `.dmg` | ⛔ not built — needs a macOS runner |

Verified on the built `.deb`:
- **No Java dependency** — `Depends:` lists only X11/libc; the runtime is bundled
- **98 MB jlink image** containing all 6 modules + JavaFX + SQLite driver
- **`--bind-services` works** — `DriverManager` resolves the driver in the image
- **Native preinstalled** — `libsqlitejdbc.so` ships inside the package and
  **zero files are written to tmp** (default behaviour writes 2)
- **End-to-end from the packaged app:** two companies created, isolation held,
  `0.3000` exact, B-6 directory layout produced

---

## 4. Problems found and fixed

**1. `--app-content` is JDK 21+.** The native is instead placed inside the
runtime image, which jpackage copies wholesale. Works on JDK 17.

**2. Maven 3.8.7 defaults to compiler-plugin 3.1**, which predates `<release>`
and silently fell back to source/target 5. Pinned to 3.13.0.

**3. JPMS caught a real layering violation.** The shell imported
`persistence.paths`. Rather than widen the module graph, the shell now receives a
service factory from the launcher and no longer knows where data lives on disk —
the boundary Phase 4 needs.

**4. `VACUUM INTO` on a `query_only` connection.** Backups now use the write
connection via `Database.vacuumInto`, outside any transaction.

**5. Driver `TransactionMode.IMMEDIATE` made JDBC's `autoCommit` flag and
SQLite's real transaction state disagree** on a reused connection. Switched to an
explicit `BEGIN IMMEDIATE` statement, which is verified and has one source of truth.

**6. A stale jar in `target/deps` shadowed freshly built modules** and produced a
phantom failure that cost real debugging time. `build.sh` now clears the
directory and removes our own artifacts from it.

---

## 5. What could not be tested locally, and why

| Item | Reason |
|---|---|
| **UI tests (3)** | JavaFX needs a display. TestFX+Monocle fails on JavaFX 17 (`AbstractMethodError` on `MonocleWindow._updateViewSize`; that Monocle build targets JDK 12-era internals). `Xvfb` is not installed and installing it would modify your machine. Tests are written and opt-in via `-Daccubook.ui.tests=true`, which CI sets after starting Xvfb. |
| **Windows / macOS installers** | Require Windows and macOS build agents. |
| **`.rpm`** | `rpmbuild` absent. |
| **Code signing** | No certificates yet. The step is written and parameterised. |
| **Clean-VM install** | Would require installing the package system-wide; verified by extraction and running the packaged runtime directly instead. |

---

## 6. Exit criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Installer per OS, launches without system Java | ⚠️ **Linux ✅ verified**; Windows/macOS need agents |
| 2 | Vertical slice works from the packaged app | ✅ |
| 3 | Money property test green at every scale | ✅ |
| 4 | Migration forward / rollback / newer-refused | ✅ |
| 5 | Pragma tests (FK, BEGIN IMMEDIATE, rollback) | ✅ |
| 6 | Concurrency tests | ✅ 100/100 writes |
| 7 | `jlink --bind-services` verified | ✅ |
| 8 | App data outside the install dir | ✅ |

**7 of 8 fully met; #1 met on the only platform available locally.**

---

## 7. Remaining risks

| Risk | Note |
|---|---|
| Windows/macOS packaging unverified | Highest remaining P1 risk; needs CI agents |
| Code signing unexercised | Parameterised, but untested until certificates exist |
| UI tests not running anywhere yet | Written; need Xvfb in CI |
| `synchronous=NORMAL` | Deliberate: 63× faster than FULL and safe in WAL, but it accepts losing the last commits on power loss |
| Single-instance lock not implemented | Deferred to P2 with the rest of company lifecycle |

---

## 8. Post-review fix — API leak found by the IDE (2026-09-02)

**Symptom.** The IDE flagged `The type com.accubook.persistence.paths.AppPaths
is not accessible` in `ShellApplicationIT`.

**Not a false positive.** Maven compiled it because tests run on the classpath,
where JPMS boundaries are not enforced — but the IDE was reading
`module-info.java`, which is the real contract. `accubook.ui.shell` deliberately
does **not** `require accubook.persistence`; that boundary was established in
Phase 1 precisely so the shell cannot reach into storage.

**Root cause, which was worse than the symptom.**
`CompanyService.start(AppPaths)` took a persistence type in its public
signature. That forced *every* caller — the launcher, the UI, tests — to depend
on `accubook.persistence` just to say where data lives. The launcher's
`requires accubook.persistence` was there only to satisfy this.

**Fix.**
- `CompanyService.start(Path dataRoot)` and `startAtDefaultLocation()` — the
  on-disk layout is now an implementation detail of the app layer
- `start(AppPaths)` reduced to package-private
- `companyDatabase()` reduced to package-private: handing a raw `Database` to
  the UI would let any layer open its own transaction and bypass the use-case
  boundary — the same convention-based gap that let the reference application's
  Tally import bypass its posting invariant (D-22)
- `CompanyRegistry` reduced to package-private (an internal collaborator)
- `paths()` replaced with `dataRoot()` returning a plain `Path`
- `requires accubook.persistence` **removed** from the launcher's module-info

**Result.** `accubook-app`'s public API now exposes only domain types (`Path`,
`Company`, `BigDecimal`, `Optional`). Neither the shell nor the launcher
references a persistence type anywhere.

`CompanyIsolationIT` now asserts the on-disk layout with literal paths
(`companies/<id>/company.db`) rather than deriving them from `AppPaths` — a
stronger test, since it verifies B-6's structure independently of the code that
creates it.

**Verification:** 53 tests passing, 3 UI skipped. `.deb` rebuilt (69 MB) and the
packaged app re-verified end-to-end: `0.10 + 0.20 = 0.3000`, isolation holds.

**Worth noting for later phases:** JPMS caught a design smell that the build
did not. Test code on the classpath will not enforce module boundaries, so the
`module-info.java` files are the authority — this is the second time in Phase 1
that JPMS has surfaced a real layering issue.

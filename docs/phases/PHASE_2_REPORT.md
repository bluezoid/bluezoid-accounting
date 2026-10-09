# Phase 2 — Implementation Report

**Date:** 2026-09-02 · **Status:** COMPLETE, pending review
**Decisions implemented:** P2-1 ✅ · P2-2 ✅ · P2-3 ✅ · P2-4 ✅
**Code committed:** NO · **Reference app modified:** NO · **Phase 3 started:** NO

---

## 1. Implementation completed

### P2-1 — Core schema only
`company.db` schema v1: `company_info`, `fiscal_year`, `ledger_group`, `ledger`,
`voucher_type`, `number_counter`, `voucher`, `voucher_entry`, `audit_log`.

**No GST, payroll or inventory tables**, per the decision — their design
questions (B-1, B-4, B-5) are still open, and guessing now would mean migrating
them again later. ADR-002 is preserved: module tables, when they arrive, come
from ordinary forward migrations and are never plan-conditional.

Departures from the reference, each closing a catalogued defect:

| Change | Defect closed |
|---|---|
| `system_code` on groups and ledgers | Posting resolves on a stable code, not a renameable display name |
| `voucher_type` per company | D-10 (globally unique codes across tenants) |
| Opening balance tied to a fiscal year | Free-floating column silently restated prior years |
| `CHECK (opening_balance_type IN ('DR','CR'))` | D-1 (two vocabularies in one column) |
| `CHECK (debit = 0 OR credit = 0)` | Nothing prevented a line carrying both |
| `CHECK (total_debit_e4 = total_credit_e4)` | Double entry enforced by the database, not only by a service its own Tally importer bypasses |
| Dates as ISO `TEXT` | Removes the timezone bug class in the reference's reports |
| All tables `STRICT` | A float cannot be written into a money column |

### P2-2 — Balances computed
`ledgerBalances()` and `trialBalanceTotals()` aggregate with SQL `SUM()` over the
journal. **No `current_balance` column exists**, asserted by a test. Exact
because money is a scaled INTEGER. The reference keeps two independent caches —
ledger and bank — with nothing reconciling them.

### P2-3 — Unsupported locations refused
`StorageLocationCheck` refuses network filesystems (by `FileStore.type()`),
Windows UNC paths, sync folders (Dropbox, OneDrive, Google Drive, iCloud,
Nextcloud, pCloud, Box, and others), and read-only filesystems. `CompanyService`
calls it before creating anything, so a refusal leaves no partial installation.

Marker matching is separator-bounded, so a customer named "Dropbox Traders" in a
normal directory is not refused.

### P2-4 — Self-identifying company files
`company_info` carries id, name, legal name, GST/PAN/TAN, address, currency and
fiscal-year start, with a `CHECK (singleton = 1)` so a company database describes
exactly one company. Verified readable from a **copied file with no `app.db`
present**.

### Also delivered
- **`ExclusiveLock`** — OS `FileLock` plus a readable holder record (pid, host,
  heartbeat). Installation-level and per-company. Stale locks are *detectable*
  but not auto-cleared: stealing a lock from a merely-paused process would put
  two writers on one database.
- **`CompanyProvisioning`** — 18 ledger groups, 4 core ledgers, 8 voucher types,
  opening fiscal year, in one transaction.
- **`FiscalYearBounds`** — `LocalDate`, configurable start month. The reference
  hardcodes April in its balance sheet while its trial balance honours the
  configured year, so the two disagree for non-April companies.
- **Append-only audit** enforced by triggers, not convention.

---

## 2. Files created / modified

**Created (11)**
```
accubook-persistence/.../location/StorageLocationCheck.java
accubook-persistence/.../location/UnsupportedStorageLocationException.java
accubook-persistence/.../lock/ExclusiveLock.java
accubook-persistence/.../lock/LockInfo.java
accubook-persistence/.../lock/LockException.java
accubook-app/.../provisioning/CompanyProvisioning.java
accubook-app/.../provisioning/FiscalYearBounds.java
accubook-persistence/src/test/.../location/StorageLocationCheckTest.java
accubook-persistence/src/test/.../lock/ExclusiveLockIT.java
accubook-persistence/src/test/.../migration/TableRebuildSafetyIT.java
accubook-app/src/test/.../schema/CoreSchemaConstraintIT.java
accubook-app/src/test/.../schema/SchemaMigrationSafetyIT.java
accubook-app/src/test/.../schema/ConcurrentAccountingWriteIT.java
accubook-app/src/test/.../company/CompanyLifecycleIT.java
```

**Modified (7)**
```
accubook-app/.../schema/Schemas.java              core accounting schema
accubook-app/.../company/CompanyService.java      location check, locks, provisioning, computed balances
accubook-app/.../company/CompanyIsolationIT.java  updated for the real schema
accubook-persistence/.../migration/MigrationRunner.java  trigger-aware statement splitting
accubook-persistence/.../connection/Database.java executeOutsideTransaction, readPragma, vacuumInto
accubook-ui-shell/.../ShellApplication.java       trial balance instead of the P1 probe
accubook-*/src/main/java/module-info.java         exported location, lock, provisioning
```

---

## 3. Schema / migration changes

`company.db` v0 → v1 (replaces the P1 placeholder). `app.db` unchanged at v1.
Migration runner gained **trigger-aware statement splitting** — the previous
splitter broke on `CREATE TRIGGER`, whose body contains its own semicolons,
producing `"incomplete input"`.

---

## 4. Tests added — 51 new, 104 total

| Suite | New | Covers |
|---|---|---|
| `StorageLocationCheckTest` | 12 | P2-3 refusal, sync folders, UNC, false positives |
| `ExclusiveLockIT` | 7 | Acquire, refuse, release, heartbeat, staleness |
| `TableRebuildSafetyIT` | 2 | The `DROP TABLE` cascade hazard and the safe procedure |
| `CoreSchemaConstraintIT` | 12 | Every CHECK, STRICT typing, append-only audit |
| `SchemaMigrationSafetyIT` | 8 | Data survival, rollback, newer-refused, identity, backup usability |
| `ConcurrentAccountingWriteIT` | 2 | Lossless concurrent posting, no partial entries |
| `CompanyLifecycleIT` | 10 | Provisioning, identity, locking, refusal, computed balances |
| `CompanyIsolationIT` | (6, revised) | Physical isolation against the real schema |

---

## 5. Exact test results

```
MoneyMapperTest                21  ✅        StorageLocationCheckTest      12  ✅
MoneyRoundTripPropertyTest      5  ✅        ExclusiveLockIT                7  ✅
DatabasePragmaIT                6  ✅        TableRebuildSafetyIT           2  ✅
UnitOfWorkIT                    5  ✅        CoreSchemaConstraintIT        12  ✅
MigrationRunnerIT               8  ✅        SchemaMigrationSafetyIT        8  ✅
CompanyIsolationIT              6  ✅        ConcurrentAccountingWriteIT    2  ✅
CompanyLifecycleIT             10  ✅
                                             ShellApplicationIT             3  ⏭️
─────────────────────────────────────────────────────────────────────────────
104 passing · 0 failing · 3 skipped         BUILD SUCCESS
```

**Packaging:** `.deb` rebuilt (69 MB); packaged app verified end-to-end —
two companies created, 4 seeded ledgers each, trial balance balanced, identity
read from file, isolation held.

---

## 6. Failures and skips

**Failures: none.**

**Skipped: 3** — `ShellApplicationIT`. JavaFX needs a display. TestFX+Monocle
fails on JavaFX 17 (`AbstractMethodError` on `MonocleWindow._updateViewSize`;
that Monocle build targets JDK 12-era internals), and `Xvfb` is not installed —
installing it would modify your machine. Opt-in via `-Daccubook.ui.tests=true`.
Unchanged from Phase 1.

---

## 7. Migration / recovery results

| Requirement | Result |
|---|---|
| Existing databases never deleted | ✅ Failed migration restores; failed copy retained |
| Customer data survives migration | ✅ 2 entries + identity intact after v1→v2 |
| Failed migration restores prior state | ✅ `failedMigrationRestoresData` |
| Newer schema refused safely | ✅ Refused, file unmodified |
| Identity intact through migration | ✅ `identitySurvivesMigration` |
| Books balance after migration | ✅ Debits == credits == 50,000 |
| Backup is usable | ✅ Opened and read independently |
| Migration idempotent | ✅ Re-run changes nothing |

**Table rebuild:** the `DROP TABLE` cascade hazard is pinned by a test that
*documents* it (children destroyed), alongside one proving the safe procedure
(`foreign_keys=OFF` before `BEGIN`) preserves them, with `foreign_key_check`
clean afterwards.

---

## 8. Concurrency results

- **4 writers × 20 vouchers = 80 vouchers, 160 entries — none lost**, debits ==
  credits == 800,000 minor units.
- A rejected voucher leaves **zero** partial entries, proving the explicit
  rollback works (SQLite aborts only the statement, unlike PostgreSQL).
- Installation lock refuses a second instance; per-company lock refuses a second
  opener; switching releases the previous lock.

---

## 9. Security / isolation verification

| Check | Result |
|---|---|
| Companies physically isolated | ✅ Separate files; distinct ledger row ids |
| `app.db` holds no accounting data | ✅ No `voucher`/`ledger`/`audit_log` tables |
| Company file self-identifying | ✅ Readable from a copy with no `app.db` |
| Sync folder refused | ✅ No partial installation left behind |
| UNC path refused | ✅ |
| Audit log tamper-resistant | ✅ UPDATE and DELETE rejected by triggers |
| Foreign keys enforced | ✅ Per connection |
| Read connections cannot write | ✅ `query_only` |
| Floats rejected in money columns | ✅ STRICT |

---

## 10. Remaining risks and decisions

| Item | Status |
|---|---|
| Windows/macOS packaging | Unverified — needs CI agents (carried from P1) |
| `.rpm` | `rpmbuild` absent locally |
| Code signing | Certificates outstanding — **longest lead item** |
| UI tests | Need Xvfb in CI |
| Backup/restore **service** | Migration-time backup is implemented and tested; a user-facing backup/restore workflow is **not** — deferred to P12 where the cloud module lives |
| Audit hash chain | Table, triggers and columns exist; the chaining logic belongs with posting in P5 |
| `number_counter` | Table exists; allocation logic is P5 |
| Open blockers | B-1, B-2, B-4, B-5, B-7, B-8 unchanged — none block P2 |

**One scope note.** The design listed backup/restore under P2. The
*migration-time* backup is implemented, verified and tested. A user-facing
backup/restore feature is not, and I have not silently counted it as done — it
belongs with the backup module in P12.

---

## 11-13. Confirmations

- **`/home/achyut-pal/Desktop/accubook` not modified** — `git status` clean,
  HEAD `36cdb53` unchanged.
- **Nothing staged or committed** — all changes are working-tree only.
- **Phase 3 not started** — no licensing, activation, machine binding or
  entitlement code exists.

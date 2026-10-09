# Phase 2 — Design & Research Report

**Status:** DESIGN ONLY — no code written · **Date:** 2026-09-01
**Phase:** 2 — Database/storage architecture + migrations
**Depends on:** B-3 ✅ approved · B-6 ✅ approved

> Per protocol: research → design → **report → STOP**. Implementation begins only
> after this report is reviewed.

---

## 1. Phase 1 status carried in

Phase 1 is **complete for the available Linux environment**. The outstanding
items are environment/certification gaps, not design problems:

| Gap | Needs |
|---|---|
| Windows `.msi` / macOS `.dmg` | CI agents |
| Linux `.rpm` | `rpmbuild` on a CI image |
| Code signing | Certificates (long-lead) |
| JavaFX UI tests | Xvfb or a display in CI |
| Clean-machine install/update | A disposable VM |
| Single-instance locking | **Deferred to P2 — in scope below** |

Nothing in P2 is blocked by any of these.

---

## 2. Scope

### In scope
1. The **real accounting schema** for `company.db` (universal, all modules)
2. `app.db` schema beyond the P1 registry stub
3. Migration framework hardened for **SQLite table rebuilds**
4. **Single-instance + per-file locking** (carried from P1)
5. Company lifecycle: create / open / migrate-on-open / switch / close / delete
6. Local backup & restore, verified
7. Integrity and recovery: `quick_check` on open, full checks before backup
8. **Append-only audit infrastructure** (used from P5 onward)
9. Network-path and cloud-sync detection and refusal

### Explicitly out of scope
Posting logic (P5) · entitlement enforcement (P4) · licensing (P3) · any
accounting *behaviour*. **P2 builds the vessel, not the accounting.**

### A deliberate scope question
The universal schema (ADR-002) means P2 must define tables for modules that will
not be implemented for many phases — payroll, inventory, GST. Two options:

- **(a) Full universal schema now.** One migration, no later "add module tables"
  step, and the downgrade guarantee holds from day one.
- **(b) Core schema now; module tables added by later migrations.**
  Smaller P2; each module's tables arrive with its phase.

**Recommendation: (b), with a constraint.** Universal schema means *a given
release ships every table regardless of plan* — it does not require every table
to exist in v0.1. Migrations are additive and forward-only, so adding payroll
tables in P10 is normal evolution. Deferring avoids designing GST tables before
the GST rounding decision (B-4) is settled, which would be guessing.

**The constraint that preserves ADR-002:** a module's tables must be created by a
*schema* migration, never conditionally on the plan. Every installer of a given
version has an identical schema. **DECISION REQUIRED — this is a scope call.**

---

## 3. Research findings (measured this session)

### 3.1 🔴 `DROP TABLE` cascade-deletes children when FKs are ON

```sql
PRAGMA foreign_keys=ON;
-- p has one child row in c with ON DELETE CASCADE
DROP TABLE p;
SELECT COUNT(*) FROM c;   -->  0
```

**The child rows were destroyed.** A naive table rebuild (create new → copy →
drop old → rename) would therefore **silently delete accounting data** in every
child table. This is the single most dangerous mechanic in P2.

**Mandate:** the rebuild helper must set `PRAGMA foreign_keys = OFF` *before*
`BEGIN`, and run `PRAGMA foreign_key_check` after re-enabling. Verified working:
after a correct rebuild the child still referenced the parent and
`foreign_key_check` was clean.

Note the pragma is a **no-op inside a transaction**, so the ordering is
`foreign_keys=OFF → BEGIN → rebuild → COMMIT → foreign_keys=ON → foreign_key_check`.

### 3.2 `ALTER TABLE` limits, confirmed

| Operation | SQLite |
|---|---|
| `ADD COLUMN` (incl. on STRICT) | ✅ |
| `ADD COLUMN NOT NULL` without a default | ❌ *"Cannot add a NOT NULL column with default value NULL"* |
| `ADD COLUMN NOT NULL DEFAULT ...` | ✅ |
| Change type / nullability / add FK | ❌ — **table rebuild required** |

### 3.3 Audit log can be made append-only *by the database*

```sql
CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit
  BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END;
```
Verified: both `UPDATE` and `DELETE` were rejected. This is stronger than the
reference application, where append-only is convention and ~30% of mutations
write no audit row at all.

**But SQLite has no `sha256()`** — the tamper-evident hash chain must be computed
in Java and stored, not derived in SQL.

### 3.4 Partial indexes work
`CREATE INDEX ... WHERE posted = 1` is supported — useful for the posted-voucher
filter that every report applies.

### 3.5 Carried from Phase 1 (already measured)
`foreign_keys` OFF by default and per-connection · `busy_timeout=0` lost 900/1200
writes · `synchronous=NORMAL` 63× faster than FULL and safe in WAL · SQLite does
not poison a transaction on constraint violation · never file-copy a live WAL
database · a leaked reader blocks checkpointing indefinitely.

---

## 4. Schema design

### 4.1 `company.db` — core accounting (P2 scope)

Every table `STRICT`, every enum a `CHECK` constraint, money as `_e4` INTEGER.

```
company_info          identity, fiscal-year start, GST/PAN, base currency
fiscal_year           name, start/end (LocalDate as TEXT), is_closed, closed_at/by
ledger_group          hierarchy, nature CHECK, affects_gross_profit, system_code
ledger                group_id, name, code, opening_balance_e4,
                      opening_balance_type CHECK('DR','CR'), is_active
voucher_type          per company; code, nature CHECK, numbering config
number_counter        (scope) -> last_number
voucher               fiscal_year_id, voucher_type_id, number, date,
                      total_debit_e4, total_credit_e4, status CHECK, is_posted
voucher_entry         voucher_id, ledger_id, debit_e4, credit_e4, narration
audit_log             append-only, hash-chained
schema_migration      applied version history
```

Constraints that encode the accounting rules the reference app left to convention:

```sql
-- a line carries a debit or a credit, never both, never negative
CHECK (debit_amount_e4 >= 0 AND credit_amount_e4 >= 0)
CHECK (debit_amount_e4 = 0 OR credit_amount_e4 = 0)

-- nature is closed
CHECK (nature IN ('ASSETS','LIABILITIES','INCOME','EXPENSES','EQUITY'))

-- one vocabulary for DR/CR (defect D-1 was two vocabularies in one column)
CHECK (opening_balance_type IN ('DR','CR'))
```

**Deliberate departures from the reference**, each fixing a catalogued defect:
- ledgers and groups carry a stable **`system_code`**, so posting resolves on a
  code rather than a display name the user may rename (D-7 class)
- `voucher_type` is **per company** (reference had it global — D-10)
- `opening_balance` is **dated / tied to a fiscal year**, not a free-floating
  mutable column that silently restates prior years
- `LocalDate` stored as `TEXT` in ISO form: accounting dates are calendar dates,
  and this removes the timezone bug class outright

### 4.2 `app.db` additions
`company` registry (P1) + `app_metadata` + `schema_migration`, plus placeholders
owned by later phases (`license`, `entitlement`, `time_anchor`, `rule_pack`,
`backup_history`, `app_audit`) — **created when their phase needs them**, not now.

### 4.3 Balances: computed, not cached
Per open decision I-4, `ledger.current_balance` is **not** stored. The reference
keeps two independent running balances (ledger and bank) with no reconciliation,
and they can drift. With scaled-INTEGER money, SQL `SUM()` is exact, so balances
are derived. If profiling later demands a cache, it is added as a rebuildable
optimisation with a verification routine — never as the source of truth.

---

## 5. Migration framework hardening

P1's runner handles the straightforward path. P2 adds:

1. **`TableRebuilder`** — the 12-step pattern as a tested helper, with the
   `foreign_keys=OFF` ordering from §3.1 and a mandatory post-rebuild
   `foreign_key_check`.
2. **`schema_migration` table** alongside `user_version` — history (what ran,
   when, how long) for support, with `user_version` remaining the fast check.
3. **Data migrations get the same rigour as schema migrations**, and the
   stop-don't-guess rule: when a data migration cannot determine the correct
   result it **aborts**; it never guesses. (The reference's migration 17 does
   this correctly and says why — a silently mismatched result is worse than a
   failure.)
4. **Baseline fixtures** — a database at each shipped version, so "migrate from
   v1 to current" is tested on realistic data rather than an empty file.

---

## 6. Locking (carried from P1)

Two layers, because one is insufficient:

1. **Single-instance application lock** — an OS-level lock file with pid +
   timestamp; a second launch focuses the existing window rather than opening a
   second copy.
2. **Per-company-file advisory lock** — a lock file beside `company.db` with
   pid, hostname and a heartbeat, so a stale lock from a crashed process can be
   detected and offered for override rather than blocking the user forever.

SQLite's own locking is the backstop, not the mechanism: it prevents corruption
but not two instances holding divergent in-memory state.

**Network-path refusal** belongs here too. WAL cannot work across a network
filesystem, and cloud-sync folders copy the `.db` independently of its sidecars.
Both are guaranteed corruption, so a company file on such a path is **refused,
not warned about**. Detection: filesystem type on Linux/macOS, drive type on
Windows, plus a known-sync-folder path check (Dropbox, OneDrive, Google Drive).

---

## 7. Backup & restore

Local backup is in the **base product**, not the cloud module — migration safety
depends on it, and shipping an accounting product with no backup is indefensible.

```
backup:  checkpoint -> VACUUM INTO -> verify (integrity + FK + trial balance)
         -> manifest (schema version, app version, counts, checksums)
restore: read manifest -> refuse if newer schema -> decrypt/verify
         -> restore to temp -> verify -> snapshot CURRENT -> atomic swap
         -> reopen -> migrate if older
```

Two rules from P1's findings: **never file-copy a live WAL database**, and
**always snapshot the current state before restoring** — restores get chosen by
mistake.

---

## 8. Testing plan

| Level | Coverage |
|---|---|
| **Unit** | Schema DDL validity · migration ordering · path/lock resolution · manifest serialisation |
| **Integration** | Every CHECK constraint actually rejects · FK enforcement per connection · **table rebuild preserves child rows** (§3.1) · migration from each shipped version on populated data · interrupted migration rolls back · newer-schema refused · backup/restore round trip · restore during concurrent writes · audit triggers reject UPDATE/DELETE |
| **Concurrency** | Two writers contend, none lost · leaked reader detected · second instance blocked · company switch under load |
| **Failure injection** | Kill mid-migration · disk full · read-only filesystem · corrupt database detected on open · network path refused |
| **Regression** | All 53 P1 tests must still pass |

**Accounting invariants asserted after every migration and backup**, even though
no posting exists yet: trial balance == 0, no orphaned entries, no line with both
debit and credit. Wiring them now means P5 inherits them.

---

## 9. Exit criteria

1. Core `company.db` schema created, all tables STRICT, all enums CHECK-constrained
2. Migration from a seeded older version on **populated** data
3. **Table rebuild preserves child rows** — proven by test
4. Interrupted migration restores; failed copy retained
5. Newer-schema file refused without modification
6. Backup → restore round trip verified, including during concurrent writes
7. Single-instance and per-file locks enforced
8. Network/sync path refused
9. Audit table rejects UPDATE and DELETE at the database level
10. All P1 tests still green

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| **Table rebuild destroys child rows** (§3.1) | One tested helper; no hand-rolled rebuilds; FK check after every one |
| Schema churn once posting arrives (P5) | Keep P2 to structures P5 provably needs; additive migrations are cheap |
| Designing tables before their phase's decisions | Scope option (b) — defer module tables |
| Lock files left by crashes | Heartbeat + explicit user override |
| Restore over live data | Always snapshot current first; verify before swap |

---

## 11. Decisions — ✅ ALL APPROVED 2026-09-02

| # | Question | Decision |
|---|---|---|
| **P2-1** | Schema scope | ✅ **CORE schema only.** Module-specific schemas (GST, payroll, inventory) are deferred to their own phases, since their decisions are still pending. Constraint retained: module tables, when they arrive, are created by schema migrations and are **never plan-conditional**. |
| **P2-2** | Balances | ✅ **COMPUTED, never cached as the source of truth.** Derived from the underlying accounting data; correctness and auditability take priority over read speed. |
| **P2-3** | Network/shared paths | ✅ **REFUSE**, not warn. If a company database sits on an unsupported network or sync filesystem, refuse to operate rather than risk SQLite corruption. |
| **P2-4** | Company identity | ✅ **Self-contained.** Each `company.db` carries enough identity/metadata to be safely identifiable during backup, restore, migration and recovery. |

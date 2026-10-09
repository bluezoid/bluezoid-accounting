# Database Architecture

**Status:** RECOMMENDED — awaiting sign-off
**Supersedes/expands:** ADR-001 (topology decision and its justification)

---

## 1. Topology

```
<app-data-root>/
├── app.db                        installation state — NOT customer books
├── app.db-wal / app.db-shm
├── companies/
│   ├── 7f3a…-e91c/               one directory per company (UUIDv7)
│   │   ├── company.db            THE BOOKS — one company only
│   │   ├── company.db-wal
│   │   ├── company.db-shm
│   │   └── attachments/          documents, logos, imported statements
│   └── b204…-11af/ …
├── backups/                      local snapshots (pre-migration + scheduled)
├── logs/
└── tmp/                          staging for restore/migration (same volume)
```

**Justification (validated).** The reference application has **zero**
database-level or ORM-level tenant enforcement — verified: 0 `$use`, 0
`$extends`, 0 RLS policies, and 110 route files each hand-writing
`organizationId` filters. Shared-database multi-tenancy therefore costs
permanent vigilance. One file per company converts that into a structural
property: a query cannot reach another company's rows because those rows are in
a file that is not open.

## 2. What lives where

### `app.db` — installation state

| Area | Tables (indicative) |
|---|---|
| Company registry | `company` (id, name, dir, schema_version, last_opened, status) |
| Licensing | `license`, `activation`, `entitlement_token`, `machine_binding` |
| Time anchor | `time_anchor` (monotonic anti-rollback state) |
| Updates | `app_version_history`, `migration_history` |
| Statutory rules | `rule_pack`, `tds_rule`, `gst_slab`, `hsn`, `state_code` (effective-dated) |
| Templates | seed templates for units, voucher types, roles, chart of accounts |
| Backup | `backup_history`, `backup_target`, `retention_policy` |
| Preferences | UI state, last company, window layout |
| App audit | activation, module changes, restores, company create/delete |

### `company.db` — the books

Everything accounting: chart of accounts, ledgers, vouchers and entries, parties,
documents, inventory, payroll, tax records, company-level audit, **and the
company's own voucher types, units and roles**.

### The self-containment rule

> A company file must contain everything needed to interpret its own books.

`app.db` holds *templates for creating* companies and *installation* state — never
anything a company's books depend on. This is what keeps "send my books to my CA"
and "restore this company on a new machine" working. It also fixes reference
defects D-10 and D-19 (globally-unique voucher type codes; cross-tenant units).

**Consequence:** statutory rule packs are a genuine tension. Rates live in
`app.db`, but a *posted* tax figure must be reproducible from the company file
alone. **Resolution: posted documents store the resolved tax figures and the
rule-pack version used.** Recomputation is never required to read history.

## 3. Connection management

**Per company file, at most one write connection and a small read pool.**

```
CompanyContext
├── writeConnection   single, serialized; ALL mutations
└── readPool          2–4 connections; reports and lists (WAL allows concurrent reads)
```

Rules:
1. **One writer per file.** SQLite permits one anyway; making it explicit turns a
   random `SQLITE_BUSY` into a queue with predictable ordering.
2. **The JavaFX UI thread never touches the database.** Use cases run on a
   background executor; results marshal back via `Platform.runLater`.
3. Background work (backup, import, integrity check) uses the *same* write queue
   so it cannot collide with interactive posting.
4. Connections are opened lazily on company open and closed on company switch.
5. Pooling: for SQLite a hand-rolled pool is usually simpler and safer than
   HikariCP, because the write side must be a single dedicated connection, not a
   pool member. (Driver specifics pending research — see OPEN_DECISIONS.)

### Mandatory pragmas — applied by the connection factory, not by callers

```sql
PRAGMA journal_mode = WAL;      -- persistent; set once per file
PRAGMA foreign_keys = ON;       -- PER CONNECTION, mandatory
PRAGMA busy_timeout = 5000;     -- per connection
PRAGMA synchronous = FULL;      -- durability over speed (accounting)
PRAGMA temp_store = MEMORY;
PRAGMA cache_size = -32000;     -- ~32MB, tune later
```

`foreign_keys` **defaults to OFF and is per-connection** (verified). A connection
that forgets it silently accepts orphaned rows. There must be a startup
self-check that asserts a violation actually throws.

## 4. Transaction boundaries

- The **application/use-case layer** owns the transaction. Domain code never
  begins or commits one.
- One use case = one transaction. Audit rows are written **inside** it (the
  reference's discipline, which should be structural here — see NR-4).
- `BEGIN IMMEDIATE` for any transaction that will write, so lock acquisition
  fails fast rather than mid-way through.
- **Cross-database transactions are not used.** `app.db` and `company.db` are
  never written in one atomic unit. Where a workflow spans both (e.g. create
  company = registry row + new file), sequence it so the company file is created
  and validated **first**, and the registry row last — an orphaned file is
  recoverable, a registry row pointing at nothing is not.

## 5. Company switching

```
close current company
  → flush + checkpoint WAL (PRAGMA wal_checkpoint(TRUNCATE))
  → close read pool, then write connection
  → release file lock
open target company
  → acquire exclusive app-level file lock
  → open write connection, apply pragmas
  → quick_check + foreign_key_check
  → read schema_version → MIGRATE ON OPEN if behind (see §7)
  → refuse if file is NEWER than the app supports
  → open read pool
  → load company context into UI
```

Switching must be explicit and modal — never two companies open for writing at
once. Read-only cross-company reporting is a separate, deliberate feature (§9).

## 6. Locking and the second-instance problem

Two app instances opening one company file is a real scenario (user
double-clicks twice). SQLite will not corrupt, but in-memory caches and any
denormalized balance would diverge.

Defences, layered:
1. **Single-instance application lock** (OS-level named mutex / lock file with
   pid) — second launch focuses the existing window.
2. **Per-company-file advisory lock** — a lock file beside `company.db`
   recording pid + hostname + timestamp, plus a heartbeat so a stale lock from a
   crashed process can be detected and offered for override.
3. SQLite's own locking as the final backstop.

## 7. Migration strategy

Each company file carries its **own** `schema_version`. Migration happens
**on open**, never as a global startup sweep — a company file may be absent at
update time (external drive, archived, restored later).

```
version == expected  → proceed
version >  expected  → REFUSE. "Made with a newer version." Never open.
version <  expected  → backup → verify → migrate → validate → commit
```

Verification is not just `integrity_check`; it includes an **accounting
invariant**: total debits == total credits across posted entries. A migration can
be structurally valid and still have corrupted the books (mis-scaled money
column, dropped entry) — this catches that class.

SQLite cannot `ALTER COLUMN`, add a FK to an existing table, or change
nullability in place. Those need the **12-step table rebuild**. Migrations are
therefore hand-authored SQL, forward-only, numbered, immutable once shipped.

`app.db` migrates on launch, before any company is opened.

## 8. Integrity, corruption and recovery

**Scheduled/triggered checks**
- On open: `PRAGMA quick_check` (cheap).
- Before backup and after migration: `integrity_check` + `foreign_key_check` +
  trial-balance-equals-zero.
- Periodic (weekly, idle): full `integrity_check`.

**Corruption causes to design against**
- Filesystem copy of a live WAL database → **backup must use the Online Backup
  API or `VACUUM INTO`, never a file copy.**
- Network filesystems (SMB/NFS) — WAL needs shared memory and misbehaves. The app
  must **detect a non-local path at company open** and refuse or warn loudly.
  Accountants *will* put data on a shared drive.
- `synchronous = OFF` — never used.
- Killed process mid-write — WAL + `synchronous = FULL` handles this.

**Recovery ladder**
1. `quick_check` fails → offer restore from most recent verified backup.
2. Attempt `VACUUM INTO` a fresh file (often recovers readable pages).
3. `.recover`-style salvage into a new file, then re-validate invariants.
4. Never auto-overwrite the damaged file; preserve it for support.

## 9. Cross-company reporting

Deliberate feature, not a free query. Options:
- Read-only `ATTACH` of several company files in one connection (SQLite has a
  compile-time limit on attached DBs — commonly 10; must be verified and treated
  as a hard cap), or
- Sequential per-company queries aggregated in Java.

**No core accounting flow may depend on cross-company access.** Consolidation is
a reporting nicety; isolation is the guarantee.

## 10. Encryption at rest

Plain SQLite has none. Options, with real costs:

| Option | Notes |
|---|---|
| **OS full-disk encryption** (BitLocker/FileVault/LUKS) | Zero engineering cost; protects a stolen laptop; does *not* protect a copied file on a running machine |
| **SQLCipher** | Proven, but a native library per platform/arch, licensing review needed, and complicates jlink/jpackage |
| **Application-level field encryption** | Breaks indexing, sorting and SQL aggregation — unacceptable for an accounting DB |

**Recommendation:** ship v1 relying on OS disk encryption plus **encrypted
backups** (which is where data actually leaves the machine), and treat SQLCipher
as an evaluated option for a later release. Full-file encryption is worth far
less than it appears once the app itself must hold the key on the same machine.
Marked as a decision, not a foregone conclusion.

## 11. File permissions and locations

| OS | App data root |
|---|---|
| Windows | `%LOCALAPPDATA%\AccuBook\` |
| macOS | `~/Library/Application Support/AccuBook/` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/accubook/` |

- Created with **owner-only** permissions (`0700` dirs, `0600` files on POSIX;
  restricted ACL on Windows).
- **Strictly separate from program files**, so install/uninstall/update cannot
  touch books.
- Path is overridable (config + CLI flag) for customers who keep data on a second
  drive — with the network-path check from §8 applied.

## 12. Multi-company concurrency summary

| Scenario | Behaviour |
|---|---|
| One company open, user posting | Single writer, serialized; reads concurrent via WAL |
| Backup while user works | Same write queue / Online Backup API; no file copy |
| Two companies "open" | Only one is open for **write**; others closed or read-only attached |
| Second app instance | Blocked by single-instance lock |
| Same file on a network share | Detected at open; refused or warned |
| Import (Tally) running | Uses the write queue; must pass the **same** posting invariants as interactive entry (see D-22) |

---

## 13. Verified engine behaviour — and three corrections

Measured against SQLite 3.53.4 through the xerial JDBC driver on Corretto 17.
**Three of these correct recommendations made earlier in this document.**

### 13.1 ⚠️ CORRECTION: `synchronous = NORMAL`, not `FULL`

Earlier in this document I specified `synchronous = FULL` ("durability over
speed"). The measurement changes that:

| Mode | 2,000 individual commits |
|---|---|
| OFF (0) | 0.05 s |
| **NORMAL (1)** | **0.08 s** |
| FULL (2) | **5.01 s** |
| EXTRA (3) | 11.73 s |

**FULL is 63× slower than NORMAL.** And in WAL mode `NORMAL` **cannot corrupt
the database** — it risks only the last few commits on OS crash or power loss,
not integrity.

A 63× write penalty on an interactive accounting app is not a reasonable price
for protecting the last commit or two, especially when the app can re-post from
UI state. **Revised recommendation: `synchronous = NORMAL` in WAL mode.**

### 13.2 ⚠️ CORRECTION: driver defaults differ from CLI defaults

| Pragma | CLI default | **xerial driver default** |
|---|---|---|
| `busy_timeout` | 0 | **3000** |
| `foreign_keys` | 0 (off) | **0 (off)** |

`foreign_keys` is off **everywhere** and must be set on every connection.
`busy_timeout` differing between CLI and driver means testing via `sqlite3` does
not reproduce app behaviour. **Set both explicitly, always.**

Measured impact of `busy_timeout` — 4 concurrent processes × 300 writes:

| busy_timeout | Rows written | Lost |
|---|---|---|
| 10000 | **1200 / 1200** | 0 |
| 0 | 300 / 1200 | **900 writes silently lost** |

### 13.3 🔴 CRITICAL: `BEGIN IMMEDIATE` is mandatory, and JDBC does not do it

> A deferred transaction that upgrades read→write returns `SQLITE_BUSY`
> **instantly, regardless of `busy_timeout`.**

`setAutoCommit(false)` issues a plain **deferred** `BEGIN`. So the natural JDBC
idiom silently defeats the busy-timeout protection above. Every writing
transaction must execute `BEGIN IMMEDIATE` explicitly.

Treat `SQLITE_BUSY` and `SQLITE_BUSY_SNAPSHOT` identically (restart the
transaction) — current versions do not reliably distinguish them.

### 13.4 🔴 CRITICAL: a constraint violation does NOT roll back the transaction

The single most dangerous difference from PostgreSQL:

```sql
BEGIN;
INSERT INTO t VALUES (1, 10);
INSERT INTO t VALUES (2, -5);   -- CHECK constraint failed
INSERT INTO t VALUES (3, 30);   -- SUCCEEDS  (Postgres: transaction aborted)
COMMIT;                          -- commits rows 1 and 3
```

PostgreSQL poisons the transaction; **SQLite aborts only the statement**. A Java
`catch`-and-continue around a failed statement will therefore **silently commit a
partial journal entry** — precisely the "voucher that balanced on paper while
only some effects landed" failure the reference app fought.

**Mandate:** the DAO/unit-of-work layer must roll back explicitly on any
`SQLException`. Never rely on the engine. This must be a tested invariant.

Note the reference's Postgres-era comments describe transaction poisoning as a
*constraint* they worked around; under SQLite it becomes a *hazard* instead.

### 13.5 WAL growth — a single leaked reader is unbounded

With one long-lived reader open, five write batches grew the WAL to **6 MB while
the main DB stayed at 12 KB and zero pages were ever checkpointed**:

```
batch 1: wal=1,194,832  checkpointed=0
batch 5: wal=6,089,392  checkpointed=0
```

`wal_autocheckpoint` (default 1000 pages ≈ 4 MB) **cannot run while any reader is
open**. Releasing the reader let `wal_checkpoint(TRUNCATE)` reclaim everything.

**Mandates:** read connections are short-lived and always closed;
`leakDetectionThreshold` enabled in development; explicit
`wal_checkpoint(TRUNCATE)` on company close and on idle;
`journal_size_limit` set per connection (it is **not** persistent).

### 13.6 Backup: both mechanisms verified on a live database

`VACUUM INTO` and the Online Backup API both worked **while another process held
an open write transaction** — acting as readers taking a consistent snapshot.
`integrity_check` on the copy returned `ok`, and the uncommitted row was
correctly absent. `VACUUM INTO` compacted 11.1 MB → 5.5 MB.

Two caveats:
- **`VACUUM INTO` fails from inside your own transaction**
  (`cannot VACUUM from within a transaction`).
- The output file reverts to `journal_mode=delete` — re-enable WAL on restore.

The driver exposes the real Online Backup API with a throttling overload
(`sleepTimeMillis`, `nTimeouts`, `pagesPerStep`), which is what to use so a large
backup does not stall the UI thread.

### 13.7 Integrity check costs (53 MB, 300k rows)

| Check | Time | Detects |
|---|---|---|
| `integrity_check` | **0.90 s** | full btree + **index↔table content** |
| `quick_check` | **0.06 s** | btree structure only |
| `foreign_key_check` | 0.02 s | orphaned FK rows |

15× difference. **Confirms the plan:** `quick_check` on open, full
`integrity_check` before backup / after migration / on user request.

### 13.8 ATTACH limit is higher than assumed

- System CLI: **max 10**
- **xerial driver: `MAX_ATTACHED=125`**

So cross-company reporting via `ATTACH` is more viable than §9 assumed. Cross-database
transactions are **atomic** — a `ROLLBACK` spanning two attached files correctly
reverted both. The design still should not make core flows depend on it.

### 13.9 Unclean shutdown recovers correctly

SIGKILL mid-transaction: sidecars survived, next open replayed the WAL,
committed rows present, uncommitted row discarded, `integrity_check` = `ok`.
Automatic and correct.

### 13.10 Network filesystems — confirmed prohibited

**WAL requires shared memory and cannot work at all across a network
filesystem.** Additionally, cloud-sync folders (Dropbox, OneDrive, Google Drive)
copy the `.db` independently of `-wal`/`-shm`, producing a torn, unrecoverable
file.

**Mandate:** detect non-local paths *and* known sync-folder locations at company
open, and refuse. This is not a warning-level issue — it is a guaranteed
corruption path, and accountants genuinely do this.

### 13.11 Revised connection pragma set

```sql
PRAGMA journal_mode = WAL;        -- persistent; set once per file
PRAGMA foreign_keys = ON;         -- per connection, MANDATORY (off by default)
PRAGMA busy_timeout = 10000;      -- per connection; 0 loses writes
PRAGMA synchronous = NORMAL;      -- REVISED from FULL: 63× faster, still safe in WAL
PRAGMA temp_store = MEMORY;       -- also required if encryption is ever added
PRAGMA journal_size_limit = 6291456;
PRAGMA cache_size = -32000;
-- and for read-only connections:
PRAGMA query_only = 1;
```

Plus, per writing transaction: **`BEGIN IMMEDIATE`**, never a bare
`setAutoCommit(false)`.

### 13.12 Pooling

HikariCP works (the old `isValid()` objection is obsolete — do **not** set
`connectionTestQuery`), but its usual benefit is near-zero for an embedded file
database. What it buys is lifecycle discipline and leak detection.

**Recommended: two pools against the same file.**
- **Writer:** `maximumPoolSize = 1`, `autoCommit = false` — makes the
  single-writer rule a *structural invariant* rather than a convention.
- **Readers:** 4–8, `PRAGMA query_only = 1`.
- `maxLifetime = 0`, `idleTimeout = 0`, `leakDetectionThreshold = 10000` in dev.

Pragmas are applied via `SQLiteConfig` on a `SQLiteDataSource`
(`connectionInitSql` accepts only **one** statement).

### 13.13 Encryption — revised guidance

`io.github.willena:sqlite-jdbc` is a drop-in Apache-2.0 fork with
SQLite3MultipleCiphers (ChaCha20-Poly1305 or SQLCipher-compatible AES-256),
all platform natives in one jar. SQLCipher Community has **no JDBC binding**;
SEE is C-source only at $2,000.

Three hard constraints if encryption is adopted:
1. **`PRAGMA rekey` does not work in WAL mode** — a "change master password"
   feature must drop to `journal_mode=DELETE`, rekey, then restore WAL.
2. **`PRAGMA temp_store = MEMORY` is mandatory** — temp files are **never
   encrypted**, so large sorts and GROUP BYs would silently spill plaintext
   ledger data to disk. This is a serious, non-obvious leak.
3. **Stock `org.xerial:sqlite-jdbc` must not also be on the classpath** — two
   SQLite libraries in one process can make cipher pragmas **silently no-op**.

Given (2) especially, the earlier recommendation stands: **OS-level disk
encryption plus encrypted backups for v1**, with the Willena fork as a
well-understood upgrade path.

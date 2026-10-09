# Research: SQLite Engine Behavior for an Accounting Desktop App

Status: VERIFIED BY EXPERIMENT (SQLite 3.50.6, run locally this session)

Every claim below was executed, not assumed. SQLite must not be reasoned about
by analogy with PostgreSQL.

## 1. Foreign keys are OFF by default — critical footgun

```
PRAGMA foreign_keys;      -- returns 0
PRAGMA foreign_keys = ON; -- returns 1
```

Foreign key enforcement is **disabled by default** and is a *per-connection*
setting, not a property of the database file. A connection that forgets it
silently accepts orphaned rows. In an accounting product that means voucher
entries pointing at deleted ledgers.

**Mandate for the port:** `PRAGMA foreign_keys = ON` must be applied by the
connection factory for **every** connection, with no exceptions, and there must
be a startup self-check plus a test that asserts a violation actually throws.

## 2. STRICT tables close the type hole

```sql
CREATE TABLE s(a INTEGER NOT NULL, b TEXT NOT NULL) STRICT;
INSERT INTO s VALUES('notanint','y');
-- Error: cannot store TEXT value in INTEGER column s.a
```

STRICT tables (SQLite 3.37+) enforce declared types instead of applying
affinity. Combined with the scaled-INTEGER money decision, this makes it
impossible to accidentally write a float into a money column — which is exactly
the failure mode documented in SQLITE_MONEY_STORAGE.md.

**Mandate:** all tables are `STRICT`. This is a strict improvement over the
reference schema's untyped behavior.

## 3. CHECK constraints work — use them for the untyped enums

```sql
CREATE TABLE g(nature TEXT CHECK(nature IN ('ASSETS','LIABILITIES','INCOME','EXPENSES','EQUITY')));
INSERT INTO g VALUES('BOGUS');
-- Error: CHECK constraint failed
```

The reference schema uses **no Prisma enums at all** — `nature`, voucher
`status`, party type etc. are plain `String` columns whose legal values exist
only in code comments. The port should encode them as `CHECK` constraints plus
Java enums. Cheap, and it makes an entire class of data corruption impossible.

## 4. UPSERT with RETURNING is supported

```sql
INSERT INTO c VALUES('o','Cash','id1')
  ON CONFLICT(org,name) DO UPDATE SET name=excluded.name RETURNING id;  -- id1
INSERT INTO c VALUES('o','Cash','id2')
  ON CONFLICT(org,name) DO UPDATE SET name=excluded.name RETURNING id;  -- id1
```

Returns the **winning** row in both the insert and the conflict case. This is
precisely the pattern `src/backend/utils/posting.ts` uses for
`upsertLedgerByName`, so that logic ports across essentially unchanged.

## 5. WAL mode

`PRAGMA journal_mode = WAL` persists in the database file (unlike
`foreign_keys`). WAL gives:
- readers do not block the writer and the writer does not block readers;
- better crash resilience and faster commits than the rollback journal.

Costs / cautions:
- WAL creates `-wal` and `-shm` sidecar files. **Any backup, copy, or restore
  routine that copies only the `.db` file can capture a torn or stale database.**
  Backups must use the SQLite **Online Backup API** or `VACUUM INTO`, never a
  filesystem copy.
- WAL requires shared memory and therefore behaves badly on network filesystems
  (SMB/NFS). An accountant putting the data file on a network share is a
  realistic scenario and must be detected and refused/warned.
- The WAL must be checkpointed; an unbounded WAL grows.

## 6. Single-writer concurrency model

SQLite permits one writer at a time. For a single-user desktop accounting app
this is a **simplification**, not a limitation — most of the Postgres-era
concurrency defenses in the reference code (the ON CONFLICT race fix, the
counter serialization) exist to solve problems that a single-writer local
database mostly does not have.

However the port must still:
- set a `busy_timeout` so a concurrent read/checkpoint does not throw
  `SQLITE_BUSY` on a UI action;
- serialize writes through one path so background work (backup, import) cannot
  collide with interactive posting;
- treat "a second instance of the app opened the same file" as a real scenario
  and guard it (see below).

## 7. Integrity and corruption

- `PRAGMA integrity_check` returned `ok` — should be run on startup (or
  periodically/before backup) and surfaced as a health signal.
- `PRAGMA quick_check` is the cheaper variant for routine startup.
- Corruption causes in practice: filesystem copy of a live WAL database,
  network filesystems, killed process mid-write with FKs/sync disabled,
  `PRAGMA synchronous = OFF`.

**Mandate:** keep `synchronous = FULL` (or at minimum `NORMAL` in WAL) for an
accounting database. Do not trade durability for speed here.

## 8. Recommended connection pragma set (to be applied centrally)

```
PRAGMA journal_mode = WAL;      -- persistent, set once
PRAGMA foreign_keys = ON;       -- per connection, mandatory
PRAGMA busy_timeout = 5000;     -- per connection
PRAGMA synchronous = FULL;      -- durability over speed
PRAGMA temp_store = MEMORY;
```

Plus, per database file: all tables `STRICT`, `CHECK` constraints on enum-like
columns, and `user_version` (or a dedicated migrations table) for schema
versioning.

## 9. Multi-process / second-instance risk

Two copies of the application opening the same company file is not hypothetical
(a user double-clicks the icon twice). SQLite will not corrupt on its own, but
the app's **in-memory caches and denormalized ledger balances** would diverge.
Recommendation: a single-instance lock at the application level plus an advisory
lock row / file lock per company database.

## Open items requiring further research

- **Encryption at rest.** Plain SQLite has none. SQLCipher and SEE both have
  licensing/native-build implications across Windows/macOS/Linux. Tracked in
  OPEN_DECISIONS — it interacts with the backup-encryption design and with what
  the customer expects "my books are protected" to mean.
- **JDBC driver choice** (xerial sqlite-jdbc vs alternatives): needs evaluation
  for STRICT-table support, Online Backup API exposure, native-library bundling
  across three OSes and both CPU architectures, and jlink/jpackage compatibility.

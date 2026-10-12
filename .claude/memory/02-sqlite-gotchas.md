---
name: sqlite-gotchas
description: Measured SQLite behaviours that will corrupt accounting data if forgotten
metadata:
  type: reference
---

# SQLite gotchas — all measured, not assumed

## The money problem
A column declared `DECIMAL(18,4)` is stored as an IEEE-754 **`real`**.
`sum(a) = 0.3` returns **FALSE**. The declaration is cosmetic.

- **`SUM()` over a TEXT column silently returns `real`** — so TEXT storage
  forfeits SQL aggregation, which is what every report needs.
- Non-numeric TEXT (`'Rs 500'`, `'1,234.56'`) silently sums to **0**.
- TEXT sorts lexicographically: `-100 | -5 | 10 | 2`.
- INTEGER at scale 4 covers ±₹922 lakh crore (~3× India's GDP) and is exact.
- **Never** `amount_e4 / 10000.0` in SQL — that is float division.
- **Never** multiply money by a rate in SQL: `money_e4 × fx_e8` ≈ 8.31e19,
  overflowing `long` by ~9×, and SQLite silently degrades it to a float.

## Two per-connection settings that fail silently
- **`foreign_keys` defaults to OFF** and is per-connection. A connection that
  forgets it accepts orphaned rows.
- **`busy_timeout` defaults to 0 in the CLI, 3000 in the driver.** Measured with
  0: four concurrent writers **lost 900 of 1200 writes**.

## Transactions differ from PostgreSQL
**SQLite does not poison a transaction on a constraint violation.** It aborts
only the statement; the transaction stays usable. A catch-and-continue would
commit half a voucher. **Explicit rollback is mandatory.**

`BEGIN IMMEDIATE` is required: a deferred transaction that upgrades read→write
fails with `SQLITE_BUSY` **instantly, regardless of `busy_timeout`**.
Do *not* use `SQLiteConfig.setTransactionMode(IMMEDIATE)` — it desynchronises
JDBC's `autoCommit` flag from SQLite's real state on a reused connection. Issue
the statement explicitly.

## Other
- `synchronous = NORMAL`, not FULL: measured **63× faster** (0.08s vs 5.01s for
  2000 commits) and it cannot corrupt in WAL mode.
- **Never file-copy a live WAL database** — committed data may be in the `-wal`
  sidecar. Use `VACUUM INTO` or the Online Backup API. `VACUUM INTO` cannot run
  inside a transaction or on a `query_only` connection.
- **A single leaked reader blocks checkpointing indefinitely** — measured a 6 MB
  WAL against a 12 KB database.
- **WAL cannot work on a network filesystem** (needs shared memory), and
  cloud-sync folders copy the `.db` independently of its sidecars. Detect and
  refuse.
- STRICT tables reject a float in an INTEGER column — the schema-level guard
  behind decision B-3.
- `quick_check` 0.06s vs `integrity_check` 0.90s on 53 MB: cheap one on open,
  full one before backup and after migration.

## Learned in P9b (2026-10-12)
- A BEFORE trigger's RAISE fires before CHECK constraints: a test expecting
  "CHECK" gets the trigger's message if a trigger also objects.
- To backfill a column on posted (immutable) rows inside a migration: DROP the
  immutability trigger, UPDATE, CREATE the new trigger - all in the one
  migration transaction. A table with no constraints can be rebuilt by
  CREATE new / INSERT SELECT / DROP / RENAME (copy, never assume empty).
- JDBC `wasNull()` describes the column read LAST - call it straight after
  the getter, never inside a constructor argument list after other getters.

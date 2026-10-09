# Update & Database Migration Architecture

Status: PROPOSED — awaiting sign-off
Addresses brief §17 (update system), §18 (upgrade/downgrade installers),
§19 (schema strategy).

**The governing rule (brief §40): an application update must never delete,
reset, replace or corrupt accounting data.** Everything below follows from it.

---

## 1. Three independent version numbers

Conflating these is the classic source of update disasters:

| Version | Lives in | Meaning |
|---|---|---|
| **Application version** | the binary | e.g. `1.4.2` |
| **Schema version** | `user_version` / a `schema_migrations` table in *each* database file | integer, monotonic |
| **Statutory rule-pack version** | data, in `app.db` | tax rates/formats effective-dated |

An app declares the schema version it requires. Each company file records the
version it is at. The rule pack updates **independently** of the app — essential,
because a Finance Bill change must reach customers who have not updated the
application (see TAXATION_VERIFIED §8).

## 2. Per-file migration, driven by ADR-001

With one database per company, migration is **not** a single global event:

- Each company file carries its own `schema_version`.
- **Migrate on open**, never "migrate everything at startup" — a company file may
  be absent at update time (external drive, archived, restored later).
- A file at a **newer** schema than the app must be **refused**, clearly, never
  opened. Opening a newer file with older code is how data gets destroyed.
- `app.db` migrates on launch.

This is a first-class requirement, not an edge case. A customer will restore a
year-old company file after three app updates, and it must simply work.

## 3. The migration transaction

```
open company.db
  read schema_version
  if version == expected           → proceed
  if version >  expected           → REFUSE, tell the user to update the app
  if version <  expected:
     1. BACKUP  → snapshot via Online Backup API / VACUUM INTO
     2. VERIFY  → integrity_check + foreign_key_check + trial balance == 0
                  (a backup that is not verified is not a backup)
     3. MIGRATE → each step in its own transaction, in order
     4. VALIDATE→ integrity_check, foreign_key_check, trial balance == 0,
                  row-count reconciliation vs the pre-migration manifest
     5. COMMIT  → write new schema_version
     on ANY failure:
        restore the pre-migration snapshot, keep the failed copy for diagnosis,
        report precisely what happened. NEVER leave a half-migrated file.
```

**Step 4's trial-balance check is the accounting-specific safeguard.** A
migration can be structurally valid and still have corrupted the books (a
mis-scaled money column, a dropped entry). Re-asserting that debits equal credits
catches exactly that class of error, and it is cheap.

### SQLite-specific migration constraints (verified)

SQLite cannot `ALTER COLUMN`, cannot add a FK to an existing table, and cannot
change nullability in place. Those require the **12-step table rebuild**:
create new table → copy → drop old → rename → recreate indexes/triggers, with
`PRAGMA foreign_keys = OFF` during the rebuild and `foreign_key_check` after.

The reference app's migration 17 does exactly these operations in Postgres
(`ALTER COLUMN ... SET NOT NULL`, post-hoc `ADD CONSTRAINT ... FOREIGN KEY`,
`CREATE TEMP TABLE ... ON COMMIT DROP`, `gen_random_uuid()`, `UPDATE ... FROM`).
**Confirmation that migrations must be hand-authored for SQLite, not translated.**

**Rules:** migrations are forward-only, numbered, immutable once shipped, and
hand-written SQL (no auto-generated diffs). Every migration ships with a test
that runs it against a realistic populated database, not an empty one.

## 4. Data migrations need the same rigour as schema migrations

The reference app has genuine data migrations — e.g. `14_direct_expenses_affect_gross_profit`
which flips a flag that changes reported gross margin, and `17_hr_masters_per_tenant`
which *clones* shared rows per tenant and deliberately **aborts** on ambiguous
data ("Failing here is recoverable; a silently mismatched HR master set is not").

That instinct is exactly right and should be policy: **when a data migration
cannot determine the correct result, it must stop, not guess.** A migration that
silently mis-assigns accounting data is far worse than one that fails loudly.

Note also the porting consequence of defect D-1: `openingBalanceType` is stored
as `DEBIT`/`CREDIT` but read as `DR`/`CR`, so **stored opening-balance direction
cannot be trusted** on any data imported from the web app. It must be re-derived,
and that re-derivation is itself a data migration requiring the stop-don't-guess
rule.

## 5. Update delivery

**Recommendation: installer-based updates, with in-app detection.**

The app checks for updates (or the user checks manually), downloads, **verifies a
signature**, and hands off to the platform installer. It does not attempt to
patch itself in place.

Rationale: self-patching a running JVM across three platforms is fragile;
platform installers already handle elevation, file locking, uninstall entries,
and code-signing trust. The complexity of a custom updater is not repaid.

**Non-negotiable:** the update package is **signed** and the signature is
verified before execution. An unsigned or unverified update channel is a remote
code execution path into an accounting system.

Update flow:
```
check (signed manifest: version, min-supported-version, URL, hash, notes)
  → download → verify hash + signature
  → prompt (never force mid-session; an accountant may be mid-entry)
  → close cleanly → run installer → relaunch
  → on first launch: migrate each company file on open
```

**Downgrade of the application itself must be refused** where the schema has
advanced — the older binary cannot read the newer files. The installer should
detect and warn rather than let a customer break their data by "reinstalling the
old version". Note this is distinct from a *plan* downgrade (ADR-002), which is
safe by design.

## 6. Plan-change installers (brief §18)

Because schema is universal (ADR-002), a plan change is **only** a code change:

```
BASIC → PROFESSIONAL     new installer with more modules; data untouched;
                         refreshed entitlement activates them
PROFESSIONAL → BASIC     new installer with fewer modules; data untouched;
                         those modules become READ_ONLY / DORMANT
```

**"Module not installed" must never imply "module data should be deleted."**
This should be written into the uninstall/installer scripts as an explicit
guarantee: installers touch program files only, never the app-data directory.

An uninstall must likewise never remove company databases without an explicit,
separate, clearly-worded confirmation — and even then it should offer to export
first.

## 7. Rule-pack updates (statutory data)

Separate, lightweight, signed channel:
```
rule-pack v2026.04  → TDS rates/thresholds, GST slabs, HSN, portal formats,
                      state codes, due-date rules — all effective-dated
```
Applied to `app.db`, versioned, and **effective-dated so historical periods keep
computing with the rules that applied then**. This is the single most important
structural fix versus the reference app, where `TDS_RULES` is a compiled constant
with **no effective-date dimension at all** — recomputing a prior-year deduction
today would apply today's rates.

**RESEARCH REQUIRED:** whether return *formats* (GSTR JSON shapes) can be fully
data-driven, or whether format changes inevitably require code. Rates and
thresholds are clearly data; JSON structure changes may not be.

## 8. Testing requirements

- Migration from **every** shipped schema version to current, on realistic data.
- Migration interrupted at each step (kill the process) → rollback verified.
- Company file at N-3 versions opened by a current app → migrates correctly.
- Company file at N+1 → refused cleanly.
- Update over an installation with several companies in different states.
- Uninstall/reinstall → data survives.
- Plan downgrade then re-upgrade → all data intact and mutable again.
- Trial balance sums to zero before and after every migration.

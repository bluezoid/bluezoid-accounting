# ADR-001: SQLite Database Topology for Multi-Company

Status: **RECOMMENDED — awaiting sign-off**
Date: 2026-08-27
Context: Brief §7 — one accountant may manage Company A, B, C in one installation.

---

## Problem

A single desktop installation must host several companies' books. SQLite must be
laid out so that one company's data cannot leak into, or corrupt, another's —
while keeping backup, restore, migration and reporting workable.

## Decisive evidence from the reference application

Three findings from the code analysis drive this decision. All are
VERIFIED FROM EXISTING ACCUBOOK.

**1. Tenant isolation in the web app is manual convention, not enforcement.**
There is no Prisma middleware, no client extension, and no Postgres RLS. Isolation
rests on two things a developer must remember at every call site:
- `withOrgAuth` proves the *caller* belongs to the org in the URL (110 of 111
  org-scoped route files use it);
- `findForeignReferences` validates client-supplied FKs — but it is **opt-in per
  handler**, and every remaining query hand-writes `organizationId: orgId`.

The codebase itself records what happens when this discipline lapses: a comment
in `with-org-auth.ts` notes that *"55 of the 71 mutating route files did not"*
call `hasPermission`, so a VIEWER could post journal vouchers and run payroll
until enforcement was centralised. That is the same class of failure applied to
permissions rather than tenancy — evidence that a convention-based invariant
across ~110 files does eventually fail.

**2. Some entities have no `organizationId` at all.** `Role`, `VoucherType`,
`UnitOfMeasure` and `Currency` are **global** tables. `VoucherType.code` is
globally `@unique`. `ExpenseClaim.claimNumber` is globally unique. Some models
(`ExpenseClaim`, `Leave`) are scoped only *indirectly*, through a relation
(`where: { employee: { organizationId } }`).

**3. There is already a live cross-tenant write primitive.** The manual
reconciliation `match` action writes `voucherId` straight from the request body
**with no org ownership check** (see DEFECTS, banking section).

The conclusion is not that the web app is badly built — it is that
*shared-database multi-tenancy costs continuous vigilance*, and that cost was
being paid by code review in a system with a professional team and CI.

## Options

### Option A — one SQLite file, all companies, `company_id` on every table

- Mirrors the current Postgres model, so domain code ports with least change.
- Cross-company reporting is a simple query.
- Single file to back up.
- **But** it reproduces exactly the invariant that requires perfect discipline
  in every query, in a product where the developer is a small team and the blast
  radius is a customer's statutory books. One missing `WHERE company_id = ?` in a
  report is a confidentiality breach between two of the accountant's clients.
- One corrupt file loses **every** company.
- A single write lock is shared across companies.
- Restoring one company from backup means surgically extracting rows.

### Option B — one SQLite file per company

- **Isolation is structural, not conventional.** A query cannot reach another
  company's data because that data is in a different file that is not open.
  This is the decisive advantage: it converts a discipline problem into an
  architecture property.
- Corruption is contained to one company.
- Backup, restore, archive, and "send my books to my CA" are all
  *copy one file* — which is what accountants already expect.
- Per-company WAL and write lock; no cross-company contention.
- Company deletion is a file delete. Company-level encryption is per file.
- **Costs:** migrations must run per file (N files × M migrations), with the
  real risk that one file is left behind at an older schema — this must be
  handled explicitly, not assumed. Cross-company reporting requires either
  `ATTACH` or sequential queries. Shared/global reference data needs a home.

### Option C — hybrid: one application/platform DB + one file per company

- A small `app.db` holds installation-level state: registered companies and
  their file paths, licence and entitlement state, machine binding, update
  history, user preferences, backup metadata, and the global reference data
  the web app keeps in global tables (currencies, and the *templates* for units
  / voucher types / roles).
- Each company gets `companies/<uuid>/company.db` holding **only** that
  company's books, with its own schema version.
- Cross-company reporting uses `ATTACH DATABASE` (SQLite supports attaching
  multiple files in one connection, read-only) or sequential aggregation.

## Recommendation: **Option C (hybrid)**

Option C keeps Option B's structural isolation — the property that actually
protects the customer — while giving licence, entitlement and company-registry
state a proper home that is not inside any one company's books.

This separation matters for reasons beyond isolation:

- **Licensing must not live in a company file.** If entitlement state were stored
  per company, deleting or restoring a company file would alter licence state.
  Keeping `app.db` separate means restoring last month's books cannot roll back
  the subscription, and copying a company file to another machine does not carry
  an activation with it.
- **The downgrade requirement (brief §9) is an app-level concept.** Which modules
  are entitled is a property of the installation, while the *data* those modules
  wrote is per company. Option C expresses that split directly.
- **Backup granularity matches user intent.** Accountants think in companies.

### Concrete layout

```
<app-data>/
├── app.db                  installation state (NOT customer books)
│     companies registry (id, name, file path, schema version, last opened)
│     licence + activation + entitlement snapshot
│     machine binding
│     application/update history
│     global reference templates (currencies, unit/voucher-type/role templates)
│     backup history + metadata
│     application-level audit (activation, module changes, restores)
│
├── companies/
│   ├── <company-uuid>/
│   │   ├── company.db      the books — one company only
│   │   ├── company.db-wal
│   │   └── attachments/    documents, logos, imported statements
│   └── <company-uuid>/ ...
│
├── backups/                local pre-migration and scheduled snapshots
└── logs/
```

### Consequences to plan for

1. **Migrations run per company file and must be transactional and resumable.**
   A company file that was not open during an update (external drive, restored
   later) will be at an older schema. Therefore: every company file carries its
   own `schema_version`; the app migrates *on open*, never assuming all files are
   current; and a file from a *newer* app version must be refused with a clear
   message rather than opened and damaged. This is a first-class requirement, not
   an edge case. Tracked in UPDATE_AND_MIGRATION_ARCHITECTURE.
2. **`organizationId` largely disappears from company tables.** This is a genuine
   simplification: ~40 tables shed a column, and every `WHERE organizationId = ?`
   in ported logic becomes unnecessary. But it also means **the ported code must
   be re-read rather than transliterated** — silently dropping the filter from a
   query that still needs it in some other sense would be a bug.
3. **The global tables must be resolved deliberately.** `VoucherType`,
   `UnitOfMeasure` and `Role` become **per-company** rows seeded from templates in
   `app.db` at company creation. This also fixes reference defects D-10 and D-19
   (globally-unique voucher type codes and cross-tenant units).
4. **Cross-company reporting is a deliberate feature, not a free query.** Use
   read-only `ATTACH` with a hard cap on attached databases (SQLite's default
   limit is 10 attached DBs; it is a compile-time bound), or sequential
   aggregation in Java. Do not design any core accounting flow to require it.
5. **A company file must be self-contained enough to hand to another accountant**
   — which is a real workflow. Anything a company's books depend on (its voucher
   types, units, chart of accounts) must live *in* the company file, not in
   `app.db`. `app.db` holds only templates for *creating* companies and
   installation state. This rule is what keeps "send my books to my CA" working.
6. **Single-instance guard is still required.** Two app instances opening the same
   company file would diverge in cached ledger balances. Enforce an app-level
   single-instance lock plus a per-file lock.

### What this does NOT solve

Option C does not by itself prevent a *bug within one company's logic* from
corrupting that company's books. Double-entry invariants, CHECK constraints, FK
enforcement and the posting choke point remain necessary. Isolation and
correctness are separate concerns.

## Open questions for sign-off

- **Encryption at rest** — per-company file encryption (SQLCipher) interacts with
  this layout cleanly, but has native-build and licensing implications on three
  OSes. Tracked in OPEN_DECISIONS.
- **Attachment storage** — kept beside the company DB (as above) or inside it as
  BLOBs? Files beside the DB keep the DB small and backups incremental, but make
  "one file = one company" no longer literally true, which weakens the
  hand-it-to-your-CA property. **DECISION REQUIRED.**

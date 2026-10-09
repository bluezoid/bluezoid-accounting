# CLAUDE_MEMORY.md — AccuBook Desktop

**Read this first in any new session.** Persistent project context.
Last updated: **2026-08-31** (Phase 0 complete + independently validated)

---

## 1. Product vision

A **commercial, offline-first, cross-platform desktop accounting/ERP product**
for Indian businesses. Sold under per-plan licences with **physically modular
builds**, machine-bound activation, time-bounded subscriptions, and optional
encrypted cloud backup.

One accountant may manage **several companies** in one installation.

## 2. Technology stack

| Layer | Choice |
|---|---|
| Language / runtime | **Java 17**, AWS Corretto (17.0.20.1 verified installed) |
| UI | **JavaFX / OpenJFX** |
| Database | **SQLite** (3.53.4 via driver; 3.50.6 CLI verified) |
| JDBC | **`org.xerial:sqlite-jdbc:3.53.4.0`** — Apache-2.0 + BSD-2, real JPMS module, Online Backup API exposed |
| Packaging | **jlink `--bind-services` + jpackage**, native preinstalled via `org.sqlite.lib.path` |
| Build | Maven (installed) or Gradle — **N-1 undecided** |

## 3. Reference project

`/home/achyut-pal/Desktop/accubook` — Next.js + TypeScript + Prisma + PostgreSQL.

> ⚠️ **READ-ONLY. Never modify, never commit to it, never touch its database.**
> Verified untouched (`git status` clean) at the end of every session so far.

It is the **accounting/business oracle** — *not* the architectural or security
oracle. See §11.

**Verified scale:** 74 models · 0 enums · 122 API routes · 18,329 lines of
services · **~103k hand-written TS** (the "300k" figure includes ~198k generated
Prisma client) · 646 tests · 73 dashboard pages · 169 Decimal columns.

## 4. Architecture decisions (all PROPOSED, awaiting sign-off)

| Area | Decision |
|---|---|
| Layering | Presentation → Application → Domain → Infrastructure, with a **sealed posting core** |
| Database | One `company.db` per company + one `app.db` (ADR-001) |
| Money | **Scaled INTEGER** storage + `BigDecimal` in code + **STRICT tables** |
| Dates | `LocalDate` for accounting dates — no zones |
| Enums | Java enums + SQLite `CHECK` constraints |
| Modules | Code packaged per plan + **universal schema** + **signed entitlement** (ADR-002) |
| Downgrade | Code removed, schema kept, **data never deleted**, module → read-only |
| Licensing | Ed25519-signed entitlement tokens; composite fuzzy machine binding |
| Subscription | Monotonic time anchor defeats clock rollback; grace → read-only |
| **Year-end** | **Approach A — real closing voucher** (reverses Phase 0) |
| **Inventory** | **Perpetual + persisted cost layers** (reverses Phase 0) |
| Backup | Online Backup API / `VACUUM INTO`; AES-256-GCM; **customer-only key** |
| Updates | Installer-based, signed; **per-company migrate-on-open** |
| Encryption at rest | OS-level for v1; Willena fork as upgrade path |

## 5. Database architecture

```
<app-data>/
├── app.db              installation state: company registry, licence,
│                       entitlement, time anchor, rule packs, templates,
│                       backup history, app audit   — NOT customer books
├── companies/<uuid>/
│   ├── company.db      THE BOOKS (self-contained: own voucher types, units, roles)
│   └── attachments/
├── backups/  logs/  tmp/
```

**Self-containment rule:** a company file must contain everything needed to
interpret its own books, so "hand it to your CA" and "restore on a new machine"
work. Posted documents store resolved tax figures **and** the rule-pack version.

**Verified pragmas (mandatory, per connection):**
```
journal_mode=WAL · foreign_keys=ON · busy_timeout=10000
synchronous=NORMAL · temp_store=MEMORY · query_only=1 (readers)
```
Plus **`BEGIN IMMEDIATE` for every writing transaction** — `setAutoCommit(false)`
issues a *deferred* begin, which returns `SQLITE_BUSY` instantly regardless of
`busy_timeout`.

## 6. Module philosophy

**CODE (per build) ≠ SCHEMA (always universal) ≠ ENTITLEMENT (signed).**
Collapsing any pair causes data loss or revenue loss. Universal schema avoids 2ⁿ
migration shapes and makes downgrade/re-upgrade lossless.

Module states: `ACTIVE` / `READ_ONLY` / `DORMANT` / `ABSENT`.
**Invariant: reads are never gated by entitlement.**
Rule: *a module owns its engines and its own tables — never columns on another
module's tables, and never posted GL entries.*
`effective = build_modules ∩ entitled_modules`; **the licence is authoritative**.

## 7. Licensing & subscription philosophy

- **Honest threat model:** a determined attacker with local admin **can** patch
  the binary. Goal is to stop casual copying/config editing/clock rollback and
  make circumvention deliberate. Priority: **customer data > revenue**.
- Ed25519; private key in HSM/KMS; client holds only public keys.
  **Embed two public keys from v1** or rotation is impossible later.
- Machine binding: composite **K-of-N fuzzy** fingerprint with silent re-bind.
- Expiry: `max(system_now, last_known_good_time)`; monotonic runtime floor.
- Grace ~14 days fully functional → then **read-only**.
- **Export always works, in every state.** No licensing failure may destroy,
  lock away or corrupt data.

## 8. Upgrade / downgrade rules

- Plan change is a **licence event, not a data event**.
- Installers touch program files only; **never** the app-data directory.
- Posted GL entries are **core** — payroll JVs stay in the trial balance after a
  payroll downgrade (otherwise the books would not balance).
- PRO → BASIC → PRO restores full mutability with **zero** data loss.
- Migrate **first**, then entitle.

## 9. Backup rules

- **Never file-copy a live WAL database.** Use the Online Backup API or
  `VACUUM INTO` (verified safe on a live DB; fails inside your own transaction).
- Verify every backup: `integrity_check` + `foreign_key_check` +
  **trial balance == 0**.
- Encrypt **before** leaving the machine (AES-256-GCM, Argon2id).
- **Customer-only key** — we genuinely cannot decrypt. A lost passphrase means
  permanent loss; warn unavoidably at setup.
- Always snapshot current state before a restore.

## 10. Security & testing rules

- Every mutating use case: entitlement check → transaction → audit **inside** it.
- Append-only, hash-chained audit emitted by the pipeline, not per handler
  (the reference drifted to ~70% coverage doing it per handler).
- **Explicit rollback on `SQLException`** — SQLite does *not* poison a
  transaction on constraint violation, unlike PostgreSQL. Catch-and-continue
  would silently commit a partial journal entry.
- A feature is done when its tests pass **and** the existing suite passes.
- Port a reference test only after confirming it exercises the production path
  (the year-end test does not).

## 11. Known reference defects — DO NOT COPY

**Critical:**
1. **D-23 — credit/debit notes post BACKWARDS.** `decideInvoiceEntries` has no
   `CREDIT_NOTE` branch; a credit note *increases* revenue and receivables.
2. **D-25 — manufacturing double-counts assets.** The `Stock-in-Hand` ledger is
   in `assetLedgerTotal` *and* derived `stockNow` is added on top.
3. **D-24 — inventory `asOf` ignores quantities.** Historical valuation is
   fiction; for weighted-average items the periodic adjustment cancels to zero.
4. **D-1 — opening balances sign-flipped.** Writes store `DEBIT`/`CREDIT`, reads
   compare `DR`/`CR`; the fallback also fails because the string is truthy.

**High:** D-22 Tally import bypasses the posting invariant (no balance check, no
tenant scoping) · D-2 cash flow finds no cash · fabricated IRN (32-bit hash +
`Date.now()`, comment falsely claims SHA-256) · D-11 SO/PO/quote numbering race ·
D-26 approvals dead-end for INVOICE/PURCHASE_ORDER · D-27 batches never
decremented · D-28 leave quota unenforced · D-29 two reconcilers, UI uses the
untested one.

**Schema-only (13):** FiscalPeriod · ItemUnit.conversionFactor ·
SalesOrderItem.deliveredQty · Stock.reservedQty · Stock.lastPurchasePrice ·
Bom.effectiveFrom/To · WorkOrder CANCELLED · VoucherType.numberingFormat ·
InvoiceTax · BillTax · ReportTemplate.schedule · Designation · multi-currency.

**Missing:** year-end close · GSTR-4 · Form 27D · FEFO · expense-claim GL
posting · budget-vs-actual.

Full detail: `docs/analysis/DEFECTS_IN_REFERENCE_APP.md` and
`docs/phase-0-review/PHASE_0_VALIDATION.md`.

## 12. Current status

**Phase 0 COMPLETE. Phase 1 IMPLEMENTED, pending review.**
**Architectural baseline CONFIRMED 2026-08-31. B-3 + B-6 APPROVED 2026-09-01.**

Phase 1 delivered: 6-module Maven/JPMS skeleton · `MoneyMapper` (scales
4/2/8/6/1) · per-company SQLite topology · pragmas + `BEGIN IMMEDIATE` +
explicit rollback · migration runner with backup/verify/rollback · JavaFX
walking skeleton · jlink+jpackage with parameterised signing.
**53 tests passing, 3 UI tests skipped (no display).** Linux `.deb` built and
verified end-to-end. See `docs/phases/PHASE_1_REPORT.md`.
Build lives in `accubook-desktop/`. **Nothing committed.**

Phase 0 was re-validated against the repository: metrics confirmed except the
LOC figure (corrected 300k → ~103k hand-written); the "single posting choke
point" claim was **refuted** (Tally bypasses it); and **four defects more severe
than anything in the original report** were found.

### Confirmed baseline (user-approved)
This report — not the earlier Phase 0 recommendations that were subsequently
reversed — is the architectural baseline. Specifically confirmed:
one `company.db` per company + `app.db` · sealed posting core ·
**perpetual inventory + persisted cost layers** · **real year-end closing
voucher** · physically packaged modules with universal schema ·
activation + machine binding + expiry → read-only · encrypted-before-upload
backup module · Windows/Linux/macOS · unit + integration + UI + regression tests.

### Locked architectural rules (binding on every phase)
1. **Entitlement enforcement starts in P4 and lives in the domain layer.**
   Write protection is enforced at the application/domain layer — **never only
   by hiding buttons in JavaFX**. Chain: physical module → installed? →
   entitlement → subscription valid? → operation allowed? → write/read-only.
   Reads are **never** entitlement-gated.
2. **Perpetual costing comes after document flows.**
   Items/Warehouses → Purchase → Sales Invoice → Dispatch/Issue → Costing →
   COGS → Accounting. Masters P7, engine P8.
3. **Testing is continuous.** Every phase: implementation → unit → integration →
   UI → concurrency/security where applicable → regression → exit.
   **P16 confirms; it never discovers.**

### Roadmap: 16 phases (baseline)
The user proposed 14 phases; dependency analysis split two overloaded phases.
**No phase was reordered in principle.** Three adjustments:
1. **Entitlement enforcement moved INTO Phase 4** with the module system — the
   persistence guard must exist before the posting core (P5) and modules (P6),
   or it must be retrofitted across finished features. The reference proves this
   drifts: 55 of 71 mutating routes forgot the permission check.
2. **Inventory split** — masters land with sales/purchase (P7); perpetual
   costing follows (P8), because COGS relieves stock against *documents* and
   returns attach to credit notes.
3. **Security/concurrency testing is a certification gate (P15), not the first
   occurrence** — concurrency invariants start in P2, security tests accompany
   the feature introducing each surface.

Rationale: `docs/decisions/ROADMAP_SEQUENCING_ANALYSIS.md`

### Decision status
Only **B-3** (money encoding) and **B-6** (topology) gate Phase 2; both are
evidence-led and effectively confirmations. B-7/B-8 gate P4, B-2 gates P5,
B-1 gates P7, B-4 gates P9, B-5 gates P15.
Worksheet: `docs/decisions/BLOCKING_DECISIONS_WORKSHEET.md`

**Next:** resolve B-3 and B-6 → start **Phase 1 in parallel** (it depends on
none of the eight). Code-signing procurement starts now.

## 13. Constraints (non-negotiable)

1. **Never modify** `/home/achyut-pal/Desktop/accubook`.
2. **Never `git add` / `commit` / `push`** unless explicitly told. Report changes
   and stop. (This directory is **not** a git repository.)
3. Do not write application code until Phase 0 decisions are signed off.
4. Never silently make a business or accounting decision — record it in
   `OPEN_DECISIONS.md`.
5. Never destroy customer accounting data for downgrade, expiry, update, module
   removal or installer change.
6. Report honestly: failing tests are reported with output.

## 14. Document map

```
CLAUDE_MEMORY.md                       ← this file
docs/
  ROADMAP.md · PROJECT_OVERVIEW.md
  phase-0-review/PHASE_0_VALIDATION.md ← what Phase 0 got right/wrong
  architecture/  DATABASE_ARCHITECTURE · MONEY_AND_ROUNDING ·
                 INVENTORY_ACCOUNTING · YEAR_END_ACCOUNTING ·
                 MODULE_ARCHITECTURE · LICENSING_AND_ACTIVATION ·
                 SUBSCRIPTION_AND_ENTITLEMENTS · UPGRADE_DOWNGRADE ·
                 UPDATE_AND_MIGRATION · BACKUP_ARCHITECTURE ·
                 ADMIN_PORTAL · CROSS_PLATFORM_PACKAGING · DESKTOP_ARCHITECTURE
  decisions/     OPEN_DECISIONS (🔴/🟠/🟡) · ADR-001 · ADR-002 · RISKS
  security/      THREAT_MODEL
  testing/       TEST_STRATEGY
  reference/     FEATURE_PORTING_MATRIX
  analysis/      4 verified domain analyses
  logs/          daily session logs (2026-08-27, 2026-08-31)
  archive/       superseded docs — do NOT treat as current
```

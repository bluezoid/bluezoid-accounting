# AccuBook Desktop — Project Memory

**Read this first.** It is the entry point for any future session. Everything
below is either verified from the reference codebase or explicitly marked as a
proposal awaiting sign-off.

Last updated: 2026-08-27 (Phase 0 — analysis & architecture)

---

## 1. What this project is

Building a **commercial cross-platform desktop accounting application** for
Windows, Linux and macOS, in **Java + JavaFX (OpenJFX)** on **AWS Corretto**,
with **SQLite** for local data, working **primarily offline**.

The functional reference is the existing AccuBook **web** application at
`/home/achyut-pal/Desktop/accubook` (Next.js + TypeScript + Prisma + PostgreSQL).

> ⚠️ **The reference app is READ-ONLY. Never modify it, never commit to it,
> never touch its database.** It was verified untouched at the end of the
> analysis session (`git status` clean).

Working directory: `/home/achyut-pal/Desktop/accubook_desktop_application`

## 2. Current status

**Phase 0 (analysis, research, architecture, documentation) is COMPLETE.**
**No application code has been written yet — deliberately, per the brief.**

Next step: the user reviews the report and settles the ⚠️ blocking decisions in
`docs/decisions/OPEN_DECISIONS.md`. Recommended first build is **Phase 1**
(walking skeleton + packaging), which is independent of those decisions.

## 3. The reference application, in numbers

| | |
|---|---|
| Prisma schema | 2,172 lines, **74 models**, **zero enums** |
| API routes | 122 |
| Backend services | ~18,000 lines |
| TypeScript total | ~300,000 lines across 390 files |
| Migrations | 18, hand-authored SQL |
| Tests | ~646 (513 unit, 106 integration, 27 Playwright) |
| Decimal columns | **169** (139 at `Decimal(18,4)`) |
| Json columns | 23 · Postgres arrays: 2 · raw SQL in app code: 3 |

Domains covered: accounting, sales, purchase, inventory, manufacturing, Indian
taxation (GST/TDS/TCS/e-invoice/e-way bill), banking, HR/payroll, approvals,
OCR, Tally import, multi-tenancy.

## 4. The ten things that matter most

1. **Money.** SQLite stores `DECIMAL(18,4)` as floating-point `real` — proven
   experimentally (`0.1+0.2 != 0.3` in a column declared decimal). Use **scaled
   INTEGER** storage + `BigDecimal` in Java + **STRICT tables**.
   → `docs/research/SQLITE_MONEY_STORAGE.md`
2. **The posting choke point.** Every posting path in the reference funnels
   through `applyLedgerEntries`, which enforces debits == credits. Preserve
   this, but enforce it **structurally** (package-private ledger mutation), not
   by convention.
3. **One SQLite file per company**, plus an `app.db` for installation state.
   Tenant isolation in the reference is *manual convention across 110 route
   files*; per-file separation makes it structural. → `ADR-001`
4. **Universal schema, modular code.** Every build ships every module's tables;
   only *code* is excluded per plan, and *entitlement* controls mutation. This is
   what makes downgrade lossless and avoids 2^N migration shapes. → `ADR-002`
5. **Reads are never gated by entitlement.** History is always viewable and
   exportable — after downgrade, after expiry, always.
6. **Statutory rules must be effective-dated data**, not compiled constants, and
   shippable as signed rule-packs independent of app updates. The reference
   hardcodes TDS rates with no effective-date dimension at all.
7. **Never fabricate an IRN.** The reference's live e-invoice route generates a
   fake IRN (32-bit hash + `Date.now()`, despite a comment claiming SHA-256) and
   marks it `GENERATED`. Build payloads only.
8. **21 catalogued defects** in the reference must not be ported by accident.
   → `docs/analysis/DEFECTS_IN_REFERENCE_APP.md` — required reading per domain.
9. **The reference test suite is the porting oracle.** Port tests first, then
   make them pass. But coverage is uneven — see §7.
10. **Licensing must never cost a customer their data.** Worst case is
    read-only; export always works.

## 5. Architecture decisions (all PROPOSED, awaiting sign-off)

| Area | Decision |
|---|---|
| Layering | Presentation → Application → Domain → Infrastructure, with a **sealed posting core** |
| Database | One `company.db` per company + one `app.db` (ADR-001) |
| Money | Scaled INTEGER storage, `BigDecimal` in code, one mapper, STRICT tables |
| Dates | `LocalDate` for accounting dates — no zones (kills a whole bug class) |
| Enums | Java enums + SQLite CHECK constraints |
| Modules | JPMS + `ServiceLoader` SPI; per-plan builds via jlink/jpackage |
| Downgrade | Code excluded, schema retained, data read-only (ADR-002) |
| Licensing | Ed25519-signed entitlement tokens; composite fuzzy machine binding |
| Subscription | Monotonic time anchor defeats clock rollback; grace then read-only |
| Backup | Online Backup API / `VACUUM INTO`; AES-256-GCM; **customer-only key** |
| Updates | Installer-based, signed; per-company-file migrate-on-open |
| Admin portal | Separate platform DB; MFA; append-only audit |

## 6. Document map

```
MEMORY.md                                    ← you are here
docs/
  ROADMAP.md                                 phased plan; what to build first
  analysis/
    ACCOUNTING_CORE_VERIFIED.md              posting, money, numbering, provisioning
    DEFECTS_IN_REFERENCE_APP.md              21 defects + reproduce/don't decisions
    PAYROLL_AND_BANKING_VERIFIED.md          payroll float hazard; two reconcilers
    TAXATION_VERIFIED.md                     GST/TDS/TCS; THE ROUNDING FINDING
  architecture/
    DESKTOP_ARCHITECTURE.md                  layering, modules, concurrency
    LICENSING_AND_SUBSCRIPTION.md            threat model, tokens, binding, clock
    BACKUP_ARCHITECTURE.md                   snapshot, encryption, restore
    UPDATE_AND_MIGRATION_ARCHITECTURE.md     versions, per-file migration
    ADMIN_PORTAL_ARCHITECTURE.md             platform backend and portal
    DEPLOYMENT_ARCHITECTURE.md               jlink/jpackage, signing, paths
  decisions/
    ADR-001-database-topology.md             one DB per company
    ADR-002-modules-and-downgrade.md         universal schema, modular code
    OPEN_DECISIONS.md                        ⚠️ BLOCKING ITEMS — read before coding
    RISKS_AND_MITIGATIONS.md                 16 risks
  research/
    SQLITE_MONEY_STORAGE.md                  the decimal experiment
    SQLITE_ENGINE_BEHAVIOR.md                pragmas, STRICT, WAL, verified
  testing/TESTING_STRATEGY.md
daily-log/                                   session records
```

## 7. Where the reference is trustworthy — and where it is not

**Well tested, port closely:** GST returns, TDS/TCS, payroll calculation and
posting, permissions, posting invariants, money helpers, bank reconciliation
scoring, recurring invoices, and the concurrency idioms (14 integration files
with deliberately interleaved transactions).

**Zero tests — treat as unspecified, not correct:** budgets, approvals,
quotations, sales/purchase orders, cost centres, projects, expense claims,
attendance, leave, report templates, notifications, audit log, **multi-currency**,
GSTReturn persistence, all master CRUD, and the **entire UI**.

**Known broken — do not port:** cash flow statement (finds no cash), opening
balance direction (`DEBIT`/`CREDIT` vs `DR`/`CR` mismatch misclassifies every
user-entered opening balance), fabricated IRN, SO/PO/quotation numbering,
attendance `WEEK_OFF`/`WEEKEND` mismatch.

**Features that look implemented but are not:** cost centres/projects (never set
by automated posting, no report uses them), budgets (never compared to actuals),
batch consumption (batches never decremented), leave balances, SO→invoice
conversion, expense-claim GL posting, UOM conversion, multi-level BOM costing
(dead code), `FiscalPeriod` (dead model).

## 8. Working rules for this project

- **Never modify `/home/achyut-pal/Desktop/accubook`.** Reference only.
- **Never run `git add` / `git commit` / `git push`** unless explicitly told.
  Report what changed and let the user review. (This directory is not currently
  a git repository.)
- Analysis → design → document → report. Implementation only when asked.
- Keep `MEMORY.md`, `OPEN_DECISIONS.md` and `DAILY_LOG.md` current whenever a
  decision changes.
- Record only work actually performed — never fabricate progress.
- If an accounting rule is ambiguous, **stop and record it in OPEN_DECISIONS**
  rather than inventing behaviour.

## 9. Verified environment

AWS Corretto **17.0.20.1** · SQLite **3.50.6** · Maven present, Gradle absent.

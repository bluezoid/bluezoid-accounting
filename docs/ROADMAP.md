# Implementation Roadmap

**Status:** BASELINE — confirmed 2026-08-31
**Supersedes:** the earlier 15-phase draft.
**Rationale for changes:** `decisions/ROADMAP_SEQUENCING_ANALYSIS.md`

This roadmap follows the user-confirmed sequence, with three adjustments arising
from dependency analysis (§2 of the analysis document). Two overloaded phases were
split, so 14 phases became 16; **no phase was moved earlier or later in
principle**.

---

## Locked architectural rules

Confirmed 2026-08-31. These are **binding on every phase** and any deviation is
a decision requiring explicit sign-off, not an implementation detail.

### Rule 1 — Entitlement enforcement starts in P4, and lives in the domain layer

The module system must not merely answer *"is this module physically present?"*.
It resolves a chain:

```
Physical module
      ↓  installed?
Entitlement
      ↓  subscription valid?
Operation allowed?
      ↓
Write  /  Read-only
```

> **Write protection is enforced in the application/domain layer — never only by
> hiding buttons in JavaFX.**

Three layers, all present from P4 onward:
1. **Use-case interceptor** — every mutating use case declares its module.
2. **Persistence guard** — the write path refuses writes to tables owned by a
   non-`ACTIVE` module.
3. **UI affordance** — disabled *and explained*, never merely hidden.

**Why layer 2 is not optional.** The reference application checked permissions
per handler and **55 of 71 mutating routes forgot** — a VIEWER could post journal
vouchers and run payroll. A UI-only gate is not a gate.

**Corollary:** reads are **never** entitlement-gated. History is always viewable
and exportable — after downgrade, after expiry, always.

### Rule 2 — Perpetual costing comes after document flows

```
Items / Warehouses
        ↓
Purchase
        ↓
Sales Invoice
        ↓
Dispatch / Issue
        ↓
Inventory Costing
        ↓
COGS
        ↓
Accounting
```

Masters in **P7**; the costing engine in **P8**. The hardest accounting component
is then built and tested against **real business flows** rather than designed in
isolation — and COGS has actual documents to relieve stock against, with returns
attached to credit notes.

### Rule 3 — Testing is continuous; P16 is a gate, not a discovery

Every phase runs the same cycle before it can exit:

```
Implementation
     ↓
Unit tests
     ↓
Integration tests
     ↓
UI tests
     ↓
Concurrency / security tests where applicable
     ↓
Regression suite
     ↓
Phase exit
```

Concurrency invariants begin in **P2** (they are a property of the storage
layer). Security tests accompany the feature that introduces the surface —
licensing in P3, entitlement in P4, backup crypto in P12.

> **P16 confirms; it must never be the first serious test of security,
> concurrency, recovery or data integrity.** A late phase that *discovers*
> architectural problems is a rewrite; one that *confirms* them is a gate.

---

## Governing rule

> **Do not implement the accounting domain until the architectural and security
> foundations are closed and the walking skeleton exists.**

---

## Phase 0 — Reference analysis & architecture ✅ COMPLETE

Reference application analysed and independently validated. 13 architecture
documents. 32 defects catalogued (4 critical). No code written; reference
untouched.

**Exit:** ✅ met. Awaiting sign-off on the 8 🔴 BLOCKING decisions.

## Phase 1 — Desktop foundation + signed cross-platform packaging ✅ COMPLETE (Linux verified; Windows/macOS/signing pending certificates and CI)
**Blocked by: NOTHING to start. B-3 + B-6 needed to finish.**

> **Sequencing note.** P1 can begin today, but two of its deliverables depend on
> the Tier-1 decisions: the `MoneyMapper` needs **B-3**'s per-class scales, and
> the migration runner needs **B-6**'s per-company-file topology. Neither is a
> debate (both are evidence-led confirmations), so the practical guidance is:
> **start P1 now; settle B-3 and B-6 before the P1 work reaches those two
> items.** Everything up to that point — build skeleton, JPMS layout, JavaFX
> shell, packaging pipeline, signing — is genuinely unblocked.

- Maven/Gradle multi-module skeleton; JPMS layout; `FeatureModule` SPI stub
- SQLite connection factory with the verified pragma set
- Two-pool model (writer size 1, reader pool); **`BEGIN IMMEDIATE`** on writes
- **Explicit rollback on `SQLException`** — SQLite does not poison transactions
- Money type + `MoneyMapper` (`longValueExact()` boundary) + property tests
- Migration runner: backup → verify → migrate → validate → rollback
- Minimal JavaFX shell; one trivial feature end to end
- **jlink `--bind-services` + jpackage, signed, all three OSes**, native
  preinstalled via `org.sqlite.lib.path`

**Exit:** a signed installer per OS that creates a company, writes a row,
survives a migration, and passes the money round-trip property test — on a clean
VM with **no system Java**.

**Starts now in parallel:** code-signing certificate procurement (longest lead
item on the entire roadmap; gates this phase's exit).

## Phase 2 — Database/storage architecture + migrations ✅ COMPLETE (user-facing backup → P12)
**Blocked by: B-3 (money encoding), B-6 (topology)**

- Full **universal** schema, hand-authored for SQLite, all `STRICT`
- CHECK constraints on every enum-like column
- `app.db`: company registry, settings, rule-pack tables
- Company create / open / **migrate-on-open** / switch / close
- Single-instance lock; per-file lock; **network-path detection and refusal**
- Local backup & restore with trial-balance verification
- Integrity checks (`quick_check` on open; full check before backup)
- **Concurrency invariants tested from here on** (adjustment §2.3)

**Exit:** multiple isolated companies; backup/restore round-trip verified;
migration from a seeded older version proven; concurrency suite green.

## Phase 3 — Licensing + machine binding + subscription lifecycle ✅ COMPLETE (activation flow → P13)
**Blocked by: I-11 (grace period), I-12 (re-bind allowance), I-17 (key rotation)**

- Ed25519 verification; entitlement token format; **two public keys embedded**
- Activation and renewal protocol; offline renewal path
- Composite K-of-N fuzzy machine binding with silent re-bind
- Monotonic time anchor (clock-rollback resistance)
- Subscription state machine: ACTIVE → EXPIRING → GRACE → READ_ONLY
- **Export works in every state**

**Exit:** activate, expire, renew, re-bind, suspend and clock-rollback all behave
correctly; no licensing failure can destroy or lock away data.

## Phase 4 — Module/build architecture + entitlement enforcement ✅ COMPLETE
**Blocked by: B-7 (plan matrix), B-8 (dormant read access)**

⚠️ **Adjustment §2.1 — this phase now delivers the enforcement infrastructure,
not just the build system.**

- Module registry; `ServiceLoader` discovery; module state resolution
  (ACTIVE / READ_ONLY / DORMANT / ABSENT)
- **All three enforcement layers as working infrastructure:**
  1. use-case interceptor, 2. **persistence guard in the write path**,
  3. UI affordance
- `effective = build_modules ∩ entitled_modules`; licence is authoritative
- Per-plan build script; jlink/jpackage per plan; embedded build metadata

**Why the guard lands here:** the reference application checked permissions per
handler and **55 of 71 mutating routes forgot**. Retrofitting a cross-cutting
guard across finished features reproduces exactly that drift. Phase 5 onward must
be written *against* an existing guard.

**Exit:** BASIC and PROFESSIONAL installers from one codebase; a non-ACTIVE
module is refused at both the use-case and persistence layers.

## Phase 5 — Core accounting engine / sealed posting core ✅ COMPLETE
**Blocked by: nothing further**

- **Sealed posting core** — package-private ledger mutation, so the D-22 Tally
  bypass is structurally impossible
- Double-entry invariant (exact, no epsilon); tenant/company scoping
- Voucher, voucher entry, document numbering on the counter table
- Append-only, hash-chained audit emitted by the use-case pipeline (not per
  handler — the reference drifted to ~70% coverage doing it per handler)

**Exit:** no code path outside the posting package can mutate a ledger balance;
posting invariants asserted; ported posting tests green.

## Phase 6 — Accounting modules ✅ COMPLETE (P6-4 screen 2026-10-09)
- Chart of accounts, ledger groups (hierarchy), ledgers — resolved by **stable
  `system_code`**, never display name
- Company provisioning (18 groups, 23 ledgers, HR masters, fiscal year)
- Voucher types **per company**; fiscal years
- Trial balance — **SQL aggregation**, not in-memory
- **Opening balances fixed and dated** (defect D-1: sign-flipped in reference)

**Exit:** a full manual bookkeeping cycle with a correct trial balance.

## Phase 7 — Inventory masters + Sales/Purchase documents ✅ COMPLETE 2026-10-10
⚠️ **Adjustment §2.2 — inventory split; masters land with documents.**

- Items, categories, units (**per company**), warehouses
- Stock movements with the guarded-decrement concurrency pattern (quantity only)
- Parties; invoices, bills
- **Sign-aware credit and debit notes** — fixes critical defect **D-23**
- Payments, receipts; settlement status incl. the TDS netting rule
- Outstanding and aging; orders and quotations with **safe numbering** (D-11)
- SO→invoice conversion and `deliveredQty` (schema-only in the reference)

**Exit:** invoice → receipt → ledger → statement reconciles exactly; a credit
note **reduces** revenue and receivables.

## Phase 8 — Inventory valuation ✅ COMPLETE 2026-10-10 (periodic 5a per B-1: persisted costing + posted closing stock; originally "perpetual inventory costing")
**Blocked by: B-1 (perpetual confirmation), I-1 (methods), I-2 (granularity), I-3 (scrap)**

⚠️ Lands **after** documents exist, so COGS has something to relieve against.

- **Persisted cost layers + consumption records** (not report-time reconstruction)
- **COGS posted on issue** — provisions the missing COGS ledger
- Adjustments **post to the GL**; transfers preserve cost, create no layers
- Returns as first-class sign-aware movements linked to credit/debit notes
- Batches with real consumption and FEFO; expiry blocks issue
- Manufacturing: BOM, work orders, WIP, **costed scrap**

**Exit:** valuation reproducible for any historical date (fixes **D-24**);
stock ledger and trial balance agree (fixes **D-25**).

## Phase 9 — Indian taxation ✅ COMPLETE 2026-10-24 (see `phases/PHASE_9_REPORT.md`; I-6/I-7 moved to P15; content still gated by V-1)
**Was blocked by: B-4 (GST rounding, decided), I-6 (TDS surcharge base), I-7 (PT states) — both payroll, P15**

- **Effective-dated rule-pack infrastructure first** — the key structural fix
- GST computation, place of supply, composition
- GSTR-1, 3B, 2B reconciliation, 9, CMP-08 + portal JSON
- TDS/TCS with versioned rates; Form 16A; monthly challan
- E-invoice **payload only** — **never a fabricated IRN**; e-way bill payload
- Unified state-code table; GSTIN checksum wired up

**Exit:** ~124 GST and ~75 TDS/TCS ported tests green.
*Met in substance, not by count:* tests were written afresh against the law,
not ported (the reference's tables are stale, R-17). 134 GST and 40 TDS/TCS
tests; every reference TDS case reviewed and either covered or recorded as a
gap (194C(6) transporters, P9-29; Form 27D).

## Phase 10 — Banking + reconciliation ✅ COMPLETE 2026-10-24 (see `phases/PHASE_10_REPORT.md`)
- Bank accounts with a **single** balance source of truth (reference stores two)
- Statement import: correct amount sanitizer, dedup backed by a unique index
- **One** reconciliation engine using the scoring model (fixes **D-29**)

## Phase 11 — Year-end closing + financial reporting ✅ COMPLETE 2026-10-24 (see `phases/PHASE_11_REPORT.md`)
B-2 and I-16 decided 2026-10-24. Print/PDF moves with the screens; the party
statement is the party's ledger account for now.

- **Real closing voucher** moving P&L to Retained Earnings
- `isClosed` **enforced at posting time**; voucher date validated against the FY
- Opening balances rolled forward; audited reopen path
- P&L, balance sheet, cash flow (**redesigned** — reference finds no cash)
- Fiscal calendar read from data, never hardcoded April–March (fixes **D-32**)
- Registers, party statement, XLSX/CSV/JSON export, print/PDF

**Exit:** close year 1, verify year 2 opening equals year 1 closing, and confirm
a back-dated post into the closed year is refused.

## Phase 12 — Backup / cloud module
**Blocked by: I-14 (key custody confirmation)**

- `CloudStorageProvider` SPI; Cloudflare R2 / S3 implementation
- Argon2id + AES-256-GCM, encrypted **before** leaving the machine
- Short-lived scoped credentials (client never holds long-lived keys)
- Retention, resumable upload, restore onto a new machine

## Phase 13 — Company admin / licensing portal
- Platform DB (**no customer accounting data**)
- Activation/licence API; customer, licence, machine, subscription lifecycle
- MFA-mandatory portal; append-only admin audit
- Version and rule-pack publishing

## Phase 14 — Upgrade / downgrade / data migration
- Full round trip **PRO → BASIC → PRO** with zero data loss
- Dormant-module viewers; plan-change installers
- Combined plan-change + version-update paths
- Migration from every shipped schema version on realistic data

## Phase 15 — HR / Payroll — DEFERRED
**Blocked by: B-5 (payroll rounding), I-7 (Professional Tax state coverage)**

Numbered rather than dropped, so it has a defined home once the rounding
decision lands — but explicitly **not** scheduled into the core architecture.

**Why deferred.** The reference payroll engine is entirely float
(`Math.round` ×11, zero Decimal), and `post-month` *deliberately* re-derives
employer contributions with the same float math to keep JV totals
byte-identical. A `BigDecimal` port produces **different rupee figures** at
`.5` boundaries — on figures filed with statutory authorities. Rushing payroll
into the core and then changing statutory calculations later is strictly worse
than waiting.

Scope when unblocked: employees, departments, designations; attendance and leave
with **one** status vocabulary and **enforced** quotas (D-28); payroll
calculation validated against a real payroll run; post-month / pay-month with
the conditional-claim idiom; expense claims **with GL posting** (absent entirely
in the reference).

**Exit:** a real payroll month reconciles to the rupee against a
customer-supplied reference run, before any statutory filing depends on it.

## Phase 16 — Production certification / release gate
The final gate. Everything below must have been exercised **continuously in
earlier phases** — this phase confirms, it does not discover.

- **Adversarial / penetration review** against the full threat model
- **Failure injection at scale:** interrupted update, backup, restore; disk
  full; power loss mid-migration
- **Corruption and recovery drills;** WAL growth under leaked readers
- **Key-rotation rehearsal**
- Every `THREAT_MODEL.md` §8 row exercised
- Real-hardware testing on all targets **and both CPU architectures**
- macOS notarization; installer/update rehearsal on clean machines
- Performance on large books (multi-year, high voucher count)
- Documentation; support runbooks

**Exit:** signed installers for Windows, Linux and macOS, verified on clean
hardware, with every accounting invariant and security test green.

---

## Deferred — decide by demand
Cost centres, projects, budgets (I-9) · multi-currency (I-10) · GSTR-4 ·
Form 27D · barcode · OCR module · Tally import (**must go through the sealed
posting core**).

HR/Payroll is **P15** — deferred but numbered, pending B-5.

---

## Immediate next actions

1. **Settle the 8 🔴 BLOCKING decisions** (`decisions/OPEN_DECISIONS.md`).
   Only B-3 and B-6 gate Phase 2; the rest gate later phases.
2. **Start Phase 1** — depends on none of them.
3. **Begin code-signing certificate procurement** — longest lead item.
4. **Notify the web-app team** about D-23, D-24, D-25, D-22 (see
   `docs/reference/WEB_APP_DEFECT_REPORT.md`).

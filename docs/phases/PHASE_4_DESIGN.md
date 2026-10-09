# Phase 4 — Design & Research Report

**Status:** DESIGN FINALISED — no code written · **Date:** 2026-09-04, reviewed 2026-09-05

> Adversarial review completed 2026-09-05. Detailed decisions now live in
> `docs/decisions/P4_MODULE_ARCHITECTURE.md`, `P4_PLAN_MODULE_MATRIX.md`,
> `P4_UPGRADE_DOWNGRADE.md` and `P4_DEPENDENCY_RULES.md`. This document remains
> the research record; those four are authoritative for implementation.
**Phase:** 4 — Physical modules, build variants, registry, entitlement enforcement

> Protocol: inspect → research → design → **report → STOP.**

---

## 1. Current repository state (verified, not assumed)

```
9842fae Phase 3: licensing, machine binding and subscription lifecycle
d7caf01 Add Phase 3 design and research report
4c3ea4a Phase 2: core accounting schema, locking, and storage safety
ce3fe76 Stop leaking persistence types through the app layer's public API
92d51ee Add Phase 2 design and research report
5bd7dc6 Fix three .gitignore problems
```
Working tree **clean**, 0 staged.

**Test baseline re-run and confirmed: 196 passing, 3 skipped** across 21 suites
(platform 26, module-api 8, licensing 78, persistence 40, app 44).

Modules present: `accubook-platform`, `accubook-persistence`,
`accubook-module-api`, `accubook-licensing`, `accubook-app`,
`accubook-ui-shell`, `accubook-launcher`.

## 2. Architecture verified by inspection

- `FeatureModule` exists as a **P1 stub**: `id()`, `displayName()`,
  `navigationOrder()`, `ownedTables()`. Nothing implements or consumes it.
- `ModuleState` / `ModuleStateProvider` exist in `accubook-module-api`, which has
  **no dependency on `accubook-licensing`** — enforced by the build.
- `Entitlement` (P3) is the only implementation of `ModuleStateProvider`.
- **Nothing consumes it yet.** P4 wires the first consumer.
- `CompanyService` currently exposes two mutating operations
  (`createCompany`, `openCompany`) and several read operations. This is the
  surface the guard must cover.

## 3. P0–P3 decisions verified

Confirmed still in force: ADR-001 (one `company.db` per company + `app.db`) ·
ADR-002 (code packaged per plan, **universal schema**, signed entitlement) ·
B-3 scaled-INTEGER money · B-6 topology · P2-1…P2-4 · P3-1…P3-8.

**B-7 (plan → module matrix) is UNRESOLVED.** §12 therefore presents a
*proposed* catalogue and explicitly does not invent the commercial matrix.

---

## 4. Research findings (measured this session)

I built a throwaway JPMS project to test what actually happens, rather than
reasoning from documentation.

### 4.1 ✅ `ServiceLoader` degrades silently when a module is absent

| Build | Discovered |
|---|---|
| Module present | `[payroll]` |
| Module physically absent | `[]` — **no crash** |

This is exactly the downgrade behaviour required: a Basic build simply finds
fewer providers. No `if (plan == ...)` anywhere.

### 4.2 ✅ Confirmed inside a real jlink image

```
Basic image  --list-modules → ex.api, ex.app          → discovered []
Pro image    --list-modules → ex.api, ex.app, ex.impl → discovered [payroll]
```

### 4.3 ✅ A jlink image cannot be extended by dropping in a jar

Copying `ex.impl.jar` into the Basic image changed nothing — still `[]`. Modules
are linked into the image's `modules` file, so "copy the module jar from a
friend's Professional install" does **not** work.

### 4.4 🔴 But `--module-path` at launch CAN smuggle a module in

```
img_basic/bin/java --module-path mods --add-modules ex.impl ... → [payroll]
```

A user who edits the jpackage launcher `.cfg` can add a module path. **So
physical packaging alone is not an entitlement boundary.**

### 4.5 🔴 Therefore the manifest must NOT be a gate

Modelled both designs:

| Design | Smuggled `payroll` resolves to |
|---|---|
| **Manifest is authority** | `ACTIVE` — **unlocked, paid feature free** |
| **Entitlement is authority** | `READ_ONLY` — smuggling gains nothing |

Because the entitlement is Ed25519-signed and unforgeable, and the rule is
`effective = installed ∩ entitled`, a smuggled module lands in `READ_ONLY`.

**Consequence: the module manifest does not need signing, and must never be
consulted for permission.** It is diagnostic metadata for support. Signing it
would create a false sense of security around a value that is not load-bearing.
This directly answers requirement (E): *"a customer must not be able to edit
`modules.json` and unlock paid functionality"* — they cannot, because nothing
reads it for that purpose.

---

## 5. Proposed module architecture

### 5.1 Three concepts, never collapsed

```
PHYSICAL   what the build packaged        → jlink image contents
INSTALLED  what this runtime can see      → ServiceLoader discovery
ENTITLED   what the licence permits       → signed entitlement (P3)

effective = INSTALLED ∩ ENTITLED
```

### 5.2 Extending `FeatureModule`

The P1 stub is close but insufficient. Proposed additions, each with a reason:

| Member | Why |
|---|---|
| `id()`, `displayName()` | exist |
| `version()` | manifest + schema-compatibility reporting |
| `dependsOn()` | build-time validation; prevents impossible builds |
| `ownedTables()` | exists — used by the persistence guard |
| `navigationEntries()` | shell renders navigation without importing modules |
| `requiredSchemaVersion()` | module refuses to run against an older schema |

Deliberately **not** on the interface: anything licensing-shaped. A module still
sees only `ModuleState`.

### 5.3 `ModuleRegistry`

Discovers providers via `ServiceLoader`, validates the dependency graph at
startup, and answers "what is installed". It does **not** decide permission —
that is the entitlement's job.

---

## 6. Dependency graph — evidence-based

Derived from the reference schema and services. The three most consequential
findings were re-verified by me directly.

### 6.1 Build tiers

| Tier | Domains | Why |
|---|---|---|
| **0 — unavoidable kernel** | `core`, `accounting`, `parties` | `Organization` declares **40 back-relations**; `Voucher.fiscalYearId`, `.createdById`, `.voucherTypeId` are **non-nullable**; `posting.ts` resolves ledger groups by hardcoded name and **throws** if absent; `Ledger.partyId` is part of the chart of accounts itself |
| **1 — near-core** | `inventory`, `sales`, `purchase`, `TaxConfig` | Removable in principle; painful in practice |
| **2 — optional, but posts to the GL** | `payroll`, `manufacturing`, `banking`, `tally-import` | Each writes `Voucher` rows that **must survive removal** |
| **3 — genuinely leaf** | GST returns / e-invoice / TDS / TCS, `hr`, budgets/cost-centres/projects, `approvals`, `notifications`, `ocr`, recurring invoices, extended reporting | Clean removal |

**`parties` moves into Tier 0**, correcting §12's provisional listing:
`Ledger.partyId` means party ledgers are part of the chart of accounts, and
`Invoice`/`Bill`/`Receipt`/`Payment` all have **non-nullable** `partyId`.

### 6.2 🔴 A real cycle: INVENTORY ↔ SALES

Verified myself:
```
valuation.ts:4    import { loadPendingLines } from "./pending-dispatch";
valuation.ts:162  const pending = await loadPendingLines(orgId, client);
pending-dispatch.ts:52  const invoices = await client.invoice.findMany({
```

Inventory **valuation reads sales invoices**. Not incidental: raising an invoice
does not move stock, so "sold but not yet shipped" must be derived from
invoices, and closing stock is `physical − inProgress`.

**Consequence.** An inventory-without-sales build needs `loadPendingLines` to
return empty — which is *semantically* correct (no invoices ⇒ nothing pending)
but must be a deliberate compile-time seam, not an accident.

### 6.3 🔴 Nullability decides what is optional

| Field | Nullable? | Consequence |
|---|---|---|
| `InvoiceItem.itemId` | **`String?`** | Invoicing works **without** inventory — a services business genuinely needs no items |
| `BillItem.itemId` | `String?` | Same |
| `SalesOrderItem.itemId` | **`String`** | Sales **orders** hard-require inventory |
| `QuotationItem.itemId` | **`String`** | Quotations hard-require inventory |
| `PurchaseOrderItem.itemId` | **`String`** | Purchase orders hard-require inventory |

So "sales" is not one module: **invoicing** is inventory-optional while **orders
and quotations** are not. That is a module-boundary decision, raised as **P4-7**.

### 6.4 🔴 Ten paths post to the General Ledger

`vouchers` (manual JV) · invoice · receipt · bill · payment · payroll accrual ·
payroll disbursement · manufacturing WO · Tally import · approvals promotion.

The trial balance reads `voucherEntry` with **no module filter**, so **the GL is
module-agnostic by construction.** That is the property making modular builds
safe — and it means **removing a module must never delete its vouchers.**

### 6.5 🔴 Prisma-style back-relations are a build hazard — and we avoid it

`Voucher` names `Payslip`, `TdsDeduction`, `TcsCollection`, `Bill`, `Invoice`,
`Payment`, `Receipt` as back-relations (verified). In the reference, a slim build
would have to *generate* a filtered schema or `prisma generate` fails.

**We are not exposed to this**, because P2 chose hand-written SQL with no ORM and
a **universal schema** — every build ships every table. Recorded because it
validates the P2 decision and warns against reintroducing a schema generator.

### 6.6 Reporting must split

Trial balance is pure GL. But `profit-loss` and `balance-sheet` both import
`valueClosingStock` from inventory — the balance sheet **does** read inventory
valuation. Cash flow additionally reads payslips and expense claims.

**Recommendation:** `reporting-core` (trial balance, always shipped) and
`reporting-extended` (P&L, balance sheet, cash flow, aging, registers), which
degrades gracefully when a domain is absent — closing stock is zero for a
services business, which is arithmetically correct.

### 6.7 Soft references are deliberate seams — mirror them

`StockMovement.referenceType/referenceId`, `BankTransaction.matchedVoucherId`,
`DocumentExtraction.postedEntityType/Id`, `Quotation.convertedToOrder` are all
untyped strings rather than foreign keys. Whoever wrote the reference schema was
already decoupling. **New cross-module links should follow that pattern rather
than adding a foreign key**, since a FK across a module boundary is what makes a
module unremovable.

### 6.8 Cycle 2: ACCOUNTING ↔ BANKING (confirms P4-6)

`Ledger.bankAccountId` points into banking; `bank-accounts` imports
`getOrCreateBankLedger` from posting. Mutual. Minor, but it means banking cannot
be cleanly excised without inverting the reference — which is what P4-6 proposes.

## 7. Build variants

```
./build.sh --plan basic
./build.sh --plan professional
./build.sh --modules core,accounting,sales,gst   (custom)
```

- Plan → module list resolved from **one source of truth** shared with the
  licence server, so a build cannot disagree with what is sold.
- Dependency closure computed and **invalid combinations rejected before
  compiling** (e.g. inventory without accounting, if that proves invalid).
- `build.sh` already discovers modules from the reactor (fixed in P3 after a
  hardcoded list silently omitted the licensing module).

**Correction to the earlier design:** P1's `build.sh` embeds build metadata but
does not select modules. P4 makes selection real.

## 8. Installed-module manifest

A generated resource inside the image recording build id, app version, plan
label, module ids and versions, and schema expectations.

**Diagnostic only** (§4.5). Purpose: support can ask "what build are you
running?" and get a truthful answer. It is not consulted for permission, so
editing it changes nothing a customer would want changed.

---

## 9. Entitlement enforcement — three layers

Reads are **never** gated. Writes pass through:

```
UI  →  Use case  →  [1] EntitlementGuard  →  Domain  →  [2] Persistence guard  →  SQLite
                          ▲
                    [3] UI affordance (disabled + explained, never the boundary)
```

**Layer 1 — use-case interceptor.** Every mutating use case declares its module.

**Layer 2 — persistence guard.** The write path refuses inserts/updates to tables
owned by a non-`ACTIVE` module. This exists because layer 1 depends on a
developer remembering an annotation, and the reference application proves that
fails: **55 of 71 mutating routes forgot their permission check.**

**Layer 3 — UI.** Disabled *and explained*. Never the security boundary.

A direct call to a mutating use case, bypassing the UI entirely, must fail.
Tests will prove it.

---

## 10. READ / WRITE state matrix

| Situation | Module state | Read | Write |
|---|---|---|---|
| Installed + entitled + subscription `ACTIVE`/`RENEWAL_WINDOW` | `ACTIVE` | ✅ | ✅ |
| Installed + entitled + `EXPIRED`/`SUSPENDED`/`INVALID` | `READ_ONLY` | ✅ | ❌ |
| Installed + **not** entitled (downgrade, code still present) | `READ_ONLY` | ✅ | ❌ |
| **Not installed** + data exists (downgrade, code removed) | `DORMANT` | ⚠️ see §11 | ❌ |
| Not installed + no data ever | `ABSENT` | ❌ | ❌ |
| Smuggled onto the module path but not entitled | `READ_ONLY` | ✅ | ❌ |

## 11. 🔴 The DORMANT question — the hardest problem in P4

ADR-002 raised it; P4 must settle it. If payroll code is physically absent, the
application **cannot render the payroll UI**. Saying otherwise would be
dishonest.

| Option | Customer gets | Cost |
|---|---|---|
| **(a) Generic read-only viewer** + per-module view metadata in the base build | Records viewable in a table/detail view, and exportable | Moderate — engines are the bulk, not renderers |
| **(b) Export-only** | CSV/PDF of the historical data; no in-app view | Low |
| **(c) Ship all code, gate at runtime** | Full original UI, read-only | **Contradicts physical packaging** |

**Recommendation: (a).** It satisfies both requirements — the payroll *engine* is
genuinely absent (the feature cannot be used, and the build genuinely differs)
while history stays viewable and exportable.

**This needs your decision (P4-1).** It defines what you promise a downgrading
customer: *"view your historical payroll records"* (a) versus *"export your
historical payroll records"* (b). Data is never deleted under any option.

---

## 12. Proposed module catalogue — PROPOSED, NOT APPROVED

Derived from documented reference-app domains; the dependency evidence is
pending (§6). **B-7 remains unresolved and I am not inventing it.**

| Module | Status | Notes |
|---|---|---|
| `core` | **mandatory** | company, fiscal year, audit, numbering, migrations |
| `accounting` | **mandatory** | chart of accounts, ledgers, vouchers, posting |
| `parties` | **mandatory** (corrected — §6.1) | `Ledger.partyId`; non-nullable on every document |
| `sales` | confirmed domain | invoices, credit notes, receipts |
| `purchase` | confirmed domain | bills, debit notes, payments |
| `inventory` | confirmed domain | items, stock, valuation |
| `taxation` | confirmed domain | GST returns, TDS/TCS engines |
| `banking` | confirmed domain | statement import, reconciliation |
| `manufacturing` | confirmed domain | BOM, work orders |
| `hr`, `payroll` | confirmed domain | deferred to P15 (B-5 open) |
| `reporting-core` | **mandatory** | trial balance; pure GL |
| `reporting-extended` | confirmed domain | P&L, balance sheet, cash flow — **reads inventory valuation** (§6.6) |
| `ocr`, `tally-import`, `backup` | proposed optional | leaf domains |

Proposed matrix (illustrative, awaiting B-7):

```
                Basic   Professional   Enterprise
core              ✓          ✓              ✓
accounting        ✓          ✓              ✓
parties           ✓          ✓              ✓
sales             ✓          ✓              ✓
purchase          ✓          ✓              ✓
inventory         ?          ✓              ✓
taxation          ?          ✓              ✓
banking           ✗          ?              ✓
manufacturing     ✗          ?              ✓
payroll           ✗          ?              ✓
backup            add-on?    add-on?        ✓
```

`?` marks a commercial decision only you can make.

---

## 12a. Adversarial review findings (2026-09-05)

Four questions asked against the reference application and our own code.

### 12a.1 ✅ Reports cannot be a write bypass — verified
All nine report routes plus export contain **zero** create/update/delete calls.
So reads can remain ungated without opening a write path.

### 12a.2 🔴 Three non-UI write paths exist — verified
| Path | Evidence | Why it matters |
|---|---|---|
| **Scheduler** | `run-recurring.ts:200` `tx.invoice.create` | A background job creates accounting documents with no user present |
| **OCR importer** | `post-extracted.ts:270,349` creates bills and invoices | An import path writes accounting data |
| **Tally importer** | `tally.ts:654` creates vouchers, `:688` uses its own `applyLedgerBalances` | **Bypasses the posting core entirely** (defect D-22) |

**A UI-only guard would miss all three.** They are the strongest argument for
enforcement at the use-case and persistence layers, and for routing every GL
write through a single accounting port.

### 12a.3 🔴 JPMS is not a security boundary — measured
Compile time, a feature module without `requires accubook.persistence`:
```
error: package com.p is not visible
```
Runtime, the same module using reflection:
```
persistence module present → reflection SUCCEEDED
persistence module absent  → ClassNotFoundException
```

Since persistence must be on the module path for the app to run at all, **a
feature module can reach it reflectively.** JPMS prevents *accidental* coupling,
not *deliberate* bypass.

**This changed the design.** The persistence guard is **load-bearing, not
belt-and-braces**, and JPMS must not be presented as the security boundary.

### 12a.4 ✅ Our own surface is small and containable
`accubook-app` is currently the **only** module requiring `accubook.persistence`.
That is the boundary to preserve: feature modules get an accounting *port*, never
the database.

## 13. Security analysis

| Bypass attempt | Outcome |
|---|---|
| Edit `modules.json` | **Nothing** — manifest is not a gate (§4.5) |
| Copy module jars into the image | **Fails** — measured (§4.3) |
| `--module-path` smuggling | Module loads but resolves `READ_ONLY` (§4.4) |
| Call a use case directly | Blocked by layer 1 |
| Call a repository directly | Blocked by layer 2 |
| UI controls re-enabled | UI is not the boundary |
| Edit entitlement | Ed25519 signature fails → `INVALID` (P3) |
| Roll the clock back | Monotonic anchor (P3) |
| Run an older binary | Refuses a newer schema (P2) |
| Edit SQLite directly | Not preventable; detected by integrity + trial-balance checks |
| **Patch the binary** | **Accepted** — stated in the P3 threat model |

## 14. Upgrade / downgrade

`PRO → BASIC`: entitlement refreshed first (modules become `READ_ONLY`), then the
Basic installer removes the code (`DORMANT`). **No schema change, no data
deleted.** `BASIC → PRO`: install, refresh entitlement, data immediately usable
again.

Order matters: entitle *after* migrating, so a module never activates against an
unmigrated database.

## 15. Testing strategy

**Build:** each variant contains only its modules · invalid combinations
rejected · manifest matches actual contents · **assert a Basic image does not
contain payroll classes.**
**Runtime:** discovery with/without modules · every row of §10 · `ServiceLoader`
returns empty rather than throwing.
**Security:** direct use-case call blocked · direct repository write blocked ·
edited manifest changes nothing · smuggled module is `READ_ONLY`.
**Downgrade:** PRO → BASIC → PRO round trip, **SHA-256 comparison** proving
company.db is byte-identical.
**Regression:** all 196 must stay green; none weakened.

## 16. Documentation plan

Update the existing `MODULE_ARCHITECTURE.md` and `UPGRADE_DOWNGRADE.md` rather
than duplicating them. New: `ENTITLEMENT_ENFORCEMENT.md`, `BUILD_VARIANTS.md`,
`MODULE_DEPENDENCY_GRAPH.md`, `ADR-003`, `ADR-004`, `PHASE_4_REPORT.md`, log.

## 17. Contradictions found

1. **The design brief's example shows GST as `READ_ONLY` when not entitled but
   installed** — consistent with §10. But when the module is *not installed*, the
   brief asks whether `READ_ONLY` or `UNAVAILABLE` is correct. **These are
   different situations and P3 already distinguishes them:** `READ_ONLY`
   (installed, not permitted) versus `DORMANT` (not installed). I recommend
   keeping both rather than collapsing to `UNAVAILABLE`, because a customer with
   payroll history needs a different message from one who never had payroll.
2. **`FeatureModule.ownedTables()` exists but nothing enforces it.** P4 gives it
   meaning via the persistence guard.
3. **P1's `build.sh --plan` accepts a plan but does not select modules.** P4
   makes it real; until then the flag is metadata only.

## 18. Decisions required

| # | Question | Recommendation |
|---|---|---|
| **P4-1** | DORMANT read access: generic viewer, export-only, or ship-all-code? | **(a) generic viewer** |
| **P4-2** | Confirm the manifest is diagnostic-only and unsigned | **Yes** — signing implies a guarantee it does not provide |
| **P4-3** | Keep `DORMANT` distinct from `READ_ONLY`? | **Yes** — different customer situations |
| **P4-4** | **B-7 plan → module matrix** | **Yours.** I will not invent it |
| **P4-5** | Is `reporting` core or a module? | Likely **core** — the balance sheet reads inventory valuation |
| **P4-6** | `Ledger.bankAccountId` creates an accounting ↔ banking cycle (§6.8) | **Invert** so banking points at accounting |
| **P4-7** | Split `sales` into `invoicing` (inventory-optional) and `orders` (inventory-required)? §6.3 | **Yes** — otherwise inventory cannot be optional at all |
| **P4-8** | Confirm `reporting` splits into core + extended (§6.6) | **Yes** |

## 19. Explicitly NOT implemented in P4

Accounting transactions · sales · purchase · inventory · GST · banking ·
payroll · manufacturing · OCR · cloud backup · licence server · admin portal.
**P4 is infrastructure only.** Feature modules created in P4 are empty shells
proving the mechanism, not business functionality.

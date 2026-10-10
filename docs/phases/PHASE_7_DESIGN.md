# Phase 7 Design — Parties, Inventory Masters, Sales & Purchase Documents

**Status:** APPROVED 2026-10-09 (P7-1…P7-6 as recommended) · **Date:** 2026-10-09
**Unblocked by:** B-1 decided 2026-10-09 — **periodic, form 5a**
**Baseline verified:** 373 passing · 0 failing · 3 UI skipped (clean
`mvn -o clean verify`, 2026-10-09)

> **Exit criterion (roadmap).** invoice → receipt → ledger → statement
> reconciles exactly; a credit note **reduces** revenue and receivables.

---

## 1. P7 is too large for one step — split into five sub-phases

The roadmap's P7 covers parties, five inventory masters, stock movements, four
document families, settlement, aging and orders. Built in one step it would be
one unreviewable change. Each sub-phase below runs the full cycle (design detail
→ implement → test → small commits) and leaves the suite green.

| Sub | Scope | Modules touched | Exit |
|---|---|---|---|
| **P7a** | Parties + party ledgers · feature-module transaction port · `Scale.QUANTITY` | kernel `parties`, `module-api` | party ↔ ledger bound by id, never name (R-5) |
| **P7b** | Units, categories, items, warehouses · stock movements (quantity only) · guarded decrement · opening stock · **first real `FeatureModule` + guard wired in production** | `inventory` | stock never negative under concurrency; module refused when READ_ONLY at both layers |
| **P7c** | Sales invoices, purchase bills, **sign-aware credit/debit notes** · posting via `PostingService` · stock issue/receipt from documents | `sales-invoicing`, `purchase-billing` | credit note reduces revenue and receivables (D-23 property test) |
| **P7d** | Receipts, payments, **allocations**, on-account advances · outstanding, aging, party statement | `sales-invoicing`, `purchase-billing` | invoice → receipt → ledger → statement reconciles exactly |
| **P7e** | Quotations, sales/purchase orders on the counter table (D-11) · SO→invoice line copy · `delivered_qty` | `sales-orders`, `purchase-orders` | concurrent order creation never duplicates a number |

The two P4 carried tests (module smuggling end-to-end; downgrade SHA-256
comparison) land in **P7b**, the first sub-phase with a genuine optional module.

---

## 2. Fixed constraints

| Constraint | Source | Consequence for P7 |
|---|---|---|
| **Periodic 5a** | B-1 | Purchases post **Dr Purchase Accounts** (Direct Expenses). Sales post revenue only. **No COGS at issue.** Stock movements carry quantity; valuation is P8. |
| Sealed posting core | P5 | Every document that reaches the ledger posts through `PostingService`. No document code writes `voucher`. |
| Computed balances | P2-2 / I-4 | Outstanding amounts are **derived from allocations**, never cached on the document (reference caches `amountPaid`/`amountDue`). |
| Resolve by `system_code` | P6 / R-4 | Sales and Purchase ledgers resolved by code. Party ledgers resolved by **id** (§4). |
| Module ledgers arrive with modules | P2-1 | `SALES_ACCOUNTS` and `PURCHASE_ACCOUNTS` ledgers are seeded because `sales-invoicing` and `purchase-billing` are in **every** plan (B-7). GST ledgers still wait for P9. |
| `sales-invoicing` does not require `inventory` | B-7 | Invoice lines may be free-text (service) lines with no item. |
| Universal schema | ADR-002 | Every table ships in every build via ordinary migrations; ownership, not existence, varies by plan. |
| Quantity at scale 4 | B-3 | Approved but **absent from `Scale`** — added in P7a as `QUANTITY(4, "_e4")`. |

---

## 3. Reference research — what P7 must not copy

| ID | Finding | Evidence | P7 response |
|---|---|---|---|
| **R-5** *(new)* | **Party ledgers are bound by display name.** `upsertLedgerByName` does `INSERT … ON CONFLICT (organizationId, name) DO UPDATE … RETURNING id`. A party named "Cash in Hand" or "Sales" is silently attached to *that existing ledger* — customer receivables post into the cash account. | `utils/posting.ts:46-70`, called by `getOrCreatePartyLedger:85` | `party.ledger_id` FK, created with the party. A name clash is **refused** with a message, never merged. |
| D-23 | Credit/debit notes post **backwards** — no `CREDIT_NOTE` branch in `decideInvoiceEntries`. | `post-invoice.ts:79-99` | Document *kind* determines entry direction; property-tested. |
| D-11 | SO/PO/quote numbers derived from the last row's string. | `sales-orders/route.ts:113` | All document families use `number_counter` inside `BEGIN IMMEDIATE`. |
| R-6 *(new)* | **Settlement is 1:1.** `Receipt.invoiceId` is a single optional FK — one receipt cannot settle two invoices, and an unlinked receipt never reduces any invoice's due. | `schema.prisma` Receipt / Payment; `recomputeInvoiceStatus:481` | Allocation table: one receipt → many documents; unallocated remainder is an on-account advance. |
| R-7 *(new)* | **`amountPaid`/`amountDue` are cached and recomputed by hand** after each receipt; any path that forgets leaves them stale. | `posting.ts:481-560` | Not stored. Derived from allocations in SQL. |
| R-8 *(new)* | A `BOTH` party is silently treated as a customer for ledger placement. | `posting.ts:80` comment, `:96` | Explicit rule — see P7-2. |
| D-16 | Negative-stock guard has a string bypass. | analysis | Guarded decrement in SQL; see P7-4. |
| keep | `movement-rules.ts` direction table; guarded decrement (`updateMany … gte`); `deriveSettlementStatus` being *total* (every input maps to one status). | `movement-rules.ts`, `posting.ts:452` | Port the ideas. |

**R-5 is worth reporting to the web-app team** alongside D-22…D-25: it is live,
silent, and moves receivables into arbitrary ledgers.

---

## 4. Parties (P7a) — kernel

Parties are **kernel** (B-7: `Ledger.partyId` is part of the chart of accounts),
so they live in `accubook-accounting` as `com.accubook.accounting.party` and are
never entitlement-gated.

```sql
CREATE TABLE party (
    id                 TEXT NOT NULL PRIMARY KEY,
    ledger_id          TEXT NOT NULL UNIQUE REFERENCES ledger (id),
    role               TEXT NOT NULL CHECK (role IN ('CUSTOMER','VENDOR','BOTH')),
    name               TEXT NOT NULL UNIQUE,
    code               TEXT UNIQUE,
    gstin              TEXT,      -- validated in P9; stored now
    pan                TEXT,
    state_code         TEXT,      -- place-of-supply input for P9
    credit_limit_e4    INTEGER CHECK (credit_limit_e4 IS NULL OR credit_limit_e4 >= 0),
    credit_days        INTEGER CHECK (credit_days IS NULL OR credit_days >= 0),
    contact / address columns …,
    is_active          INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
    created_at, updated_at
) STRICT;
```

- **Party and ledger are created in one transaction.** The ledger is a normal
  user ledger (`is_system = 0`) under Sundry Debtors or Sundry Creditors.
- **Rename** renames both together. **Delete** only when the ledger has never
  been posted to (P6 rule). **Deactivate** deactivates the ledger too.
- **Role change** after posting is refused when it would move the ledger across
  natures (P6 / R-3); Debtors and Creditors are both current, but ASSETS vs
  LIABILITIES is a nature change.
- *Clarified during P7a:* P7-2 places a **new** `BOTH` party under Sundry
  Debtors. A posted vendor that becomes `BOTH` keeps its ledger under Sundry
  Creditors, because moving it would flip its history's sign. `BOTH` therefore
  accepts either group; `CUSTOMER` and `VENDOR` accept only their own.
- *Clarified during P7a:* the **ledger is the source of truth** for a party's
  name and active flag; triggers mirror them onto `party`. The chart of
  accounts refuses to move a party's ledger (change the role instead), and a
  party's ledger cannot be deleted while the party exists.
- Party and account names are unique **ignoring case**, so "CASH IN HAND" cannot
  sit beside "Cash in Hand".

---

## 5. Feature-module persistence (P7a) — closing a P4 gap

P4 forbade **feature → persistence** but did not say how a feature stores its own
tables. Simply adding `requires accubook.persistence` would also expose
`Database.inUnguardedTransaction`, which is public — a feature could step around
the guard without reflection.

**Design:** `accubook-module-api` gains a narrow port,

```java
public interface ModuleTransactions {
    <T> T inTransaction(Work<T> work) throws SQLException;   // always guarded
    <T> T read(Work<T> work) throws SQLException;            // read connection
}
```

implemented in `accubook-app` over `Database.inTransaction`. Feature modules
`requires accubook.module.api` and `accubook.accounting` (for `PostingService`)
— **never** `accubook.persistence`. An architecture test asserts it.

Schema for feature tables lives in `Schemas` (universal schema); the module only
*declares ownership* through `ownedTables()`.

---

## 6. Inventory (P7b) — quantities only

Tables (owned by `inventory`): `unit`, `item_category`, `item`, `warehouse`,
`stock` (item × warehouse, `quantity_e4`), `stock_movement`.

- Movement types: `OPENING`, `PURCHASE`, `SALE`, `SALES_RETURN`,
  `PURCHASE_RETURN`, `TRANSFER`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`. Direction is a
  property of the type (ported idea from `movement-rules.ts`), plus
  **`PURCHASE_RETURN`**, which the reference cannot represent.
- Decrement is guarded in SQL:
  `UPDATE stock SET quantity_e4 = quantity_e4 - ? WHERE item_id=? AND warehouse_id=? AND quantity_e4 >= ?`
  and a zero row count refuses (P7-4).
- `stock` is a cache of `SUM(stock_movement)` **with a verification routine**
  (I-4 allows a rebuildable cache); concurrency needs a row to guard.
- **No `rate`, `avg_cost` or value columns are written in P7.** Valuation is P8
  (A-1 persisted layers). Movements carry an optional `unit_cost_e4` recorded
  from the source document so P8 has its input; nothing reads it in P7.
- Services items (`type = 'SERVICE'`) never move stock.

---

## 7. Documents (P7c) — sign-aware by construction

One header/line pair per side, discriminated by kind:

| Kind | Party ledger | Income / expense ledger | Stock |
|---|---|---|---|
| `SALES_INVOICE` | **Dr** customer | **Cr** Sales Accounts | out (`SALE`) |
| `CREDIT_NOTE` | **Cr** customer | **Dr** Sales Accounts | in (`SALES_RETURN`) |
| `PURCHASE_BILL` | **Cr** vendor | **Dr** Purchase Accounts | in (`PURCHASE`) |
| `DEBIT_NOTE` | **Dr** vendor | **Cr** Purchase Accounts | out (`PURCHASE_RETURN`) |

Round-off posts to the existing `ROUND_OFF` system ledger. The posting plan is
a **pure function of (kind, amounts)** so the D-23 property — *a credit note
always moves revenue and receivables opposite to an invoice* — is tested without
a database.

- Lifecycle: `DRAFT → POSTED → CANCELLED`. Posting calls `PostingService.post`
  with an idempotency key `doc:<id>`; cancelling calls `PostingService.reverse`.
  A posted document is immutable (trigger), mirroring vouchers.
- Credit/debit notes **may** reference an original document; when they do, the
  amount may not exceed the original's un-credited value.
- Line amounts: `quantity × rate − discount = taxable`, computed in `BigDecimal`,
  stored at scale 4. Header totals derived from lines, never accepted (the P5
  lesson from `post-wo-journal.ts:92`).

---

## 8. Settlement (P7d)

```
receipt / payment  ──< allocation >──  invoice / bill / note
```

- An allocation links a settlement voucher to a document with an amount.
  Allocated total ≤ receipt amount; per document ≤ outstanding — **checked inside
  the transaction** (reference D-21 checked outside it).
- Unallocated remainder is an **on-account advance**, allocatable later.
- Credit notes settle invoices through the same table.
- Outstanding = document total − Σ allocations, in SQL. Status
  (`OPEN / PARTIAL / SETTLED / OVERDUE`) is derived, total, never stored.
- Aging buckets 0-30 / 31-60 / 61-90 / 90+ on due date; party statement is the
  party ledger's entries with running balance.
- **TDS netting** (the reference's bill-closing rule) is **deferred to P9** with
  TDS computation; P7 settlement has no TDS.

---

## 9. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P7-1** | GST on P7 documents? B-4 (GST rounding) is open and gates P9. | **Tax-ready, tax-free in P7.** Lines carry a taxable amount; a separate `document_tax_line` table exists but stays empty until P9 fills it. No GST is computed or posted in P7. Nothing ships to customers before P16, so no customer sees a GST-less invoice. |
| **P7-2** | Ledger for a party that is both customer and vendor. | **One ledger per party**, under Sundry Debtors for `CUSTOMER`/`BOTH`, Sundry Creditors for `VENDOR`; the net balance is the true position (Tally convention). |
| **P7-3** | Settlement model. | **Allocation table** (§8), on-account advances allowed, outstanding computed. |
| **P7-4** | Negative stock. | **Refuse.** A sale that would take stock below zero fails with a clear message. Traders who enter out of order can record the purchase first. |
| **P7-5** | Does an invoice move stock, or a separate delivery document? | **Invoice moves stock** in the same transaction (Tally-style; fits the trader target). Separate delivery notes come with orders in P7e, where an invoice made from a delivered order does not move stock twice. |
| **P7-6** | Sub-phase plan (§1). | Approve P7a…P7e as listed. |

Architectural (recorded, not business): §5 transaction port; parties in
`accubook-accounting`; seeding `SALES_ACCOUNTS` / `PURCHASE_ACCOUNTS` system
ledgers by migration for existing companies and by provisioning for new ones.

---

## 10. Not decided here

I-1 costing method, I-2 granularity, I-3 scrap (P8) · B-4 GST rounding (P9) ·
TDS/TCS (P9) · bank ledgers (P10) · UoM conversion (`conversionFactor`, schema
only in the reference) — **deferred**: P7 items have one unit.

---

## 11. P7b detailed design — inventory, the first real optional module

### 11.1 Physical module
New Maven module **`accubook-inventory`** → JPMS `accubook.inventory`, the id
`build.sh` derives from plan module `inventory`. It `requires` only
`accubook.module.api` and `accubook.platform` — **never** persistence, licensing
or another feature (architecture test). It `provides FeatureModule with
InventoryModule`, which declares `ownedTables()` and `historicalViews()`.

Present in PROFESSIONAL and ENTERPRISE images; absent from BASIC. In a BASIC
build its tables still exist (universal schema) and are `DORMANT` if they hold
data.

### 11.2 Schema — migration 5 (all owned by `inventory`)

| Table | Key points |
|---|---|
| `unit` | per company; `symbol` unique ignoring case; `decimals` 0–4 (how many places a quantity in this unit may carry) |
| `item_category` | optional hierarchy, name unique |
| `item` | `kind` `GOODS`/`SERVICE`; `unit_id`; `sku` unique when present; `hsn_sac` stored for P9; `is_active` |
| `warehouse` | name unique; `is_active` |
| `stock` | `(item_id, warehouse_id)` PK, `quantity_e4 >= 0` CHECK — the negative-stock refusal (P7-4) is enforced by the database as well as by the guarded decrement |
| `stock_movement` | append-only (triggers refuse UPDATE/DELETE); `movement_type`, `direction` derived from type and CHECKed against which warehouse columns are set; `quantity_e4 > 0`; optional `unit_cost_e4` recorded for P8, read by nothing in P7; `source_type`/`source_id` for the document that caused it |

A service item can never appear in `stock` or `stock_movement` (trigger).

### 11.3 Movements
`OPENING`, `PURCHASE`, `SALES_RETURN`, `ADJUSTMENT_IN` bring stock in;
`SALE`, `PURCHASE_RETURN`, `ADJUSTMENT_OUT` take it out; `TRANSFER` does both
and creates no value. Every out-movement is a **guarded decrement**: a single
`UPDATE … WHERE quantity_e4 >= ?` whose row count decides, so two concurrent
sales of the last unit cannot both succeed. `stock` is a cache of the movement
log with a **verification routine** that recomputes it and reports any
disagreement (I-4: a cache is allowed only if it is rebuildable and verified).

Movements are corrected by an opposite movement, never edited — the same rule as
vouchers.

### 11.4 Enforcement — all three layers, in production
1. **Use case:** every mutating `Inventory` method calls
   `EntitlementGuard.requireWritable(inventory, …)` first.
2. **Persistence:** `CompanyService` installs `ModuleWriteAuthorization` on
   every company database it opens, from a `ModuleRegistry` and a
   `ModuleStateProvider` handed to it by the composition root. The default
   `permitAll` is no longer reachable from the application.
3. **UI:** none yet in P7b; the screens arrive with documents.

Launcher (P7-7): `ModuleRegistry.discover()` plus `DevelopmentEntitlement`; a
release-channel build refuses to start.

### 11.5 Carried P4 tests, now possible
- **Smuggling end to end:** an inventory module on the path but not entitled
  resolves `READ_ONLY`, and both layers refuse its writes.
- **Downgrade preserves data:** a company with inventory history opened by a
  build without the module — the file's SHA-256 is unchanged by opening, the
  module is `DORMANT`, the records stay readable.

### 11.6 Not in P7b
Stock movements from documents (P7c, which also needs posting and stock in one
transaction) · UoM conversion · batches/expiry · valuation (P8) · screens.

---

## 12. P7c detailed design — sales and purchase documents

**Status:** APPROVED 2026-10-09 (P7-8…P7-12 as recommended).

### 12.1 Reference research

| ID | Finding | Evidence |
|---|---|---|
| **R-9** *(new)* | The invoice series is chosen from **today's** date with April hard-coded, not from the invoice date. A back-dated March invoice entered in April joins next year's series. | `invoices/route.ts:162` `new Date().getMonth() >= 3` |
| **R-10** *(new)* | Invoice numbers are 17 characters (`INV/2026-27/00001`). CGST Rule 46(b) allows **at most 16**. Every number the reference issues is non-compliant. | `invoices/route.ts:295` |
| **R-11** *(new)* | Line amounts are never rounded: `quantity × price × discount% / 100` keeps every digit until Postgres `Decimal(18,4)` silently rounds on insert, while the header total is summed from the unrounded values. Header and lines can disagree. | `invoices/route.ts:244-246, 288` |
| D-23 | Credit notes post backwards. | (P0) |

### 12.2 Physical modules

The two document families are separate sellable modules (`sales-invoicing`,
`purchase-billing`, B-7) but share almost all their logic. P4 forbids one
feature depending on another, so the shared engine is a **library**, not a
feature:

```
accubook-trade             (library: line maths, posting plans, numbering, lifecycle)
   ├── accubook-sales-invoicing    (FeatureModule; owns sales_* tables)
   └── accubook-purchase-billing   (FeatureModule; owns purchase_* tables)
```

`accubook-trade` requires `module-api`, `platform` and `accubook.accounting`
(for posting). It owns no tables and is not a `FeatureModule`.

### 12.3 Two ports

**Posting inside a caller's transaction.** P7-5 requires the voucher and the
stock movement to commit together. `PostingService` today opens its own
transaction. Add `PostingService.postWithin(Connection, PostingRequest)` and
`reverseWithin(...)`: same validation, same entitlement check, same sealed
writer - only the transaction boundary moves to the caller. The connection a
feature holds is the guarded one from `ModuleTransactions`.

**Stock without depending on inventory.** `module-api` gains a `StockPort`
service interface (`record(Connection, movement)`, `item(Connection, id)`).
`accubook-inventory` `provides` it; the documents library `uses` it through
`ServiceLoader`. In a BASIC build there is no provider: document lines must be
item-less (free text, services), and a line naming an item is refused.

### 12.4 Schema — migration 6

Per side (`sales_*` owned by `sales-invoicing`, `purchase_*` by
`purchase-billing`):

| Table | Key points |
|---|---|
| `*_document` | `kind` (`SALES_INVOICE`/`CREDIT_NOTE`; `PURCHASE_BILL`/`DEBIT_NOTE`), `number` (null while DRAFT), `party_id`, `document_date`, `due_date`, `status` `DRAFT`/`POSTED`/`CANCELLED`, `original_document_id` (notes), `voucher_id`, `cancellation_voucher_id`, totals derived from lines; purchase adds `supplier_document_no` |
| `*_document_line` | `item_id` (nullable), `description`, `quantity_e4`, `rate_e4`, `discount_bp`, `amount_e4`, `warehouse_id` (goods only) |
| `*_tax_line` | P7-1: exists, stays empty until P9 |

Triggers: a POSTED or CANCELLED document and its lines are immutable except the
POSTED→CANCELLED transition; a number, once given, never changes.

Seed ledgers: **`SALES_ACCOUNTS`** "Sales" under Sales Accounts and
**`PURCHASE_ACCOUNTS`** "Purchases" under Direct Expenses (5a), as system ledgers
— by migration for existing companies, by provisioning for new ones. Both
modules are in every plan, so P2-1 permits them.

### 12.5 Posting plans (pure functions, property-tested)

| Kind | Party | Sales/Purchases | Round off | Stock |
|---|---|---|---|---|
| Sales invoice | Dr total | Cr amount | Cr/Dr | `SALE` out |
| Credit note | **Cr** total | **Dr** amount | Dr/Cr | `SALES_RETURN` in |
| Purchase bill | Cr total | Dr amount | Dr/Cr | `PURCHASE` in |
| Debit note | **Dr** total | **Cr** amount | Cr/Dr | `PURCHASE_RETURN` out |

Property (D-23): for any amounts, a note moves the party and the
sales/purchases ledger in exactly the opposite direction to its parent kind.

Cancellation: reverse the voucher (P5 `reverse`) and record opposite stock
movements, in one transaction. The number stays used.

### 12.6 Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P7-8** | Document numbering | Allocated **when the document is posted**, not when drafted, so abandoned drafts leave no gaps. Series per kind per fiscal year **of the document date**. Format `INV/26-27/00001` — **15 characters**, within Rule 46(b)'s 16. Prefixes INV, CN, PB, DN. A supplier's own bill number is stored separately. |
| **P7-9** | Line amount rounding | Each line's amount `quantity × rate − discount` rounded to **paise, half-up**; the document total is the exact sum of rounded lines. Matches the printed invoice; fixes R-11. |
| **P7-10** | Rounding the document total | Round the total to the **nearest rupee**, posting the difference to Round Off, on by default with a per-document switch. |
| **P7-11** | Must a credit/debit note reference an original document? | **Yes**: a posted document of the same party, and the note's total may not exceed what remains un-credited. GST returns (P9) need the reference. |
| **P7-12** | Cancelling a posted document | Allowed only while nothing is allocated against it (P7d) and no note references it; posts a reversal and restores stock; the number stays used. |

---

## 13. P7d detailed design — settlement, outstanding, aging, statements

**Status:** APPROVED 2026-10-10 (P7-13…P7-17 as recommended).

### 13.1 Reference research

| ID | Finding | Evidence |
|---|---|---|
| R-6 | Settlement is 1:1: a receipt names at most one invoice; an unlinked receipt reduces nothing. | `Receipt.invoiceId` |
| R-7 | `amountPaid`/`amountDue` cached on the invoice, recomputed by hand. | `posting.ts:481-560` |
| **R-12** *(new)* | Aging's "as of" is cosmetic: it subtracts **today's** `amountPaid`, filters on **today's** status and has no date filter, so an aging run for last March includes invoices raised in April and ignores payments made since. | `reports/aging/route.ts:100-140` |
| **R-13** *(new)* | Receivables aging has **no document-type filter**: a credit note (type `CREDIT_NOTE`, status `SENT`) is aged as money the customer owes. Every credit note **inflates** receivables. | `reports/aging/route.ts:97-101` |
| D-21 | The over-allocation check runs outside the transaction. | (P0) |

### 13.2 Model

```
receipt (sales side)   ──<  sales_allocation  >──  sales invoice
credit note            ──<                    >──
payment (purchase)     ──<  purchase_allocation >──  purchase bill
debit note             ──<                      >──
```

- A **receipt** posts `Dr Cash/Bank, Cr customer`; a **payment** posts
  `Dr vendor, Cr Cash/Bank` — through `TransactionalPosting`, numbered
  `RCT/26-27/00001` / `PAY/26-27/00001` at posting, like documents (P7-8).
- An **allocation** matches a credit source (receipt or note) to an invoice. It
  moves no ledger balance — the voucher already did — so it only records *which*
  invoice was paid. It can be removed and redone.
- **Outstanding** = invoice total − Σ live allocations, in SQL, never stored
  (P7-3). Unallocated receipt money is an **on-account advance**.
- Over-allocation is refused **inside** the transaction (D-21): a source cannot
  allocate more than its amount, an invoice cannot receive more than its total.
- **Status** (`OPEN`, `PARTIAL`, `SETTLED`, `OVERDUE`) is derived, total —
  every input maps to one status (the reference's good idea).
- **Cancelling** a receipt reverses its voucher and removes its allocations.
  Cancelling an invoice is now refused while allocations exist (completes P7-12).

### 13.3 Reports
- **Outstanding by party** and **aging** at any as-of date: documents dated on or
  before it, allocations whose source is dated on or before it — correct
  historically (fixes R-12). Notes and receipts are never aged as receivables
  (fixes R-13); unallocated credits are shown separately as advances.
- **Party statement:** the party ledger's posted entries between two dates with
  an opening balance (movements before the start) and running balance — from
  the journal, so it reconciles with the trial balance by construction.

### 13.4 Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P7-13** | A posted credit/debit note and its original invoice | **Apply automatically**: at posting, allocate the note to its original document up to that document's outstanding; anything beyond (the invoice was already paid) stays as an unallocated credit for the party. |
| **P7-14** | Receipts and payments: draft first, or posted on entry? | **Posted on entry**, no drafts. Money received is a fact; a wrong receipt is cancelled (reversal kept), not edited. |
| **P7-15** | Aging basis | **Days past due date**; a document with no due date ages from its document date. Buckets: not yet due, 1–30, 31–60, 61–90, over 90. |
| **P7-16** | Which accounts may receive or pay money until banking (P10) exists | **Any active ledger in the Cash & Bank group** (Cash in Hand, or bank ledgers the customer creates in the chart). |
| **P7-17** | Credit/debit notes returning goods | **Per-item quantity check**: a note may not return more of an item than the original document carried, less earlier notes. Today only the amount is limited. |

Not in P7d: TDS netting and TCS (P9), refunds of a party's credit balance
(record as a payment to a customer / receipt from a vendor — a later decision),
bank reconciliation (P10), cheque bounce workflow beyond cancellation.

---

## 14. P7e detailed design — quotations and orders

**Status:** DESIGN — awaiting P7-18…P7-21.

### 14.1 Reference research

| ID | Finding | Evidence |
|---|---|---|
| D-11 | SO/PO/quotation numbers derived from the last row's string, outside any transaction; a series silently restarts at 1. | `sales-orders/route.ts:113` |
| **R-14** *(new)* | `SalesOrderItem.deliveredQty` exists and is **written by nothing**; SO → invoice copies no lines; an order can never become partially or fully delivered. | schema; FEATURE_PORTING_MATRIX §2 |
| **R-15** *(new)* | A purchase order is received **all or nothing**: setting status `RECEIVED` books every ordered line into stock. A short delivery cannot be recorded. | `purchase-orders/[orderId]/route.ts:173-245` |
| **R-16** *(new)* | Stock comes in only through PO receipt; a purchase **bill never moves stock**. A trader who buys without a purchase order never gets the goods into inventory. | `post-bill.ts` (no stock); 4 `stockMovement.create` sites |
| keep | Quotation → order conversion refuses converted, rejected and expired quotations. | `quotations/[id]/convert/route.ts:30-37` |

### 14.2 Our own gap
`PlanCatalogue` knows `purchase-orders` requires `inventory`, but **no plan
contains it** — no customer could ever be sold purchase orders (P7-21).

### 14.3 Model
- **Quotation** (sales) → **sales order** → **sales invoice**;
  **purchase order** → **purchase bill**. A quotation may also go straight to an
  invoice. All are commitments with **no ledger effect**; only the invoice or
  bill posts and moves stock (P7-5 stands).
- Order lines carry items (orders require inventory, B-7). Converting copies
  lines into a draft invoice/bill linked line-by-line to the order; posting that
  document fulfils those quantities. **Fulfilled quantity is derived** from
  posted, uncancelled linked lines — never a stored counter that nothing updates
  (R-14).
- Numbers `QT/26-27/00001`, `SO/…`, `PO/…` from `number_counter` inside the
  transaction, allocated on confirmation (fixes D-11, follows P7-8).
- Tables owned by `sales-orders` / `purchase-orders`; document lines gain a
  nullable `order_line_id`.

### 14.4 Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P7-18** | How are orders fulfilled? | **Through the invoice or bill**: converting copies lines, posting moves stock and fulfils the order (partial deliveries = several invoices). Separate delivery challans / goods-receipt notes — which move stock *before* an invoice — deferred to a later decision; they need "invoice without stock" rules to avoid counting goods twice. |
| **P7-19** | Quotation lifecycle | `DRAFT → SENT → ACCEPTED / REJECTED`, then `CONVERTED`; **expiry derived** from the valid-until date, never stored. Converted, rejected or expired quotations cannot be converted. A quotation may convert to an order or directly to an invoice. |
| **P7-20** | Over-delivery and leftovers | An invoice may **not** fulfil more than an order line has open; extra goods go on a separate, unlinked line. A partly fulfilled order can be **short-closed** with a reason, which ends its open quantity. |
| **P7-21** | Which plans get purchase orders? | **Professional and Enterprise, alongside sales orders** — both need inventory, and a trader who orders from customers usually orders from suppliers. Changes the approved B-7 matrix, so it is yours. |


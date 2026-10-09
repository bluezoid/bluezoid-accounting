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

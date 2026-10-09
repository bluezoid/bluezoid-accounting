# Defects Found in the Reference Web Application

Status: VERIFIED FROM EXISTING ACCUBOOK (located by code reading, this session)
Scope: `/home/achyut-pal/Desktop/accubook` — reference only, NOT modified.

Per the brief (§24): the web app is the *functional* reference, not a
specification to copy blindly. These are defects found during analysis. Each one
carries a decision about whether the desktop port should reproduce it.

**None of these have been reported or fixed in the web app.** Deciding whether
to also fix them there is a separate business call — see OPEN_DECISIONS.

---

## D-1 — `openingBalanceType` vocabulary mismatch (SEVERITY: HIGH)

**Every user-entered opening balance is classified as CREDIT regardless of the
account's nature.**

- Write paths store `"DEBIT"` / `"CREDIT"`:
  `ledgers/route.ts:18` (`z.enum(["DEBIT","CREDIT"]).default("DEBIT")`),
  `ledgers/[ledgerId]/route.ts:18`, `parties/[partyId]/route.ts:32`, and the UI forms.
- Read paths compare against `"DR"` / `"CR"`:
  `trial-balance/route.ts:111`, `balance-sheet/route.ts:237`,
  `cash-flow/route.ts:63`, `export/route.ts:69,363`.

So `openingBalanceType === "DR"` is never true for an API-created ledger, **and**
the intended fallback `(isDebitNature && !ledger.openingBalanceType)` also fails,
because the field is non-empty. An opening bank balance is booked as a credit.
Only `bank-accounts/route.ts:129,152` writes the `DR`/`CR` the readers expect.

**Port decision:** DO NOT REPRODUCE. Use a single closed enum `DR`/`CR`,
enforced by a Java enum *and* a SQLite `CHECK` constraint, with one shared
mapping. Pin with a test asserting a debit-nature opening balance lands on the
debit side of the trial balance.

**Migration consequence:** any data imported from the web app needs the opening
balance direction re-derived, since the stored value cannot be trusted. Flagged
in OPEN_DECISIONS (data migration path from web → desktop).

## D-2 — Cash Flow statement finds no cash (SEVERITY: HIGH)

`cash-flow/route.ts:48-56` identifies cash ledgers by hardcoded group names:
`["Cash-in-Hand", "Bank Accounts", "Cash", "Bank"]`.
The name actually seeded by `provision.ts:52` is **`"Cash & Bank"`** — which
matches none of them. On a default-provisioned organization the cash flow
statement finds zero cash ledgers and reports all zeros.

Also: Investing and Financing sections are **hardcoded empty placeholders**
(`:236-248`), so the statement is structurally incomplete even when it finds cash.

**Port decision:** DO NOT REPRODUCE. Identify cash/bank accounts structurally
(a `isCashEquivalent` flag or stable `systemCode` on the group), never by
display-name matching. Implement real investing/financing classification.
Cash Flow needs a genuine design pass, not a port — flagged in OPEN_DECISIONS.

## D-3 — No year-end close exists (SEVERITY: ARCHITECTURAL)

Confirmed by exhaustive search: **no API route ever sets `FiscalYear.isClosed = true`**.
`fiscal-years/route.ts` has only GET and POST — no PATCH, no close endpoint, and
no validation that FY ranges do not overlap. There is:

- no closing/transfer voucher,
- no opening-balance roll-forward into the next year,
- no P&L-to-equity transfer,
- no retained-earnings ledger.

`FiscalPeriod` is a **dead model** — no code reads or writes it.

Instead, `balance-sheet/route.ts:138-226` **derives all retained earnings on
every request** by reading two P&L windows (all-time and current-FY) and
subtracting. The code comment states this outright: *"Nothing closes a year —
there is no closing voucher, and profit is never posted into an equity ledger."*

**Consequence:** the balance sheet only balances *because* of this re-derivation.

**Port decision — this is the single most important accounting design decision
in the project.** A commercial accounting product is expected to support year-end
close. But implementing a real close *while* keeping the derivation would
double-count profit. The two designs are mutually exclusive and must be chosen
deliberately. Marked **DECISION REQUIRED** in OPEN_DECISIONS.

Also: `FiscalYear.isClosed` is **never checked at post time**, and `Voucher.date`
is never validated against the fiscal year's range. Back-dating into a closed
year is possible through the API. The port must enforce both.

## D-4 — Balance tolerance inconsistency causes 500s (SEVERITY: MEDIUM)

`vouchers/route.ts:136` validates with `closeEnough` (epsilon **0.01**), while
`applyLedgerEntries` (`posting.ts:394`) requires **exact** Decimal equality.
A hand-keyed voucher off by ≤ ₹0.01 passes the friendly 400 check and then
throws an unhandled 500 from the posting layer.

**Port decision:** DO NOT REPRODUCE. One rule, applied at both layers: exact
equality. A tolerance has no place in double-entry validation — if debits and
credits differ by a paisa, something is wrong and must be surfaced, not absorbed.

## D-5 — `nature` is an unvalidated free string (SEVERITY: MEDIUM)

`ledger-groups/route.ts` accepts any string for `nature`. `applyLedgerEntries`
then silently treats an unrecognised nature as **credit-natured**
(`posting.ts:417`, the `else` branch), and every financial statement drops the
group entirely. A typo produces a silently wrong balance sheet.

**Port decision:** DO NOT REPRODUCE. Closed Java enum + SQLite `CHECK`
constraint. This is one of several places where the reference app's total
absence of database-level enums costs correctness.

## D-6 — A voucher line may carry BOTH debit and credit (SEVERITY: MEDIUM)

Nothing prevents a `VoucherEntry` with `debitAmount != 0` **and**
`creditAmount != 0`. The two-column DR/CR model is enforced only by convention.
The voucher total could still balance while individual lines are meaningless.

**Port decision:** add `CHECK (debit_amount_minor = 0 OR credit_amount_minor = 0)`
and `CHECK (debit_amount_minor >= 0 AND credit_amount_minor >= 0)`.

## D-7 — Report fiscal-year math uses server-local time (SEVERITY: MEDIUM)

`provision.ts:101-107` documents the hazard and correctly builds FY bounds in
**UTC**. But every report route then does exactly the thing it warns against —
`new Date(currentYear, 3, 1)` in `trial-balance:48-50`, `balance-sheet:41-43`,
`profit-loss:49-51`, `cash-flow:41-43`, `export:46-48`. In UTC+5:30 this shifts
the boundary and can pull 1-April vouchers into the wrong year.

Additionally the trial balance's FY fallback sets `fyEnd = asOfDate` rather than
the real FY end, so its "fiscal year" window is really FY-start → asOfDate.

**Port decision:** DO NOT REPRODUCE. Fiscal dates are `LocalDate` throughout —
no instants, no zones, no server timezone. A date in accounting is a calendar
date, not a point in time. Desktop actually makes this easier: there is no
server/client zone split.

## D-8 — Budget PATCH silently discards line edits (SEVERITY: LOW)

`budgets/route.ts:219` destructures `lines` out and discards it; `:237-251`
passes `updateData` straight through. Editing a budget leaves stale monthly and
`annual` figures. `annual` is also computed in **JS `Number` float math**
(`:89-104`, `:165-177`) — one of only two float-money sites in the accounting
domain (the other is the running balance in `ledgers/[ledgerId]/route.ts`).

**Port decision:** DO NOT REPRODUCE. All money in `BigDecimal`, no exceptions.

## D-9 — Budgets and cost centers are effectively non-functional

Not bugs exactly, but the brief lists them as features to inventory, so their
real state matters for scoping:

- **Cost centers / projects** attach *only* to `VoucherEntry`, and only the
  manual-voucher route ever populates them. **Every automated posting path**
  (invoices, bills, payments, receipts, payroll, manufacturing, Tally import)
  writes `NULL`. No report filters or groups by them. There is no cost-centre
  P&L and no project profitability.
- **Budgets** are never compared to actuals anywhere — no variance report, no
  budget-vs-actual endpoint, no check at posting time.

**Port decision:** treat these as **new feature work**, not a port. Scoping them
as "already exists in the web app" would badly underestimate the effort. Their
data models are a reasonable starting point; the functionality is not there.

## D-10 — `VoucherType.code` is globally unique, not org-scoped

`VoucherType` has **no `organizationId` at all** — voucher types are shared
across every tenant, with `code @unique` globally.

**Port decision:** DO NOT REPRODUCE. In a multi-company desktop database each
company must own its voucher types (companies legitimately want their own
numbering series and custom types). Scope per company.

---

## Summary for planning

| ID | Issue | Reproduce? |
|---|---|---|
| D-1 | Opening balance direction mismatch | No — fix, and re-derive on import |
| D-2 | Cash flow finds no cash; sections stubbed | No — redesign |
| D-3 | No year-end close; retained earnings derived | **DECISION REQUIRED** |
| D-4 | Balance tolerance 0.01 vs exact | No — exact everywhere |
| D-5 | `nature` unvalidated free string | No — enum + CHECK |
| D-6 | Line may hold both DR and CR | No — CHECK constraint |
| D-7 | Report FY math in server-local time | No — `LocalDate` |
| D-8 | Budget PATCH drops lines; float math | No — `BigDecimal` |
| D-9 | Cost centers/projects/budgets not wired up | Treat as new feature work |
| D-10 | `VoucherType` not org-scoped | No — scope per company |

**The most important takeaway:** the reference app's *posting engine* is sound
and worth preserving closely. Its *reporting layer* has multiple real defects
and needs redesign rather than transliteration.

---

# Part 2 — Defects in Sales / Purchase / Inventory / Manufacturing

Added after the sales-purchase and inventory domain analyses.

## D-11 — Unsafe document numbering for SO / PO / Quotation (SEVERITY: HIGH)

Invoices, bills, payments, receipts and vouchers use the safe `NumberCounter`
table. But **sales orders, purchase orders and quotations do not.** They read the
last row and parse the number out of the string:

```ts
// sales-orders/route.ts:113-124 — duplicated in purchase-orders, quotations, and convert
const lastOrder = await prisma.salesOrder.findFirst({
  where: { organizationId: orgId }, orderBy: { createdAt: "desc" },
});
const orderNumber = lastOrder
  ? `SO-${String(parseInt(lastOrder.orderNumber.split("-")[1] || "0") + 1).padStart(6,"0")}`
  : "SO-000001";
```

Three separate faults: it runs **outside any transaction** (concurrent POSTs mint
the same number, loser hits P2002); `orderBy createdAt` is not max-number order;
and `.split("-")[1]` breaks on any imported or manually-formatted number,
**silently restarting the series at 1**.

**Port decision:** DO NOT REPRODUCE. Unify all six document families on the
counter table. In SQLite this becomes
`UPDATE number_counters SET last_number = last_number + 1 WHERE ... RETURNING last_number`
inside a `BEGIN IMMEDIATE` transaction — simpler *and* safer than the original.

## D-12 — Float money math in orders and budgets (SEVERITY: MEDIUM)

`sales-orders/route.ts:127-152` and `[orderId]/route.ts:106-135` do totals in raw
JS `Number`, unlike the rest of the system which is strictly `Prisma.Decimal`.
Also `SalesOrder.taxAmount` is **hardcoded to 0** — sales orders carry no tax at all.

**Port decision:** `BigDecimal` everywhere, no exceptions. Orders must compute tax.

## D-13 — No COGS entry at dispatch (SEVERITY: ARCHITECTURAL)

`dispatch.ts` declares `DispatchedLine.unitCost` as *"Unit cost the stock was
relieved at, for the COGS entry"* — but **no COGS entry is ever made.** Posting an
invoice creates **no stock movement**, and posting a bill creates **no stock
movement** either.

The system is therefore **periodic**, not perpetual: purchases are expensed to
`Purchase Accounts` at bill time, sales post no cost side, and gross profit is only
made correct by the closing-stock adjustment
(`COGS = opening + purchases − closing`) computed at report time by
`valueClosingStock`.

This is internally consistent and matches Tally-style Indian SMB practice, so it is
defensible. But it must be a **conscious choice**, because it means:
- inventory value in the GL and the stock module are two separate systems
  reconciled only at report time;
- there is no real-time gross margin per invoice;
- `valueClosingStock(orgId, pastDate)` is **not historically accurate** — it uses
  *today's* quantities costed with layers up to that date.

**Port decision: DECISION REQUIRED** — periodic (match reference) vs perpetual
(post COGS at dispatch, real-time inventory GL). Perpetual is what a commercial
product is usually expected to do, but it is a significant design change and
alters every financial statement. Recorded in OPEN_DECISIONS.

## D-14 — Inventory valuation is not a real cost-layer ledger (SEVERITY: HIGH)

`valuation.ts` does **not** maintain FIFO/LIFO cost layers. There are no persisted
layers and outbound movements are never matched against inbound ones. At report
time it loads **all inbound movements ever**, then walks from one end
(FIFO → newest-first; LIFO → oldest-first), falling back to weighted `avgCost`.

Consequences:
- Layers are **item-level, not warehouse-level**, so an internal `TRANSFER` inflates
  the layer pool.
- `normaliseMethod` silently maps *anything unrecognised* — **including the
  schema's own documented literal `"WEIGHTED_AVG"`** — to `WEIGHTED_AVERAGE`.
  So the schema comment and the code disagree, and typos are silently accepted.
- Meanwhile `Stock.avgCost` **is** maintained perpetually by weighted average on
  each receipt and is what actually costs issues and dispatches. So the product is
  a hybrid: perpetual weighted-average for movement costing, periodic
  method-driven derivation for the financial statements.

**Port decision:** DO NOT REPRODUCE AS-IS. A commercial product needs real,
persisted, warehouse-aware cost layers with recorded consumption. Marked
**DECISION REQUIRED** alongside D-13, since costing method and periodic/perpetual
are one combined decision.

## D-15 — Batches are a parallel register with no consumption (SEVERITY: HIGH)

`StockMovement.batchId` is written in **exactly one place** (manual stock entry,
taken verbatim from the request body). It is never validated against the item or
warehouse, never set by dispatch / GRN / work-order issue / completion, and
**never decrements `Batch.quantity`**. There is no FEFO or batch-selection logic
anywhere.

Also: nothing ever transitions a batch `ACTIVE → EXPIRED` (status is set at
creation only; the reports compensate by checking dates at read time), and
`CONSUMED` is never set. The batch PATCH path adjusts `Stock` **without** the
`gte` guard every other path uses, so it can drive stock negative.

**Port decision:** treat batch/expiry tracking as **new feature work**. The schema
is a starting point; the functionality is not there. Required for pharma/food
verticals — scope accordingly.

## D-16 — Negative-stock guard has a string bypass (SEVERITY: MEDIUM)

Negative stock is correctly prevented by `quantity: { gte: qty }` on the decrement
in all three OUT paths. But the Zod schema is
`z.union([z.number().positive(), z.string()])` — **the string branch has no
positivity check**. Posting `quantity: "-5"` decrements by −5, i.e. *increases*
stock, and the `gte: -5` guard passes trivially.

**Port decision:** DO NOT REPRODUCE. Validate sign and magnitude on a parsed
`BigDecimal`, at one boundary, for every quantity input.

## D-17 — Unimplemented schema fields (scope-estimation trap)

Present in the schema, written by nothing:
`Stock.reservedQty`, `Stock.lastPurchasePrice`, `Stock.lastSalePrice`,
`ItemUnit.conversionFactor` (**no UOM conversion is performed anywhere**),
`Bom.effectiveFrom/effectiveTo`, `WorkOrder` `CANCELLED` status,
`InvoiceTax` / `BillTax` junction tables, `VoucherType.numberingPrefix` /
`numberingFormat` / `autoNumbering`, `FiscalPeriod` (entire model).

Also missing as flows: **no SO→Invoice conversion**, **no Quotation→Invoice
conversion**, and `SalesOrderItem.deliveredQty` is **never updated** — sales order
fulfilment is entirely manual. `entityType: "INVOICE"` and `"PURCHASE_ORDER"` route
for approval but have **no promotion branch**, so those approvals never promote
anything.

**Port implication:** a schema-driven estimate of "what already exists" would be
badly wrong. These are unbuilt features, not ported ones.

## D-18 — Multi-level BOM cost is dead code

`bom-cost.ts` is a well-written pure module with cycle detection — and it is
**called by no route**. Issue/complete compute costs from `Stock.avgCost` instead.
Inside `resolveLeafCost`, `const cost = computeBomCost(...)` is computed then
discarded via `void cost`.

## D-19 — Units are global across all tenants

`UnitOfMeasure` has **no `organizationId`** and `@@unique([name])` globally;
`/api/units` is a non-org route where any authenticated user can create a unit
visible to every tenant. Same class of problem as D-10 (`VoucherType`).

**Port decision:** scope units per company in the desktop database.

## D-20 — Reference-integrity inconsistency in PO GRN

`purchase-orders/[orderId]/route.ts:196` writes `referenceId: existing.orderNumber`
— **the order *number* (a string), not the order id** — unlike every other movement
which stores an id. Any join from movement back to PO silently fails.

## D-21 — Over-allocation check runs outside the transaction

`receipts/route.ts:129-139` and `payments/route.ts:129-139` verify
`amount <= amountDue` **before** opening the transaction, so two concurrent
receipts against the same invoice can both pass and overpay it.

**Port decision:** perform the check inside the write transaction, against
freshly-read state.

---

## Updated summary

| ID | Issue | Reproduce? |
|---|---|---|
| D-11 | SO/PO/Quotation numbering race + series reset | No — unify on counter |
| D-12 | Float money in orders; SO tax hardcoded 0 | No — BigDecimal |
| D-13 | No COGS at dispatch (periodic model) | **DECISION REQUIRED** |
| D-14 | Valuation is not a real cost-layer ledger | **DECISION REQUIRED** |
| D-15 | Batches never consumed; no FEFO | New feature work |
| D-16 | Negative-qty string bypass | No — validate parsed decimal |
| D-17 | Many schema fields/flows unimplemented | Scope as new work |
| D-18 | Multi-level BOM cost is dead code | Wire it up |
| D-19 | Units global across tenants | No — scope per company |
| D-20 | PO GRN stores order number as referenceId | No — store id |
| D-21 | Over-allocation checked outside transaction | No — check inside tx |

---

# Part 3 — Defects found during Phase 0 validation (2026-08-31)

These were **missed by the original Phase 0 pass** and found during independent
validation. Three are more severe than anything previously recorded.

## D-22 — Tally import bypasses the posting invariant (SEVERITY: HIGH)

`migration/tally.ts:697-718` inlines its own `applyLedgerBalances` instead of
calling `applyLedgerEntries`, with an explicit comment saying this "keeps this
service free of the posting helper's coupling".

The copy omits every safeguard:

| Safeguard | `applyLedgerEntries` | Tally's copy |
|---|---|---|
| Debits == credits | ✅ throws | ❌ **absent** |
| `organizationId` scoping | ✅ | ❌ **filters by id only** |
| Unresolved ledger | ✅ throws | ❌ **`if (!nature) continue`** |

That last row reintroduces exactly the bug `applyLedgerEntries`' own comments
describe having fixed. **An unbalanced Tally import can post silently** — and
Tally import is how a customer's entire historical opening position enters the
system.

**Port decision: DO NOT REPRODUCE.** All import paths must go through the sealed
posting core. This defect is the primary justification for enforcing the choke
point *structurally* (package-private ledger mutation) rather than by convention.

## D-23 — Credit and debit notes post BACKWARDS (SEVERITY: CRITICAL)

**The most serious accounting defect in the reference application.**

`decideInvoiceEntries` (`post-invoice.ts:79-99`) contains **no `CREDIT_NOTE`
branch** — verified, `grep 'CREDIT_NOTE\|DEBIT_NOTE'` across `post-invoice.ts`
and `post-bill.ts` returns nothing. It unconditionally computes:

```ts
const drCustomer = taxable.plus(gst).plus(roundOff);
return { drCustomer, crSales: taxable, crGstOutput: gst, roundOff };
```

The UI creates credit notes (`sales/credit-notes/page.tsx:242` posts
`type: "CREDIT_NOTE"`), and `invoices/[invoiceId]/route.ts:185` calls
`postInvoiceToGl` for **any** invoice moving DRAFT → SENT with no type check.

**A credit note therefore debits the customer and credits sales — a sales return
*increases* revenue and receivables.** The purchase side is the same, and
`bills/route.ts:22` types `type` as a bare `z.string()` with no enum guard.

Impact: overstated revenue, overstated AR/AP, wrong GST output liability.
Worse than D-1 because it affects routine, everyday transactions.

**Port decision: DO NOT REPRODUCE.** Credit/debit notes are first-class,
sign-aware document types. Property test: a credit note always moves revenue and
receivables in the **opposite** direction to an invoice.

**Recommend reporting to the web-app team — this affects live users now.**

## D-24 — Inventory `asOf` does not filter quantities (SEVERITY: CRITICAL)

`valuation.ts` applies the `asOf` date **only** to the cost-layer query (`:181`).
`stock.findMany` (`:116`) and `loadPendingLines` (`:162`) read **current**
quantities with no date predicate.

Consequences:
- `valueClosingStock(orgId, lastYearEnd)` returns **today's quantity** priced
  with last year's layers.
- For weighted-average items (the default **and** the silent fallback for any
  unrecognised method), opening and closing are computed from the same
  quantities and **cancel to zero** — the periodic adjustment does nothing.
- Reprinting last year's P&L after this year's trading gives a different answer.
  **Statements are not reproducible.**

**Port decision: DO NOT REPRODUCE.** See `INVENTORY_ACCOUNTING.md` — this is a
primary reason to go perpetual with persisted cost layers.

## D-25 — Manufacturing double-counts inventory (SEVERITY: CRITICAL)

`balance-sheet/route.ts:329-331` states in a comment that "Stock-in-Hand is
derived from the inventory, not from a ledger". **This is false for any company
that manufactures.**

The `Stock-in-Hand` *ledger* (`provision.ts:78`, ASSETS under Current Assets) is
written by `post-wo-journal.ts:64`, so it **is** in `assetLedgerTotal` (`:328`).
Line `:332` then adds the derived valuation on top:
`totalAssets = assetLedgerTotal.plus(stockNow)`.

**A manufacturer's balance sheet overstates assets by cumulative completed
production value.** Relatedly the WIP journal pair is net-zero (same amount both
legs, both ledgers in the same group), so it is ceremony that changes no reported
total while still corrupting the balance sheet.

## D-26 — Approval routing dead-ends for INVOICE and PURCHASE_ORDER (MEDIUM)

`route-entity.ts:36` declares both in the entityType union, but
`promote-entity.ts` has branches only for VOUCHER, BILL, EXPENSE_CLAIM and LEAVE.
Approvals for those two types accumulate rows that never promote anything.

## D-27 — Batch quantities never decremented (MEDIUM)

Dispatch and work-order issue adjust `Stock`, never `Batch`. Batch balances drift
permanently. There is also no FEFO selection anywhere, and the batch PATCH path
adjusts stock **without** the `gte` guard every other path uses.

## D-28 — Leave quota is decorative (MEDIUM)

`LeaveType.annualQuota` is validated on create and displayed in the UI, but
`leaves/route.ts` performs **no balance check**. Employees can exceed quota
without limit.

## D-29 — Two reconcilers; the UI calls the untested one (MEDIUM)

The **tested** service (`banking/reconcile.ts`, with `reconcile.test.ts`) never
writes the `BankReconciliation` model. The **untested** 570-line inline route
(`bank-reconciliation/route.ts:481-548`) does, uses `Number()` float math for
book balance, includes DRAFT vouchers — and **is the one the UI calls**.

## D-30 — Stock adjustments post no GL entry (MEDIUM)

`stock/route.ts` (413 lines) contains **zero** occurrences of `voucher` or
`applyLedgerEntries`. A write-off reduces quantity — and therefore silently
reduces gross profit through the derived `− closing` term — with **no expense
ledger and no visible write-off line in the P&L**.

## D-31 — Transfers corrupt valuation two ways (MEDIUM)

- `TRANSFER` is in `INBOUND` (`valuation.ts:70`), so an internal transfer
  **fabricates a phantom FIFO cost layer**.
- Transfer-in does **not** revalue the destination's `avgCost`
  (`stock/route.ts:346-355`), so per-warehouse averages are wrong after any
  transfer.

## D-32 — Balance sheet hardcodes April–March (MEDIUM)

`balance-sheet/route.ts:41-43` computes the fiscal-year split from
`getMonth() >= 3`, ignoring both the `FiscalYear` table and
`Organization.fiscalYearStart`. The trial balance *does* honour `fiscalYearId`,
so **the two reports disagree for any company not on an April year**.

---

## Revised severity ranking (all parts)

| Rank | ID | Defect |
|---|---|---|
| 1 | **D-23** | Credit/debit notes post backwards |
| 2 | **D-25** | Manufacturing double-counts assets |
| 3 | **D-24** | Inventory `asOf` ignores quantities |
| 4 | **D-1** | Opening balances sign-flipped |
| 5 | **D-22** | Tally import bypasses posting invariant |
| 6 | D-2 | Cash flow finds no cash |
| 7 | R-6 | Fabricated IRN |
| 8 | D-30/D-31 | Adjustments unposted; transfers corrupt valuation |

**Original Phase 0 identified #4, #6 and #7 only.**

# Inventory Accounting

**Status:** RECOMMENDED — **Perpetual, weighted-average default, FIFO optional,
LIFO excluded.**
**Reverses the Phase 0 leaning toward "periodic + derived closing stock".**

---

## 1. What the reference actually is (verified)

Phase 0 called it "periodic with derived closing stock". That is the **headline**,
but it is incomplete in a way that changes the decision. The reference is really:

- **Periodic for trading** — purchases expensed to `Purchase Accounts` (an
  EXPENSE under Direct Expenses), no cost booked on sale, closing stock derived
  at report time and injected into both statements.
- **Accidentally perpetual for manufacturing** — real WIP vouchers
  (`Dr WIP / Cr Stock-in-Hand` on issue, reversed on completion).
- **An abandoned perpetual dispatch design** — `dispatch.ts:351` computes
  `unitCost`, `dispatch.ts:43` documents it as *"Unit cost the stock was relieved
  at, for the COGS entry"*, it is threaded into the return value and the audit
  log — **and the COGS entry is never posted.**

Verified structural facts:
- Exactly **4 `stockMovement.create` sites**. Neither invoice nor bill posting is
  among them (both files contain zero occurrences of "stock").
- **No COGS ledger is provisioned at all** — there is nowhere to post a
  perpetual cost entry even if you wanted to.
- `postDispatch` opens a transaction that does three things: guarded decrement,
  movement insert, audit write. **No voucher, no `applyLedgerEntries`.**

## 2. Why the periodic model does not actually work

The decisive finding: **the periodic model's one distinguishing feature evaluates
to zero for most items.**

`valuation.ts` applies `asOf` **only to the cost-layer query** (`:181`). The
quantity sources — `stock.findMany` (`:116`) and `loadPendingLines` (`:162`) —
have **no date predicate** and read *current* values.

So for a weighted-average item (the default, and the silent fallback for any
unrecognised method string), opening and closing stock are computed from the
**same quantities**, differing only by layer pricing that WA does not use. The
periodic adjustment

```
COGS = opening + purchases − closing
```

therefore **cancels to zero**. The reference is not a working periodic system; it
is a periodic *skeleton* whose adjustment silently does nothing for the majority
of items, and whose historical reports are not reproducible.

Add the manufacturing double-count (§8.3 of the validation: the `Stock-in-Hand`
ledger is inside `assetLedgerTotal` **and** `stockNow` is added on top), and the
balance sheet is wrong for any company that manufactures.

**Conclusion: there is no working periodic implementation to preserve
compatibility with.** The argument for choosing periodic — "match the reference"
— does not survive contact with the evidence.

## 3. The decision

| Criterion | Periodic | **Perpetual (recommended)** |
|---|---|---|
| COGS visible per sale | No | **Yes** |
| Real-time gross margin | No | **Yes** |
| Stock-in-Hand is a real ledger balance | No — derived and bolted on | **Yes** |
| Historical statements reproducible | Only with a dated valuation engine | **Yes — it's a posted entry** |
| Write-offs visible as expense | **No** (leak into COGS silently) | **Yes** |
| Balance sheet correctness with manufacturing | Broken in reference | **Consistent** |
| Implementation cost | Lower | Higher |
| Matches Tally SMB habit | Closer | Slightly further |

**Recommendation: perpetual.** It eliminates four separate defect classes at
once (D-24 non-reproducible valuation, D-25 double-count, D-26 invisible
write-offs, and the abandoned-COGS scaffolding), and it makes Stock-in-Hand a
real, auditable ledger balance instead of a number injected into the balance
sheet by the report layer.

**DECISION REQUIRED** — this is a genuine accounting-policy choice with cost
implications, and it should be confirmed by you (and ideally a CA), not assumed.
My recommendation is perpetual, and the reasoning is above.

## 4. Costing methods

| Method | Recommendation |
|---|---|
| **Weighted average** | **Default.** What most Indian SMEs use; no layer table needed for valuation |
| **FIFO** | **Offer.** Statutory alternative; requires a real consumption ledger |
| **LIFO** | **Exclude.** Prohibited under **AS 2 / Ind AS 2**. The reference "supports" it only as a report-time array reversal that no auditor would accept. Shipping it invites misuse |

Excluding LIFO is a deliberate correctness decision, not a scope cut — offering a
method that is not permitted under Indian accounting standards would be a defect.

## 5. Required design elements

### 5.1 Record consumption, do not reconstruct it
The reference rebuilds a notional layer stack at report time from surviving
inbound movements. That is why a backdated or deleted movement silently rewrites
history.

**Design:** a `stock_layer` table (receipts, with remaining quantity) and a
`stock_layer_consumption` table (which layer each issue relieved, and at what
cost). SQLite handles this comfortably, and it is the only way to get
reproducible historical valuation and auditable FIFO.

### 5.2 Post cost on issue
```
Dr  Cost of Goods Sold        (at the relieved cost)
  Cr Stock-in-Hand
```
in the **same transaction** as the stock movement. This finishes the job the
reference abandoned at `dispatch.ts:43`.

Requires provisioning a **`Cost of Goods Sold` ledger** under Direct Expenses —
the reference has none.

### 5.3 Cost granularity must be consistent
The reference maintains `avgCost` **per warehouse** but builds FIFO layers
**per item** (`valuation.ts:150` groups on `itemId` alone, dropping warehouse).
That mismatch is a bug generator.

**Decide once and hold it: per item **per warehouse**.** That matches how stock
is physically held and how `Stock` is already keyed.

### 5.4 Transfers must not create or destroy value
- A transfer moves cost with the goods: relieve the source layer, create a
  destination layer **at the same cost**.
- A transfer must **never** appear as an inbound costing layer for valuation
  purposes (the reference's `TRANSFER ∈ INBOUND` fabricates phantom layers).
- Net GL effect of a transfer: **zero**.

### 5.5 Returns are first-class, sign-aware movements
- `SALES_RETURN` (goods back in) — reverse at the **original issue cost**, not
  the document's selling price. The reference's `RETURN ∈ isIncomingMovement`
  would revalue inventory at retail, inflating assets and profit.
- `PURCHASE_RETURN` (goods back out) — **has no representable movement type in
  the reference at all** (`RETURN` is hardcoded direction IN).
- Both must be **linked to the credit/debit note**, so the stock side and the
  financial side cannot diverge. This also interlocks with D-23 (credit notes
  posting backwards) — both must be fixed together.

### 5.6 Adjustments post to the GL
```
Write-down:  Dr Inventory Write-off (Indirect Expenses) / Cr Stock-in-Hand
Write-up:    Dr Stock-in-Hand / Cr Inventory Gain
```
Shrinkage and write-offs must be **visible as expenses**, not silently absorbed
into cost of sales via a derived closing-stock term.

### 5.7 Manufacturing
- Issue: `Dr WIP / Cr Stock-in-Hand` (real, at relieved cost).
- Completion: `Dr Finished Goods / Cr WIP`.
- **WIP and Finished Goods must be distinguishable** — the reference puts
  `Work in Progress` and `Stock-in-Hand` in the *same group*, which is why its
  JV nets to nothing.
- **Scrap must be costed.** `fgUnitCost = wipValue / completed` silently loads
  scrap cost onto good units. Either post scrap to a
  `Manufacturing Scrap Loss` expense, or (if policy is absorption) make that an
  explicit, documented, configurable choice. **DECISION REQUIRED.**

### 5.8 Batches
- Batch quantity must be **decremented on issue** (D-27).
- **FEFO** (first-expiry-first-out) selection for batch-tracked items.
- Expired stock must be **blocked from issue**, not merely reported.

## 6. What to keep from the reference

These are genuinely good and should be ported closely:

1. **The invoice/dispatch split.** Revenue recognition is decoupled from physical
   movement, with `pending = invoiced − dispatched` derived from movements. The
   three-position model (**physical / inProgress / accounting**) is clear and
   correct. **Do not couple invoicing to stock relief** — keep them separate
   documents with a derived pending position.
2. **The guarded-decrement concurrency pattern:**
   `updateMany({ where: { …, quantity: { gte: qty } } })` then check
   `count === 0`. Race-free, cheap, and it maps directly onto JDBC row counts.
3. **The movement-direction table** (`movement-rules.ts`) — centralising which
   warehouses each movement type may name, after a real bug where a `SALE` with
   only `toWarehouseId` *increased* stock.
4. **Decimal discipline** — never floats for money or quantity.

## 7. Consequences

**Costs:** more schema (layers + consumption), more posting logic, a COGS ledger
in provisioning, and migration thought for any imported opening stock.

**Benefits:** reproducible historical statements; correct balance sheet for
manufacturers; visible write-offs; real-time margin; and the elimination of
D-24, D-25, and the invisible-adjustment problem in one design.

**Open sub-decisions** → `OPEN_DECISIONS.md`: perpetual vs periodic confirmation;
FIFO in v1 or WA-only; scrap absorption policy; negative stock policy (the
reference forbids it physically but permits it *accounting*-wise by allowing
invoicing beyond stock).

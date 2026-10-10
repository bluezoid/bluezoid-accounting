# Phase 8 Design — Inventory Valuation (periodic, form 5a)

**Status:** APPROVED 2026-10-10 (P8-1…P8-5 as recommended) · **Date:** 2026-10-10
**Baseline:** 610 passing · 0 failing (UI on Xvfb)

> **The roadmap calls this phase "perpetual inventory costing". B-1 chose
> periodic 5a**, so the phase is: *persisted cost layers (A-1) + a closing-stock
> entry posted at period end*. No cost of goods sold is posted when goods are
> issued.

**Exit criterion (roadmap, adapted):** valuation is reproducible for any
historical date (fixes D-24) and the Stock-in-Hand ledger agrees with the stock
valuation at every posted valuation date (fixes D-25 for trading).

---

## 1. What exists

- Every stock movement already records quantity, warehouse(s) and date;
  purchase bills record `unit_cost_e4` on their movements (P7c).
- Stock never goes negative (P7-4), so every issue has stock to be costed from.
- Movement log is append-only; corrections are opposite movements.

## 2. Reference defects this phase must not repeat

| ID | Defect |
|---|---|
| D-24 | "As of" valuation uses **today's** quantities at historical costs. |
| D-14 | No persisted layers; FIFO/LIFO re-derived at report time from all inbound movements; unknown method strings silently become weighted average. |
| D-31 | A transfer creates a phantom inbound layer and does not move cost to the destination. |
| — | LIFO offered, though AS 2 / Ind AS 2 prohibit it. |

## 3. Model — a costing ledger per item and warehouse

For every movement, one **valuation entry** per warehouse it touches:

| Column | Meaning |
|---|---|
| `movement_id`, `item_id`, `warehouse_id` | which movement, where |
| `entry_date`, `sequence` | costing order: date, then entry order |
| `quantity_e4` | signed: + in, − out |
| `value_e4` | signed value of this entry |
| `quantity_after_e4`, `value_after_e4` | the position after it |

**Valuation at any date** = for each item and warehouse, `value_after` of its
last entry on or before that date. Stored, so it is reproducible (A-1, fixes
D-24) — never recomputed from current quantities.

- **Incoming** (purchase, opening, adjustment in) enters at its own unit cost.
- **Outgoing** (sale, purchase return, adjustment out) leaves at the method's
  cost: weighted average → current average; FIFO → oldest open layers, with a
  **consumption record** per layer drawn on.
- **Transfer** leaves the source at its cost and enters the destination at
  exactly that value — no value created or lost, no phantom layer (fixes D-31).
- **Sales return** comes back at the cost it left with on the original invoice
  (§5.5 of the inventory architecture); a purchase return leaves at the cost it
  came in at.

## 4. Posting the closing stock

A **valuation run** at a date computes total stock value V and posts one voucher
dated that day for the difference between V and the Stock-in-Hand balance:

```
increase:  Dr Stock-in-Hand        Cr Change in Stock   (Direct Expenses → gross profit)
decrease:  Dr Change in Stock      Cr Stock-in-Hand
```

So Stock-in-Hand always equals the last posted valuation, gross profit reflects
opening + purchases − closing, and everything goes through the sealed posting
core. Ledgers `STOCK_IN_HAND` and `CHANGE_IN_STOCK` are created the first time a
valuation is posted (P2-1: a build without inventory never shows them).

## 5. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P8-1** (I-1, I-2) | Methods and granularity | **Weighted average (default) and FIFO, chosen per item, fixed once the item has moved; LIFO excluded** (not permitted by AS 2). Costed **per item per warehouse**. |
| **P8-2** | Which weighted average? | **Moving average**: recalculated on every receipt. Matches what traders see in Tally and gives a cost at every date. |
| **P8-3** | Backdated and out-of-order entry | **Re-cost forward**: an entry dated in the past re-costs that item and warehouse from its date onwards. Any posted valuation at or after that date is marked **stale** and must be re-posted, which posts the correcting difference on its original date. Closed years refuse it (P11). |
| **P8-4** | When is closing stock posted? | **On demand, at any date** (month, quarter or year end), by a valuation run. Year-end close (P11) will require a current one. |
| **P8-5** | Costs the system cannot know | **Opening stock and stock found in a count need a unit cost**; a positive adjustment without one is valued at the current average. Write-downs to net realisable value (AS 2's "lower of cost and NRV") are **manual adjustments** for now, not computed. |

## 6. Not in Phase 8
Batches, FEFO and expiry (D-15, D-27) · manufacturing, WIP and scrap (I-3) ·
landed cost (freight added to purchase cost) · NRV computation · screens.

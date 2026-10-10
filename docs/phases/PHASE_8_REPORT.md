# Phase 8 Report — Inventory Valuation (periodic 5a)

**Date:** 2026-10-10 · **Status:** ✅ **COMPLETE**
**Tests:** **627 passing · 0 failing · 0 skipped** (UI on Xvfb) · `BUILD SUCCESS`
**Baseline before P8:** 610 (+17)

## Exit criterion — met
- **Valuation is reproducible for any historical date (fixes D-24):** value at a
  date is read from the persisted valuation ledger; a test shows later sales and
  purchases leave an earlier date's value unchanged.
- **Stock-in-Hand agrees with the valuation at every posted run:** asserted for
  single, incremental, superseded and stale-then-corrected runs, and for a
  month of real invoices and bills (to the paisa, P8-6).

## Decisions (all yours)
P8-1 weighted average + FIFO, no LIFO, per item per warehouse (resolves I-1,
I-2) · P8-2 moving average · P8-3 backdated entries re-cost forward, posted
runs go stale · P8-4 valuation runs on demand · P8-5 unknown costs ·
**P8-6** (raised during implementation) closing stock posts in whole paise.

## Implemented

| Item | Where |
|---|---|
| Migration 9: costing method per item (fixed once moved), `valuation_entry`, `fifo_layer`, `fifo_consumption`, `valuation_run`, movement cost source | `Schemas` |
| `Costing`: replays an item across all warehouses from a date; moving average; FIFO with consumption; transfers move value (D-31) and keep FIFO layers; returns and cancellations at the cost of what they undo | `accubook-inventory` |
| Every stock movement is costed in its own transaction; posted runs it affects go stale | `StockWriter` |
| `Inventory.valuation(asOf)`, `postValuation(date)`, `valuationRuns()`, `setCostingMethod` | `accubook-inventory` |
| Closing-stock voucher Dr Stock-in-Hand / Cr Change in Stock (Direct Expenses), through the sealed core; ledgers created on first use | `Inventory` |
| Documents pass a cost source on notes and cancellations, so inventory never reads their tables | `DocumentEngine`, `StockPort` |

## Found during implementation
1. **FIFO across transfers** — my first version merged transferred goods into
   one blended layer; a hand-worked test caught it. Layers now travel intact.
2. **Sub-paisa closing stock** — valuation at four decimals would have put
   fractions of a paisa into the ledger. Raised as P8-6 rather than decided.
3. **A boundary test forbade inventory → accounting**; P4 allows a feature to
   use the kernel posting core, so the test was widened deliberately, and the
   tests needing persistence moved to the integration module (the P7d rule).

## Mutation checks
| Mutation | Caught by |
|---|---|
| transfer arrives at zero value (D-31) | `CostingIT.transfer` |
| cost source ignored | `DocumentCostingIT.salesReturnAtIssueCost`, `cancellationAtIssueCost` |

## Risks / not done
- **No screens** (valuation report, runs, costing method).
- Re-costing replays an item from the backdated date; for items with very long
  histories this could be slow — not measured yet (P16 performance).
- A movement dated in a closed year is not yet refused — closing arrives in P11.
- NRV write-downs are manual; landed cost, batches/FEFO and manufacturing are
  out of scope (§6 of the design).
- Year-end (P11) must require a current valuation run before closing.

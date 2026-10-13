---
name: reference-app-defects
description: Defects in the reference web app that must NOT be ported
metadata:
  type: reference
---

# Reference app defects — do not copy

`/home/achyut-pal/Desktop/accubook` is the **functional** oracle only. 32 defects
catalogued; full list in `docs/analysis/DEFECTS_IN_REFERENCE_APP.md` and
`docs/phase-0-review/PHASE_0_VALIDATION.md`.

## Critical
1. **D-23 — credit/debit notes post BACKWARDS.** `decideInvoiceEntries` has no
   `CREDIT_NOTE` branch, so a sales return *increases* revenue and receivables.
   Affects live users today.
2. **D-25 — manufacturing double-counts assets.** The `Stock-in-Hand` ledger is
   inside `assetLedgerTotal` *and* derived `stockNow` is added on top.
3. **D-24 — inventory `asOf` ignores quantities.** Historical valuation is
   fiction; for weighted-average items the periodic adjustment cancels to zero.
4. **D-1 — opening balances sign-flipped.** Writes store `DEBIT`/`CREDIT`, reads
   compare `DR`/`CR`, and the fallback fails because the string is truthy.

## High
- **D-22 — Tally import bypasses the posting invariant** (no balance check, no
  tenant scoping). This is why the posting core must be *sealed*, not merely
  conventional.
- **D-2 — cash flow finds no cash** (hardcoded group names never match the seed).
- **Fabricated IRN** — a 32-bit hash + `Date.now()` behind a comment claiming
  SHA-256, stamped `GENERATED` with no NIC call. **Never fabricate an IRN.**
- D-11 SO/PO/quote numbering race · D-26 approvals dead-end for
  INVOICE/PURCHASE_ORDER · D-27 batches never decremented · D-28 leave quota
  unenforced · D-29 two reconcilers, UI uses the untested one.

## Schema-only — look implemented, are not
FiscalPeriod · ItemUnit.conversionFactor · SalesOrderItem.deliveredQty ·
Stock.reservedQty · Stock.lastPurchasePrice · Bom.effectiveFrom/To ·
WorkOrder CANCELLED · VoucherType.numberingFormat · InvoiceTax · BillTax ·
ReportTemplate.schedule · Designation · multi-currency.

**Missing entirely:** year-end close · GSTR-4 · Form 27D · FEFO ·
expense-claim GL posting · budget-vs-actual.

## Scale, corrected
74 models · 122 routes · **~103k hand-written TS** (the "300k" figure includes
~198k of generated Prisma client) · 646 tests, of which 21 Playwright point at
**production** — effective oracle is 625.

## Test coverage is uneven
**Trust:** GST, TDS/TCS, payroll calc, posting, permissions, concurrency.
**Zero tests:** budgets, approvals, quotations, orders, cost centres, projects,
expense claims, attendance, leave, multi-currency, all master CRUD, entire UI.
Read a test before porting it — the year-end test never calls the route it
appears to test.
- R-18 (P9c): reference TDS on annual-threshold crossing deducts only on the crossing bill; earlier below-threshold bills of the year never bear TDS. Also never rounds TDS.
- R-22: 2B money via Number() floats. R-23 (suspected): reads idt/iamt names from 2B (2B uses dt/igst). R-24: CMP-08 composition tax on all turnover for traders; rate undated.

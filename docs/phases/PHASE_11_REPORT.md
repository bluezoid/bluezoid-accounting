# Phase 11 Report — Year-end closing and financial reporting

**Status:** ✅ **COMPLETE 2026-10-24**
**Design:** `PHASE_11_DESIGN.md` (B-2, I-16, P11-1, P11-2 approved 2026-10-24)
**Tests:** **841 passing · 0 failing · 0 skipped** with UI on Xvfb (832 + 9 UI)
**Before Phase 11:** 820 (+21)

## Implemented

| Item | Where |
|---|---|
| Migration 19: no voucher into a closed year or dated outside its year (trigger); years close in order and only the latest reopens; `fiscal_year_event` (OPENING / CLOSE / REOPEN), append-only; the unused ledger opening columns retired | `Schemas` |
| The same checks in the posting core, for posting and reversal | `VoucherWriter.requireOpenYear` |
| The next fiscal year follows on from the last year's end, never a hard-coded April–March (D-32) | `FiscalYears.createNext` |
| **P11-1** opening voucher on the first day of the first year; any difference to Opening Balance Equity, reported; once only | `FiscalYears.postOpening` |
| **B-2** close: a closing voucher on the last day moves every income and expense balance to Retained Earnings, then the year is locked; refused while drafts exist or stock moved without a year-end valuation; the next year is created if missing | `FiscalYears.close` |
| **I-16** reopen: latest closed year only, with a reason; reverses its closing voucher; closing again posts a fresh one | `FiscalYears.reopen` |
| Profit and loss (trading section, gross profit, net profit) for any period; closing and reopening vouchers left out so a closed year still shows its trading | `FinancialStatements.profitAndLoss` |
| Balance sheet as of any date, with the unclosed profit shown and a balance check | `FinancialStatements.balanceSheet` |
| Migration 20 and **P11-2** cash flow, direct method: Cash & Bank movements classed by the counter-ledger's group; contra transfers and the opening voucher left out of flows; classification changeable | `FinancialStatements.cashFlow`, `classify` |
| Day book, ledger account with balance brought forward and running balance, sales and purchase registers (notes as negatives) | `Registers` |
| CSV (RFC 4180), JSON and XLSX export of any report table; money exact, never through a double; the XLSX written directly, no library | `Table`, `TableExport` |

## Findings
- **D-2** closed: the reference's cash flow finds no cash; ours reconciles
  opening + flows = closing, by test.
- **D-32** closed: years follow from data.
- The posting core accepted a journal or reversal in any year; only the
  document engine checked `is_closed`. Now the core and the schema refuse it.
- A draft can still be *created* in a closed year; *posting* it is refused.
  Closing refuses while drafts exist, so none is left behind by a close.

## Verification
- `YearEndIT` (6): the **roadmap exit** — close year 1, year 2 opens at year
  1's closing, a back-dated posting into year 1 refused — plus opening voucher,
  out-of-year dates, incomplete books, reopen-correct-close, order of closing.
- `FinancialStatementsIT` (5): a year of trade by hand — gross profit 20,000,
  net 15,000; assets 1,35,000 balance; Retained Earnings 15,000 after closing;
  cash flow 1,00,000 + 5,000 − 8,000 + 20,000 = 1,17,000; reclassification.
- `RegistersIT` (5): day book debits = credits = 2,28,000; cash account
  1,00,000 → 70,000 → 65,000 → 57,000; sales register 50,000 − 5,000 note.
- `TableExportTest` (5): CSV quoting and formula guard, JSON escaping, XLSX
  parts, date serials (1 April 2026 = 46113), A1 references past Z.
- Mutations caught (P11c): credit-note sign, brought-forward boundary,
  running-balance credit, formula guard, XML quote escape, date epoch.
- `./build.sh --plan basic`: launched on Xvfb for 25 s, no exception.

## Found on the way
- With a fixed clock, vouchers on one day share `posted_at`; ordering then by
  voucher id (a random UUID) would shuffle a running balance. Entries are
  ordered by insertion after date and posting time.
- **CSV injection**: a party named `=HYPERLINK(...)` would run as a formula in
  a spreadsheet. Text cells starting with `= + - @` are prefixed with `'` in
  CSV only; money is never altered; XLSX text is inline and never evaluated.
- The IDE trap struck twice more (`FiscalYears`, `RegistersIT`); clean builds.

## Not done / limits
- No screens; print and PDF come with the screens (design §2).
- Party statement is the ledger account of the party's ledger; no separate
  layout with ageing yet.
- Cash flow by the direct method only; the indirect method can follow.
- Stock and party openings are their own documents (P7b, P11-1), not lines of
  the opening voucher.
- XLSX is a single sheet with no column widths or totals rows.

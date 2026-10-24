# Phase 11 Design — Year-end closing and financial reporting

**Status:** DESIGN — awaiting B-2, I-16, P11-1, P11-2

---

## 1. What exists, and what it lacks
- `fiscal_year` has `is_closed`, but **nothing closes a year**, and the
  **posting core does not refuse a closed year** or a voucher dated outside its
  fiscal year: only the document engine checks `is_closed`. A journal or a
  reversal can still be posted into any year.
- `ledger` has opening-balance columns that **nothing sets or reads**; the trial
  balance shows one year's movements only.
- Opening Balance Equity and Retained Earnings ledgers are seeded.
- The reference has **no close at all** and re-derives retained earnings from
  every voucher since inception on every report (B-2 evidence); its cash flow
  finds **no cash** (D-2); its fiscal calendar is hard-coded April–March (D-32).

## 2. Sub-phases
| | Scope |
|---|---|
| **P11a** | Fiscal years from the company's start month (D-32); creating the next year; **closed years and out-of-year dates refused in the posting core and the schema**; opening balances; close and reopen |
| **P11b** | Profit and loss (gross profit through the direct-expense groups and closing stock), balance sheet as of any date, cash flow |
| **P11c** | Day book, ledger account, sales and purchase registers; CSV, JSON and XLSX export (print and PDF with the screens) |

**Exit (roadmap):** close year 1, see year 2's opening equal year 1's closing,
and have a back-dated posting into year 1 refused.

## 3. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **B-2** | Year-end | **(a) A real closing voucher** on the year's last day moves every income and expense ledger's balance to Retained Earnings, and the year is **locked** - in the posting core and by a schema trigger. Year 2's opening is year 1's closing **by construction**: balance-sheet ledgers carry their cumulative balance, profit-and-loss ledgers start at nil because the close emptied them. No carry-forward voucher. **Closing is refused** while the year has drafts (documents or vouchers) or, with inventory, while its year-end closing-stock valuation is missing or stale: the books must be complete. The next year is created if it does not exist. |
| **I-16** | Reopening a closed year | **Only the most recently closed year**, by an explicit action with a reason, recorded in the audit log. Reopening **reverses its closing voucher**; corrections are then posted **in that year**, and closing again posts a fresh closing voucher. A year whose next year is also closed cannot be reopened (reopen the later one first). Once the accounts are adopted, the user should not reopen: corrections then go in the current year as prior-period items (AS 5) - an ordinary journal, not a feature. |
| **P11-1** | Opening balances when a business starts using AccuBook | **An opening voucher** dated the first day of the first year: one balanced journal of every ledger's balance, any difference to **Opening Balance Equity** and shown as a warning. Party and stock openings as their own documents (party opening invoices; opening stock movements, P7b). The unused ledger opening columns are **retired**: one kind of record - vouchers - for every balance. |
| **P11-2** | Cash flow statement | **Direct method** from the Cash & Bank ledgers' movements, each classified by the group of the ledger it moved against: operating by default; fixed assets → investing; loans, capital → financing; cash-to-bank transfers (contra) left out. The classification of each group is shown and can be changed. (Indirect method, which reconciles from profit, can follow.) |

# Phase 10 Report — Banking and bank reconciliation

**Status:** ✅ **COMPLETE 2026-10-24**
**Design:** `PHASE_10_DESIGN.md` (P10-1…P10-5 approved 2026-10-24)
**Tests:** **820 passing · 0 failing · 0 skipped** with UI on Xvfb (811 + 9 UI)
**Before Phase 10:** 805 (+15)

## Implemented

| Item | Where |
|---|---|
| `accubook-banking` feature module (Professional, Enterprise); uses only the kernel posting core | new module |
| Migration 18: bank details of a Cash & Bank ledger **with no balance of its own**; statement imports kept with SHA-256; statement lines unique per account by identity; matches removed with a reason, never edited; reconciliation start fixed once anything is matched | `Schemas` |
| CSV statements: HDFC, ICICI, SBI, Axis and user-mapped layouts; header found after any preamble; `Dr`/`Cr` and brackets read as signs; an unreadable amount or date **refuses the file** naming the row (R-26); running balances must follow on; newest-first files turned round | `StatementParser` |
| Import counted before it is written; overlapping statements add only new lines; identical lines on one day stay distinct (R-27) | `Banking.importStatement` |
| **One** engine (D-29): suggestions on exact amount within 10 days, scored on date, reference (cheque padding ignored) and party names; confirmed by the user; "confirm all certain" only for unique exact-reference matches (P10-3) | `Banking.suggestions`, `confirm` |
| Matches one-to-many either way, agreeing to the paisa; dated with the bank's date; unmatched with a reason | `Banking.confirm`, `unmatch` |
| Charges, interest and direct items posted from their statement line to a chosen ledger, matched at once (P10-4) | `Banking.postFromLine` |
| Reconciliation statement as of a date: books, opening difference at the start (P10-5), cheques issued not presented, deposits not credited, bank-only items, computed and stated bank balance, difference | `Banking.statement` |

## Findings
- **R-26** — the reference's amount sanitiser strips every I, N and R and drops
  brackets: "500.00 Dr" imports as 0, "(500.00)" loses its sign.
- **R-27** — duplicates found in application code, without the running
  balance and without a unique index: two identical charges collapse into one.
- **D-29** confirmed and closed: one engine, tested, is the only one.

## Verification
- `StatementParserTest` (7), `BankingIT` (6), `BankingModuleTest` (2).
- A month reconciled to a **nil difference** by hand: books 1,03,864.60 + a
  2,000 cheque not presented = bank 1,05,864.60.
- Mutations caught: certainty without a reference, no exact-sum check, no
  occurrence in the identity (R-27), a sign error in the statement.
- `./build.sh --plan professional`: `accubook.banking` in the image; launched.

## Found on the way
- Every mutation first "failed" in `setUp`: VS Code's compiler had written its
  own `BankingIT` class (an unresolved-compilation stub) into `target/`. The
  known IDE trap; mutation runs now clean the test module first.
- The reference matcher's minimum reference length, applied after stripping
  zeros, would miss cheque 000045 against 45; ours applies it to the reference
  as written.

## Not done / limits
- Excel statements (P10-1); bank feeds; cheque registers and post-dated cheques.
- No screen yet; a bank account's ledger is created through the chart of
  accounts (tests insert it directly).
- Suggestions are one-to-one; one-to-many matches are assembled by the user.
- Matching reads receipt and payment cheque or UTR numbers from the settlement
  tables; vouchers entered otherwise match on amount, date and names.

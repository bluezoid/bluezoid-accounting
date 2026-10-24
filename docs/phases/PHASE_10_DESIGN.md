# Phase 10 Design — Banking and bank reconciliation

**Status:** APPROVED 2026-10-24 (P10-1…P10-5 as recommended)
**Module:** `banking` (Professional and Enterprise; B-7: "reconciliation is a
step up"). Posts to the ledger; removal never deletes its vouchers.

---

## 1. What already exists
- A bank account **is a ledger** in the Cash & Bank group (P7-16); receipts,
  payments and every voucher already post to it. Its balance is the ledger's.
- Settlements record mode, reference and instrument date.

## 2. What Phase 10 adds
1. **Bank details** for a Cash & Bank ledger: bank, branch, account number,
   IFSC, type. **No balance is stored anywhere but the ledger** — the reference
   keeps `openingBalance` and `currentBalance` on the bank account as well as the
   ledger, two truths that drift.
2. **Statement import** (CSV) from HDFC, ICICI, SBI and Axis layouts, and a
   generic layout mapped by the user. Each file is kept with its SHA-256.
   Amounts are parsed exactly; **a row that cannot be read refuses the file**,
   naming the row - never a silent zero.
3. **One reconciliation engine** (fixes D-29): statement lines matched to the
   bank ledger's voucher entries, one-to-one or one-to-many (a deposit of
   several cheques; a payment cleared in parts), each match dated with the
   statement's date (the "bank date").
4. **Bank reconciliation statement** as of any date: balance per books; cheques
   issued not presented; deposits not credited; items in the bank not in the
   books; balance per bank - with the difference that must be nil.
5. **Bank-only items** (charges, interest, direct debits and credits) posted
   from the statement line.

## 3. Findings in the reference
| ID | Finding |
|---|---|
| **R-26** | The amount sanitiser strips every `I`, `N` and `R` (a character class `[,\s₹INR]`) and drops brackets: "500.00 Dr" becomes unparseable and is imported as **0**; "(500.00)" loses its sign. Unreadable amounts are silently zero. |
| **R-27** | Duplicates are found in application code with no unique index, by a key without the running balance: two genuine identical charges on one day collapse into one, and concurrent imports race. |
| **D-29** | Two reconciliation engines; the UI calls the untested one, which recomputes the book balance with float `Number()`. |
| — | The bank account stores its own opening and current balance besides the ledger's. |
| — | The tested matcher auto-matches on amount plus a date within three days, with no human confirmation. |

## 4. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P10-1** | Statement formats | **CSV** in the four bank layouts plus a user-mapped generic layout. Excel (`.xls/.xlsx`) later: it needs a large spreadsheet library in the image; every bank also offers CSV. |
| **P10-2** | Duplicates and file integrity | A line's identity is its date, amount, direction, reference, normalised narration, the statement's running balance, and - for genuinely identical lines - its occurrence on that day; enforced by a **unique index**, so re-importing an overlapping statement adds only new lines and two identical charges stay two. Where the file has running balances, **each must follow from the previous; a break refuses the file**. |
| **P10-3** | Matching | **Suggestions, confirmed by the user.** One engine scores exact amount, date distance, reference and party name; the best is proposed, and a user confirms or picks another. "Confirm all certain" is offered only for unique exact-amount, exact-reference matches. Nothing is matched silently. |
| **P10-4** | Bank charges and interest | Posted **from the statement line**, to a ledger the user chooses (a Payment for charges, a Receipt for interest), dated the statement date and matched at once. **No ledgers seeded** (P2-1: Basic has no banking); the last ledger used for a narration is remembered as the suggestion. |
| **P10-5** | Starting reconciliation on existing books | Each bank account has a **reconciliation start date and the statement balance on it**. Entries before it are not offered; the first reconciliation statement starts from that balance. Changing it later is refused once anything after it is matched. |

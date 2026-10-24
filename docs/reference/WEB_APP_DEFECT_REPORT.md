# Defect Report — AccuBook Web Application

**To:** the AccuBook web application team
**From:** desktop port architecture review
**Date:** 2026-08-31
**Repository:** `/home/achyut-pal/Desktop/accubook` (read-only analysis; **no
files were modified and nothing was committed**)

---

## Summary

While analysing the web application as the functional reference for a desktop
port, four defects were found that affect **live accounting behaviour**. They are
reported here because they impact production users today, independently of the
desktop project.

**D-23 is the most urgent** — it silently produces wrong financial statements
during ordinary, everyday use.

| ID | Defect | Severity | Affects |
|---|---|---|---|
| **D-23** | Credit/debit notes post **backwards** | 🔴 **Critical** | Every customer issuing a return |
| **D-25** | Manufacturing **double-counts** inventory on the balance sheet | 🔴 Critical | Every manufacturing customer |
| **D-24** | Historical inventory valuation is not date-correct | 🔴 Critical | Comparatives, prior-period reports |
| **D-22** | Tally import bypasses the posting invariant | 🟠 High | Every new customer onboarding |
| **R-5** | Party ledger bound by **display name** — a party can attach to an unrelated ledger | 🟠 High | Any party named like an existing ledger *(added 2026-10-09)* |

These were found by code reading, not by running the application. Each includes
the evidence so your team can verify independently before acting.

---

## D-23 — Credit and debit notes post backwards 🔴

### What happens
Issuing a credit note **debits the customer and credits sales** — the opposite of
correct. A sales return **increases** revenue and **increases** receivables.

### Evidence

`src/backend/services/billing/post-invoice.ts:79-99` — `decideInvoiceEntries`
has **no `CREDIT_NOTE` branch**:

```ts
export function decideInvoiceEntries(opts: {taxable, gst, roundOff}) {
  const drCustomer = taxable.plus(gst).plus(roundOff);
  return { drCustomer, crSales: taxable, crGstOutput: gst, roundOff };
}
```

Verification:
```bash
grep -n 'CREDIT_NOTE\|DEBIT_NOTE' src/backend/services/billing/post-invoice.ts \
                                   src/backend/services/billing/post-bill.ts
# → no matches
```

The UI does create them — `src/app/(dashboard)/sales/credit-notes/page.tsx:242`
posts `type: "CREDIT_NOTE"` — and
`src/app/api/organizations/[orgId]/invoices/[invoiceId]/route.ts:185` calls
`postInvoiceToGl` for **any** invoice transitioning DRAFT → SENT, with no type
discrimination.

The purchase side is the same, and `bills/route.ts:22` declares
`type: z.string()` with **no enum guard at all**.

### Impact
Overstated revenue · overstated AR/AP · wrong GST output liability · customer
ledgers moving the wrong way on every return.

### Suggested check
Query for any posted invoice/bill where `type` is `CREDIT_NOTE` or `DEBIT_NOTE`
and `voucherId IS NOT NULL`. Each is a voucher with a reversed sign.

---

## D-25 — Manufacturing double-counts inventory 🔴

### What happens
A manufacturing company's balance sheet **overstates assets** by cumulative
completed production value.

### Evidence

`reports/balance-sheet/route.ts:329-331` states in a comment that Stock-in-Hand
is *"derived from the inventory, not from a ledger"*. **That is false when
manufacturing is used.**

- The `Stock-in-Hand` **ledger** is provisioned at `provision.ts:78` with nature
  `ASSETS` under Current Assets.
- It is written by `post-wo-journal.ts:64` on every work-order journal.
- It is therefore included in `assetLedgerTotal` (`balance-sheet/route.ts:328`).
- Line `:332` then adds the derived valuation **on top**:
  `totalAssets = assetLedgerTotal.plus(stockNow)`.

Related: the WIP journal pair is net-zero — both legs use the same `wipValue`
and both ledgers sit in the **same group** — so it changes no reported total
while still corrupting the balance sheet through the path above.

### Suggested check
For an org with completed work orders, compare `totalAssets` against the sum of
its asset ledgers plus physical stock valued independently.

---

## D-24 — Historical inventory valuation is not date-correct 🔴

### What happens
`valueClosingStock(orgId, pastDate)` returns **today's quantities** priced with
that date's cost layers. Reprinting a prior-period P&L after further trading
gives a different answer each time.

### Evidence

In `src/backend/services/inventory/valuation.ts`, the `asOf` date is applied
**only** to the cost-layer query:

- `:181` — movement/layer query: `date: { lte: asOf }` ✅
- `:116` — `stock.findMany` — **no date predicate**
- `:162` — `loadPendingLines` — **takes no date argument at all**

### Consequence beyond non-reproducibility
For **weighted-average** items — the default, and the silent fallback for any
unrecognised `valuationMethod` — opening and closing stock are computed from the
**same quantities** and differ only by layer pricing that WA does not use. So in
`COGS = opening + purchases − closing`, the adjustment **cancels to zero**.

The periodic inventory adjustment effectively does nothing for most items.

### Note
`normaliseMethod` (`:62-67`) silently maps anything unrecognised — **including
the schema's own documented literal `"WEIGHTED_AVG"`** — to `WEIGHTED_AVERAGE`.
It works by accident, and typos are accepted silently.

---

## D-22 — Tally import bypasses the posting invariant 🟠

### What happens
`applyLedgerEntries` is the intended single choke point enforcing
debits == credits. The Tally importer **does not use it**.

### Evidence

`src/backend/services/migration/tally.ts:697-718` inlines its own
`applyLedgerBalances`, with a comment explaining the intent:

```ts
// Apply ledger balance impact. Inlining a minimal version here
// (instead of importing applyLedgerEntries) keeps this service
// free of the posting helper's coupling — tests mock just the tx.
```

The inlined copy omits every safeguard:

| Safeguard | `applyLedgerEntries` | Tally's copy |
|---|---|---|
| Debits == credits check | ✅ throws `UnbalancedEntriesError` | ❌ **absent** |
| `organizationId` scoping | ✅ | ❌ filters by id only |
| Unresolved ledger | ✅ throws `LedgerScopeError` | ❌ `if (!nature) continue` |

That last row reintroduces precisely the bug `applyLedgerEntries`' own comments
describe having fixed ("a voucher that balanced on paper while only some of its
ledger effects landed").

### Impact
An unbalanced Tally import can post **silently**. Since Tally import is how a
customer's entire historical opening position enters the system, the blast radius
is a customer's whole opening balance set.

### Suggested check
For orgs onboarded via Tally import, verify
`SUM(debitAmount) = SUM(creditAmount)` per voucher, and check for voucher entries
referencing ledgers outside the org.

---

## Lower-severity items (informational)

Found during the same review; not urgent, but worth logging:

| ID | Defect | Location |
|---|---|---|
| D-1 | Opening balances **sign-flipped** — writes store `DEBIT`/`CREDIT`, reads compare `DR`/`CR`, and the fallback fails because the string is truthy | `ledgers/route.ts:18` vs `trial-balance/route.ts:111` |
| D-2 | Cash flow finds **no cash** — hardcoded group names never match the seeded `"Cash & Bank"` | `cash-flow/route.ts:53` vs `provision.ts:52` |
| — | **Fabricated IRN** — a 32-bit hash + `Date.now()` behind a comment claiming SHA-256, stamped `eInvoiceStatus: "GENERATED"` with no NIC call | `invoices/[invoiceId]/e-invoice/route.ts:122-135` |
| D-26 | Approvals declared for `INVOICE`/`PURCHASE_ORDER` but never promote | `promote-entity.ts` |
| D-27 | Batch quantities never decremented on issue | `dispatch.ts`, WO issue |
| D-28 | `LeaveType.annualQuota` displayed but never enforced | `leaves/route.ts` |
| D-29 | Two reconciliation implementations; the **UI calls the untested one** | `bank-reconciliation/route.ts` vs `banking/reconcile.ts` |
| D-32 | Balance sheet hardcodes April–March, ignoring the `FiscalYear` table — so it **disagrees with the trial balance** for non-April companies | `balance-sheet/route.ts:41-43` |

The fabricated IRN is worth separate attention: presenting a locally generated
value as a GENERATED e-invoice IRN is arguably a compliance misstatement, even
though no NIC call is made.

---

## Caveats

- Findings are from **static code reading**. They were not reproduced against a
  running instance or a live database.
- It is possible a data migration or runtime guard exists that this review did
  not locate — though for D-23 the absence of any `CREDIT_NOTE` branch in the
  posting path is unambiguous.
- **No changes were made to the repository.** Verification and remediation are
  entirely your team's call.

---

## R-5 — A party's ledger is found by display name 🟠 *(added 2026-10-09)*

### What happens
When a party first posts, its ledger is created **or found by name**. If any
ledger already has that name — "Cash in Hand", "Sales", "Round Off", another
party — the party is silently attached to **that** ledger. A customer named
like the cash ledger posts its receivables into cash; two parties sharing a
name share one ledger and one balance.

### Evidence
`src/backend/utils/posting.ts:46-70`, `upsertLedgerByName`:

```sql
INSERT INTO "ledgers" (...) VALUES (...)
ON CONFLICT ("organizationId", "name")
  DO UPDATE SET "name" = EXCLUDED."name"
RETURNING "id"
```

`RETURNING` yields the **existing** row on conflict, whatever its group or
`partyId`. Called by `getOrCreatePartyLedger` (`posting.ts:85`), which only
checks `partyId` first — so a new party with a colliding name falls through to
the upsert and receives the other ledger's id.

### Suggested check
Create a customer named exactly like an existing ledger, post an invoice, and
inspect which ledger the voucher debits.

---

## R-26 — Bank statement amounts that cannot be read are imported as zero 🔴 *(added 2026-10-24)*

### What happens
`tryParseAmount` removes characters with the class `[,\s₹INR]` - every comma,
space, rupee sign and **every letter I, N or R** - then drops brackets. A cell
"500.00 Dr" becomes "500.00D", which is not a number, and the function returns
**0** without error. "(500.00)", a bank's way of writing a negative, becomes
+500.00.

### Evidence
`src/backend/services/banking/statement-import.ts:76-86`.

### Suggested check
Import a statement with a cell "500.00 Dr" and look at the imported amount.

---

## R-27 — Identical statement lines collapse into one 🟠 *(added 2026-10-24)*

### What happens
Duplicates are detected in application code by (account, date, amounts,
reference, narration), without the running balance and without a unique
index. Two genuine identical lines on one day - two SMS charges - are treated
as one, and two imports at the same moment can both insert.

### Evidence
`src/backend/services/banking/statement-import.ts` (dedup by key, lines 266-304);
no unique index on `bank_transactions`.


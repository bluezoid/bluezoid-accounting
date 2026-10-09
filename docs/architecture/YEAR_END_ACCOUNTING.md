# Year-End Accounting

**Status:** RECOMMENDED — **Approach A (real closing voucher)**
**Reverses the Phase 0 leaning toward derived retained earnings.**

---

## 1. What the reference actually does (verified)

**Approach B — derived retained earnings, with no close of any kind.**

Verified evidence:
- `FiscalYear.isClosed` is set to `true` **nowhere**. The only write is
  `isClosed: false` at `provision.ts:269`.
- `fiscal-years/route.ts` is 57 lines, exports **only `GET` and `POST`**. No
  close endpoint, no PATCH, no `[fiscalYearId]` route.
- No closing voucher, no opening-balance roll-forward, no P&L-to-equity transfer.
- `FiscalPeriod` is **dead** — zero references outside the schema; rows are never
  created.

The source comment at `balance-sheet/route.ts:141-143` is candid:

> "Nothing closes a year — there is no closing voucher, and profit is never
> posted into an equity ledger"

### The derivation, and why it balances

`balance-sheet/route.ts:216-226`:
```ts
const currentYearProfit = profitThisYearFromLedgers.plus(stockNow).minus(stockBrought);
const retainedEarningsBroughtForward = profitAllTime
  .minus(profitThisYearFromLedgers)
  .plus(stockBrought);
```

Expanding the equity total:
```
Equity = totalEquity + REbf + currentYearProfit
       = totalEquity + (profitAllTime − profitThisYear + stockBrought)
                     + (profitThisYear + stockNow − stockBrought)
       = totalEquity + profitAllTime + stockNow
```

The `stockBrought` and `profitThisYear` terms **cancel exactly**. Retained
earnings and current-year profit are two *presentation buckets* carved out of one
number. The split point is cosmetic — the balance check is mathematically
insensitive to it.

That is the crux: **approach B cannot fail to balance, because the plug is
derived from the same entries as the assets.** It is not evidence of correctness;
it is a tautology.

## 2. Why Phase 0's leaning toward B was wrong

Phase 0 flagged this as an open decision and leaned toward keeping the
derivation for compatibility. Deeper validation shows B is not a designed choice
but a **workaround** — the code comments describe it as fixing a case where "the
statement stopped balancing". Adopting it deliberately in a new product would
import the workaround without the constraint that produced it.

## 3. Comparison

| Criterion | A — closing voucher | B — derived |
|---|---|---|
| Balance sheet always balances | Yes, because entries balance | Yes, **by construction** (tautology) |
| Filed statement reproducible later | **Yes** — the closing entry is a fact | **No** — recomputed from live data |
| Prior-year correction | Blocked, or requires an explicit reopen | **Silently rewrites history** |
| Audit trail of the closing figure | A real voucher, dated, attributable | **None** |
| Opening balances for the new year | Rolled forward as real balances | Implicit, recomputed forever |
| Performance | Bounded — read the current year only | **Unbounded — every entry ever, per request** |
| Tally-like behaviour | Matches (Tally closes and carries forward) | Diverges |
| User expectation | "Close the year" is expected | Absent |
| Statutory/audit acceptability | Standard | Hard to defend to an auditor |

### The decisive arguments for A

**1. Reproducibility.** A balance sheet filed with a return must still produce the
same numbers next year. Under B it will not: any back-dated voucher retroactively
changes `retainedEarningsBroughtForward` on every previously issued statement.
There is no snapshot and no immutability. For a product sold to accountants who
file statutory returns, this is disqualifying.

**2. Performance.** Verified: four unbounded `voucherEntry.findMany` calls per
balance-sheet request, aggregated in JavaScript with `entries.filter(...)`
**inside** a `forEach` over ledgers — O(ledgers × entries). At 500 ledgers and
200k entries that is ~10⁸ comparisons and full materialisation of every row, on
**every request**, on desktop hardware. Approach B gets structurally slower every
year the customer uses the product. Approach A reads one year.

**3. Back-dating is currently unrestricted.** `vouchers/route.ts` POST does not
check `isClosed` and **does not validate `date` against the fiscal year range** —
`date` is only `z.string().min(1)`, and `fiscalYearId` is taken from the client
and trusted. A voucher dated 1990 can be tagged to FY2025-26 and written clean.
(Note the inconsistency: service-side posting *derives* the FY from the date via
`getFiscalYearForDate`; only the user-facing voucher API trusts the client.)

## 4. Recommendation: Approach A, with a derived preview

**Implement a real year-end close.** Specifically:

1. **Retained Earnings ledger** under Capital Account, seeded at provisioning
   with a stable `system_code` (not a display name — see D-7 class of bug).
2. **Closing voucher**: on close, post a real journal moving every P&L ledger
   balance to Retained Earnings, dated the last day of the fiscal year, flagged
   as a system-generated closing entry.
3. **Closing stock becomes a real journal entry**, not a live recomputation. This
   removes the defect where a backdated stock movement or a costing-method change
   retroactively shifts profit between years.
4. **Set `isClosed = true`** and **enforce it at posting time** — the check the
   reference never wrote.
5. **Validate `voucher.date` against the fiscal year range**, always. Derive the
   FY from the date rather than trusting a client-supplied id.
6. **Opening balances roll forward** as real balances into the next year.
7. **Explicit reopen** for corrections: permitted, permission-gated, audited, and
   it reverses the closing voucher rather than editing history silently.
8. **Derived figure retained only as a preview** for the *open* year — labelled
   "provisional", never presented as a closed position.

### Fiscal year must come from the fiscal year table

`balance-sheet/route.ts:41-43` hardcodes April–March:
```ts
const fyStart = asOfDate.getMonth() >= 3 ? new Date(currentYear, 3, 1)
                                         : new Date(currentYear - 1, 3, 1);
```
It ignores `FiscalYear` **and** `Organization.fiscalYearStart` (which exists and
defaults to 4). The trial balance *does* try to honour `fiscalYearId`, so **the
two reports disagree for any company not on an April year**. The desktop product
must read the fiscal calendar from data, always.

## 5. Opening balances — a live corruption bug to fix, not port

Confirmed in full. Write sites store `"DEBIT"`/`"CREDIT"`
(`ledgers/route.ts:18`, `ledgers/[ledgerId]/route.ts:18`,
`parties/[partyId]/route.ts:32`, and the UI). Read sites compare `"DR"`/`"CR"`
(`trial-balance:111`, `balance-sheet:237`, `export:68,69,363`,
`cash-flow:63`). The sole exception is `bank-accounts/route.ts:129,152`, which
writes `DR`/`CR` — so **the column genuinely holds two vocabularies**.

The failure is worse than "ignored":
```ts
if (ledger.openingBalanceType === "DR" || (isDebitNature && !ledger.openingBalanceType))
```
For a UI-created ledger the first test fails (`"DEBIT" !== "DR"`) **and the
fallback also fails**, because `"DEBIT"` is a non-empty, truthy string. So an
asset ledger's debit opening balance is booked as a **credit** — sign-flipped,
not dropped. In cash flow (`:63`) there is no nature fallback at all, so the
opening balance is **subtracted instead of added**.

Additionally the frontend compares against `"DEBIT"`
(`parties/page.tsx:340,345`, `chart-of-accounts/page.tsx:260,264`), so **frontend
and backend disagree on the same column.**

This likely went unnoticed because it *breaks the trial balance* — and most test
organisations have zero opening balances.

**Desktop rules:**
- One canonical `DR`/`CR` enum, enforced by a Java enum **and** a SQLite `CHECK`
  constraint. SQLite's lack of native enums makes this *more* necessary, not less.
- `openingBalance` must be **dated / attached to a fiscal year**. In the
  reference it is a mutable column with no date, applied at every `asOfDate`, so
  editing it silently restates every prior year.
- Any data imported from the web app must have opening-balance direction
  **re-derived**, never trusted.

## 6. The existing test is not a regression guard

`tests/integration/costing-and-year-end.test.ts:134-209` **never calls the
balance-sheet route**. It re-implements the arithmetic inline with its own
`findMany` calls and asserts its own re-implementation is self-consistent. It
also drops `isPosted: true`, so it does not exercise the production filter. The
final assertion is an algebraic tautology given how the operands were built.

> If the `retainedEarningsBroughtForward` line were deleted from the route
> entirely, this test would still pass.

Treat it as documentation of intent, not as a test. The desktop suite must assert
against the **actual report output**, and include a genuine multi-year scenario:
close year 1, verify year 2 opening equals year 1 closing, attempt a back-dated
post into the closed year and confirm refusal.

## 7. Consequences of choosing A

**Costs**
- A close is a real, irreversible-feeling operation and needs a careful UI,
  a preview, and an audited reopen path.
- More code than a derivation.
- Data imported mid-year needs a defined starting position.

**Benefits**
- Filed statements are reproducible.
- Reporting is bounded by year, not by history — the single largest scaling fix.
- Closed periods are actually protected.
- Matches what accountants expect and what auditors require.

**Open sub-decisions** → `docs/decisions/OPEN_DECISIONS.md`
- Reopen policy: who may reopen a closed year, and how many times?
- Are prior-period adjustments posted to the prior year (on reopen) or as
  current-year adjusting entries? (Both are defensible; the choice must be
  explicit and consistent.)

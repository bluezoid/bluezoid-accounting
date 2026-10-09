# B-1 Decision Brief — Perpetual or Periodic Inventory?

**Status:** 🔴 BLOCKING · awaiting decision · **Date:** 2026-09-03
**Gates:** P7 (inventory masters, sales/purchase documents), P8 (costing)
**Does NOT decide:** I-1 (costing method), I-2 (granularity), I-3 (scrap).
Those are separate and should be settled inside P7/P8 with this as context.

> This is a decision document, not an argument for a predetermined answer. §10
> gives a recommendation; §§2–9 give the evidence to disagree with it.

---

## 1. What P2 and P6 already constrain

Four constraints are locked and shipped. Any option must live inside them.

| Constraint | Where | Consequence for B-1 |
|---|---|---|
| **18 groups**, including `STOCK_IN_HAND` under Current Assets | `CompanyProvisioning` | An inventory *asset* group already exists. Both models can use it. |
| **4 seeded ledgers only** — Cash in Hand, Opening Balance Equity, Retained Earnings, Round Off | P2-1 | **No COGS and no Purchase Accounts ledger exists.** Neither model is pre-committed. |
| **`affects_gross_profit` set on `DIRECT_EXPENSES` alone** | `CompanyProvisioning:62` | Whichever account carries cost of sales must sit in Direct Expenses to reach gross profit. Same flag, different account. |
| **Balances are computed, never cached** (P2-2); posting is sealed (P5) | `ChartOfAccounts`, `PostingService` | Any inventory GL effect must be a real voucher through `PostingService`. There is no cached balance to nudge. |

The last point is the important one. **The reference's inventory numbers never
become vouchers**; ours would have to. That is a genuine difference in kind, not
degree.

---

## 2. What the reference actually does — measured, not recalled

### 2.1 It is periodic, and only nominally so

Purchase posting debits **Purchase Accounts**, not inventory:

```
post-bill.ts:26   Dr Purchase Accounts        taxable
                  Dr GST Input                gst
                  Cr Sundry Creditors         total
```

That is textbook periodic.

### 2.2 COGS is never posted

```
$ grep -rn "COGS" src/backend --include=*.ts | grep -v test
dispatch.ts:43:  /** Unit cost the stock was relieved at, for the COGS entry. */
```

One comment. `dispatch.ts` computes a unit cost *for* a COGS entry and then:

```
$ grep -nE "applyLedgerEntries|voucher.create" src/backend/services/inventory/dispatch.ts
(nothing)
```

**Stock dispatch never touches the ledger at all.** The cost is computed and
discarded.

### 2.3 Stock reaches the P&L only at read time

`valuation.ts` is called by the balance-sheet and P&L API routes directly. There
is no closing-stock voucher, no period-end adjustment entry. The financial
statements are assembled from the ledger *plus a live inventory query*.

### 2.4 D-24, precisely

```ts
const stocks = await client.stock.findMany({ ... });    // line 116 — NO asOf filter
...
const movements = await client.stockMovement.findMany({
  where: { date: { lte: asOf }, ... }                   // line 181 — asOf HERE only
});
```

**Quantities come from the current `stock` table; only the cost layers are
filtered by date.** So "closing stock as at 31 March" uses today's quantities
priced at March's costs.

For weighted-average items the effect is worse: opening and closing are computed
from the *same* current quantities, so the periodic adjustment cancels to
approximately zero. The mechanism that is supposed to move stock into cost of
sales does nothing for most items.

### 2.5 What cannot be copied

| Reference behaviour | Copyable? |
|---|---|
| Debiting Purchase Accounts on a bill | **Yes** — correct periodic |
| Computing COGS in `dispatch.ts` and discarding it | No |
| Reading valuation into reports without a voucher | **No** — violates P2-2 and P5 |
| `asOf` applied to layers but not quantities (D-24) | No — the defect itself |
| Double-counting manufacturing assets (D-25) | No |

So the reference is a source of *schema shape* and *GST/TDS arithmetic*, not of
an inventory accounting model. **Neither option below is "port the reference".**

---

## 3. Perpetual inventory

### 3.1 Model
Inventory is an asset account maintained continuously. Every stock movement
posts to the GL at the moment it happens.

```
Purchase:   Dr Stock-in-Hand        cost        Cr Sundry Creditors
Sale:       Dr Sundry Debtors       price       Cr Sales
            Dr COGS                 cost        Cr Stock-in-Hand    ← the extra voucher
```

### 3.2 Accounts required
`STOCK_IN_HAND` ledger (group exists), **`COGS`** in Direct Expenses (new),
`INVENTORY_ADJUSTMENT` in Direct or Indirect Expenses (new). No Purchase Accounts
ledger at all.

### 3.3 Consequences
- Gross profit is correct **at any instant**, not only after a count.
- The balance sheet always shows real stock value.
- **Every sale becomes two vouchers**, or one voucher with four legs. Volume of
  journal rows roughly doubles for a trading business.
- **A sale cannot be posted until its cost is known.** This is the hard part:
  negative stock, backdated purchases and zero-cost receipts all become
  *posting-time* problems rather than reporting-time ones.
- Backdating a purchase after dependent sales have been costed requires
  recosting those sales — and P5 made posted vouchers immutable, so recosting
  means **reversal plus repost**, not an update.

---

## 4. Periodic inventory

### 4.1 Model
Purchases are expensed. Stock is valued at period end and the difference is
journalised.

```
Purchase:   Dr Purchase Accounts    cost        Cr Sundry Creditors
Sale:       Dr Sundry Debtors       price       Cr Sales            (no cost entry)
Period end: Dr Stock-in-Hand        closing     Cr Closing Stock (P&L)
            Dr Opening Stock (P&L)  opening     Cr Stock-in-Hand
```

### 4.2 Accounts required
`PURCHASE_ACCOUNTS` in Direct Expenses (new), `STOCK_IN_HAND` ledger (group
exists), `OPENING_STOCK` and `CLOSING_STOCK` P&L accounts (new). No COGS.

### 4.3 Consequences
- Simple posting: a sale is one voucher. Matches how many Indian SMEs and their
  CAs already work, and matches Tally's default presentation.
- Stock movements need no cost at posting time, so negative stock and backdated
  purchases are **reporting** problems, not blocking ones.
- **Gross profit is wrong between period ends** — unavoidably, by design.
- The period-end adjustment must be a *real voucher* (P2-2, P5). This is exactly
  where the reference failed: it never wrote one.
- Interim P&L requires either a valuation run or an honest "provisional" label.

---

## 5. Hybrid options

**5a. Perpetual quantities, periodic value** — track quantity continuously, value
only at period end. This is effectively §4 with better stock reports, and is what
the reference *attempted*. Viable and honest, provided the period-end entry is a
real voucher.

**5b. Per-item election** — some items perpetual, some periodic.
**Not recommended.** Two cost-of-sales paths in one P&L makes gross profit
un-auditable and doubles every edge case in §9. This is complexity without a
customer asking for it.

**5c. Perpetual with deferred costing** — post the sale immediately, post COGS in
a nightly batch. **Not recommended.** It creates a window where the books do not
balance in the way a reader expects, and P5's whole design premise is that an
accounting operation either completes or leaves nothing.

---

## 6. Impact on P7

| P7 area | Perpetual | Periodic |
|---|---|---|
| Item masters | + cost method, valuation fields per item | quantity and rate only |
| Warehouses | cost must be tracked per location if I-2 says so | quantity per location suffices |
| Units / conversions | conversion must preserve cost | quantity only |
| Purchase documents | posts to Stock-in-Hand | posts to Purchase Accounts |
| Sales documents | **must resolve cost at posting time** | no cost involvement |
| Credit / debit notes | must reverse cost as well as value | value only |
| Inventory accounts | COGS + Inventory Adjustment seeded by the module | Purchase Accounts + Opening/Closing Stock |

**P7 is materially larger under perpetual**, because the sales document cannot be
completed without the costing engine that P8 is supposed to build. That is a
sequencing risk: P7 would depend on P8.

---

## 7. Impact on P8

| Concern | Perpetual | Periodic |
|---|---|---|
| Cost layers | **required, persisted** | required only for valuation |
| FIFO / weighted average (I-1) | decided at posting time | decided at valuation time |
| Negative stock | must block or cost at zero — a **posting** decision | a reporting anomaly |
| Backdated purchase | forces recost + reversal of dependent sales | changes the next valuation only |
| Returns | must restore a cost layer | quantity and value only |
| Reversal after costing | reversal must restore the exact layer consumed | trivial |
| Stock adjustments | post to the GL | valuation input only |

Perpetual makes P8 **load-bearing for correctness**; periodic makes it
load-bearing for *reporting*.

### 7.1 Persisted cost layers — our choice, not an inescapable law

An earlier draft of this brief said "both models require persisted cost layers
regardless". That overstated the evidence, and the distinction matters.

**What the reference actually proves.** Reconstructing valuation from *current
stock quantities + historical movement costs* is insufficient: it produced D-24,
where "closing stock as at 31 March" uses today's quantities at March's costs.
That is a proof about one specific reconstruction strategy failing.

**What it does not prove.** That every conceivable periodic implementation needs
persisted layers. A periodic system could, in principle, reconstruct valuation
correctly from a complete and immutable movement log — replaying inbound and
outbound movements in date order to derive the quantity *and* cost position at
any past date. Nothing forbids that.

**So this is an architectural choice, stated as one:**

> **We choose to persist cost layers and consumption records**, rather than
> reconstruct valuation at report time, because we want historical valuation to
> be *reproducible* — the same `asOf` date must yield the same answer next year,
> after the movement log has grown and the costing method may have changed.

Reasons for the choice, none of which are "periodic requires it":

- A reconstruction is only as good as the completeness of the movement log. A
  persisted layer is a **record**; a reconstruction is a **derivation** that
  silently changes when its inputs or its algorithm change.
- P11 needs prior-year comparatives to stay stable after the year is closed. A
  re-derived figure can drift; a stored one cannot.
- Statutory records should be reproducible on demand years later, by someone who
  no longer has the code that computed them.
- Replay cost grows with history; a persisted layer does not.

The trade is real: persisted layers are more schema, more migration surface, and
they must themselves be kept correct. We accept that for reproducibility.

**This choice is independent of B-1** and would be made the same way under
either model.

---

## 8. Impact on GST / P9

**Minimal, and this is worth stating plainly.** GST attaches to the *document*
(invoice, bill), not to the costing model. Input tax credit is claimed on
purchase regardless. Neither option changes GSTR-1, 3B or 2B.

One narrow exception: goods **written off** require ITC reversal under s.17(5).
That is easier to detect under perpetual, where the write-off is already a
posting. Not decisive.

---

## 9. Adversarial scenarios

| # | Scenario | Perpetual | Periodic |
|---|---|---|---|
| 1 | Negative stock | **blocks the sale** or costs at zero, corrupting margin | reporting anomaly, flagged |
| 2 | Zero-cost stock | COGS posts as zero — silently overstates profit | valuation flags it |
| 3 | Purchase entered *after* the sale | sale was costed wrong; needs reversal + repost | next valuation absorbs it |
| 4 | Backdated purchase | recost every dependent sale, all immutable | absorbed |
| 5 | Sales return | must restore the exact cost layer | quantity + value |
| 6 | Purchase return | reduces the layer; may go negative | reduces purchases |
| 7 | Partial return | layer arithmetic, order-dependent | proportional |
| 8 | Multiple warehouses | cost per warehouse or one pool (I-2) | quantity per warehouse |
| 9 | Fiscal-year boundary | COGS already posted; nothing special | **opening/closing entry is essential** |
| 10 | Reversal after costing | contra must restore consumed layers exactly | trivial |
| 11 | Deactivated item with stock | still on the balance sheet — P6 A-6 already handles the ledger side | same |
| 12 | Concurrent postings | cost layer consumption must serialise — `BEGIN IMMEDIATE` already does | valuation is read-only |

**Scenarios 3, 4 and 10 are the crux.** Under perpetual, combined with P5's
immutability, a backdated purchase turns into a cascade of reversals and reposts
across every sale that consumed the affected layers. That is correct behaviour,
and it is a large amount of machinery — and backdated entry is *normal* in Indian
SME practice, not an edge case.

---

## 10. Recommendation

**Periodic, in the 5a form: perpetual quantities, periodic valuation — with the
period-end adjustment posted as a real voucher through `PostingService`.**

### What we gain
- P7 stops depending on P8. Sales and purchase documents can be built and shipped
  before the costing engine exists.
- Backdated and out-of-order entry — normal for the target customer — does not
  cascade into reversals of immutable vouchers.
- Matches Tally's presentation, so migrating customers and their CAs recognise
  the output.
- Stock reports are still live and accurate in *quantity*, which is what a
  warehouse actually asks for.

### What we give up
- **Gross profit is not correct between period ends.** Interim P&L must either
  run a valuation or be labelled provisional. This must be honest in the UI.
- No automatic per-transaction margin. A customer who wants "profit on this
  invoice" cannot have it without a valuation run.

### What becomes constrained
- P8 persists cost layers and consumption records — **our choice for
  reproducible historical valuation (§7.1)**, not something periodic accounting
  compels. Made the same way under either model.
- P11 must treat the closing-stock entry as part of year-end close.
- Moving to perpetual later is a **migration**, not a switch: historical vouchers
  would carry the wrong shape. Choosing periodic now is a real commitment.

### What this does NOT decide
I-1 (FIFO / weighted average / standard), I-2 (per warehouse or per company),
I-3 (scrap). All remain open and belong in P7/P8 with this model as context.

---

## 11. The case against the recommendation

Stated deliberately, since a brief that only argues one way is not a brief.

Perpetual is the more *correct* model. Every serious mid-market accounting system
uses it, it gives real-time margin, and it makes the balance sheet true at every
instant rather than only at period end. If AccuBook's customers are inventory-led
businesses — manufacturers, distributors — periodic will feel like a limitation
quickly, and the migration cost later is real.

**The question that decides it:** are the target customers primarily traders and
service businesses who file periodically and enter transactions out of order, or
inventory-led businesses who need live margin? The former favours periodic
strongly; the latter is worth paying the perpetual complexity for.

I do not have that customer information, and it is not derivable from the code.

---

## 12. The product questions that close B-1

Repository analysis has reached its stopping point. Nothing further can be
learned from the code, and no amount of additional archaeology will substitute
for the following. Searched `PROJECT_OVERVIEW.md`, `ROADMAP.md` and every
decision document: **no target customer is recorded anywhere.**

**The question:** who is AccuBook primarily being built for in its initial
commercial release?

Segments worth evaluating explicitly — traders and wholesalers · distributors ·
retailers · service businesses · manufacturers · mixed SMEs.

| # | Question | Why it decides B-1 |
|---|---|---|
| 1 | Is live stock valuation required **during the day**? | Yes → perpetual. Periodic cannot supply it between period ends, by design. |
| 2 | Is live gross margin per invoice a **selling point**? | Yes → perpetual. Under periodic, per-invoice margin needs a valuation run. |
| 3 | How common will **backdated / out-of-order** entry be? | Common → periodic. Under perpetual + P5 immutability, a backdated purchase cascades into reversing and reposting every dependent sale (§9, scenarios 3, 4, 10). |
| 4 | Is **manufacturing** a first-release commercial feature or a later module? | First release → pushes toward perpetual, since WIP and costed scrap assume transaction-time costing. |
| 5 | Which segment are you realistically trying to **acquire first**? | Settles the tie when 1–4 disagree. |

Answers 1–4 will usually agree. Where they do not, question 5 governs: build for
the customer being sold to first, not the union of everyone imaginable.

### If the answer is C (deliberately both)

Do **not** average A and B, and do not build two costing engines — two
cost-of-sales paths in one P&L makes gross profit un-auditable (§5b).

The shape worth investigating instead: **one costing foundation, periodic first,
perpetual later.** The persisted cost layers of §7.1 are needed either way, so
they are built once; periodic ships on top of them, and perpetual becomes a
later capability over the same layers rather than a parallel engine.

That is a direction to *investigate before committing to P7/P8*, not a settled
design. It would need its own brief.

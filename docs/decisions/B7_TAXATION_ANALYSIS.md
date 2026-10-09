# B-7 — The Taxation Question

**Status:** ✅ **RESOLVED 2026-09-06 — Option C (add-on at any tier)**
**Date:** 2026-09-06

You asked me to analyse whether `taxation-india` belongs in Basic. The analysis
changed the shape of the question, so I am reporting that before answering.

---

## 1. "Taxation" is not one module — it is three, and they are not equally optional

Measured this session:

| Piece | Size | Coupling | Can it be optional? |
|---|---|---|---|
| **GST computation** — `india-tax.ts` | **176 lines** | Imported by the invoice write path (`invoices/route.ts:8,191,258`) and drives GL posting (`Cr GST Output`) | ❌ **No — it is core** |
| **TDS/TCS computation** — `tax/tds.ts` etc. | ~661 lines | **`post-bill.ts:12` imports `computeTds` directly** and posts to `TDS Payable` | ❌ **No — it is part of the purchase posting path** |
| **Return generation** — `gst/*.ts` (GSTR-1/2B/3B/9, CMP-08, e-invoice, e-way bill) | **~3,615 lines** | Reads documents; imports only the `Tx` type from posting. **Nothing in posting imports it** | ✅ **Yes — genuinely separable** |

**This is the finding that matters.** The separable module is *return generation
and filing*, not "taxation". GST and TDS **calculation** are inseparable from
invoicing and billing, because the amounts land in the ledger.

Verified: `post-invoice.ts` has **zero** imports from `gst/` or `tax/`.
`post-bill.ts` has exactly **one** — `computeTds`.

## 2. What this means commercially

A Basic build **without** the returns module still:

- computes CGST/SGST/IGST correctly on every invoice and bill
- posts GST Output and GST Input to the ledger correctly
- withholds TDS on vendor bills and posts TDS Payable correctly
- stores the full per-line tax breakdown (those columns are in the universal
  schema and always present)
- shows GST amounts on printed invoices

What it **cannot** do is *generate the statutory return files* — GSTR-1, 3B, 9,
CMP-08, the e-invoice payload and the e-way bill payload.

So the honest framing is not *"Basic has no GST"*. It is:

> **Basic records GST correctly. Professional files it for you.**

## 3. The ten questions you asked

| # | Question | Answer |
|---|---|---|
| 1 | Basic customer usability | Fully usable for bookkeeping and invoicing. They would file returns via their CA or the GST portal — which is what most small businesses already do |
| 2 | Indian GST-registered expectations | ~Every registered business must file. But most **small** businesses have a CA who files for them; they need correct *books*, not a filing engine |
| 3 | Is Basic unattractive without it? | **This is the real question, and it is a market judgement, not an architectural one.** See §4 |
| 4 | Which tier? | See §4 — I have a recommendation but not a decision |
| 5 | Engineering implications | Returns are ~3,615 lines with ~124 tests — the largest single surface after core accounting. Genuinely separable, cleanly |
| 6 | Entitlement implications | Clean: the module owns engines, not columns. No entitlement check reaches into invoicing |
| 7 | Upgrade/downgrade | Trivial — no schema change either way |
| 8 | Data compatibility | **Perfect.** Tax data lives in the universal schema, so a downgraded customer keeps every rupee of tax history |
| 9 | Safe downgrade? | **Yes.** Nothing is deleted; the customer stops being able to *generate returns*, not to see their tax data |
| 10 | Dormant viewer? | Barely needed — the tax *data* is on invoices, which the invoicing module renders. Only saved `GSTReturn` filing records would need the viewer, and that table is standalone with no relations |

## 4. 🔴 The decision I cannot make for you

Both options are architecturally clean and cost the same to build.

### Option A — returns in Professional (my earlier recommendation)
- Basic is a bookkeeping product; Professional is a compliance product
- Natural, defensible upsell
- **Risk:** if your target Basic customer expects to file from the software, Basic
  is a hard sell in the Indian market

### Option B — returns in Basic
- Basic is immediately complete for a small GST-registered business
- **Risk:** removes your strongest upsell lever, and gives away the single
  largest engineering investment at the lowest price point

### Option C — returns as an add-on at any tier
- Matches how customers actually differ: a tiny trader who files their own
  returns can buy Basic + returns; a larger one who has a CA can skip it
- **Risk:** more SKUs, more admin-portal complexity

**Decision: Option C.** (My lean was toward A; on the grounds that a Basic customer who has a CA
gains little from a filing engine, and Option A keeps the largest engineering
asset behind the upgrade. But this depends on facts I do not have:

- **Who is your Basic customer?** A sole trader doing their own compliance, or a
  small business with a CA?
- **What are competitors charging for GST filing?** In India, Tally and Zoho both
  bundle it, which may make its absence conspicuous.
- **What is your price gap** between Basic and Professional?

**If your Basic customer files their own returns, choose B.** If they have a CA,
choose A. If you genuinely serve both, choose C — **and that is the answer
given: the product serves both, so returns are an add-on.**)

## 5. What is NOT in question

Regardless of your answer:

- GST and TDS **computation** stay in the kernel — they are not optional
- Tax **columns** stay in the universal schema for every build
- A downgrade **never** deletes tax history
- The returns module is a clean seam either way

So the decision is reversible and low-risk: it changes one line in the plan→module
map, not the architecture.

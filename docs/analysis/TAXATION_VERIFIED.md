# Indian Taxation — Verified Analysis

Status: VERIFIED FROM EXISTING ACCUBOOK
Source: `/home/achyut-pal/Desktop/accubook` (read-only reference)

This is the most compliance-sensitive domain in the product and the most
heavily tested (~124 GST tests, ~75 TDS/TCS tests). Those tests are the
porting oracle.

---

## 1. THE ROUNDING FINDING — read before writing any tax code

**There is essentially no rounding in the GST computation pipeline.** Values
carry full arbitrary precision from line computation all the way to export, and
are truncated to 2dp exactly once, at JSON serialization.

- `computeLineGst` does `taxable.times(rate).dividedBy(100)` with **no**
  `.toDecimalPlaces()`. There is no `Decimal.set({precision, rounding})`
  anywhere in the codebase.
- A test pins this explicitly: ₹1234.56 @ 5% intrastate →
  `cgstAmount === "30.864"` — **three decimal places, unrounded**.
- Postgres then silently truncates to the column's 4dp on write.
- Aggregation is **sum-then-round**, not round-then-sum. Correct for GST (the
  portal reconciles on totals), but the port must match it or every return cell
  drifts by paise.
- The single rounding step is, in each portal converter:
  ```ts
  function n(value: string): number {
    const num = Number(value);
    return Number.isFinite(num) ? Number(num.toFixed(2)) : 0;
  }
  ```

### Why this is a DECISION REQUIRED, not a transcription task

That helper goes **through IEEE-754 double**, abandoning the Decimal guarantee
exactly at the compliance boundary. And `Number.prototype.toFixed` is
effectively round-half-to-even in practice because of binary representation:
`(1.005).toFixed(2) === "1.00"`, not `"1.01"`.

So a Java port using `BigDecimal.setScale(2, HALF_UP)` will produce **different
portal JSON on exact-half cases** than the web app does today.

Two mutually exclusive options:
- **(a) Byte-compatible** — replicate double-then-toFixed. Matches returns
  already filed by existing customers. Knowingly carries float into the export.
- **(b) Correct** — `BigDecimal` with an explicit, documented rounding mode
  (HALF_UP is the conventional reading of Rule 34 CGST). Diverges from history
  by at most a paisa per cell.

I recommend **(b)** for a new product, with the divergence documented and the
existing test vectors re-baselined deliberately rather than silently. But this
is a business/compliance call, not an engineering one. **DECISION REQUIRED.**

Also note `Invoice.roundOff` exists, is read by e-invoice, and **is never
written by any path** — Section 170 round-off-to-nearest-rupee is *not*
implemented. The posting layer honours the column; nothing populates it.

## 2. GST core semantics to preserve

**Supply type** — `determineSupplyType(supplierState, placeOfSupplyState)`:
2-letter ISO 3166-2:IN codes, case-insensitive, trimmed. **Fails safe to
INTERSTATE (IGST)** when either is missing — deliberate, because CGST+SGST
without same-state confirmation risks ITC denial.

**Rate split** — intrastate halves the combined rate (18 → 9/9); interstate puts
it all in IGST.

**Place of supply is always the customer's `billingState`.** No per-invoice
override, no Section 12/13 special-category rules (explicitly out of scope).
`placeOfSupply` and `supplyType` are **locked at invoice creation** so the
breakdown cannot shift if the party's address is later edited. Reports read the
stored value and never recompute. **Preserve this.**

**Lines are authoritative; headers are rollups.** Every return reads
`InvoiceItem`/`BillItem`, never the header. `InvoiceTax`/`BillTax` junction
tables exist but **no write path populates them** — dead weight.

### Traps found in rate resolution
- The rate is **always** taken as the *combined* rate from `TaxConfig.rate`;
  `taxType` is not consulted. A config marked `taxType: "CGST", rate: 9` is
  treated as 9% combined and split into 4.5+4.5. **Latent trap — fix in port.**
- If a line has no `taxId`, `combinedRate = 0` — a **silently nil-rated line**.
  The item master's sales/purchase tax relations exist but are not used as a
  fallback. **Fix in port.**
- **Cess is never computed.** `computeLineGst` returns only CGST/SGST/IGST;
  the schema's `cessRate`/`cessAmount` are read by every aggregator but written
  by nothing. Cess is always zero in practice.
- **UTGST is not modeled** — approximated as SGST. `TaxConfig.taxType` has no
  UTGST value.

## 3. Returns implemented, and their gaps

| Return | Status | Notable gaps |
|---|---|---|
| GSTR-1 | Implemented + portal JSON | No ATXP (advances), no SUPECO (e-commerce); nil not split into nil/exempt/non-GST; HSN `description` hardcoded null; `totalInvoices` double-counts |
| GSTR-2B | Reconciliation only | B2B only — no cdnr, impg (imports), isd; results **not persisted** |
| GSTR-3B | Implemented + portal | 3.1(e) non-GST always zero; 4.B reversals always zero; Table 4 not restructured per Sept-2022 rules; no 3.1.1 (e-comm u/s 9(5)) |
| GSTR-9 | Implemented + portal | Sections 6A-L, 8 (2A/2B recon), 10-14 amendments, 15-18 out of scope; **no Section 49 cross-utilisation** of IGST credit |
| CMP-08 | Implemented | **GSTR-4 (annual composition return) not implemented at all**; Section 3.4 interest is a placeholder |

**Sign-convention trap:** GSTR-3B uses **signed accumulation** for credit notes
(`sign = -1`), so cells can go negative. GSTR-9 accumulates credit/debit notes
**positively** then applies `netSupplies = (subtotal + dn) − cn`. Opposite
conventions in adjacent modules — a real porting hazard.

**GSTR-9 Section 9** takes a per-cell `min(payable, ITC)` and clamps at zero.
There is **no cross-utilisation of IGST credit against CGST/SGST** — the real
Section 49 set-off order is unimplemented.

**GSTR-2B matching:** key is supplier GSTIN (uppercased) + vendor invoice number
(trimmed, lowercased); tolerance **₹1 absolute per cell**, using `>` so exactly
₹1 still matches. Taxable value is summed but **never compared**. The buyer-side
window is the same calendar month as the 2B period — but a supplier's invoice
dated month N can legitimately appear in the buyer's 2B for month N+1, so
genuine cross-period cases surface as false MISSING pairs. **Fix in port.**

## 4. TDS / TCS

`TDS_RULES` is a static table for FY 2025-26 with **no effective-date dimension
at all** — recomputing a prior-year deduction today applies today's rates. For a
desktop app running for years without redeploys this is the **single most
important thing to restructure**: rates and thresholds must move to a
versioned, effective-dated table keyed by financial year.

Sections: 194C (1%/2%, ₹30k single / ₹1L annual), 194C_TRANSPORT (0%), 194J
(10%, ₹30k), 194I_LAND (10%, ₹2.4L), 194I_PM (2%), 194H (5%, ₹15k), 194Q (0.1%,
₹50L), 194O (1%, ₹5L); TCS 206C_1H (0.1%, ₹50L), 206C_1F (1%, ₹10L single).
`PENAL_RATE_NO_PAN = 20`.

**Three computation branches — port exactly:**
1. Zero rate → amount 0, reason `ZERO_RATE`.
2. **194Q / 206C_1H are excess-only** — tax only the amount above the annual
   threshold.
3. Everything else is **full-amount** once either threshold trips — not the
   excess. Note the **asymmetric comparators**: single uses `>=`, annual uses `>`.

`noPan` overrides the rate but **still respects thresholds**.

**Direction is opposite for TDS vs TCS and must not be copied across:**
- TDS: `netAfter = amount − tax`; bank credited with the **net**.
- TCS: `netAfter = amount + tax`; bank debited with **amount + tcs** (collected
  on top of the receivable).

**Two different bases, deliberately:**
- Payment path (cash basis): base is the **gross payment amount**.
- Bill path (accrual): base is **net of GST**, citing **CBDT Circular 13/2021
  Q.5** — aggregating gross would cross ₹50L earlier than legally required.

**YTD aggregates are scoped per `(org, party, section)`** — deliberately, so an
unrelated 194J fee does not count toward the 194C ceiling. The code documents a
known under-count: rows exist only where tax was actually deducted, so
below-threshold payments do not contribute to the aggregate.

`Bill.amountDue` is written **net of TDS** — without this the AP subledger
drifts from the vendor ledger by exactly the TDS withheld.

Rows are written **only when `tdsAmount > 0`**, so the `rationale` values other
than `DEDUCTED` are never actually persisted despite being a documented domain.

**Form 16A quarter boundaries** carry the most important bug-fix comment in the
domain: `endDate` closes at `23:59:59.999`, not midnight, because an end of
`2026-03-31T00:00:00Z` *silently dropped every deduction made on 31 March* — the
heaviest TDS day of the Indian fiscal year, and for an IST deployment it excluded
the entire working day. An entire test block pins this. **Port with the same care
— and prefer `LocalDate` half-open ranges, which make the class of bug impossible.**

**Not modeled:** Section 197 lower/nil certificates, surcharge & cess, Section
195 non-resident. Form 27D has no dedicated builder (reuses Form 16A's shape).

## 5. E-invoice — TWO implementations that disagree

**(A) `services/gst/einvoice.ts`** — the good one. Proper NIC schema v1.1
payload, strict pre-flight validation collecting *all* errors before throwing.
Payload-only: **no API call** (needs NIC credentials).

**(B) `invoices/[invoiceId]/e-invoice/route.ts`** — the one that actually
**writes `irnNumber`/`qrCode`/`eInvoiceStatus` to the database**, and it does not
use (A) at all. It must not be reproduced:

```ts
// Comment claims SHA-256. It is a 32-bit hashCode + Date.now().
let hash = 0;
for (...) { hash = ((hash << 5) - hash) + char; hash = hash & hash; }
return `${hashStr}${Date.now().toString(16)}`.substring(0, 64).toUpperCase();
```
- The IRN is **fabricated locally**, non-deterministic, ~24 chars not 64, and
  stamped `eInvoiceStatus: "GENERATED"` with no NIC round-trip.
- It carries an **independent, incorrect state table** keyed by full state name,
  missing UTs, defaulting unknown states to `"27"` (Maharashtra).
- It **re-derives the tax split in JS floats**, ignoring the stored per-line
  columns: `cgst = taxAmount / 2`.
- It tests `item.type === "SERVICES"` where (A) tests `"SERVICE"` — at most one
  can be right.

**Port decision:** implement (A)'s payload builder; treat real IRN generation as
an integration requiring NIC credentials. **Never fabricate an IRN.** A fake IRN
stamped as GENERATED is arguably a compliance misstatement.

**E-way bill** is payload-only, single implementation, nothing persisted (no
`ewbNumber` column). Threshold ₹50,000 using `<`, so exactly ₹50,000 passes.
`hsnCode: Number(li.hsnCode)` **coerces HSN to a number**, corrupting any
leading-zero code. Intra-state threshold variation by state is not modeled.

## 6. Composition scheme

Three effects, all correct and worth porting:
1. Invoices become **bills of supply** — per-line GST forced to zero, but
   `placeOfSupply`/`supplyType` still recorded so CMP-08 can roll up turnover.
2. Purchases **lose ITC** — GST collapses into the expense debit.
3. **CMP-08 replaces GSTR-1/3B.** Turnover is gross, tax-inclusive, **including
   exempt** (the composition rate already accounts for the mix). Inward RCM is a
   separate liability at *regular* rates, not the composition rate.

Gaps: GSTR-4 not implemented; composition **turnover eligibility limit**
(₹1.5cr / ₹75L special states) is nowhere in the code — nothing prevents
enabling composition on an ineligible organization.

## 7. HSN

A hardcoded frozen array of 57 entries (36 HSN + 21 SAC) — the official GSTN
list has ~17,000. `defaultGstRate` is **never consumed by any write path**;
rates come from `TaxConfig`. The library is autocomplete data only.

`searchHsn` applies its `limit` via `break` **during** collection, *before* the
sort — so an exact code-prefix match ranked 21st is dropped and never gets to
sort first. **Fix in port.**

`GSTIN` validation: format regex plus a **Mod-36 check-digit verifier** that is
correct and tested — but **never called by any production path**. Only the format
check is used. **Wire the checksum up in the port.**

The state-code map is **duplicated three times** with different representations
(strings, zero-padded strings, numbers) across india-tax, e-invoice and e-way
bill. Missing codes 28 (old AP), 97 (Other Territory), 99 (Centre). **Unify to
one table in the port.**

## 8. Things requiring annual maintenance — design for this

Because a desktop app runs for years without redeploys, every one of these must
become **versioned, effective-dated data**, not a compiled constant:

- The entire `TDS_RULES` table + `PENAL_RATE_NO_PAN`
- `B2CL_THRESHOLD` (₹2.5L), `EWAY_THRESHOLD` (₹50k)
- Composition rates (1/5/6) and eligibility limits
- HSN library and its default rates; the 0/5/12/18/28 slab structure
- E-invoice applicability turnover (₹5cr — **documented but not enforced**)
- 194Q/206C(1H) payer-turnover applicability (₹10cr — **not checked**)
- Statutory due dates (challan 7th/30-Apr; CMP-08 18th; GSTR due dates are
  currently **caller-supplied**, i.e. outside the system entirely)
- GSTN portal JSON schemas (GSTR-1/3B/9/2B), NIC e-invoice v1.1, NIC EWB v1.04
- State code table (changes on state reorganisation, e.g. Ladakh/38)

**This is a first-class architectural requirement for the desktop product**, and
it interacts directly with the update mechanism: a tax-rule change must be
shippable **without** a full application update, because customers may be on
older versions when a Finance Bill lands. Recorded in OPEN_DECISIONS and
UPDATE_AND_MIGRATION_ARCHITECTURE.

## 9. Postgres-specific surface in this domain: almost none

Zero raw SQL, zero Postgres functions, zero JSONB operators. Only:
- 169 `Decimal` columns (the general problem).
- One `mode: "insensitive"` in `tax-config/route.ts`.
- **`SUM()` over NUMERIC:** Postgres sums exactly; **SQLite `SUM()` over a
  NUMERIC column returns a float.** At the ₹50 lakh 194Q threshold boundary this
  matters. With scaled-INTEGER storage (see SQLITE_MONEY_STORAGE) SQLite `SUM()`
  is exact — another argument for that decision.
- Transaction-poisoning semantics differ (SQLite does not poison on constraint
  violation), so error-handling that assumes an aborted transaction behaves
  differently.

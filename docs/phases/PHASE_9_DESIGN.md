# Phase 9 Design — Indian Taxation

**Status:** APPROVED 2026-10-10 (B-4 and P9-1…P9-4 as recommended) · **Date:** 2026-10-10
**Baseline:** 627 passing · 0 failing (UI on Xvfb)

> Note: I-6 (TDS surcharge base) and I-7 (Professional Tax states) concern
> **salary** TDS and payroll. They gate Phase 15, not this phase.

## 1. What already exists
- Documents carry per-line amounts in paise (P7-9) and empty `*_tax_line`
  tables (P7-1); `party.gstin`, `party.state_code`, `company_info.gst_no`,
  `address_state_code` and `item.hsn_sac` are stored, unvalidated.
- The sealed posting core, settlement (P7d) and as-of reporting.
- Architecture (DATABASE_ARCHITECTURE §2, UPDATE_AND_MIGRATION §7): statutory
  rates live in **signed, effective-dated rule packs in `app.db`**, updatable
  without an application release; **a posted document stores the resolved tax
  figures and the rule-pack version used**, so history is never recomputed.

## 2. Reference findings to honour (TAXATION_VERIFIED)
| Area | Finding | Port |
|---|---|---|
| Rounding | GST computed unrounded, truncated once at JSON export through IEEE-754 `toFixed` (effectively half-even) | **B-4** |
| Supply type | intra/inter from supplier and place-of-supply states; **fails safe to IGST** when either is missing; locked at creation | keep |
| Rates | combined rate always split, `taxType` ignored; a line with no tax is **silently nil-rated**; cess never computed; UTGST approximated as SGST | fix (P9-2) |
| TDS/TCS | static rate table with **no effective date**; threshold branches (excess-only for 194Q/206C(1H), full-amount otherwise, `>=` single vs `>` annual); TDS net vs TCS gross; bill base net of GST | port into rule packs, exactly |
| Form 16A | quarter end dropped 31 March | `LocalDate` half-open ranges |
| E-invoice | a **fabricated IRN** stamped GENERATED | payload only; never fabricate |
| E-way bill | HSN coerced to a number (drops leading zeros) | keep HSN as text |
| GSTIN | Mod-36 checksum written, tested, **never called** | wire it in |
| State codes | three disagreeing tables, missing 28/97/99 | one table, in the rule pack |
| GSTR-3B vs 9 | opposite credit-note sign conventions | one convention, tested |
| GSTR-2B | cross-period invoices reported MISSING | match across periods |
| Composition | no turnover-eligibility check; GSTR-4 missing | check; GSTR-4 later |

## 3. Sub-phases (P9-1)
| Sub | Scope | Module |
|---|---|---|
| **P9a** | Rule-pack infrastructure (signed, effective-dated, in `app.db`; bundled pack + offline import); unified state table; GSTIN checksum; company GST registration and composition status | kernel |
| **P9b** | GST on documents: tax per line, place of supply and supply type locked at posting, CGST/SGST/UTGST/IGST/cess, RCM purchases, composition (bills of supply, no ITC), notes reverse tax; GST ledgers; resolved figures + rule-pack version stored | kernel (B-7: computation is in every plan) |
| **P9c** | TDS on bills/payments, TCS on receipts, from rule packs; settlement net of TDS; Form 16A/27D data | kernel |
| **P9d** | Returns: GSTR-1, GSTR-3B, GSTR-2B reconciliation, GSTR-9, CMP-08, portal JSON | **`taxation-returns` add-on** (B-7) |
| **P9e** | E-invoice (NIC schema) and e-way bill **payloads only**; an IRN obtained outside the app may be recorded, never generated | `taxation-returns` |

## 4. Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **B-4** | GST rounding | **Correct, not byte-compatible:** each line's CGST/SGST/IGST/cess rounded **half-up to the paisa** when computed, and everything else — invoice totals, return cells — is the exact sum of those line figures. What the printed invoice shows is what the return reports. Differs from the web app's exports by at most a paisa per cell on exact-half cases; documented and test vectors re-based deliberately. |
| **P9-2** | Where a line's GST rate comes from | **The item carries its GST rate** (a slab from the rule pack: 0, 0.25, 3, 5, 12, 18, 28, plus cess), changed with an effective date, like Tally. A free-text line chooses one. **Every line has an explicit tax category** — taxable at a rate, nil-rated, exempt, or non-GST — so nothing is ever silently nil-rated. The 57-entry HSN list is suggestions only. |
| **P9-3** | Scope of supplies in this phase | **In:** regular B2B and B2C, intra- and inter-state, reverse charge on purchases, composition, cess. **Out (later):** exports and SEZ (zero-rated with or without payment), e-commerce operator supplies, advances (GSTR-1 ATXP), GSTR-4. |
| **P9-4** | Rule-pack delivery | A rule pack is a **signed file** (its own Ed25519 key pair with the same two-key rotation as licences). One ships inside the app; a newer one can be **imported offline** from a file. Online download arrives with the admin portal (P13). A pack can never change a posted document. |

---

## 5. Findings while preparing P9a (2026-10-10)

| ID | Finding | Consequence |
|---|---|---|
| **R-17** *(new)* | The reference's `TDS_RULES`, labelled FY 2025-26, carries **superseded values**: 194J threshold ₹30k (₹50k from 1 Apr 2025), 194H ₹15k / 5% (₹20k; rate 2% from 1 Oct 2024), 194I rent ₹2.4L a year (₹50k **per month** from 1 Apr 2025), and **206C(1H) TCS still active** though withdrawn from 1 Apr 2025. | Never port the table as data. Rule-pack content is sourced and verified separately. |
| GST 2.0 | From **22 Sep 2025** the 12% and 28% slabs were removed and a **40%** rate added; compensation cess ended except for tobacco products pending notification. | The slab set is effective-dated rule-pack data; P9-2's list was illustrative. |
| Model | A **per-month** threshold (194I) cannot be expressed as "single / annual". | Threshold types: single transaction, annual aggregate, monthly, excess-only. |

### V-1 · Rule-pack content must be verified before release
Rates, thresholds and dates in the bundled pack come from public secondary
sources (taxguru, incometaxindia.gov.in budget brief, cleartax and others) and
are marked **UNVERIFIED** inside the pack. **A chartered accountant must verify
the pack before any release (P16 gate)**, as must the development signing keys
be replaced. The infrastructure — signing, effective dating, import — is what
P9a builds and tests; the numbers are data and will change.

---

## 6. P9b detailed design — GST on documents

**Status:** APPROVED 2026-10-12 (P9-5…P9-8 as recommended).

### 6.1 Model
- **Item tax** (inventory-owned, effective-dated, P9-2): category (`TAXABLE`,
  `NIL_RATED`, `EXEMPT`, `NON_GST`), GST rate (a slab in force on the date) and
  cess rate. Read by documents through `StockPort` for the document date.
- **Document line** gains its own category, GST rate and cess rate, defaulted from
  the item and editable while DRAFT. A free-text line (or a build without
  inventory) chooses them. No line posts without an explicit category.
- **Document** gains place of supply and supply type (locked at posting, reports
  never recompute — reference rule kept), reverse-charge flag (purchases), the
  registration scheme in force, and the **rule-pack version used**.
- **Tax lines** (`*_tax_line`, P7-1): one row per component per line — CGST, SGST,
  UTGST, IGST, cess — each rounded half-up to the paisa (B-4). Intra-state:
  CGST and SGST (UTGST in a union territory without a legislature) each at half
  the rate; inter-state: IGST at the full rate.
- **Posting** adds the tax to the party line and posts each component to its
  ledger; round-off applies to the tax-inclusive total.
- **Composition company:** sales are bills of supply (no tax lines; turnover
  still recorded with place of supply for CMP-08); purchase GST is added to the
  cost (no input credit). **Eligibility:** registering for composition is refused
  when the previous year's turnover (Sales ledger) exceeds the limit for the
  state — rule-pack data, UNVERIFIED (V-1).
- **Reverse charge (purchase):** the vendor is owed only the taxable value; the
  company owes the GST itself and may claim it back as input credit (regular) or
  must expense it (composition).

### 6.2 Decisions required

| ID | Question | Recommendation |
|---|---|---|
| **P9-5** | Place of supply when it is not obvious | Document-level, **defaulting from the party** (a registered party's state comes from its GSTIN), editable while DRAFT, locked at posting. A **B2B** sale needs the customer's valid GSTIN. An **unregistered customer with no state** (a counter sale) defaults to the **company's own state** — intra-state, as a walk-in sale is. The reference instead fails safe to IGST, which mis-taxes every counter sale. |
| **P9-6** | Prices entered inclusive of GST (retail MRP) | **Later.** P9b takes prices exclusive of tax, as B2B traders quote. Inclusive entry (back-calculating the taxable value) is a follow-up before retail screens. |
| **P9-7** | GST ledgers | **Separate ledgers per component and direction** under Duties & Taxes: Output CGST/SGST/UTGST/IGST/Cess, Input CGST/SGST/UTGST/IGST/Cess, and **RCM payable** per component (reverse-charge tax must be paid in cash, so it is kept apart). The reference uses one "GST Output" and one "GST Input", found by name. Created in every plan (computation is kernel, B-7). |
| **P9-8** | No GST registration recorded for the document date | **Refuse** the document until the company records its position (Regular, Composition or Unregistered, with state). Unregistered: no GST on sales; purchase GST from the vendor's rate is added to cost, no input credit. |

### 6.3 Rules that follow from law, not choice
- A composition dealer may not make an inter-state outward supply: refused.
- Non-creditable GST (composition, unregistered) is part of the cost of
  purchase (AS 2), so it is in the unit cost stock is valued at.
- A note is taxed on the basis of the document it corrects: same place of
  supply, supply type and, for an item, the original line's rates.
- Reverse charge is a purchase-side flag; an unregistered company cannot use it.

### 6.4 Not in P9b
Inclusive (MRP) prices (P9-6), specific-rate cess (per unit), blocked input
credit under s.17(5), tax on quotations and orders, exports/SEZ (P9-3).


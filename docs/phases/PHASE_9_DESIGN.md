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

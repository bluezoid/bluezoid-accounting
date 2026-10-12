# Phase 9 Report — Indian Taxation

**Status:** IN PROGRESS — **P9a ✅** · **P9b ✅** · **P9c ✅** · P9d in progress (**P9d-1 ✅ P9d-2 ✅**) · P9e not started
**Design:** `PHASE_9_DESIGN.md` (B-4, P9-1…P9-4 approved 2026-10-10; P9-5…P9-16 2026-10-12)

---

## P9a — Rule packs, state codes, GSTIN, GST registration ✅ (2026-10-10)

**Tests:** **657 passing · 0 failing · 0 skipped** with UI on Xvfb (648 + 9 UI)
**Before P9a:** 627 (+30)

### Implemented

| Item | Where |
|---|---|
| **`accubook-tax`** kernel module (in every build, B-7) | new module |
| Rule-pack text format: states, GST slab sets, thresholds, TDS/TCS sections, each effective-dated; threshold types incl. **monthly** (194I) and excess-only (194Q); strict parsing with line numbers; overlaps refused | `RulePack` |
| Signed packs: Ed25519 header over the exact text; own two dev keys, distinct from licence keys (P9-4); private keys only in `~/.accubook-dev/`; `tools/SignRulePack.java` | `SignedRulePack`, `TrustedRulePackKeys` |
| Bundled pack `2025.10.1`, **UNVERIFIED** with cited sources (V-1); signed resource kept identical to the reviewable source by a test | `rulepack/rulepack-2025.10.1.txt` |
| `app.db` migration 2: append-only `rule_pack`; bundled installs at startup; **offline import** of newer signed packs; older/equal refused (no rate rollback); **re-verified on every load** | `RulePacks`, `CompanyService` |
| GSTIN: format + **state from the rule pack** + **Mod-36 check character** (the reference's was never called) | `Gstin` |
| Company migration 10: **effective-dated GST registration** (regular / composition / unregistered), forward-only, never edited, records the pack version | `GstRegistrations` |

### Findings
- **R-17** — the reference's "FY 2025-26" TDS table is stale (194J, 194H,
  194I thresholds; 206C(1H) withdrawn 1 Apr 2025).
- **GST 2.0** — 12% and 28% ended and 40% began on 22 Sep 2025.
- **V-1** release gate: bundled pack data must be verified by a chartered
  accountant; development rule-pack keys must be replaced.
- I-6 and I-7 are payroll questions (P15), not taxation.

### Packaged-artifact verification
`./build.sh --plan basic` → the installed app's first launch installed pack
`2025.10.1` (UNVERIFIED, BUNDLED, key `acb-rulepack-dev-primary`) into `app.db`;
no exception.

### Deferred to P9b
- **Composition eligibility** (turnover limit, special-category states) — done in P9b.
- Validating **party** GSTINs — done in P9b (on every document).

### Risks
- Pack content is unverified (V-1). The plumbing is tested; the numbers are not
  authoritative.
- `company_info.gst_no` still exists alongside `gst_registration`; the
  registration table is the source of truth from P9b on.

---

## P9b — GST on documents ✅ (2026-10-12)

**Tests:** **708 passing · 0 failing · 0 skipped** with UI on Xvfb (699 + 9 UI)
**Before P9b:** 657 (+51)
**Decisions:** P9-5 place of supply (counter sale = company's state), P9-6
inclusive prices deferred, P9-7 GST ledgers per component and direction,
P9-8 no document without a GST registration on record.

### Implemented

| Item | Where |
|---|---|
| Per-line GST: CGST + SGST/UTGST or IGST, plus ad-valorem cess; each component half-up to the paisa (B-4); rates checked against the slabs on the date | `GstCalculator`, `LineTax` |
| Place of supply for sales and purchases; counter sale intra-state (P9-5); 99 refused | `PlaceOfSupply` |
| Company migration 11: `item_tax` (inventory); document GST basis, taxable and tax; line category, rates, HSN/SAC; rebuilt constrained tax-line tables frozen once posted; amount = taxable + tax (− tax under RCM) enforced; no posted row without a GST basis | `Schemas` |
| 15 system ledgers under Duties & Taxes: Output, Input, RCM Payable × CGST/SGST/UTGST/IGST/Cess (P9-7) | provisioning + migration 11 |
| `Scale.TAX_RATE` (percent to 4 places: 0.125% fits) | platform |
| Item GST effective-dated, same-date correction | `Inventory.setTax/taxOn/taxHistory` |
| Engine: basis on the draft (registration on the date, place of supply, party GSTIN validated, rule pack); lines default from the item; notes reverse on the original's basis and rates; untaxed when composition/unregistered sale or unregistered vendor without RCM; composition inter-state sale refused; posting refuses a draft whose basis changed | `DocumentEngine`, `GstBasis` |
| Posting: output on sales, input on creditable purchases, RCM payable on the party's side; non-creditable GST in Purchases **and in stock cost** (AS 2) | `PostingPlan` |
| Composition eligibility: preceding April–March turnover (posted invoices − credit notes, taxable value) vs the state's limit; special-category states in rule pack **2025.10.2** | `GstRegistrations`, `RulePack` |

### Verification
- `GstDocumentsIT` (15 end-to-end cases, figures by hand) and posting-plan properties (balance, note mirrors, ledger direction).
- Mutation checks, each caught: creditable composition GST, missing RCM leg, note rate from item, no basis re-check, non-creditable GST left out of cost, RCM vendor owed tax, eligibility `>` → `>=`, HALF_UP → HALF_EVEN, UTGST → SGST. The note-rate mutation first **survived**; the test was strengthened (item rate corrected after invoicing).
- `./build.sh --plan professional`: jlink resolved (inventory now requires `accubook.tax`); the runtime launched under Xvfb and installed pack 2025.10.2 into a fresh `app.db`.

### Defects found and fixed on the way
- The party reader called `wasNull()` after `is_active`, not `credit_days`: a party with no credit days got a due date on the document date. Aging was unaffected; the stored date was wrong. Same ordering in `CompanyRegistry` (harmless by luck) corrected.

### Not done / limits
- Inclusive (MRP) prices (P9-6), specific-rate cess, blocked credit under s.17(5), ITC where the place of supply is another state, tax on quotations and orders (a quotation's free-text line must get a category when converted).
- A vendor's own composition status is unknown to the party record; its bills must be entered as untaxed lines.
- Composition turnover is this company file's; the law's aggregate turnover is PAN-wide.
- No document screen exists yet: the application must pass `CompanyService::rules` to the facades when one is wired (the convenience constructors use the bundled pack).
- Migration 11 was amended after its first commit (basis triggers), as migration 9 was — acceptable only because nothing has shipped.
- Open question **P9-9** (composition turnover crossing the limit mid-year) — recorded, not decided.

---

## P9c — TDS and TCS ✅ (2026-10-12)

**Tests:** **735 passing · 0 failing · 0 skipped** with UI on Xvfb (726 + 9 UI)
**Before P9c:** 708 (+27)
**Decisions:** P9-10 scope (TDS both ways, TCS on a test pack only), P9-11
paisa rounding, P9-12 catch-up on crossing an annual limit, P9-13 one TDS
Payable + one TDS Receivable (+ TCS Payable).

### Implemented

| Item | Where |
|---|---|
| PAN format and holder status (4th letter: individual/HUF or other) | `Pan` |
| Section resolution by PAN (194C → 194C-IND / 194C-OTH); strict single limits; annual limits with **catch-up** of the year's untaxed amounts (R-18); monthly (194I); excess-only (194Q); no-PAN rate; paisa rounding | `Withholding` |
| Company migration 12: `withholding_entry` (kernel, cancel-only); documents' TDS/TCS and generated **`settle_e4` = total − TDS + TCS** used by the allocation limits; receipts' customer TDS; TDS Payable, TDS Receivable, TCS Payable ledgers | `Schemas` |
| Outstanding, settlement status, note application and allocation checks by `settle_e4` | `SettlementEngine`, `DocumentEngine` |
| TDS on bills / TCS on sales at posting: section checked on the draft, year's (or month's) prior from records, TDS on value before GST, TCS on the whole amount; notes reverse at the original's rate capped at what is left; cancellation cancels the entry; invalid PAN refused | `DocumentEngine` |
| Customer TDS on receipts: Dr bank net, Dr TDS Receivable, Cr customer gross; section in force; never on payments | `SettlementEngine` |

### Verification
- `WithholdingTest` (12) and `WithholdingDocumentsIT` (10), figures by hand; `WithholdingSchemaIT` (5).
- Mutations caught: catch-up removed, single limit `>`→`>=`, HALF_UP→HALF_EVEN, cancelled entries counted, note reverses nothing, TDS on value including GST, bank debited gross.
- Intermediate commit `83c63c7` verified green in a clean worktree.

### Not done / limits
- TDS on advance payments before the bill, s.197 lower-deduction certificates, challans (ITNS 281) and Forms 26Q/27Q/16A/27D (P9d).
- 194Q applies only to a buyer with turnover over ₹10 crore; P9c does not check it.
- Thresholds read the records posted so far: a bill backdated before others in the year is computed on what had been posted when it was posted.
- No TCS section is in force in the bundled pack; TCS is exercised on a test pack only.
- A customer's TDS is recorded as stated; the rate on the record is derived from it.

---

## P9d-1, P9d-2 — GSTR-1 and GSTR-3B ✅ (2026-10-12)

**Tests:** **760 passing · 0 failing · 0 skipped** with UI on Xvfb (751 + 9 UI)
**Before:** 735 (+25)
**Decisions:** P9-9 composition invoice over the limit refused; P9-14 filing
snapshots and locks the period; P9-15 portal JSON only; P9-16 UQC warns, HSN
blocks.

### Implemented

| Item | Where |
|---|---|
| P9-9: the bill of supply that takes a composition dealer's year over the limit is refused, pointing to a regular registration from that date | `DocumentEngine` |
| Rule pack 2025.10.3: GST UQC list (45 codes, UNVERIFIED) | `rulepack-2025.10.3.txt` |
| Migration 13: unit and line UQC; `gst_return` (JSON + SHA-256, unlock-only); triggers lock filed periods (GSTR-1 → sales; 3B/CMP-08/9 → both) | `Schemas` |
| Units' GST code; lines keep HSN/SAC and UQC; free-text lines state HSN/SAC (4/6/8 digits); posting/cancelling in a filed period refused with the return named | `Inventory`, `DocumentEngine` |
| **`accubook-taxation-returns`** add-on (requires no document module): GSTR-1 (B2B, B2CL > rule-pack limit, B2CS, CDNR, CDNUR, NIL in three columns, HSN B2B/B2C with UQC, documents incl. cancelled), GSTR-3B (outward, 3.2, RCM, ITC as per books, exempt inward, Rule 88A suggested set-off), portal JSON, mark filed / unlock | `Gstr1Builder`, `Gstr3bBuilder`, `GstReturns` |

### Findings
- **R-19** B2CL limit stale (₹2.5 lakh, `>=`); **R-20** cancelled count always 0;
  **R-21** unit symbol reported as UQC. Each reproduced by a mutation that the
  tests catch.

### Verification
- `Gstr1IT` (6), `Gstr3bIT` (2), `SetOffTest` (4), `GstReturnSchemaIT` (5), module tests (4).
- `./build.sh --plan basic --add-on taxation-returns`: jlink resolved 13 modules
  incl. `accubook.taxation.returns`; the runtime launched under Xvfb, no errors.

### Limits
- Portal JSON follows the published field names, **not validated against
  GSTN's offline tool** — a release gate with V-1. The tool's `version`/`hash`
  are left for it to add; HSN is emitted as `hsn_b2b`/`hsn_b2c`.
- ITC is as per books; GSTR-2B matching is P9d-3. No blocked credit (s.17(5)),
  so 4(B) and 4(D) are nil. No exports/SEZ, advances, amendments (B2BA etc.).
- Services report UQC `NA` with zero quantity (assumed portal convention, V-1).


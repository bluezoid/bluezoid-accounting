# Open Decisions

**Status:** LIVE — update whenever a decision is made or a new one is found.
**No business decision has been made silently.** Resolved items keep their full
original text and gain a resolution block, so the reasoning stays auditable.

**Resolved so far:** B-3, B-6 · B-7, B-8 (Phase 4) · P5-1…P5-4 (Phase 5) ·
P6-1…P6-4 (Phase 6) · A-1 cost-layer persistence · I-4, I-5 · N-1 (Maven) ·
B-1, P7-1…P7-12, P6-4-UI (2026-10-09) · P7-13…P7-21, P8-1…P8-6, I-1, I-2, B-4, P9-1…P9-4 (2026-10-10) · P9-5…P9-16 (2026-10-12) · P9-17…P9-24 (2026-10-13) · P9-25…P9-28, P10-1…P10-5 (2026-10-24).

**Still open:** B-2 · B-5 · P9-29 · I-3, I-6…I-18 · N-2…N-9.

## Classification

| Class | Meaning |
|---|---|
| 🔴 **BLOCKING** | Cannot safely implement the dependent phase without this |
| 🟠 **IMPORTANT** | Must be decided before the relevant feature is built |
| 🟡 **NON-BLOCKING** | Can be decided later without rework |

---

# 🔴 BLOCKING

> Resolved items stay in this section with their original text intact, marked
> ✅ and dated. Only the three unmarked entries — **B-2, B-4, B-5** — are
> still open.

## B-1 · Inventory: perpetual or periodic? — ✅ **DECIDED 2026-10-09**

> **Decision (user, 2026-10-09).** Target customer for the first commercial
> release is **traders, distributors, retailers, service businesses and mixed
> SMEs** (answer **A**). Inventory is therefore **periodic in form 5a:
> perpetual quantities, periodic valuation**, with the period-end
> closing-stock adjustment posted as a **real voucher through
> `PostingService`** — never derived invisibly at report time.
>
> **This supersedes the original recommendation below**, which favoured
> perpetual before customer positioning was known. The original text is kept
> unedited for the audit trail.
>
> **Consequences accepted.**
> - P7 (documents) does not depend on P8 (costing); purchases post to a
>   Purchase Accounts ledger, sales post revenue only — no COGS at issue.
> - Stock *quantities* are live and exact at every moment.
> - Interim gross profit is **provisional** between period ends and must be
>   labelled so in the UI.
> - No per-invoice margin without a valuation run.
> - Moving to perpetual later is a **migration**, not a switch.
> - P11 must include the closing-stock voucher in year-end close.
> - A-1 (persisted cost layers) stands unchanged.
>
> **Not decided by this:** I-1 (costing method), I-2 (granularity), I-3 (scrap).
> Detail: `B1_INVENTORY_DECISION_BRIEF.md` §10.

> ⛔ **BLOCKED ON A PRODUCT DECISION, not on further analysis.**
> The brief is complete; the one missing input is customer positioning, which
> is not derivable from the code and must not be assumed:
>
> **A** — SMEs, traders, distributors, service businesses; out-of-order entry is
> normal; live per-invoice margin is not essential. → favours periodic (5a)
> **B** — inventory-led businesses; live stock valuation and transaction-level
> gross margin are core. → favours perpetual
> **C** — deliberately both; needs a common kernel designed for it, not an
> average of the two.
>
> Nothing in the repository states a target customer. Searched
> `PROJECT_OVERVIEW.md`, `ROADMAP.md` and every decision doc: no positioning is
> recorded anywhere.
>
> 📋 **Decision brief prepared 2026-09-03:**
> `B1_INVENTORY_DECISION_BRIEF.md` — measured evidence, both models, adversarial
> scenarios, and a recommendation (**periodic with perpetual quantities**, form
> 5a). Awaiting your decision. It deliberately does **not** decide I-1, I-2 or
> I-3.

**Question.** Should the desktop product post COGS at issue (perpetual) or
expense purchases and derive closing stock at report time (periodic)?

**Why it matters.** Determines the chart of accounts, every inventory posting,
and the shape of the P&L and balance sheet. Very expensive to change later.

**Evidence.** The reference is nominally periodic — **but its periodic adjustment
evaluates to zero for most items**, because `valuation.ts` applies `asOf` only to
cost layers while reading *current* quantities. For weighted-average items
opening and closing are computed from the same quantities and cancel. It also
double-counts inventory on the balance sheet for manufacturers, and its COGS
scaffolding (`dispatch.ts:43`, "for the COGS entry") was written and abandoned.

**Options.** (a) **Perpetual** — post COGS on issue; Stock-in-Hand becomes a real
ledger balance. (b) Periodic — expense purchases, derive closing stock, but
implement a genuinely date-correct valuation engine.

**Recommendation: (a) Perpetual.** It eliminates four defect classes at once and
makes statements reproducible.

**Consequences.** More schema (cost layers + consumption) and more posting logic;
a `Cost of Goods Sold` ledger must be provisioned; imported opening stock needs a
defined starting position.

**Depends on it:** Phase 6 (Inventory), the P&L and balance sheet, Phase 9
(manufacturing).

## B-2 · Year-end: closing voucher or derived retained earnings?

**Question.** Post a real closing voucher and lock the year, or derive retained
earnings at report time?

**Why it matters.** Affects every financial statement, prior-period corrections,
and whether a filed statement is reproducible.

**Evidence.** The reference has **no close at all** — `isClosed` is never set to
`true`; `fiscal-years` has only GET and POST. Retained earnings are re-derived on
every request from **all vouchers since inception** (four unbounded queries,
aggregated in JavaScript). Its own comment describes this as a workaround.
Back-dating into any period is currently unrestricted.

**Options.** (a) **Real closing voucher** + locked year + rolled-forward opening
balances. (b) Derived retained earnings.

**Recommendation: (a).** Reproducibility of filed statements and bounded
reporting cost are both decisive; (b) also gets structurally slower every year.

**Consequences.** Needs a Retained Earnings ledger, a close/reopen workflow with
audit, and enforcement of `isClosed` at posting time.

**Depends on it:** Phase 4 (Accounting core), all financial reports.

## B-3 · Money storage encoding — ✅ **APPROVED 2026-09-01**

> Approved scales: money 4 · rate 2 · FX 8 · conversion 6 · days 1.
> Scaled INTEGER storage + BigDecimal in code + STRICT tables.
> Detail: `B3_B6_RESOLUTION.md`

**Question.** Scaled INTEGER, TEXT decimal, or something else?

**Evidence (measured).** SQLite stores a column declared `DECIMAL(18,4)` as
`real`; `sum(a) = 0.3` returns FALSE. **`SUM()` over a TEXT column silently
returns `real`**, so TEXT forfeits SQL aggregation. TEXT also sorts
lexicographically (`-100.00 | -5.00 | 10.00 | 2.00`). INTEGER at scale 4 covers
±₹922 trillion and aggregates exactly.

**Recommendation: scaled INTEGER + `BigDecimal` + STRICT tables**, with per-class
scales (money 4, rate 2, FX 8, conversion 6, days 1).

**Depends on it:** everything. **Phase 2.**

## B-4 · GST rounding: byte-compatible or correct? — ✅ **DECIDED 2026-10-10**

> **Decision (user): (b) correct.** Each line's CGST/SGST/IGST/cess is rounded
> **half-up to the paisa** when computed; invoice totals and every return cell
> are exact sums of those line figures. Differs from the web app's exports by at
> most a paisa per cell on exact-half cases; the divergence is documented and the
> reference test vectors are re-based deliberately, never silently.

**Question.** Reproduce the reference's `Number(x).toFixed(2)` (IEEE-754,
effectively half-even), or use `BigDecimal` with explicit `HALF_UP`?

**Why it matters.** Produces **different portal JSON** on exact-half cases.
Affects filed returns.

**Recommendation: (b) correct**, with test vectors deliberately re-baselined and
the divergence documented. **This is a compliance decision and is yours.**

**Depends on it:** Phase 7 (Taxation).

## B-5 · Payroll rounding

**Question.** The reference payroll engine is **entirely float** (11 ×
`Math.round`, zero Decimal), and `post-month` deliberately re-derives with the
same float math to keep JV totals byte-identical.

**Recommendation:** `BigDecimal` with each `Math.round` transcribed as an
explicit `setScale(0, HALF_UP)` at the same point, **validated against a real
payroll run** before go-live. Payroll figures are filed with statutory
authorities, so silent divergence is unacceptable.

**Depends on it:** Phase 9 (HR & Payroll).

## B-6 · Database topology — ✅ **APPROVED 2026-09-01**

> `app.db` for platform/licence data + one `company.db` per company.
> Single codebase; company data physically isolated at the file level.
> Detail: `B3_B6_RESOLUTION.md`

**Question.** Confirm one SQLite file per company + a separate `app.db`.

**Evidence.** The reference has **zero** ORM/DB-level tenant enforcement (0
`$use`, 0 `$extends`, 0 RLS) — isolation is hand-written `organizationId`
filters across 110 route files, and there is already a live cross-tenant write
primitive in the reconciliation route.

**Recommendation: confirm.** Per-file isolation is structural, not conventional.

**Depends on it:** Phase 2 and everything after.

## B-7 · Plan → module matrix — ✅ **RESOLVED 2026-09-01** (Phase 4)

> **Decision.** The matrix in `P4_PLAN_MODULE_MATRIX.md` is approved.
> `taxation-returns` (GSTR-1/2B/3B/9, CMP-08, e-invoice, e-way bill — ~3,615
> lines) is an **add-on purchasable at any tier**, included in Enterprise.
>
> **GST and TDS *computation* remain kernel and are in every build.** They are
> not separable: `india-tax.ts` is used by both the invoice write path and GL
> posting, and `post-bill.ts` imports `computeTds` to post TDS Payable. Only
> *return generation* is gated — so every plan records GST correctly, and the
> add-on files it.
>
> À-la-carte sale is supported by the architecture; pricing and the admin
> portal remain open (see §"What is NOT decided" in the matrix).
> Detail: `P4_PLAN_MODULE_MATRIX.md`, `B7_TAXATION_ANALYSIS.md`.
> Implemented in `PlanCatalogue.java`; asserted by `PlanCatalogueTest`.

**Question.** Which concrete modules are in BASIC / PROFESSIONAL / ENTERPRISE?

**Why it matters.** The build system cannot be written without it, and it
determines what the first sellable build contains.

**Consequences.** Also needs a decision on whether modules are sold à la carte
(e.g. PROFESSIONAL + backup) in addition to fixed plans. The architecture
supports it; pricing and the admin UI must too.

**Depended on it:** **Phase 4** (module/build system) — now delivered. Still
ahead: **Phase 13** (admin portal), **Phase 14** (upgrade/downgrade), pricing.

## B-8 · Dormant-module read access — ✅ **RESOLVED 2026-09-01** (Phase 4)

> **Decision.** Option (a) — a **generic read-only viewer** in the base build,
> driven by per-module view metadata.
>
> A module declares `List<HistoricalView>` describing its records: title, table,
> columns and sort order. This is **pure metadata with no behaviour**, so it
> cannot become a way around entitlement — the viewer executes no module logic
> and can only read.
>
> This is what lets a downgraded customer see their payroll history in a build
> that does not contain the payroll engine. Reads are never gated:
> `ModuleState.allowsRead()` is true for everything except `ABSENT`.
> Implemented in `HistoricalView.java`, `FeatureModule.historicalViews()`,
> `ModuleRegistry.historicalViews()`.
>
> **The viewer UI itself is not yet built** — P4 delivered the metadata contract
> the base build will render. The screen belongs to a later phase.

**Question.** After downgrade the module's code is physically absent. How is
historical data viewed?

**Options.** (a) **Generic read-only viewer** + per-module view metadata in the
base build (recommended). (b) Export-only. (c) Always ship all code and gate at
runtime — **contradicts the physical-packaging requirement**.

**Why it matters.** Defines what "existing data visible/read-only" means
commercially, and sizes the base build.

**Depended on it:** **Phase 4** (metadata contract) — now delivered. Still
ahead: the viewer screen itself, and **Phase 13** (admin portal).

## P5-1 · Posting core as its own Maven module? — ✅ **APPROVED 2026-09-01**

> Yes. Shipped as `accubook-accounting` with `posting.internal` unexported.

**Recommendation.** Yes — `accubook-accounting`, with
`com.accubook.accounting.posting.internal` unexported. JPMS unexported packages
are the sealing mechanism; keeping the writer inside `accubook-app` would place
it beside the code that must not reach it.

**Depends on it:** Phase 5.

## P5-2 · Reversal date when the original period is closed — ✅ **APPROVED 2026-09-01**

> Current open period. The original is **never** modified, even when its own
> period is open. A rule that behaves differently depending on whether a period
> happens to be closed is a rule people get wrong. Prior-period restatement is
> left as an extension point, not built.

**Question.** Must a reversal be dated in the current open period, or may it
carry the original voucher's date?

**Why it matters.** Dating it in the original period reopens a closed year;
dating it currently makes comparatives differ from the original filing. This is
an accounting-policy call, not a technical one.

**Recommendation.** Current open period. **Depends on it:** Phase 5, Phase 11.

## P5-3 · Idempotency keys mandatory or optional? — ✅ **APPROVED 2026-09-01**

> Mandatory for scheduler, OCR and Tally import; optional for interactive. But
> "optional" does not mean duplicates are acceptable: the interactive path uses
> a form-scoped key so a lost response followed by a second Save cannot create a
> second voucher.

**Recommendation.** Required for non-interactive callers (scheduler, OCR, Tally
import), optional for interactive posting. Mandatory everywhere burdens the UI
path; unavailable is how the reference's importers became re-runnable.

**Depends on it:** Phase 5, Phase 7.

## P5-4 · Does P5 port posting *content* or only the mechanism? — ✅ **APPROVED 2026-09-01**

> Mechanism and invariants only. Known reference defects are not reproduced.

**Recommendation.** Mechanism only. Which ledger is debited for a sales invoice
is P6/P7 and depends on B-1. **Depends on it:** Phase 5, Phase 6.

## P7-1…P7-6 · Phase 7 design decisions — ✅ **APPROVED 2026-10-09**

> All six approved as recommended in `docs/phases/PHASE_7_DESIGN.md` §9.
>
> - **P7-1 GST:** documents are tax-ready but tax-free in P7. A
>   `document_tax_line` table exists and stays empty until P9. B-4 remains open.
> - **P7-2 Party ledger:** one ledger per party. `CUSTOMER`/`BOTH` under Sundry
>   Debtors, `VENDOR` under Sundry Creditors; net balance is the position.
> - **P7-3 Settlement:** allocation table, on-account advances, outstanding
>   computed in SQL — never cached on the document.
> - **P7-4 Negative stock:** refused.
> - **P7-5 Stock coupling:** posting an invoice/bill moves stock in the same
>   transaction. Delivery notes arrive with orders in P7e.
> - **P7-6 Plan:** sub-phases P7a → P7e.

## P9-10…P9-13 · P9c TDS and TCS — ✅ **APPROVED 2026-10-12**

> As recommended in `PHASE_9_DESIGN.md` §7.3.
>
> - **P9-10:** TDS deducted on purchase bills and debit notes; TDS deducted by
>   customers on our receipts (TDS receivable); TCS on sales invoices built and
>   tested on a test pack only (no section in force). Later: TDS on advances,
>   s.197 certificates, challans and 26Q/27Q/16A (P9d).
> - **P9-11:** TDS rounded half-up to the paisa.
> - **P9-12:** crossing an annual threshold deducts on the year's whole
>   aggregate for that party and section (catch-up), not the crossing bill
>   alone (R-18).
> - **P9-13:** one TDS Payable and one TDS Receivable ledger; deductions tagged
>   with their section.

## P10-1…P10-5 · Phase 10 banking — ✅ **APPROVED 2026-10-24**

> As recommended in `PHASE_10_DESIGN.md` §4.
>
> - **P10-1:** CSV statements (HDFC, ICICI, SBI, Axis, user-mapped generic);
>   Excel later.
> - **P10-2:** line identity (incl. running balance and same-day occurrence)
>   under a unique index; an unreadable row or a running-balance break refuses
>   the file.
> - **P10-3:** matches are suggested and confirmed by the user; "confirm all
>   certain" only for unique exact-amount + exact-reference matches.
> - **P10-4:** charges and interest posted from the statement line to a ledger
>   the user chooses; no ledgers seeded; last ledger used is suggested.
> - **P10-5:** per account, a reconciliation start date and statement balance;
>   earlier entries not offered; the start is fixed once anything after it is
>   matched.

## P9-29 · Transport contractors under s.194C(6) — 🟡 **OPEN**

> No TDS is deducted on payments to a contractor in the business of plying,
> hiring or leasing goods carriages who owns ten or fewer and gives a
> declaration with PAN. AccuBook has no way to record that declaration, so such
> a contractor is taxed at 194C rates. Options: a dated declaration on the
> party (with the document kept), checked at posting; or a 0% 194C variant in
> the rule pack chosen per bill. Not blocking; decide before the TDS screens.

## P9-25…P9-28 · E-invoice and e-way bill — ✅ **APPROVED 2026-10-24**

> As recommended in `PHASE_9_DESIGN.md` §11.3.
>
> - **P9-25:** the user declares the date e-invoicing applies from
>   (effective-dated); AccuBook warns when the books' turnover passes the limit
>   with no date declared.
> - **P9-26:** an invoice with an IRN can be cancelled only within 24 hours of
>   its acknowledgement; after that, a credit note.
> - **P9-27:** e-way bill requirement is advisory, on consignment value
>   including tax (R-25): ₹50,000 inter-state; intra-state the state's limit
>   from the rule pack, else ₹50,000; goods only.
> - **P9-28:** IRN, acknowledgement, signed QR and e-way bill number come only
>   from imported portal responses, checked against the document.

## P9-20…P9-24 · CMP-08, GSTR-9, TDS deposits — ✅ **APPROVED 2026-10-13**

> As recommended in `PHASE_9_DESIGN.md` §10.5.
>
> - **P9-20:** the registration records the composition category; the rule
>   pack carries each category's dated rate and base (trader 1% on taxable
>   supplies; manufacturer 1%, restaurant 5%, services 6% on turnover).
> - **P9-21:** marking CMP-08 filed posts Dr Composition Tax / Cr Composition
>   Tax Payable on the quarter's last day.
> - **P9-22:** GSTR-9 tables 4–9 and 17 from filed returns with books
>   differences; 10–16 and 18–19 left to the user.
> - **P9-23:** s.201(1A) interest on late TDS deposits computed and shown; the
>   deposit is not refused.
> - **P9-24:** 26Q produced as structured data for Protean's RPU, not the FVU
>   file.

## P9-17…P9-19 · GSTR-2B reconciliation — ✅ **APPROVED 2026-10-13**

> As recommended in `PHASE_9_DESIGN.md` §9.3.
>
> - **P9-17:** ₹1 tolerance per figure (value, taxable, each tax).
> - **P9-18:** invoice numbers compared normalised (case, spaces, `/ - .`,
>   leading zeros ignored); each match records exact or normalised.
> - **P9-19:** GSTR-3B claims only credit in GSTR-2B (s.16(2)(aa)): per bill the
>   lower of books and 2B, for bills matched in this or an earlier month's 2B;
>   the rest deferred. 3B is not fileable until that month's 2B is imported.

## P9-9, P9-14…P9-16 · Composition limit, filed returns — ✅ **APPROVED 2026-10-12**

> As recommended in `PHASE_9_DESIGN.md` §8.4.
>
> - **P9-9:** a composition sales invoice that would take the year's turnover
>   above the limit is **refused**; the dealer records a regular registration
>   from that date, after which it is a tax invoice.
> - **P9-14:** "mark filed" stores a snapshot (figures + hash) and **locks the
>   period for GST documents** (no new, backdated or cancelled document dated in
>   it); corrections by note or amendment in an open period; unlocking is an
>   audited admin action with a reason.
> - **P9-15:** returns are produced as the portal's offline-upload **JSON**; no
>   direct GSP filing (reconsider with P13).
> - **P9-16:** units carry a GST **UQC** from the rule pack (missing → `OTH`,
>   warning); a taxable line without HSN/SAC **blocks** marking the return filed.

## P9-10…P9-13 · P9c TDS and TCS — ✅ **APPROVED 2026-10-12**

> As recommended in `PHASE_9_DESIGN.md` §7.3.
>
> - **P9-10:** TDS deducted on purchase bills and debit notes; TDS deducted by
>   customers on our receipts (TDS receivable); TCS on sales invoices built and
>   tested on a test pack only (no section in force). Later: TDS on advances,
>   s.197 certificates, challans and 26Q/27Q/16A (P9d).
> - **P9-11:** TDS rounded half-up to the paisa.
> - **P9-12:** crossing an annual threshold deducts on the year's whole
>   aggregate for that party and section (catch-up), not the crossing bill
>   alone (R-18).
> - **P9-13:** one TDS Payable and one TDS Receivable ledger; deductions tagged
>   with their section.

## P9-9 · Composition turnover crossing the limit during the year — 🟠 **OPEN**

> A composition dealer whose turnover in the year passes the limit loses the
> scheme from that day and must issue tax invoices. P9b checks eligibility only
> when a composition registration is recorded. Options: (a) warn on the invoice
> that crosses the limit; (b) refuse composition invoices beyond it until a
> regular registration is recorded; (c) leave it to the user. Needed before
> P9d (CMP-08).

## P9-5…P9-8 · P9b GST on documents — ✅ **APPROVED 2026-10-12**

> As recommended in `PHASE_9_DESIGN.md` §6.2.
>
> - **P9-5:** place of supply is set on the document, defaults from the party
>   (a registered party's state comes from its GSTIN), can be edited while DRAFT
>   and is locked at posting. A B2B sale needs a valid GSTIN. An unregistered
>   party with no state is taxed in the **company's own state** (intra-state).
>   The reference app's fail-safe IGST is not copied.
> - **P9-6:** tax-inclusive (MRP) prices are **deferred** until before the
>   retail screens. P9b prices exclude tax.
> - **P9-7:** separate ledgers for each component and direction under Duties &
>   Taxes: Output and Input CGST/SGST/UTGST/IGST/Cess, plus RCM Payable for
>   each component. Seeded in every plan.
> - **P9-8:** a document is **refused** while the company has no GST registration
>   recorded for its date. Every company records its position (Regular,
>   Composition or Unregistered, with its state) once. An Unregistered company
>   charges no GST on sales; GST on its purchase bills is computed from the
>   vendor's rate and added to cost, with no input credit (as for composition).

## P9-1…P9-4 · Phase 9 taxation — ✅ **APPROVED 2026-10-10**

> As recommended in `PHASE_9_DESIGN.md` §4.
>
> - **P9-1:** sub-phases P9a rule packs → P9b GST on documents → P9c TDS/TCS →
>   P9d returns (`taxation-returns` add-on) → P9e e-invoice / e-way payloads.
> - **P9-2:** the item carries its GST slab (+ cess) with effective-dated
>   changes; every line has an explicit tax category — never silently nil.
> - **P9-3:** in scope: B2B/B2C intra/inter, RCM purchases, composition, cess.
>   Later: exports/SEZ, e-commerce operator, advances, GSTR-4.
> - **P9-4:** signed rule-pack files (own Ed25519 keys, two-key rotation), one
>   bundled, newer ones imported offline; online delivery with P13.

## P8-1…P8-5 · Phase 8 valuation rules — ✅ **APPROVED 2026-10-10**

> All as recommended in `PHASE_8_DESIGN.md` §5. **Resolves I-1 and I-2.**
>
> - **P8-1 (I-1, I-2):** weighted average (default) and FIFO, per item, fixed
>   once the item has moved; LIFO excluded (AS 2). Costed per item per warehouse.
> - **P8-2:** weighted average is a **moving** average, recalculated on receipt.
> - **P8-3:** backdated entries re-cost the item and warehouse forward; posted
>   valuations at or after that date become stale and must be re-posted, which
>   corrects on their original date. Closed years refuse it (P11).
> - **P8-4:** closing stock is posted on demand at any date by a valuation run.
> - **P8-5:** opening stock and count gains need a unit cost; a positive
>   adjustment without one is valued at current average; NRV write-downs are
>   manual adjustments.
> - **P8-6** *(raised during implementation)*: the closing-stock voucher posts
>   the valuation **rounded half-up to the paisa**; the valuation report keeps
>   all four decimals. Each run posts against the ledger balance, so the
>   difference never accumulates.

## P7-18…P7-21 · P7e order rules — ✅ **APPROVED 2026-10-10**

> All as recommended in `PHASE_7_DESIGN.md` §14.4.
>
> - **P7-18:** orders are fulfilled through invoices and bills; posting moves
>   stock and fulfils the linked order lines. Delivery challans / GRNs deferred.
> - **P7-19:** quotation `DRAFT → SENT → ACCEPTED/REJECTED → CONVERTED`; expiry
>   derived from valid-until; converts to an order or directly to an invoice.
> - **P7-20:** no over-fulfilment of an order line; short-close with a reason.
> - **P7-21:** `purchase-orders` added to **Professional and Enterprise**.
>   *Amends the B-7 matrix* (`P4_PLAN_MODULE_MATRIX.md`).

## P7-13…P7-17 · P7d settlement rules — ✅ **APPROVED 2026-10-10**

> All as recommended in `PHASE_7_DESIGN.md` §13.4.
>
> - **P7-13:** a posted note is allocated automatically to its original
>   document, up to that document's outstanding; any excess is an unallocated
>   credit for the party.
> - **P7-14:** receipts and payments are posted on entry; corrected by
>   cancellation, never edited.
> - **P7-15:** aging by days past due date (document date when none);
>   buckets not due / 1–30 / 31–60 / 61–90 / over 90.
> - **P7-16:** money moves through any active ledger in the Cash & Bank group
>   until banking (P10).
> - **P7-17:** a note may not return more of an item than the original carried,
>   less earlier notes.

## P7-8…P7-12 · P7c document rules — ✅ **APPROVED 2026-10-09**

> All as recommended in `PHASE_7_DESIGN.md` §12.6.
>
> - **P7-8 Numbering:** allocated at posting; one series per kind per fiscal
>   year of the document date; `INV/26-27/00001` (15 chars, Rule 46(b) ≤ 16);
>   prefixes INV, CN, PB, DN; supplier's bill number stored separately.
> - **P7-9 Line rounding:** each line amount to paise, half-up; total = exact
>   sum of rounded lines.
> - **P7-10 Total round-off:** to the nearest rupee, difference to Round Off,
>   on by default, per-document switch.
> - **P7-11 Notes:** must reference a posted document of the same party; total
>   may not exceed the un-credited remainder.
> - **P7-12 Cancellation:** only while nothing is allocated and no note
>   references it; reversal voucher + stock restored; number stays used.

## P7-7 · Licence state before activation exists — ✅ **APPROVED 2026-10-09**

> **Question.** P7b installs the persistence guard and module registry in the
> running application. The real `Entitlement` makes an unactivated install
> `UNLICENSED` (no read, no write — P3), and activation is not built until P13.
> What does the launcher use meanwhile?
>
> **Decision (user):** a clearly named `DevelopmentEntitlement` (every packaged
> module `ACTIVE`) with a startup warning, **plus a release gate**. Implemented
> structurally rather than by inspection: `build.sh --release` bakes
> `accubook.channel=release` into the launcher, and a release-channel launcher
> **refuses to start** while activation is not wired. A release build therefore
> cannot run on the development provider at all. Replaced by the real
> `EntitlementLoader` when activation is built.

## P6-1 · Add `is_system` to the `ledger` table? — ✅ **APPROVED 2026-09-03**

> Yes, migration 3. `system_code` stays the machine handle; `is_system` is the
> governance flag; `name` is the renameable label. Three separate concepts.

**Question.** `ledger_group` has `is_system`; `ledger` has only `system_code`.
Should ledgers carry the flag explicitly?

**Why it matters.** `system_code IS NOT NULL` works as a proxy today, but it
conflates "the posting layer resolves this by a stable handle" with "a user may
not delete this". A future module may want a stable handle on a user-created
account.

**Recommendation.** Yes — migration 3, defaulting to `system_code IS NOT NULL`
for existing rows. Explicit beats inferred for a rule that refuses a user's
action. **Depends on it:** Phase 6.

## P6-2 · May a user rename a system ledger? — ✅ **APPROVED 2026-09-03**

> Yes. Nothing resolves a system ledger by display name, so a rename breaks
> nothing — unlike the reference, where `getOrCreateNamedLedger` makes a rename
> create a duplicate control account and split the balance.

**Recommendation.** Yes. Resolution is by `system_code`, never by display name,
so renaming is safe here — and Indian businesses legitimately call Round Off
"Rounding A/c". The reference application could not allow this: it resolves by
name via `getOrCreateNamedLedger`, so a rename silently creates a duplicate
control account (R-4). **Depends on it:** Phase 6.

## P6-3 · Trial balance default scope — ✅ **APPROVED 2026-09-03**

> Fiscal-year scoped.

**Question.** Fiscal year, or all time?

**Recommendation.** Fiscal year, matching Indian practice and the P11 closing
model. **Depends on it:** Phase 6, Phase 11.

## P6-4 · Does P6 ship a UI? — ✅ **APPROVED 2026-09-03** · ⚠️ **deviation, see P6-4-UI**

> Approved as: governance use cases plus a read-only trial-balance screen. The
> use cases and the trial-balance computation shipped; **the screen did not** —
> see P6-4-UI for why and what unblocks it.

**Recommendation.** Use cases plus a minimal read-only trial balance screen -
enough to demonstrate the exit criterion without pre-empting P7's document
screens. **Depends on it:** Phase 6.

## P6-4-UI · Trial-balance screen — ✅ **CLOSED 2026-10-09**

> **Prerequisite met and screen built.** Xvfb was installed. The UI tests had
> never actually run: the failsafe config forced Monocle even on a real display,
> and Monocle fails on JavaFX 17. Switched to GTK glass on Xvfb; one stale test
> id (renamed button) surfaced and was fixed.
>
> Delivered: `TrialBalanceView` — fiscal-year picker (opens on the year
> containing today), debit/credit in Indian grouping, totals, out-of-balance
> warning with the difference, deactivated accounts with a balance marked.
> Read-only by construction: data comes through
> `CompanyService.readOpenCompany`, a guarded `query_only` connection.
> 6 new UI tests (5 view, 1 end to end through the shell), stable across
> repeated runs. Command:
> `xvfb-run -a -s "-screen 0 1280x1024x24" mvn verify -Daccubook.ui.tests=true`
>
> **Still true:** no CI pipeline exists in the repository, so UI tests run only
> where someone runs that command.

**Status.** P6-4 was approved as a product requirement: use cases plus a minimal
read-only trial-balance screen. The use cases and the trial-balance computation
shipped in P6 and are tested. **The JavaFX screen was not built.**

**Why deferred.** UI tests cannot execute in the current environment — 3 tests
are skipped for want of a display, and TestFX/Monocle fails on JavaFX 17.
Building the screen now would add an untested UI surface to an accounting
product.

**Prerequisite.** Working UI test infrastructure: Xvfb in CI, or a TestFX and
Monocle combination that runs on JavaFX 17. The screen should be implemented once
its tests can run.

**Depends on it:** the P6 exit record (deviation noted in `PHASE_6_REPORT.md`
§8), and every later phase that ships a screen.

---

# 🟠 IMPORTANT

## A-1 · Cost layers are persisted, not reconstructed — ✅ **DECIDED 2026-09-03**

> **Architectural invariant, independent of B-1.** Holds whether inventory ends
> up periodic, perpetual, or a staged approach.
>
> **Decision.** AccuBook persists cost layers and consumption records rather than
> reconstructing valuation at report time, because historical valuation must be
> **reproducible**: the same `asOf` date must yield the same answer next year,
> after the movement log has grown and the costing method may have changed.
>
> **What this is not.** It is *not* a consequence of periodic accounting. D-24
> proves that one reconstruction strategy — current quantities priced at
> historical costs — is broken. It does not prove persisted layers are
> mathematically mandatory; a complete immutable movement log could in principle
> be replayed correctly. We choose persistence deliberately.
>
> **Why.** A persisted layer is a *record*; a reconstruction is a *derivation*
> that silently changes when its inputs or its algorithm change. P11 needs
> prior-year comparatives that do not drift after a year is closed. Statutory
> records should be reproducible years later by someone who no longer has the
> code that computed them. Replay cost grows with history; a stored layer does
> not.
>
> **Cost accepted.** More schema, more migration surface, and layers that must
> themselves be kept correct.
>
> Detail: `B1_INVENTORY_DECISION_BRIEF.md` §7.1. **Depends on it:** P8, P11.

## I-1 · Costing methods to support
**Recommendation:** weighted average (default) + FIFO. **Exclude LIFO** — it is
prohibited under AS 2 / Ind AS 2, and the reference "supports" it only as a
report-time array reversal no auditor would accept.

## I-2 · Cost granularity
Per item **per warehouse**, consistently. The reference maintains `avgCost` per
warehouse but builds FIFO layers per item — a bug generator.

## I-3 · Scrap costing
Absorb into good units (reference behaviour, silent) or post to a
`Manufacturing Scrap Loss` account (visible)? Recommend the latter, or make
absorption an explicit configurable policy.

## I-4 · Ledger balances: cached or computed? — ✅ **RESOLVED 2026-09-03**

> **Computed**, settled by P2-2 and shipped. `ledger` has no `current_balance`
> column. This is also what keeps the posting core free of chart-of-accounts
> semantics: the reference must know each ledger's nature to sign a cached
> balance, which drags in the whole chart. We sum the journal instead.
The reference caches `currentBalance` **and** separately maintains
`BankAccount.currentBalance` — two independent stores for the same quantity, with
no reconciliation. **Recommendation: compute from the journal**; treat any cache
as a rebuildable optimisation with a verification routine.

## I-5 · Reporting: SQL aggregation or in-memory? — ✅ **RESOLVED 2026-09-03**

> **SQL aggregation**, shipped in P6's `ChartOfAccounts.trialBalance`.
The reference is in-memory at O(ledgers × entries). Scaled integers make SQL
`GROUP BY` exact. **Recommendation: SQL.** Affects report parity tests.

## V-1 · Rule-pack content verification — 🔴 **RELEASE GATE (P16)**
The bundled rule pack's rates, thresholds and effective dates come from public
secondary sources and are marked UNVERIFIED. A chartered accountant must verify
them before release, and the development rule-pack signing keys must be
replaced. See `PHASE_9_DESIGN.md` §5.

## I-6 · TDS surcharge base *(payroll / salary TDS — gates P15, not P9)*
`calculateTDS` tests surcharge against **gross** `annualIncome`, not
`taxableIncome`. Likely incorrect under the Act. **Needs a tax professional's
ruling** — do not silently "fix" a tax rule.

## I-7 · Professional Tax state coverage *(payroll — gates P15, not P9)*
PT slabs are **Maharashtra only**, hardcoded. Which states ship in v1?

## I-8 · Cash flow statement in v1?
The reference version is broken (finds no cash; investing/financing are empty
placeholders). It needs a genuine design, not a port.

## I-9 · Cost centres, projects, budgets in v1?
All effectively non-functional in the reference (never set by automated posting;
budgets never compared to actuals). These are **new feature work**.

## I-10 · Multi-currency in v1?
Schema-only in the reference — no conversion, no revaluation, zero tests. Large
and pervasive if included.

## I-11 · Grace period length
**Recommendation: 14 days** fully functional after expiry.

## I-12 · Machine re-bind allowance
How many automatic re-binds per year before manual approval?

## I-13 · One licence = one machine, always?
The brief says yes; ENTERPRISE customers will ask for more. Much easier to design
multi-seat in now than to retrofit.

## I-14 · Backup key custody
**Customer-only key** (recommended; required by the brief) means a forgotten
passphrase = **permanently unrecoverable backups**. Confirm knowingly.

## I-15 · Encryption at rest for company databases
OS disk encryption (free) vs SQLCipher (native lib per platform, licensing
review, jlink/jpackage complexity). **Recommendation: OS-level for v1** plus
encrypted backups; revisit SQLCipher later.

## I-16 · Reopen policy for closed years
Who may reopen, how often, and are prior-period adjustments posted to the prior
year or as current-year adjusting entries? Both are defensible; the choice must
be explicit and consistent.

## I-17 · Signing key rotation
Older app versions embed only the old public key. **Embed two valid public keys
from v1** or rotation is impossible. Decide before first release.

## I-18 · Data migration from the existing web app
Is importing existing customer data a v1 requirement? If so: opening-balance
direction **cannot be trusted** (D-1) and must be re-derived, and Tally-imported
vouchers may be unbalanced (D-22).

---

# 🟡 NON-BLOCKING

## N-1 · Build tool: Maven or Gradle — ✅ RESOLVED (Maven)
**Resolved 2026-08-31 during Phase 1 setup.**
Verified: Maven 3.8.7 installed; Gradle **not** installed; `jlink`, `jpackage`,
`jmod` all present in Corretto 17.0.20.1. Maven works with jlink/jpackage, so
introducing a Gradle dependency would add setup cost for no functional gain.
Revisit only if a Gradle-specific capability is later required.

## N-2 · Persistence approach
Hand-written SQL + thin mapper (JDBI/MyBatis) vs a heavier ORM. Leaning
hand-written for explicit money mapping and exact SQL control.

## N-3 · JDBC driver
xerial `sqlite-jdbc` is the default candidate — verify STRICT tables, Online
Backup API exposure, native bundling across platforms, and jlink/jpackage
behaviour.

## N-4 · Attachment storage
Beside the company DB (small DB, incremental backups) vs BLOBs inside it
(literally one file per company).

## N-5 · Admin portal stack
New app vs extending the existing Next.js codebase. Platform DB must stay
separate either way.

## N-6 · GSTR-4, Form 27D, FEFO, barcode, OCR
All missing or partial in the reference. Schedule by customer demand.

## N-7 · Offboarding & backup retention periods

## N-8 · Can GST return *formats* be data-driven?
Rates and thresholds clearly can. JSON structure changes may need code.

## N-9 · Should reference-app defects be fixed in the web app too?
**D-23 (credit/debit notes post backwards), D-1 (opening balances sign-flipped),
D-25 (manufacturing double-counts), D-2 (cash flow returns zeros) and the
fabricated IRN affect live users today.** Out of scope for this project, but you
should know. D-23 is the most urgent.

---

## Decision dependency graph

Phase numbers below follow the baselined **P1–P16** roadmap (`docs/ROADMAP.md`).

```
RESOLVED
  B-3 money ──┬──► P2 database                      ✅ delivered
  B-6 topology┘                                     ✅ delivered
  B-7 plan matrix ──┬──► P4 modules/build           ✅ delivered
  B-8 dormant read ─┘                               ✅ metadata delivered
                     └──► P13 admin portal · P14 upgrade/downgrade

  B-1 inventory → periodic 5a (2026-10-09) ──► P7 masters ──► P8 costing

STILL OPEN
  B-2 year-end closing ─────────────► P11 year-end + reporting
  B-4 GST rounding ─────────────────► P9 Indian taxation
  B-5 payroll rounding ─────────────► P15 HR/payroll (deferred)
```

**P5 (sealed posting core) and P6 (accounting modules) depend on NONE of the
open decisions** and can proceed immediately.

**B-1 is the next one to settle** — it becomes architectural at P7/P8, and it
shapes the chart of accounts P6 builds. Deciding it during or just after P6 is
the natural point; it is not needed for P5.

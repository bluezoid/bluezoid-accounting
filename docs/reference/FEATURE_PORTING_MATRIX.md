# Feature Porting Matrix

**Status:** VERIFIED — every status determined by inspecting model + route +
service + UI + tests + actual write paths in
`/home/achyut-pal/Desktop/accubook` (read-only).

**Critical planning warning:** a Prisma model does **not** mean a working
feature. 13 models/fields are schema-only with zero code. 6 features are
outright broken. Estimating from the schema would overstate "already built" by a
wide margin.

## Legend

| Status | Meaning |
|---|---|
| ✅ **IMPL** | Genuinely implemented with real business logic |
| 🟡 **PARTIAL** | Works partly; missing lifecycle, UI, or enforcement |
| 📋 **SCHEMA** | Model/field exists, **zero** code reads or writes it |
| 🖥️ **NO-UI** | Backend complete, no dashboard page |
| 🔴 **BROKEN** | Present and wrong — must not be ported as-is |
| ⬜ **MISSING** | Not present at all; new build |

**Complexity:** L/M/H/XH · **Testing:** U=unit, I=integration, UI=UI test

---

## 1. Accounting core

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Chart of accounts / ledger groups | ✅ IMPL | Yes | M | — | U+I+UI |
| Ledgers | ✅ IMPL | Yes | M | Groups | U+I+UI |
| Vouchers + entries | ✅ IMPL | Yes | H | Ledgers, FY | U+I+UI |
| **Posting engine (Dr=Cr)** | ✅ IMPL **but bypassed once** (D-22) | Yes — **sealed** | H | Ledgers | U+I |
| Voucher types | 🟡 PARTIAL — GET only, global not per-org | Yes, per-company | M | — | U+I |
| `VoucherType.numberingFormat` | 📋 SCHEMA — never read | Implement or drop | L | Numbering | U |
| Document numbering | ✅ IMPL (counter table) | Yes | M | — | U+I |
| Trial balance | ✅ IMPL (in-memory) | Yes — **SQL aggregate** | M | Posting | U+I |
| P&L | ✅ IMPL | Yes | H | Posting, inventory | U+I |
| Balance sheet | 🔴 **BROKEN** — double-counts mfg (D-25) | Yes — redesigned | H | Posting, inventory | U+I |
| Cash flow | 🔴 **BROKEN** — finds no cash (D-2); sections stubbed | Redesign | H | Posting | U+I |
| Fiscal years | 🟡 PARTIAL — GET/POST only, no UI | Yes | M | — | U+I |
| Fiscal periods | 📋 SCHEMA — dead model | Decide: drop or build | L | FY | U |
| **Year-end close** | ⬜ **MISSING** | **Yes** (ADR: Approach A) | H | Posting, FY | U+I+UI |
| Retained earnings | 🟡 Derived at report time | Posted via close | M | Year-end | U+I |
| Opening balances | 🔴 **BROKEN** — sign-flipped (D-1) | Yes — fixed, dated | M | Ledgers | U+I |
| Cost centres | 🟡 CRUD only; never set by automated posting | Decide scope | M | Vouchers | U+I |
| Projects | 🟡 Same as cost centres | Decide scope | M | Vouchers | U+I |
| Budgets | 🟡 CRUD only; **no budget-vs-actual anywhere** | Decide scope | M | Ledgers, FY | U+I |
| Multi-currency / FX | 📋 SCHEMA — no conversion or revaluation | Decide scope | H | Everything | U+I |
| Audit log | 🟡 PARTIAL — ~89 sites vs 128 mutations (~70%) | Yes — **structural** | M | All | U+I |

## 2. Sales

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Invoices | ✅ IMPL | Yes | H | Party, tax, posting | U+I+UI |
| **Credit notes** | 🔴 **BROKEN — posts backwards** (D-23) | Yes — sign-aware | H | Invoices, stock | U+I+UI |
| Sales orders | 🟡 CRUD only, no fulfilment | Yes | M | Party, items | U+I+UI |
| `SalesOrderItem.deliveredQty` | 📋 SCHEMA — never written | Implement | M | SO, dispatch | U+I |
| Quotations | ✅ IMPL | Yes | M | Party | U+I+UI |
| Quotation → SO conversion | ✅ IMPL | Yes | M | Quotation, SO | U+I |
| SO → Invoice conversion | 🟡 FK link only, no line copy | Implement | M | SO, invoice | U+I |
| Receipts | ✅ IMPL (+TCS) | Yes | H | Invoice, bank, TCS | U+I+UI |
| Parties | ✅ IMPL | Yes | M | Ledgers | U+I+UI |
| Recurring invoices | ✅ IMPL | Yes | M | Invoice, scheduler | U+I |
| Aging / outstanding | ✅ IMPL | Yes | M | Invoice, receipt | U+I |
| Party statement | ✅ IMPL | Yes | M | Invoice, receipt | U+I |
| Invoice numbering (FY series) | ✅ IMPL (counter) | Yes | M | Counter, FY | U+I |
| SO/PO/Quote numbering | 🔴 **BROKEN** — non-transactional, series resets (D-11) | Yes — counter | M | Counter | U+I |

## 3. Purchase

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Bills | ✅ IMPL (+RCM, TDS) | Yes | H | Party, tax, posting | U+I+UI |
| **Debit notes** | 🔴 **BROKEN** — no sign handling; `type: z.string()` unvalidated | Yes — sign-aware | H | Bills, stock | U+I+UI |
| Purchase orders | ✅ IMPL | Yes | M | Party, items | U+I+UI |
| PO receipt / GRN | ✅ IMPL | Yes | M | PO, stock | U+I |
| Payments | ✅ IMPL (+TDS) | Yes | H | Bill, bank, TDS | U+I+UI |
| Vendors | ✅ IMPL (shared Party) | Yes | M | Ledgers | U+I+UI |
| Bill ↔ GRN linkage | ⬜ MISSING | Decide scope | M | Bill, PO | U+I |

## 4. Inventory

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Items / categories | ✅ IMPL | Yes | M | — | U+I+UI |
| Units of measure | 🟡 Global, GET only, no per-org CRUD | Yes — **per company** | L | — | U+I |
| `ItemUnit.conversionFactor` | 📋 SCHEMA — no UoM conversion at all | Decide scope | M | Items | U+I |
| Warehouses | ✅ IMPL | Yes | L | Branch | U+I+UI |
| Stock + movements | ✅ IMPL | Yes | H | Items, warehouse | U+I+UI |
| Stock adjustments | 🔴 **No GL entry** (D-26) | Yes — posts to GL | M | Stock, posting | U+I |
| Stock transfers | 🔴 **Corrupts avgCost + fabricates layers** | Yes — cost-preserving | M | Stock | U+I |
| **Inventory valuation** | 🔴 **BROKEN** — `asOf` ignores qty (D-24) | Yes — **perpetual layers** | XH | Stock, posting | U+I |
| **COGS posting** | ⬜ **MISSING** — scaffolding abandoned | **Yes** | H | Dispatch, valuation | U+I |
| Batches | 🟡 Backend only, no UI | Yes | M | Items, stock | U+I+UI |
| Batch consumption / FEFO | 🔴 **Never decremented** (D-27) | Yes | H | Batches, dispatch | U+I |
| Expiry tracking | 🟡 Reporting only; expired stock issuable | Yes — blocking | M | Batches | U+I |
| Barcode | 🟡 Backend only, no UI | Decide scope | L | Items | U+I |
| Dispatch | 🖥️ IMPL, no UI | Yes | H | Invoice, stock | U+I+UI |
| `Stock.reservedQty` | 📋 SCHEMA — "written by nothing" | Implement w/ dispatch | M | Dispatch | U+I |
| `Stock.lastPurchasePrice` | 📋 SCHEMA | Low priority | L | Stock | U |
| Sales / purchase returns → stock | ⬜ MISSING; purchase return has **no movement type** | Yes | H | CN/DN, stock | U+I |

## 5. Manufacturing

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| BOM | 🟡 GET/POST only, no UI, no edit/delete | Yes | M | Items | U+I+UI |
| BOM multi-level costing | 🟡 IMPL but **dead code** (no caller) | Yes — wired | H | BOM | U+I |
| `Bom.effectiveFrom/To` | 📋 SCHEMA — no versioning | Decide scope | M | BOM | U+I |
| Work orders | ✅ IMPL | Yes | H | BOM, stock | U+I+UI |
| Material issue | ✅ IMPL | Yes | H | WO, stock | U+I |
| Finished goods receipt | ✅ IMPL | Yes | H | WO, stock | U+I |
| WO GL posting | 🔴 **Net-zero + double-counts** (D-25) | Yes — redesigned | H | WO, posting | U+I |
| Scrap | 🔴 Absorbed into good units, no account | Yes — costed | M | WO | U+I |
| WorkOrder CANCELLED | 📋 SCHEMA — no endpoint | Yes | L | WO | U+I |

## 6. Indian taxation

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| GST computation | ✅ IMPL (well tested) | Yes | H | Invoice, bill, party | U+I |
| GSTR-1 (+portal) | ✅ IMPL | Yes | H | Invoices | U+I |
| GSTR-2B reconciliation | ✅ IMPL | Yes | H | Bills | U+I |
| GSTR-3B (+portal) | ✅ IMPL | Yes | H | Inv, bills | U+I |
| GSTR-9 (+portal) | ✅ IMPL | Yes | H | 3B | U+I |
| CMP-08 | ✅ IMPL | Yes | M | Composition | U+I |
| **GSTR-4** | ⬜ MISSING | Decide scope | M | Composition | U+I |
| Composition scheme | ✅ IMPL | Yes | M | Org, invoice | U+I |
| TDS | ✅ IMPL | Yes — **effective-dated** | H | Bill, payment | U+I |
| TCS | ✅ IMPL | Yes — **effective-dated** | H | Receipt | U+I |
| Form 16A | ✅ IMPL | Yes | M | TDS | U+I |
| **Form 27D** | ⬜ MISSING | Yes | M | TCS | U+I |
| Monthly challan | 🟡 Service+tests, no route | Yes | M | TDS | U+I |
| HSN library | ✅ IMPL (57 of ~17k entries) | Yes — **rule pack** | M | Items | U+I |
| **e-invoice IRN** | 🔴 **FABRICATED** (R-6) | Payload only, **never fake** | H | Invoice, NIC | U+I |
| e-invoice payload | ✅ IMPL (good builder) | Yes | H | Invoice | U+I |
| e-way bill payload | ✅ IMPL | Yes | M | Invoice | U+I |
| TaxConfig | ✅ IMPL | Yes | M | — | U+I |
| GSTReturn persistence | 🟡 Manual CRUD; `compute` never persists | Yes | M | Returns | U+I |
| InvoiceTax / BillTax | 📋 SCHEMA — never written | Drop (denormalised on lines) | L | — | — |

## 7. Banking

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Bank accounts | 🟡 **Balance stored twice** (NR-3) | Yes — single source | M | Ledgers | U+I+UI |
| Bank transactions | ✅ IMPL | Yes | M | Bank account | U+I |
| Statement import | 🟡 CSV only; buggy amount sanitizer; dedup unindexed | Yes — hardened | H | Bank txn | U+I |
| **Reconciliation** | 🔴 **Two impls; UI uses the untested one** (D-29) | **One** engine | H | Bank txn, vouchers | U+I+UI |
| BankReconciliation model | 🟡 Written only by the untested path | Yes — period close | M | Reconciliation | U+I |

## 8. HR & payroll

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Employees | ✅ IMPL | Yes | M | Org | U+I+UI |
| Departments | ✅ IMPL | Yes | L | Org | U+I |
| Designations | 📋 SCHEMA — no route, no UI | Yes | L | Org | U+I |
| Attendance | 🟡 No PATCH/DELETE; **status vocabulary mismatch** | Yes — one vocabulary | M | Employee | U+I+UI |
| Leave | ✅ IMPL (approval routed) | Yes | M | Employee, type | U+I+UI |
| Leave types | 🟡 Seeded, partial CRUD | Yes | L | Org | U+I |
| **Leave balance / quota** | 🔴 **Decorative — never enforced** (D-28) | Yes — enforced | M | Leave, type | U+I |
| Payroll structures | 🟡 Settings only | Yes | M | Org | U+I |
| **Payroll calculation** | 🟡 IMPL but **float math** (A-5) | Yes — BigDecimal | XH | Employee, attendance | U+I |
| Payslips | ✅ IMPL | Yes | M | Payroll | U+I+UI |
| Post-month (GL) | ✅ IMPL (good CAS guard) | Yes | H | Payslip, posting | U+I |
| Pay-month | ✅ IMPL | Yes | H | Payslip, bank | U+I |
| Expense claims | 🟡 Status only | Yes | M | Employee | U+I+UI |
| **Expense claim GL posting** | ⬜ **MISSING** — no `voucherId` column | **Yes** | M | Claims, posting | U+I |
| Payroll settings wiring | 📋 Stored but **never read** by calc | Yes | M | Payroll | U+I |

## 9. Platform

| Feature | Web status | Desktop | Cx | Dependencies | Testing |
|---|---|---|---|---|---|
| Organizations / companies | ✅ IMPL | Yes — **per-file** | H | — | U+I+UI |
| Company provisioning | ✅ IMPL (18 groups, 23 ledgers, HR masters) | Yes — **system codes** | M | Schema | U+I |
| Branches | ✅ IMPL | Yes | L | Org | U+I+UI |
| Users | ✅ IMPL | Simplify (desktop) | M | Org | U+I |
| Roles / permissions | ✅ IMPL (well tested) | Yes — drop legacy shims | M | Users | U+I |
| API keys | ✅ IMPL | ⬜ Not applicable | — | — | — |
| Notifications | ✅ IMPL | Yes (local) | M | — | U+I |
| Overdue check | ✅ IMPL | Yes (local scheduler) | M | Invoices | U+I |
| Approvals framework | ✅ IMPL for 4 of 6 types | Yes — all types | H | Entities | U+I+UI |
| **Approval: INVOICE / PURCHASE_ORDER** | 🔴 **Declared, never promotes** (D-26) | Yes | M | Approvals | U+I |
| Report templates | 🟡 CRUD only | Yes | M | Reports | U+I |
| Report scheduling | 📋 SCHEMA — never read | Decide scope | M | Templates | U+I |
| Data export (xlsx/csv/json) | ✅ IMPL | Yes | M | Reports | U+I |
| Tally import | 🔴 **Bypasses posting invariant** (D-22) | Yes — via sealed core | H | Posting | U+I |
| OCR / document extraction | ✅ IMPL (multi-provider) | Optional module | H | Documents | U+I |

## 10. Desktop-only (no web counterpart — all new)

| Feature | Cx | Testing |
|---|---|---|
| Licensing & activation | XH | U+I+Security |
| Machine binding / fingerprint | H | U+I |
| Subscription & entitlement enforcement | H | U+I+UI |
| Module packaging / per-plan builds | H | Build |
| Upgrade / downgrade handling | H | I+UI |
| Local backup & restore | H | I+Failure |
| Cloud backup module | H | I+Failure |
| Update & per-file migration | XH | Migration+Failure |
| Admin portal + platform backend | XH | U+I |
| Statutory rule packs | H | U+I |
| Company switching / multi-DB | H | I+UI |
| JavaFX UI shell + components | XH | UI |
| Cross-platform packaging & signing | H | Build |

---

## 11. Planning summary

| Bucket | Count (approx.) |
|---|---|
| ✅ Genuinely implemented | ~55 |
| 🟡 Partial | ~28 |
| 📋 Schema-only | 13 |
| 🔴 Broken | 14 |
| ⬜ Missing | 8 |
| 🆕 Desktop-only | 13 |

**Implications**

1. **~35 features are broken, schema-only or missing** — these are *new build*,
   not *port*. The reference's schema materially overstates completeness.
2. **The 14 broken features include the three most severe defects** (credit
   notes, manufacturing double-count, valuation `asOf`). Each needs a *validated
   accounting rule* before implementation, not a transliteration.
3. **73 dashboard pages** exist in the web app with **zero** UI test coverage.
   The JavaFX UI is the largest single work item and has no oracle.
4. **The best-tested domains (GST, TDS/TCS, payroll calc, posting, permissions)
   are the safest to port** and should be scheduled where their test suites can
   be reused as the specification.

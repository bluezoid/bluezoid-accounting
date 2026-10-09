# P4 — Plan → Module Matrix (B-7)

**Status:** ✅ **APPROVED 2026-09-01** (B-7 resolved)
**Date:** 2026-09-01

> This is a **recommendation**. The commercial matrix is your decision. Nothing
> here is implemented, and the technical architecture works with any matrix that
> respects the dependency rules in §3.

---

## 1. Four distinct concepts

| Concept | Owner | Example |
|---|---|---|
| **Technical module** | engineering | `accubook-inventory` |
| **Commercial plan** | business | "Professional" |
| **Entitlement** | licence server | this customer may mutate `inventory` |
| **Physical build inclusion** | build system | the Basic image omits inventory code |

A plan *maps to* a module set. It is not the same thing. A customer can be
entitled to a module their build does not contain (they need a new installer),
and can have a module installed they are not entitled to (`READ_ONLY`).

## 2. Module classification, from the reference inventory

| Classification | Modules | Why |
|---|---|---|
| **Mandatory kernel** — never optional | `core`, `accounting`, `parties`, `reporting-core` | `Organization` has 40 back-relations; `Voucher.fiscalYearId`/`.createdById`/`.voucherTypeId` are non-nullable; posting resolves ledger groups by name and **throws** if absent; `Ledger.partyId` is part of the chart of accounts; trial balance is pure GL |
| **Cross-cutting infrastructure** — always present, never sold | licensing, persistence, migrations, audit, backup-local, records viewer | Not features |
| **Near-core optional** | `sales-invoicing`, `purchase-billing`, `inventory` | Technically removable; a services business genuinely needs no inventory |
| **Optional, posts to the GL** | `banking`, `manufacturing`, `payroll`, `tally-import` | Removal must never delete their vouchers |
| **Leaf optional** | `sales-orders`, `taxation-india`, `hr`, `budgets`, `approvals`, `notifications`, `ocr`, `recurring`, `reporting-extended` | Nothing depends on them |
| **Add-on** (sold separately, any plan) | `backup-cloud` | Commercially independent of tier |

## 3. Dependency constraints the matrix MUST respect

These are technical facts, not preferences. A matrix violating them produces a
build that cannot be assembled.

```
manufacturing  ──requires──►  inventory        (BOM/WorkOrder itemId NOT NULL)
sales-orders   ──requires──►  inventory        (SalesOrderItem.itemId NOT NULL)
purchase-orders──requires──►  inventory        (PurchaseOrderItem.itemId NOT NULL)
payroll        ──requires──►  hr
taxation-india ──requires──►  sales-invoicing + purchase-billing
reporting-ext  ──degrades-with──► inventory, payroll   (not a hard requirement)
```

`sales-invoicing` does **not** require inventory — `InvoiceItem.itemId` is
nullable, verified.

## 4. RECOMMENDED MATRIX — for your approval

| Module | Basic | Professional | Enterprise | Notes |
|---|:--:|:--:|:--:|---|
| `core` | ✓ | ✓ | ✓ | **Mandatory kernel** |
| `accounting` | ✓ | ✓ | ✓ | **Mandatory kernel** |
| `parties` | ✓ | ✓ | ✓ | **Mandatory kernel** |
| `reporting-core` | ✓ | ✓ | ✓ | **Mandatory** — trial balance, pure GL |
| `sales-invoicing` | ✓ | ✓ | ✓ | A business that cannot invoice cannot trade |
| `purchase-billing` | ✓ | ✓ | ✓ | Symmetric to sales |
| `reporting-extended` | ✓ | ✓ | ✓ | P&L and balance sheet are table stakes |
| `taxation-returns` | **add-on** | **add-on** | ✓ | ✅ **B-7 RESOLVED** — purchasable at any tier. GST/TDS *computation* is kernel (§5) |
| `inventory` | ✗ | ✓ | ✓ | Services businesses do not need it |
| `sales-orders` | ✗ | ✓ | ✓ | Requires inventory |
| `banking` | ✗ | ✓ | ✓ | Reconciliation is a step up |
| `hr` | ✗ | ✗ | ✓ | |
| `payroll` | ✗ | ✗ | ✓ | Requires `hr`; also gated on B-5 |
| `manufacturing` | ✗ | ✗ | ✓ | Requires inventory |
| `budgets` / cost centres | ✗ | ✗ | ✓ | Non-functional in the reference — new build |
| `approvals` | ✗ | ✗ | ✓ | Multi-user concern |
| `ocr` | ✗ | ✗ | ✓ | Has per-document running cost |
| `tally-import` | ✓ | ✓ | ✓ | **Onboarding** — every customer migrating in needs it |
| `recurring` | ✗ | ✓ | ✓ | |
| `notifications` | ✓ | ✓ | ✓ | Cheap, improves every tier |
| `backup-cloud` | add-on | add-on | ✓ | **Add-on** — sold separately |

## 5. ✅ B-7 RESOLVED — taxation split three ways

Analysis (`B7_TAXATION_ANALYSIS.md`) showed "taxation" is **not one module**:

| Piece | Size | Tier |
|---|---|---|
| **GST computation** (`india-tax.ts`) | 176 lines | **KERNEL** — the invoice write path and GL posting both use it |
| **TDS/TCS computation** | ~661 lines | **KERNEL** — `post-bill.ts` imports `computeTds` and posts TDS Payable |
| **Return generation** (GSTR-1/2B/3B/9, CMP-08, e-invoice, e-way bill) | ~3,615 lines | **ADD-ON at any tier** |

**Decision: `taxation-returns` is an add-on**, purchasable on Basic or
Professional and included in Enterprise.

Rationale: customers genuinely differ. A trader who files their own returns can
buy Basic + returns; one whose CA files for them need not pay for an engine they
will not use. Tiering it would force both into the wrong package.

**Every build records GST and TDS correctly.** Only *return generation* is
gated. So the honest product statement is:

> Every plan records GST correctly. The returns add-on files it for you.

## 6. Two smaller decisions

**`tally-import` in Basic?** I recommend yes: it is how a customer's data gets
*in*. Withholding the import path from the cheapest tier makes onboarding harder
for the customers most likely to churn. It is also small.

**`backup-cloud` as an add-on rather than a tier feature?** Recommended, because
it has an ongoing storage cost and customers on any tier may want it. Note the
honest consequence, already documented: **without it, a destroyed machine means
destroyed books**, so it should be offered prominently rather than buried.

## 7. What is NOT decided here

Pricing · plan names · whether custom per-customer builds are offered ·
whether modules are sold à la carte in addition to plans (the architecture
supports it; pricing and the admin portal must too).

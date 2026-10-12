---
name: architecture-decisions
description: The settled architectural decisions and the three locked rules
metadata:
  type: project
---

# Architecture decisions

## Locked rules (binding on every phase)

1. **Entitlement enforcement starts in P4 and lives in the domain layer.**
   Chain: physical module → installed? → entitlement → subscription valid? →
   operation allowed? → write/read-only. **Write protection is enforced in the
   application/domain layer, never only by hiding buttons in JavaFX.**
   Reads are **never** entitlement-gated.
2. **Perpetual costing comes after document flows.**
   Items/Warehouses → Purchase → Sales Invoice → Dispatch/Issue → Costing →
   COGS → Accounting. Masters land P7, engine P8.
3. **Testing is continuous.** Every phase: implementation → unit → integration →
   UI → concurrency/security → regression → exit. **P16 confirms; it never
   discovers.**

## Approved decisions

| Area | Decision |
|---|---|
| **B-3 money** ✅ | Scaled INTEGER + `BigDecimal` + STRICT tables. Scales: money 4, rate 2 (basis points), FX 8, conversion 6, days 1 |
| **B-6 topology** ✅ | One `company.db` per company + `app.db` for installation state |
| Build tool (N-1) ✅ | Maven (installed; Gradle is not) |
| Layering | Presentation → Application → Domain → Infrastructure, with a **sealed posting core** |
| Dates | `LocalDate` for accounting dates — no zones |
| Enums | Java enums + SQLite `CHECK` constraints |
| Modules | Code packaged per plan + **universal schema** + **signed entitlement** |
| Downgrade | Code removed, schema kept, **data never deleted** |
| Year-end | **Real closing voucher** (reversed the original Phase 0 leaning) |
| Inventory | **Perpetual + persisted cost layers** (also a reversal) |
| Encryption at rest | OS-level for v1; Willena fork is the upgrade path |

## Why universal schema
"Install a module's tables only when the module ships" fails five ways: downgrade
would drop tables (data loss); migrations become 2ⁿ shapes; cross-module foreign
keys break; re-upgrade cannot restore dropped data; historical reports fail.
Empty tables cost kilobytes and buy lossless downgrade plus one linear migration
path.

## Self-containment rule
A company file must contain everything needed to interpret its own books
(its own voucher types, units, roles). That is what makes "hand it to your CA"
and "restore on a new machine" work. Posted documents store **resolved** tax
figures **plus** the rule-pack version, so history never needs recomputation.

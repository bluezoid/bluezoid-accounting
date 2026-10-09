# P4 — Module Dependency Rules

**Status:** APPROVED ARCHITECTURAL DECISION (pending your sign-off)
**Date:** 2026-09-05

---

## 1. The layering

```
                    accubook-launcher          (composition root)
                            │
              ┌─────────────┴─────────────┐
              │                           │
      accubook-ui-shell            accubook-app          ← ONLY module that
              │                           │                 requires persistence
              └─────────────┬─────────────┘
                            │
                    accubook-module-api        ← the contract feature modules see
                            │
                    accubook-platform          ← money, ids; depends on nothing

      accubook-licensing ──► module-api        (provides ModuleStateProvider)
      accubook-persistence ──► platform
      accubook-feature-* ──► module-api, platform, accounting-port
```

## 2. Allowed and forbidden

| Edge | Allowed? | Enforcement |
|---|---|---|
| feature → `module-api` | ✅ | — |
| feature → `platform` | ✅ | — |
| feature → accounting **port** | ✅ | interface only |
| **feature → `licensing`** | ❌ | JPMS: feature does not `requires` it |
| **feature → `persistence`** | ❌ | JPMS + persistence guard (§4) |
| **feature → another feature** | ❌ | via a port, never directly |
| **`ui-shell` → feature impl** | ❌ | shell discovers via `ServiceLoader` |
| **`licensing` → feature** | ❌ | licensing knows only module *ids* |
| **feature → network/server** | ❌ | no `requires java.net.http` |
| `module-api` → `licensing` | ❌ | **already enforced and tested** (P3) |

## 3. 🔴 What JPMS actually enforces — measured, not assumed

Tested this session with a throwaway project.

**Compile time — JPMS refuses.**
```
error: package com.p is not visible
  (package com.p is declared in module p.persist,
   but module p.feat does not read it)
```
A feature module that does not `requires accubook.persistence` **cannot compile**
a direct call to it. This is stronger than anything the reference application
could enforce, where every one of 110 route files hand-wrote its own scoping.

**Runtime — reflection defeats it if the module is on the path.**
```
A: persistence module present  → reflection SUCCEEDED
B: persistence module absent   → ClassNotFoundException
```

**This is the honest limit, and it changes the design.** Since `accubook-persistence`
must be on the module path for the application to function at all, a feature
module *could* reach it reflectively.

**Consequence:** JPMS prevents **accidental** coupling — a developer cannot
casually call the database from a feature module and have it compile. It does not
prevent **deliberate** bypass. Therefore **the persistence guard (§4) is
load-bearing, not belt-and-braces**, and the P4 design must not present JPMS as
the security boundary.

## 4. The persistence guard

The write path refuses inserts and updates to tables owned by a non-`ACTIVE`
module, regardless of how the caller arrived.

It exists because both layers above it are fallible:
- the use-case interceptor depends on a developer remembering an annotation, and
  the reference proves that fails — **55 of 71 mutating routes forgot** their
  permission check;
- JPMS is defeatable by reflection (§3).

`FeatureModule.ownedTables()` — which has existed unused since P1 — becomes the
mapping the guard consults.

## 5. Enforcing the rules

| Rule | How |
|---|---|
| No feature → licensing | JPMS + an architecture test asserting no feature module descriptor requires `accubook.licensing` |
| No feature → persistence | JPMS + persistence guard + architecture test |
| Shell imports no feature | JPMS (already true) + test |
| No cycles | Build-time graph validation before compiling |
| No feature → network | Architecture test on module descriptors |

Architecture tests read `ModuleDescriptor.requires()` at runtime — the same
technique already proven in P3's `ModuleIsolationTest`.

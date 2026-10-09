# P4 — Module Architecture

**Status:** APPROVED ARCHITECTURAL DECISION (pending sign-off)
**Date:** 2026-09-05 · **Extends:** ADR-002

---

## 1. Three concepts, never collapsed

```
PHYSICAL    what the build packaged      → jlink image contents
INSTALLED   what this runtime can see    → ServiceLoader discovery
ENTITLED    what the licence permits     → Ed25519-signed entitlement (P3)

effective = INSTALLED ∩ ENTITLED
```

`module installed ≠ module entitled`. Conflating them is what makes a manifest
into an authorization source, which §3 shows is unsafe.

## 2. The four module states

| State | Installed | Entitled | Read | Write | Situation |
|---|---|---|---|---|---|
| `ACTIVE` | ✅ | ✅ | ✅ | ✅ | Normal |
| `READ_ONLY` | ✅ | ❌ | ✅ | ❌ | Downgraded, expired, suspended — **code present** |
| `DORMANT` | ❌ | either | via generic viewer | ❌ | **Code absent**, data may exist |
| `ABSENT` | ❌ | ❌ | ❌ | ❌ | Never used |

### P4-3 — why `DORMANT` and `READ_ONLY` stay separate

They describe different customer situations and require different messages:

- `READ_ONLY` — *"Payroll is not in your current plan. Your records are here; you
  can view and export them."* The real payroll screens can render, disabled.
- `DORMANT` — *"Payroll is not installed in this build. Your historical records
  are available in the records viewer."* The payroll screens **do not exist**.

Collapsing them to `UNAVAILABLE` would force one message to cover both, and would
make the application claim a UI it does not have. **Keep both.**

### State transitions

```
ACTIVE ──entitlement lost (downgrade/expiry/suspension)──► READ_ONLY
       ──new build without the module──────────────────────► DORMANT
READ_ONLY ──entitlement restored──► ACTIVE
          ──new build without the module──► DORMANT
DORMANT ──build with module + entitled──► ACTIVE
        ──build with module, not entitled──► READ_ONLY
ABSENT ──first entitlement + install──► ACTIVE
```

**No transition deletes data.** Ever.

## 3. P4-2 — the manifest is diagnostic only, and deliberately unsigned

### Why signing it would be a false guarantee

Signing implies the signed value is trusted. **It is not, and must not be.**

Measured this session:

```
img_basic/bin/java --module-path mods --add-modules ex.impl ...  →  [payroll]
```

A user editing the jpackage launcher config can put a module on the path. If the
manifest were the authority, that module would resolve `ACTIVE` and a paid
feature would be unlocked. Because the **entitlement** is the authority and
`effective = installed ∩ entitled`, the smuggled module resolves `READ_ONLY`
instead — it gains nothing.

So the manifest answers *"what am I running?"* for support, and nothing else.
Signing it would:
1. suggest to a future maintainer that it is safe to trust — the dangerous
   outcome;
2. add key management for a value with no security role;
3. protect nothing, since the real gate is elsewhere.

**Decision: unsigned, diagnostic, never consulted for permission.**

## 4. P4-1 — the generic records viewer

Ships in the base build. It reads module-owned tables through **declarative view
metadata**, not module code.

**Constraints, all testable:**
- never executes module business logic (it has none to execute)
- never creates, updates or deletes
- never requires the dormant module to be installed
- opens through the same read path as any other query, so it cannot become a
  write bypass
- states plainly that the feature is unavailable for editing

**Why a viewer rather than shipping all code:** shipping everything would
contradict physical packaging, and the customer would see a full payroll UI they
cannot use — worse than an honest read-only record list. **Why not export-only:**
"view your history" is a materially better promise than "export a CSV", and the
renderers are small; the *engines* are the bulk.

## 5. `FeatureModule` — extending the P1 stub

| Member | Status | Purpose |
|---|---|---|
| `id()`, `displayName()` | exists | identity |
| `navigationOrder()` | exists | shell ordering |
| `ownedTables()` | exists, **unused** | **becomes the persistence guard's map** |
| `version()` | new | manifest + compatibility |
| `dependsOn()` | new | build-time graph validation |
| `navigationEntries()` | new | shell renders without importing features |
| `requiredSchemaVersion()` | new | module refuses an older schema |
| `historicalViews()` | new | declarative metadata for the dormant viewer |

**Nothing licensing-shaped.** A module still sees only `ModuleState`.

## 6. Discovery — measured behaviour

`ServiceLoader` over `FeatureModule`:

| Build | Result |
|---|---|
| Module present | discovered |
| Module physically absent | **`[]` — no exception** |
| Verified inside a jlink image | ✅ both cases |
| Module jar copied into an image | **ignored** — images are linked, not scanned |

The shell renders navigation from what it finds. **No `if (plan == ...)` anywhere.**

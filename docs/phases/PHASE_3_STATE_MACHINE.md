# Entitlement & Subscription State Machine

**Status:** ✅ APPROVED 2026-09-03 — binding specification for implementation
**Resolves:** the P3-1 ambiguity flagged in review

---

## 1. The ambiguity, and how it is resolved

`PHASE_3_DESIGN.md` §4 drew grace **after** the subscription end date:

```
... ACTIVE ── T-30 ──► EXPIRING ── T ──► GRACE ── +14d ──► READ_ONLY
```

That reads as *"expiry does not make the application read-only for another 14
days"*, which contradicts the core requirement:

> Subscription expired → application becomes READ-ONLY.

**Resolution: the 14 days sit BEFORE the subscription end date, not after it.**

- **Days T−14 → T** are a **pre-expiry renewal window**: the subscription is
  still active, the application is fully functional, and warnings escalate.
- **At T the subscription has expired.** The application becomes **READ-ONLY
  immediately.** There is no post-expiry functional period.

The word "grace" is deliberately **not** used for a state in the implementation,
because it invites exactly this confusion. The pre-expiry window is called
`RENEWAL_WINDOW`.

**No commercial behaviour has been invented beyond the stated requirement.**

---

## 2. States

| State | Mutations | Read | Export | Meaning |
|---|---|---|---|---|
| `UNLICENSED` | ❌ | ❌ | ❌ | Never activated; activation screen only |
| `ACTIVE` | ✅ | ✅ | ✅ | Subscription current |
| `RENEWAL_WINDOW` | ✅ | ✅ | ✅ | Within 14 days of expiry — **fully functional**, warnings shown |
| `EXPIRED` | ❌ | ✅ | ✅ | Past the end date — **READ-ONLY** |
| `SUSPENDED` | ❌ | ✅ | ✅ | Administratively suspended — **READ-ONLY** |
| `INVALID` | ❌ | ✅ | ✅ | Entitlement missing, corrupt, tampered, or wrong machine — **READ-ONLY**, reactivation required |

**Only `ACTIVE` and `RENEWAL_WINDOW` permit mutation. Every other state is
read-only.**

`RENEWAL_WINDOW` is functionally identical to `ACTIVE`; it exists solely so the
UI can warn. It is not a grant of extra time.

---

## 3. Transitions

```
                    ┌──────────────┐
                    │  UNLICENSED  │
                    └──────┬───────┘
                           │ activate (online or offline code)
                           ▼
                    ┌──────────────┐
        ┌──────────►│    ACTIVE    │◄─────────── renew ───────────┐
        │           └──────┬───────┘                              │
        │                  │ now >= end − 14d                     │
        │                  ▼                                      │
        │        ┌───────────────────┐                            │
        └─ renew ┤  RENEWAL_WINDOW   │── renew ───────────────────┤
                 │  (still writable) │                            │
                 └─────────┬─────────┘                            │
                           │ now > end                            │
                           ▼                                      │
                    ┌──────────────┐                              │
                    │   EXPIRED    │───────── renew ──────────────┤
                    │ (READ-ONLY)  │                              │
                    └──────────────┘                              │
                                                                  │
   ACTIVE / RENEWAL_WINDOW ── admin suspends ──► SUSPENDED ────────┤
                                                (READ-ONLY)       │
                                                                  │
   any state ── signature/binding/parse failure ──► INVALID ───────┘
                                                   (READ-ONLY)      reactivate
```

### Rules

1. **Evaluated on load and every 24 hours**, never per business operation.
2. Expiry uses `effectiveNow = max(systemNow, lastKnownGoodTime)`, so a backward
   clock change cannot defer it.
3. A state change mid-session takes effect at the next evaluation. A transaction
   already in progress completes; the next one is refused.
4. **Read and export are permitted in every state except `UNLICENSED`.** The
   customer's books are their property.
5. **No state ever deletes, modifies, migrates or locks away company data.**
   `INVALID` means "reactivate", never "your data is gone".

---

## 4. Warning schedule (RENEWAL_WINDOW only)

| Days remaining | Presentation |
|---|---|
| 14 → 8 | Dismissible banner |
| 7 → 2 | Persistent banner |
| 1 | Banner + one dialog on first launch that day |
| 0 (expired) | Read-only notice with renewal instructions |

Never interrupt data entry. Never a modal the user cannot dismiss while working.

---

## 5. Worked example

Subscription `2026-04-01 → 2027-03-31`.

| Date | State | Can post? |
|---|---|---|
| 2026-06-15 | `ACTIVE` | ✅ |
| **2027-03-17** | `RENEWAL_WINDOW` (14 days out) | ✅ **yes — fully functional** |
| 2027-03-31 | `RENEWAL_WINDOW` (last day) | ✅ |
| **2027-04-01** | `EXPIRED` | ❌ **read-only** |
| 2027-04-02 renewed to 2028-03-31 | `ACTIVE` | ✅ |

The customer gets 14 days of *warning*, not 14 days of *extra use*.

---

## 6. Module state contract (what P4 consumes)

```java
ModuleState moduleState(String moduleId);   // ACTIVE | READ_ONLY | DORMANT | ABSENT
```

| Subscription state | Entitled + packaged | Entitled, not packaged | Not entitled |
|---|---|---|---|
| `ACTIVE` / `RENEWAL_WINDOW` | `ACTIVE` | `DORMANT` | `READ_ONLY` |
| `EXPIRED` / `SUSPENDED` / `INVALID` | **`READ_ONLY`** | `DORMANT` | `READ_ONLY` |
| `UNLICENSED` | `READ_ONLY` | `DORMANT` | `READ_ONLY` |

`ABSENT` means the module has no data and never did.

**P4 sees only this method.** No module imports a licence type, a signature, a
fingerprint or an activation client. Subscription expiry reaches a module purely
as `READ_ONLY`.

# Subscription & Entitlements

**Status:** PROPOSED — awaiting sign-off
**Companion to:** LICENSING_AND_ACTIVATION.md · MODULE_ARCHITECTURE.md

---

## 1. The five separated concepts

Collapsing any of these into one database field causes pain later — mid-term
upgrades, custom plans, machine replacement all become special cases.

```
CUSTOMER      who bought it
  └── LICENCE       identity of one installation   (ACB7-X92K-PQ41-M8ZT)
        ├── MACHINE       the bound device
        └── SUBSCRIPTION  a time window with a PLAN
              └── PLAN → MODULES → ENTITLEMENT (signed, time-bounded)
```

## 2. Subscription state machine

```
                    ┌──────────┐
                    │ PENDING  │  licence issued, never activated
                    └────┬─────┘
                         │ payment marked + first activation
                         ▼
   ┌──────────────► ┌──────────┐ ──────────────┐
   │                │  ACTIVE  │               │ admin suspends
   │  renewal       └────┬─────┘               ▼
   │                     │ T−30          ┌────────────┐
   │                     ▼               │ SUSPENDED  │
   │              ┌─────────────┐        └─────┬──────┘
   │              │ EXPIRING    │              │ admin reactivates
   │              │ (warnings)  │              │
   │              └──────┬──────┘              │
   │                     │ expiry date         │
   │                     ▼                     │
   │              ┌─────────────┐              │
   └──────────────┤   GRACE     │              │
   │              │ (still full)│              │
   │              └──────┬──────┘              │
   │                     │ grace ends          │
   │                     ▼                     │
   └──────────────┌─────────────┐ ◄────────────┘
                  │  READ_ONLY  │
                  └──────┬──────┘
                         │ customer cancels / offboards
                         ▼
                  ┌─────────────┐
                  │   CLOSED    │  data still local, still exportable
                  └─────────────┘
```

### State definitions

| State | Create/edit/post | Read | Export | Backup | Notes |
|---|---|---|---|---|---|
| `PENDING` | ❌ | ❌ | ❌ | ❌ | Activation screen only |
| `ACTIVE` | ✅ | ✅ | ✅ | ✅ | Normal operation, fully offline |
| `EXPIRING` | ✅ | ✅ | ✅ | ✅ | Identical to ACTIVE + warnings |
| `GRACE` | ✅ | ✅ | ✅ | ✅ | Deliberately still fully functional |
| `SUSPENDED` | ❌ | ✅ | ✅ | ❌ | Admin action (e.g. non-payment) |
| `READ_ONLY` | ❌ | ✅ | ✅ | ❌ | Post-expiry resting state |
| `CLOSED` | ❌ | ✅ | ✅ | ❌ | Offboarded; data remains the customer's |

**Export works in every state where the app opens at all.** The customer's books
are their property; withholding them is both wrong and a reason to crack the
software.

## 3. Warning schedule

| Trigger | Presentation |
|---|---|
| T−30 days | Dismissible banner: "Your subscription expires in 30 days." |
| T−14 | Persistent banner |
| T−7 | Banner + one dialog on first launch of the day |
| T−1 | Dialog on launch |
| T (expiry) | Enters GRACE; clear notice of the grace end date |
| Grace end | READ-ONLY notice with renewal instructions |

Rules: **never interrupt data entry**; never a modal the user cannot dismiss
while working; always show exactly what will stop working and when.

## 4. Grace period — why it exists

Recommend **14 days, fully functional** (signed into the entitlement so it cannot
be edited locally).

Renewals are late for ordinary commercial reasons — an invoice in a payables
queue, a holiday, a bank delay. Locking an accountant out during a GST filing
window over a late payment is a support disaster and a reputational one, and it
converts a routine renewal into an emergency. The cost of 14 days of grace is
negligible; the cost of the alternative is not.

**DECISION REQUIRED** — 14 days is a recommendation, not a given.

## 5. Entitlement token

Signed by the server (Ed25519), verified by the client, stored locally.

```
licence_id, customer_id, plan
modules[]                         entitled module ids
subscription_state                ACTIVE | SUSPENDED | ...
subscription_start, subscription_end
grace_period_days
machine_binding_hash
issued_at, token_id               replay protection
rule_pack_min_version
— signature —
```

**Verified:** at startup, on company open, and before every mutating use case
(cheap — a signature check on cached bytes, not a network call).

**Replay protection:** the client refuses a token whose `issued_at` is older than
the one it holds, so an old longer-lived or higher-plan token cannot be
re-injected.

## 6. Expiry evaluation — clock-tamper resistant

Local system time is attacker-controlled. Expiry is evaluated as:

```
effective_now = max(system_now, last_known_good_time)
```

where `last_known_good_time` is a monotonically advancing, tamper-evident value
persisted in `app.db` (integrity-protected with a device-bound key) and mirrored
into each company database. A backward clock jump is detected, warned about, and
**cannot defer expiry**. Accumulated runtime from a monotonic source provides an
independent floor.

Forward drift and legitimate DST/timezone changes are tolerated via a skew
allowance, so ordinary clock correction never trips a warning.

**Explicitly not doing:** a mandatory "phone home every N days". Offline
operation for the full subscription period is a core promise, and accountants
work in places with poor connectivity.

## 7. Renewal

```
Customer pays company
        ↓
Admin marks payment received (manual initially)
        ↓
Subscription extended in the platform DB
        ↓
Customer clicks "Check Licence / Renew"  (or automatic retry when online)
        ↓
Server issues a fresh signed entitlement
        ↓
State returns to ACTIVE
```

**An offline renewal path is required, not optional.** Some customers have no
internet on the accounting machine at all. The app emits a short request code;
the admin portal produces a signed response string; the customer types it in.
Without this, a genuinely offline customer cannot renew.

## 8. Upgrade and downgrade mid-term

| Change | Effect on data | Effect on code | Effect on entitlement |
|---|---|---|---|
| **Upgrade** (BASIC → PRO) | none | new installer needed for new modules | immediate on refresh |
| **Downgrade** (PRO → BASIC) | **none — never deleted** | new installer removes module code | modules → READ_ONLY, then DORMANT after the new build |

An upgrade can be entitled **before** the new build is installed. The app then
reports "these modules are in your plan but not in this build — install the
PROFESSIONAL installer". Effective capability is
`build_modules ∩ entitled_modules`.

**Proration, refunds and mid-term plan changes are commercial policy** —
**DECISION REQUIRED** — but the data model (separate Subscription and Payment
entities) already supports them without reshaping.

## 9. What is enforced where

| Check | Location | Failure mode |
|---|---|---|
| Signature valid | Client, on load | Refuse to start → re-activation |
| Machine matches | Client, on load | Grace + re-bind prompt |
| Not expired | Client, every mutating use case | READ-ONLY |
| Module entitled | Use-case interceptor + persistence guard | Typed error, explained in UI |
| Licence not revoked | Server, on each refresh | Next refresh downgrades state |

## 10. Failure modes and their handling

| Situation | Behaviour |
|---|---|
| Entitlement file deleted/corrupt | Re-activate (online) — **company data untouched** |
| Clock set back | Detected; expiry evaluated against the anchor; warned |
| Server unreachable at renewal | Stay in current state; retry; offline path available |
| Machine hardware changed | Fuzzy match; auto re-bind if close; otherwise support |
| Subscription expired mid-session | Finish the current transaction, then read-only |
| Suspended by admin while offline | Takes effect at the next entitlement refresh |

**Overriding rule: no licensing failure may destroy, lock away, or corrupt
accounting data. The worst outcome is read-only, and export always works.**

# Company Admin Portal & Platform Backend

Status: PROPOSED — awaiting sign-off
Addresses brief §12 (admin portal), §27 (portal architecture), §29 (data
lifecycle), §31 (admin audit).

There is **no existing admin portal**. This is entirely new.

---

## 1. Strict separation from customer accounting data

```
Desktop App  ──HTTPS──►  Activation / Licence API
                              │
                         Platform Backend
                              │
                         Platform DB  ◄──── Admin Portal (internal, staff-only)
```

**The platform database contains no customer accounting data.** It holds
customers, licences, machines, plans, modules, subscriptions, payments, versions,
backup *metadata*, and audit. Books live only on customer machines and (encrypted)
in object storage we cannot read.

This is not merely tidy layering — it limits the blast radius of a platform
breach to commercial metadata, and it is what makes the backup-privacy claim in
BACKUP_ARCHITECTURE true rather than aspirational.

## 2. Platform data model

```
Customer         org name, contact, GSTIN, status, onboarding date
Plan             BASIC / PROFESSIONAL / ENTERPRISE / custom
Module           catalogue of capabilities
PlanModule       which modules a plan includes
Licence          key hash + prefix, customer, plan, status, issued/expiry
Machine          binding hash, first/last seen, OS, app version, status
Activation       licence × machine × time, result, ip/origin
Subscription     period start/end, plan, status, renewal history
Payment          amount, method, reference, marked-by, marked-at  (manual first)
AppVersion       version, channel, min-supported, artifact hashes, release notes
RulePack         statutory rule packs and effective dates
BackupMetadata   licence, company id, timestamp, size, status  (NEVER contents)
AdminUser        staff accounts, roles, MFA
AdminAuditLog    append-only record of every sensitive action
```

**Entity separation matters** (brief §28): plan ≠ module ≠ entitlement ≠ licence
≠ machine ≠ subscription. Collapsing any pair into one field is what makes later
changes (custom plans, mid-term upgrades, machine replacement) painful.

## 3. Admin capabilities

Customer & licensing: create customers; generate/assign licences; view status;
disable a machine; issue replacement activation; suspend/reactivate.
Subscription: mark payment received; activate; renew; upgrade; downgrade;
extend (goodwill).
Catalogue: manage plans, modules, plan-module mapping.
Releases: publish app versions and rule packs; set minimum supported version.
Support: view activation history, backup status, diagnostics the customer sends.

**Payment is manually marked initially** (brief §12), but the model already
separates `Payment` from `Subscription`, so a gateway can later write `Payment`
rows without reshaping anything.

## 4. Admin security — this is the crown jewels

The portal can grant entitlement and disable customers' installations. Treat it
accordingly:

- **MFA mandatory** for every admin account. No exceptions.
- Least privilege: support staff can view and reset machines; only senior staff
  can issue licences or change plans.
- **Every sensitive action is audited**: who, what, when, from where, why (a
  reason field, required for destructive actions).
- **The audit log is append-only** — no UPDATE or DELETE grant on that table for
  the application role. Exported to write-once storage periodically.
- **The Ed25519 signing key is the single most sensitive secret in the business.**
  It should live in an HSM or a managed KMS, never in application config, and
  signing should be an isolated service call. If it leaks, every licence in the
  field can be forged and the only remedy is a key rotation that older app
  versions cannot accept — which is why LICENSING §10 recommends embedding two
  public keys from day one.
- Rate limiting and anomaly alerting on the activation API (mass activation
  attempts, one binding hash appearing from many origins).

## 5. API surface

**Public (desktop-facing)** — minimal, hardened, rate-limited:
`POST /v1/activate`, `POST /v1/entitlement`, `GET /v1/updates/check`,
`GET /v1/rulepacks/latest`, `POST /v1/backup/credentials` (short-lived scoped),
`POST /v1/diagnostics` (opt-in).

**Internal (admin)** — separate service or at minimum a separate network path
and auth realm. Never reachable with a customer credential.

TLS 1.3 throughout; certificate pinning on the desktop side (with a documented
rotation plan — see LICENSING §10).

## 6. Data lifecycle (brief §29)

```
Customer created → plan selected → payment confirmed → licence generated
  → installer generated → customer installs → activation → offline usage
  → [backup if module enabled] → renewal / upgrade / downgrade
  → [machine replacement] → [restore] → offboarding
```

**Offboarding deserves an explicit, humane policy.** When a customer leaves:
their books remain on their machine and remain exportable; cloud backups are
retained for a stated period then deleted; the licence is closed; platform
records are retained per legal/tax requirements. This must be written down
before the first customer, not improvised at the first cancellation.
**DECISION REQUIRED** on retention periods.

## 7. Open decisions

- Build the portal as a new app, or extend the existing Next.js codebase (which
  the team already knows)? Reusing the stack is attractive, but the platform DB
  must remain strictly separate. **DECISION REQUIRED.**
- Where installers are built and stored; whether per-customer installers are
  generated on demand or assembled from per-plan builds. Per-plan builds plus a
  licence that carries entitlement is simpler and is what ADR-002 assumes.
  **DECISION REQUIRED.**
- Hosting and jurisdiction for the platform DB (Indian data-residency
  expectations for customer commercial data). **DECISION REQUIRED.**

# Threat Model

**Status:** PROPOSED — awaiting sign-off
**Scope:** desktop application, activation/licence API, admin portal, cloud backup.

---

## 0. Stating the limit honestly, first

**A determined attacker with local admin rights on their own machine can defeat
any client-side licensing check.** Java bytecode is trivially decompilable, and
any verification the client performs can be patched out. This is not a solvable
problem, and pretending otherwise leads to obfuscation arms races that cost real
engineering, degrade crash diagnostics, trigger antivirus false positives, and
still fail.

**Realistic goals:**
1. Make casual copying, config editing and clock rollback **ineffective**.
2. Make circumvention require **deliberate, knowing** effort (which moves it from
   a technical problem to a legal/commercial one).
3. **Never** let protection break a paying customer's access to their own data.
4. Protect **customer data** — which is where the real duty of care lies, and
   which *is* fully achievable.

Priority order: **customer data protection > revenue protection.**

---

## 1. Assets

| Asset | Owner | Impact if compromised |
|---|---|---|
| Customer accounting data | Customer | **Catastrophic** — statutory records, commercially sensitive |
| Backup encryption key | Customer | Backups unreadable (loss) or readable (breach) |
| Ed25519 licence signing key | Company | **Catastrophic** — every licence forgeable |
| Entitlement tokens | Company | Revenue loss |
| Platform DB (customers, licences) | Company | Commercial data breach |
| Cloud storage credentials | Company | Cross-customer backup exposure |
| Admin portal access | Company | Full control of licensing |

---

## 2. Local attacker (has the customer's machine)

| # | Threat | Mitigation | Residual |
|---|---|---|---|
| L1 | **Copy the database file** to read another company's books | Per-company files with OS permissions (0600); OS disk encryption; optional SQLCipher | An admin user on that machine can read it. **Accepted** — it is their machine |
| L2 | **Modify the database directly** (e.g. edit a balance) | CHECK constraints, FKs, posting invariants re-verified on open; trial-balance assertion on open, migration and backup | Cannot be fully prevented; **detection** is the goal |
| L3 | **Edit the entitlement file** to grant modules | Ed25519 signature over the whole payload; client holds only the public key | Requires forging a signature — infeasible |
| L4 | **Replay an old entitlement** (longer period / higher plan) | `issued_at` + `token_id`; client refuses a token older than the one it holds | Requires patching the binary |
| L5 | **Set the system clock back** to extend a subscription | Monotonic time anchor; expiry evaluated against `max(now, last_known_good)`; monotonic runtime floor | Requires patching the binary |
| L6 | **Patch the executable** to skip verification | Code signing (detects tampering at OS level); **accepted as undefeatable** | **ACCEPTED RISK** — legal remedy |
| L7 | **Copy the whole install to another machine** | Machine binding (composite fingerprint); server detects one binding from two origins | Detected server-side at next contact |
| L8 | **Steal backup archives** from disk or cloud | AES-256-GCM, encrypted **before** leaving the machine; key never transmitted | Attacker needs the passphrase |
| L9 | **Read secrets from memory** | Not defended against | **ACCEPTED** — local admin wins |
| L10 | **Tamper with the local audit log** | Append-only by construction; no UPDATE/DELETE path; hash-chained entries | Detectable, not preventable |

**Note on L2:** an accounting product cannot prevent a determined local user from
editing their own SQLite file. What it *can* do is **detect** it — which matters
because the realistic threat is not the owner but a bookkeeper hiding a fraud
from the owner. Hash-chained audit entries plus invariant checks on open make
silent tampering hard.

---

## 3. Network attacker

| # | Threat | Mitigation |
|---|---|---|
| N1 | **MITM on activation** | TLS 1.3 + certificate pinning; the entitlement is signed independently of the transport, so a broken TLS session still cannot forge one |
| N2 | **Fake activation server** (DNS/hosts redirect) | Signature verification against the embedded public key — a fake server cannot sign |
| N3 | **Replay a captured activation response** | Nonce + `issued_at`; client refuses older tokens |
| N4 | **Steal a token in transit** | Tokens are machine-bound; useless elsewhere |
| N5 | **Intercept backup upload** | Payload encrypted before upload; TLS is a second layer |
| N6 | **Steal cloud credentials from the client** | Client never holds long-lived credentials — only short-lived, prefix-scoped tokens issued per operation |
| N7 | **DoS the activation API** | Rate limiting; **grace period means an outage does not lock customers out** |

**N7 deserves emphasis:** if the activation service is down, customers must keep
working. The offline-first design plus grace period means an outage is an
inconvenience, not an outage for every customer simultaneously.

---

## 4. Customer misuse

| # | Threat | Mitigation | Note |
|---|---|---|---|
| C1 | Install on a second machine | Machine binding; server detects duplicate bindings from different origins | Handle commercially first, technically second |
| C2 | Share a licence key | One licence = one active binding; second activation fails | Legitimate replacement goes through support |
| C3 | Clone a VM | Same binding from multiple origins in overlapping windows → flag for review | **Do not auto-lock** — false positives are expensive |
| C4 | Repeated "machine replacement" requests | Server-side rate limit (N/year), then manual approval | Audited |
| C5 | Restore a backup onto an unlicensed machine | Restore is gated by the **backup passphrase**, not the licence — deliberately, so a customer can always recover their own data | By design |

**C5 is a deliberate trade.** Tying restore to a licence would mean an expired
customer cannot recover their own books — unacceptable. Backup restore protects
*data confidentiality*, not revenue.

---

## 5. Company-internal risks

Often underweighted, and in practice the likeliest source of a serious incident.

| # | Threat | Mitigation |
|---|---|---|
| I1 | Admin accidentally changes/cancels a subscription | Confirmation for destructive actions; required reason field; full audit; easy reversal |
| I2 | Unauthorised admin access | **MFA mandatory**; least-privilege roles (support ≠ licensing ≠ finance); session limits |
| I3 | Rogue insider issues free licences | Append-only audit; anomaly alerting on issuance volume; periodic review |
| I4 | **Signing key compromise** | HSM/KMS, never in config; isolated signing service; **two public keys embedded from v1** so rotation is possible at all |
| I5 | Platform DB breach | No customer accounting data is stored there — by design; encryption at rest; minimal PII |
| I6 | Staff access customer backups | **Impossible by construction** — customer-only encryption key. Support genuinely cannot decrypt |
| I7 | Support social-engineered into a machine reset | Verification procedure; rate limits; audit; reset ≠ data access |

**I4 is the single worst case in the whole system.** Losing the signing key means
every licence in the field can be forged, and older app versions embed only the
old public key. Embedding **two** valid public keys from the first release is
cheap insurance and impossible to retrofit.

**I6 is a genuine architectural guarantee, not a policy promise** — and it should
be stated to customers exactly that way, because it is verifiable.

---

## 6. Data-protection controls (independent of licensing)

| Control | Rationale |
|---|---|
| App-data dir 0700, DB files 0600 | Other local users cannot read the books |
| No accounting data in logs | Logs get emailed to support |
| Redact secrets in diagnostics | Licence keys, tokens, passphrases, paths |
| Trial-balance + integrity checks on open, migration, backup | Detects corruption **and** tampering |
| Hash-chained audit log | Makes silent alteration detectable |
| Backup encrypted before leaving the machine | Cloud provider is untrusted by design |
| Network-path detection at company open | Prevents a real corruption vector |

---

## 7. Deliberate non-goals

- **No obfuscation / anti-debugging.** Cost > benefit; harms diagnostics; AV false
  positives.
- **No hardware dongles.** Disproportionate cost and support burden.
- **No hidden telemetry.** Activation and renewal contact the server; that is
  disclosed. Silent phone-home would be a trust and likely a legal problem.
- **No remote kill switch that destroys data.** Ever. Expiry means read-only.

---

## 8. Security testing requirements

| Test | Asserts |
|---|---|
| Tampered entitlement | Signature check fails, app refuses, **data untouched** |
| Replayed old entitlement | Rejected on `issued_at` |
| Clock rolled back 1 year | Expiry still enforced; warning raised |
| Entitlement copied to another machine | Binding check fails |
| Corrupt/deleted entitlement | Re-activation path; **no data loss** |
| Backup with wrong passphrase | Clean failure, no partial write |
| Tampered backup archive | GCM tag fails; refused |
| Expired subscription | Read-only; **export still works** |
| SQL injection on every input | Parameterised statements throughout |
| Path traversal on import/export/attachments | Rejected |
| Diagnostics export | Contains no keys, tokens or accounting data |
| Audit chain broken | Detected and reported |

# Backup & Restore Architecture

Status: PROPOSED — awaiting sign-off
Addresses brief §15 (cloud backup module), §16 (backup security), §30 (restore
on machine replacement).

---

## 1. Local backup is NOT optional — it ships in every build

The brief scopes *cloud* backup as a paid module. Local backup must be in the
base product regardless, because:

- Migration safety depends on a verified pre-migration backup (brief §17).
- A customer without the cloud module still deserves a route to protect their
  books; shipping an accounting product with no backup at all is indefensible.
- It costs almost nothing — it is the same code path minus the upload.

**Cloud backup = local backup + encryption + a `CloudStorageProvider` upload.**
The module boundary is the *upload and remote retention*, not backup itself.

## 2. Taking a consistent snapshot — the critical mechanic

**A filesystem copy of a live SQLite database is not a backup.** In WAL mode the
`.db` file alone can be stale or torn, because committed data may live in the
`-wal` sidecar. This is the single most common way people corrupt SQLite backups.

Use one of the two supported mechanisms:

- **SQLite Online Backup API** — copies a live database safely, incrementally,
  while other connections continue. Preferred.
- **`VACUUM INTO 'file'`** — writes a compacted, consistent copy in one
  statement. Simple, and it also defragments. Verified available in SQLite
  3.50.6 (see SQLITE_ENGINE_BEHAVIOR).

After producing the copy, **verify it before trusting it**:
1. Open the copy.
2. `PRAGMA integrity_check` → must return `ok`.
3. `PRAGMA foreign_key_check` → must return no rows.
4. Assert a domain invariant: **trial balance sums to zero** (total debits ==
   total credits across all posted entries). This catches logical corruption that
   a structural check cannot.
5. Record the row counts and schema version in the backup manifest.

Step 4 matters: it is the accounting-specific check that turns "the file opens"
into "the books are intact."

## 3. What is in a backup set

Per company: `company.db` snapshot + attachments + a manifest.
Optionally `app.db` (installation state) — **but never the licence private
state**, which is device-bound and must be re-established by activation.

```
backup-<company>-<utc-timestamp>.acbk
├── manifest.json          plaintext: format version, company id/name, schema
│                          version, app version, created_at, sizes, checksums,
│                          encryption params (algorithm, KDF, salt, nonce)
├── company.db.enc         encrypted, compressed
└── attachments.tar.enc    encrypted, compressed
```

The manifest is **deliberately plaintext** so a restore tool can identify a
backup, check compatibility, and prompt for the right key without decrypting
anything. It must contain **no accounting data** — company name is the most
sensitive field, and that is a conscious, documented trade (see §5).

## 4. Encryption — before it leaves the machine

**AES-256-GCM** (authenticated encryption — detects tampering, not just
protects confidentiality). Compress **before** encrypting (ciphertext does not
compress). Chunk large files so integrity failures localise and uploads resume.

**Key derivation.** The customer supplies a **backup passphrase**, separate from
their application login. Derive with **Argon2id** (memory-hard; resists GPU
cracking) with parameters recorded in the manifest so future parameter changes
stay decryptable.

### The key-custody question — brief §16, answered explicitly

The brief requires that *we* must not be able to read customer books merely
because they sit in our storage. That forces a hard trade:

| Model | Company can read backups | Customer loses passphrase | Verdict |
|---|---|---|---|
| **Customer-only key** | No | **Data unrecoverable** | Honest; matches the brief |
| Company escrow | Yes | Recoverable | Violates the brief's requirement |
| Split/threshold escrow | Only with customer consent | Recoverable with ceremony | Complex; possible later |

**Recommendation: customer-only key**, with these obligations on us:

1. At module activation, force passphrase creation with a **clear, unavoidable**
   warning: *lose this and your backups cannot be recovered by anyone, including
   us.* Require explicit acknowledgement.
2. Offer a printable **recovery sheet** at setup.
3. Show the truth in the UI everywhere backups appear.
4. Support must be **able** to say "we genuinely cannot decrypt this" — and that
   must be true, which is exactly what the brief asks for.

**What the company CAN see** (must be disclosed in the privacy policy): licence
id, company id, backup timestamps, sizes, counts, schema/app version, success
and failure. Sizes and timing are metadata that leak *something* (how big the
books are, when work happens); this is unavoidable for a hosted backup service
and should be stated rather than glossed over.

**DECISION REQUIRED:** confirm customer-only custody. It is the right call for
the stated requirement, but the support consequence — permanent data loss on a
forgotten passphrase — is a business decision that must be made knowingly, not
discovered later during an angry support call.

## 5. Storage provider abstraction

```java
interface CloudStorageProvider {
    void        put(String key, Path file, ProgressListener l) throws StorageException;
    InputStream get(String key) throws StorageException;
    List<RemoteObject> list(String prefix);
    void        delete(String key);
    boolean     supportsResumableUpload();
}
```

Cloudflare R2 first (S3-compatible), so an S3-compatible implementation covers
R2, AWS S3 and most others; Azure Blob can be added separately.

**Credentials must never ship in the client** (brief §21). The desktop app
requests **short-lived, scoped credentials** (or pre-signed URLs) from our
backend, valid only for that customer's key prefix. A leaked client binary must
not yield access to the bucket, nor to other customers' objects.

Key layout: `backups/<licence_id>/<company_id>/<timestamp>.acbk` — with
**server-side authorization** enforcing the prefix, never client-side.

## 6. Scheduling, retention, resilience

- Trigger: on a schedule, on demand, and **always before a schema migration**.
- Only upload when online and the subscription is active; queue otherwise.
- **Retention** (defaults, configurable): last 7 daily, 4 weekly, 12 monthly.
  Never auto-delete the only surviving backup.
- **Failure handling:** exponential backoff with jitter; resumable/multipart
  uploads; a failed upload must never leave a partial object that could be
  mistaken for a good backup (upload to a temp key, then atomically finalise).
- Surface backup health prominently — a silently-failing backup is worse than no
  backup, because it produces false confidence. "Last successful backup: 34 days
  ago" must be visible, not buried in a log.

## 7. Restore

Restore is **more dangerous than backup** — it overwrites live books.

```
select backup → read manifest → check schema/app compatibility
  → prompt for passphrase → decrypt+decompress to a TEMP location
  → verify (integrity_check, foreign_key_check, trial balance)
  → snapshot the CURRENT database to a safety copy
  → atomically swap into place → reopen → migrate if the backup is older
```

Rules:
- **Never decrypt directly over the live database.** Verify first, swap last.
- **Always snapshot the current state first**, even when the user is restoring
  over "bad" data — restores get chosen by mistake.
- A backup from a **newer** schema than the app must be **refused** with a clear
  message ("this backup was made with version X; please update"), never opened.
- An older backup is migrated forward through the normal migration path.
- Restoring into a *different* installation (machine replacement) must work —
  restore is bound to the **backup passphrase**, not to the machine binding.
- Restore is an **audited** event in `app.db`.

## 8. Testing requirements

Backup/restore is safety-critical and gets first-class tests:
- Round-trip fidelity: backup → restore → byte-identical data and row counts.
- Restore of a backup taken *during* concurrent writes (WAL consistency).
- Corrupted archive → detected by GCM tag, refused, live data untouched.
- Wrong passphrase → clean failure, no partial state.
- Older-schema backup → migrates forward correctly.
- Newer-schema backup → refused.
- Interrupted restore (power loss simulation) → live data intact.
- Interrupted upload → resumes or retries cleanly, no partial object.

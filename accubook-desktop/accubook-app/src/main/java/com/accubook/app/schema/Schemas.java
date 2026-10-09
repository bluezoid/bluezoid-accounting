package com.accubook.app.schema;

import com.accubook.persistence.migration.Migration;

import java.util.List;

/**
 * Schema migrations for the two database kinds.
 *
 * <p>Hand-authored SQL, forward-only, immutable once shipped. Phase 1 defines
 * only what the walking skeleton needs; the real accounting schema arrives in
 * Phase 2.
 *
 * <p>Every table is {@code STRICT}. SQLite otherwise applies type affinity, so a
 * column declared {@code DECIMAL(18,4)} silently stores a float - which is
 * exactly the failure decision B-3 exists to prevent. STRICT makes it impossible
 * to write a float into a money column at all.
 */
public final class Schemas {

    private Schemas() {
    }

    /**
     * {@code app.db}: installation state. Never customer accounting data.
     *
     * <p>Keeping licence and registry state out of the company files means
     * restoring last month's books cannot roll back a subscription, and copying
     * a company file to another machine does not carry an activation with it.
     */
    public static List<Migration> appDatabase() {
        return List.of(
                new Migration(1, "company registry and application metadata", """
                        CREATE TABLE company (
                            id              TEXT    NOT NULL PRIMARY KEY,
                            name            TEXT    NOT NULL,
                            schema_version  INTEGER NOT NULL DEFAULT 0,
                            created_at      INTEGER NOT NULL,
                            last_opened_at  INTEGER
                        ) STRICT;

                        CREATE UNIQUE INDEX idx_company_name ON company (name);

                        CREATE TABLE app_metadata (
                            key    TEXT NOT NULL PRIMARY KEY,
                            value  TEXT NOT NULL
                        ) STRICT;
                        """)
        );
    }

    /**
     * {@code company.db}: one company's books.
     *
     * <p>Phase 1 creates only company identity plus a money-bearing table that
     * exercises the {@code MoneyMapper} boundary end to end. The
     * {@code _e4} suffix is the convention from decision B-3: it keeps the
     * encoding visible so nobody reads a raw {@code 1234500} as rupees.
     */
    public static List<Migration> companyDatabase() {
        return List.of(COMPANY_V1_CORE, COMPANY_V2_POSTING_INVARIANTS,
                COMPANY_V3_ACCOUNT_GOVERNANCE, COMPANY_V4_PARTIES, COMPANY_V5_INVENTORY);
    }

    /**
     * Core accounting schema (decision P2-1: core only).
     *
     * <p>Module-specific schemas - GST, payroll, inventory - are deliberately
     * absent. Their design decisions (B-1, B-4, B-5) are still open, and
     * guessing at their tables now would mean migrating them again later.
     *
     * <p>This does not weaken ADR-002. Universal schema means a given release
     * ships every table regardless of the customer's plan; it does not mean
     * every table must exist in the first release. Module tables arrive by
     * ordinary forward migrations and are never plan-conditional.
     *
     * <h2>Conventions</h2>
     * <ul>
     *   <li>Every table {@code STRICT}, so a float can never be written into a
     *       money column (decision B-3).</li>
     *   <li>Money as scaled INTEGER with an {@code _e4} suffix, so the encoding
     *       is visible in any ad-hoc query.</li>
     *   <li>Enumerations as {@code CHECK} constraints. The reference application
     *       has none, and paid for it with two live defects.</li>
     *   <li>Accounting dates as ISO {@code TEXT}. A fiscal date is a calendar
     *       date, not an instant; storing it as one removes the timezone bug
     *       class that afflicts the reference's reports.</li>
     *   <li>Timestamps (audit, created_at) as epoch-millis INTEGER, because
     *       those genuinely are instants.</li>
     * </ul>
     */
    private static final Migration COMPANY_V1_CORE = new Migration(1, "core accounting schema", """
            -- ---------------------------------------------------------------
            -- Company identity (decision P2-4: the file identifies itself)
            -- ---------------------------------------------------------------
            -- Duplicated from app.db's registry on purpose. A company file must
            -- be interpretable on its own, so that "send my books to my CA" and
            -- "restore onto a new machine" work without the installation that
            -- created it.
            CREATE TABLE company_info (
                id                  TEXT    NOT NULL PRIMARY KEY,
                name                TEXT    NOT NULL,
                legal_name          TEXT,
                gst_no              TEXT,
                pan_no              TEXT,
                tan_no              TEXT,
                address_line1       TEXT,
                address_city        TEXT,
                address_state_code  TEXT,
                address_postal_code TEXT,
                country_code        TEXT    NOT NULL DEFAULT 'IN',
                base_currency_code  TEXT    NOT NULL DEFAULT 'INR',
                fiscal_year_start_month INTEGER NOT NULL DEFAULT 4
                    CHECK (fiscal_year_start_month BETWEEN 1 AND 12),
                created_at          INTEGER NOT NULL,
                updated_at          INTEGER NOT NULL,
                -- Exactly one row. A company database describes one company.
                singleton           INTEGER NOT NULL DEFAULT 1
                    CHECK (singleton = 1),
                UNIQUE (singleton)
            ) STRICT;

            -- ---------------------------------------------------------------
            -- Fiscal calendar
            -- ---------------------------------------------------------------
            -- Dates are ISO calendar dates. The reference derives the fiscal
            -- window from getMonth() >= 3 in server-local time, which is why its
            -- balance sheet and trial balance disagree for non-April companies.
            CREATE TABLE fiscal_year (
                id          TEXT    NOT NULL PRIMARY KEY,
                name        TEXT    NOT NULL,
                start_date  TEXT    NOT NULL,
                end_date    TEXT    NOT NULL,
                is_closed   INTEGER NOT NULL DEFAULT 0 CHECK (is_closed IN (0, 1)),
                closed_at   INTEGER,
                closed_by   TEXT,
                created_at  INTEGER NOT NULL,
                UNIQUE (name),
                CHECK (start_date < end_date),
                CHECK (is_closed = 0 OR closed_at IS NOT NULL)
            ) STRICT;

            CREATE INDEX idx_fiscal_year_range ON fiscal_year (start_date, end_date);

            -- ---------------------------------------------------------------
            -- Chart of accounts
            -- ---------------------------------------------------------------
            -- system_code is the stable handle the posting layer resolves on.
            -- The reference looks ledgers up by display name, so renaming
            -- "Cash & Bank" breaks posting at runtime rather than compile time.
            CREATE TABLE ledger_group (
                id                   TEXT    NOT NULL PRIMARY KEY,
                parent_id            TEXT REFERENCES ledger_group (id),
                system_code          TEXT,
                name                 TEXT    NOT NULL,
                nature               TEXT    NOT NULL
                    CHECK (nature IN ('ASSETS', 'LIABILITIES', 'INCOME', 'EXPENSES', 'EQUITY')),
                affects_gross_profit INTEGER NOT NULL DEFAULT 0
                    CHECK (affects_gross_profit IN (0, 1)),
                is_system            INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)),
                sequence             INTEGER NOT NULL DEFAULT 0,
                created_at           INTEGER NOT NULL,
                updated_at           INTEGER NOT NULL,
                UNIQUE (name),
                UNIQUE (system_code),
                CHECK (parent_id IS NULL OR parent_id <> id)
            ) STRICT;

            CREATE INDEX idx_ledger_group_parent ON ledger_group (parent_id);

            CREATE TABLE ledger (
                id                    TEXT    NOT NULL PRIMARY KEY,
                group_id              TEXT    NOT NULL REFERENCES ledger_group (id),
                system_code           TEXT,
                name                  TEXT    NOT NULL,
                code                  TEXT,
                -- Opening balances are tied to a fiscal year. In the reference
                -- they are a free-floating mutable column applied at every
                -- as-of date, so editing one silently restates prior years.
                opening_fiscal_year_id TEXT REFERENCES fiscal_year (id),
                opening_balance_e4    INTEGER NOT NULL DEFAULT 0,
                -- One vocabulary. The reference writes DEBIT/CREDIT and reads
                -- DR/CR, so every user-entered opening balance is sign-flipped.
                opening_balance_type  TEXT
                    CHECK (opening_balance_type IS NULL
                           OR opening_balance_type IN ('DR', 'CR')),
                is_active             INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
                created_at            INTEGER NOT NULL,
                updated_at            INTEGER NOT NULL,
                UNIQUE (name),
                UNIQUE (system_code),
                CHECK (opening_balance_e4 = 0 OR opening_balance_type IS NOT NULL)
            ) STRICT;

            CREATE INDEX idx_ledger_group ON ledger (group_id);

            -- ---------------------------------------------------------------
            -- Voucher types and numbering
            -- ---------------------------------------------------------------
            -- Per company, not global. The reference makes VoucherType.code
            -- globally unique across all tenants, which a multi-company desktop
            -- installation cannot tolerate.
            CREATE TABLE voucher_type (
                id               TEXT    NOT NULL PRIMARY KEY,
                code             TEXT    NOT NULL,
                name             TEXT    NOT NULL,
                nature           TEXT    NOT NULL
                    CHECK (nature IN ('PAYMENT', 'RECEIPT', 'CONTRA', 'JOURNAL',
                                      'SALES', 'PURCHASE', 'DEBIT_NOTE', 'CREDIT_NOTE')),
                numbering_prefix TEXT,
                is_system        INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)),
                is_active        INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL,
                UNIQUE (code)
            ) STRICT;

            CREATE TABLE number_counter (
                scope       TEXT    NOT NULL PRIMARY KEY,
                last_number INTEGER NOT NULL DEFAULT 0 CHECK (last_number >= 0),
                updated_at  INTEGER NOT NULL
            ) STRICT;

            -- ---------------------------------------------------------------
            -- Journal
            -- ---------------------------------------------------------------
            CREATE TABLE voucher (
                id              TEXT    NOT NULL PRIMARY KEY,
                fiscal_year_id  TEXT    NOT NULL REFERENCES fiscal_year (id),
                voucher_type_id TEXT    NOT NULL REFERENCES voucher_type (id),
                voucher_number  TEXT    NOT NULL,
                voucher_date    TEXT    NOT NULL,
                reference_no    TEXT,
                narration       TEXT,
                total_debit_e4  INTEGER NOT NULL CHECK (total_debit_e4 >= 0),
                total_credit_e4 INTEGER NOT NULL CHECK (total_credit_e4 >= 0),
                status          TEXT    NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED',
                                      'REJECTED', 'CANCELLED')),
                is_posted       INTEGER NOT NULL DEFAULT 0 CHECK (is_posted IN (0, 1)),
                posted_at       INTEGER,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL,
                -- Double entry, enforced by the database rather than by
                -- convention. The reference relies on one application function,
                -- which its own Tally importer bypasses.
                CHECK (total_debit_e4 = total_credit_e4),
                CHECK (is_posted = 0 OR posted_at IS NOT NULL),
                UNIQUE (voucher_type_id, fiscal_year_id, voucher_number)
            ) STRICT;

            CREATE INDEX idx_voucher_date ON voucher (voucher_date);
            CREATE INDEX idx_voucher_fy ON voucher (fiscal_year_id);
            -- Partial index for the posted-voucher filter every report applies.
            CREATE INDEX idx_voucher_posted ON voucher (voucher_date)
                WHERE is_posted = 1 AND status = 'APPROVED';

            CREATE TABLE voucher_entry (
                id               TEXT    NOT NULL PRIMARY KEY,
                voucher_id       TEXT    NOT NULL REFERENCES voucher (id) ON DELETE CASCADE,
                ledger_id        TEXT    NOT NULL REFERENCES ledger (id),
                debit_amount_e4  INTEGER NOT NULL DEFAULT 0,
                credit_amount_e4 INTEGER NOT NULL DEFAULT 0,
                narration        TEXT,
                sequence         INTEGER NOT NULL DEFAULT 0,
                created_at       INTEGER NOT NULL,
                -- A line carries a debit or a credit, never both, never
                -- negative. Nothing prevents either in the reference schema.
                CHECK (debit_amount_e4 >= 0 AND credit_amount_e4 >= 0),
                CHECK (debit_amount_e4 = 0 OR credit_amount_e4 = 0),
                CHECK (debit_amount_e4 > 0 OR credit_amount_e4 > 0)
            ) STRICT;

            CREATE INDEX idx_voucher_entry_voucher ON voucher_entry (voucher_id);
            CREATE INDEX idx_voucher_entry_ledger ON voucher_entry (ledger_id);

            -- ---------------------------------------------------------------
            -- Audit log - append-only, enforced by the database
            -- ---------------------------------------------------------------
            -- The hash chain is computed in Java: SQLite has no sha256().
            CREATE TABLE audit_log (
                id           TEXT    NOT NULL PRIMARY KEY,
                sequence     INTEGER NOT NULL,
                occurred_at  INTEGER NOT NULL,
                action       TEXT    NOT NULL,
                entity_type  TEXT    NOT NULL,
                entity_id    TEXT,
                detail       TEXT,
                previous_hash TEXT,
                entry_hash   TEXT    NOT NULL,
                UNIQUE (sequence)
            ) STRICT;

            CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);

            -- Convention is not enough for a record an auditor may rely on.
            CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log
            BEGIN
                SELECT RAISE(ABORT, 'audit_log is append-only');
            END;

            CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log
            BEGIN
                SELECT RAISE(ABORT, 'audit_log is append-only');
            END;
            """);

    /**
     * Phase 5 posting invariants.
     *
     * <p>Closes two holes found by the P5 adversarial review, and adds the
     * columns reversal and idempotency need.
     *
     * <p><strong>A-1.</strong> {@code CHECK (total_debit_e4 = total_credit_e4)}
     * is satisfied by {@code 0 = 0}, so an entry-less voucher passed every
     * constraint while meaning nothing. Balanced is not the same as valid.
     *
     * <p><strong>A-2.</strong> {@code status} and {@code is_posted} were
     * independent, so CANCELLED + posted was representable - a voucher counted
     * in the trial balance that every screen showed as cancelled.
     *
     * <p>SQLite cannot add a CHECK to an existing table, and the P2 work
     * measured that {@code DROP TABLE} with {@code foreign_keys = ON}
     * cascade-deletes children. The table is therefore rebuilt with foreign keys
     * disabled, children copied first, and the parent swapped last.
     */
    private static final Migration COMPANY_V2_POSTING_INVARIANTS = new Migration(2,
            "posting invariants, immutability and reversal", """
            PRAGMA foreign_keys = OFF;

            CREATE TABLE voucher_new (
                id              TEXT    NOT NULL PRIMARY KEY,
                fiscal_year_id  TEXT    NOT NULL REFERENCES fiscal_year (id),
                voucher_type_id TEXT    NOT NULL REFERENCES voucher_type (id),
                voucher_number  TEXT    NOT NULL,
                voucher_date    TEXT    NOT NULL,
                reference_no    TEXT,
                narration       TEXT,
                total_debit_e4  INTEGER NOT NULL CHECK (total_debit_e4 >= 0),
                total_credit_e4 INTEGER NOT NULL CHECK (total_credit_e4 >= 0),
                status          TEXT    NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED', 'CANCELLED')),
                is_posted       INTEGER NOT NULL DEFAULT 0 CHECK (is_posted IN (0, 1)),
                posted_at       INTEGER,
                -- Reversal (P5-2): the reversal lives in the current open
                -- period and points back. The original is never touched.
                reverses_voucher_id TEXT REFERENCES voucher (id),
                reversal_reason TEXT,
                posted_by       TEXT,
                -- Idempotency: a lost response must not create a second voucher.
                idempotency_key TEXT,
                originator_module TEXT,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL,
                CHECK (total_debit_e4 = total_credit_e4),
                -- A-1: zero balances arithmetically but is not a transaction.
                CHECK (total_debit_e4 > 0),
                -- A-2: is_posted is derived from status, never independent.
                CHECK (is_posted = (CASE WHEN status IN ('POSTED', 'REVERSED')
                                         THEN 1 ELSE 0 END)),
                CHECK (is_posted = 0 OR posted_at IS NOT NULL),
                CHECK (reversal_reason IS NULL OR reverses_voucher_id IS NOT NULL),
                CHECK (reverses_voucher_id IS NULL OR reverses_voucher_id <> id),
                UNIQUE (voucher_type_id, fiscal_year_id, voucher_number)
            ) STRICT;

            INSERT INTO voucher_new (id, fiscal_year_id, voucher_type_id, voucher_number,
                                     voucher_date, reference_no, narration, total_debit_e4,
                                     total_credit_e4, status, is_posted, posted_at,
                                     created_at, updated_at)
            SELECT id, fiscal_year_id, voucher_type_id, voucher_number, voucher_date,
                   reference_no, narration, total_debit_e4, total_credit_e4,
                   CASE WHEN is_posted = 1 THEN 'POSTED'
                        WHEN status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN status = 'REJECTED' THEN 'CANCELLED'
                        ELSE 'DRAFT' END,
                   is_posted, posted_at, created_at, updated_at
            FROM voucher;

            DROP TABLE voucher;
            ALTER TABLE voucher_new RENAME TO voucher;

            CREATE INDEX idx_voucher_date ON voucher (voucher_date);
            CREATE INDEX idx_voucher_fy ON voucher (fiscal_year_id);
            CREATE INDEX idx_voucher_posted ON voucher (voucher_date)
                WHERE is_posted = 1;
            CREATE INDEX idx_voucher_reverses ON voucher (reverses_voucher_id);
            CREATE UNIQUE INDEX idx_voucher_idempotency ON voucher (idempotency_key)
                WHERE idempotency_key IS NOT NULL;

            -- Immutability. A posted voucher is a statutory record: it is
            -- reversed, never edited. Only the transition to REVERSED is
            -- allowed through, and only from POSTED.
            CREATE TRIGGER voucher_posted_no_update
            BEFORE UPDATE ON voucher
            WHEN OLD.is_posted = 1
             AND NOT (OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
                      AND NEW.id = OLD.id
                      AND NEW.total_debit_e4 = OLD.total_debit_e4
                      AND NEW.total_credit_e4 = OLD.total_credit_e4
                      AND NEW.voucher_date = OLD.voucher_date
                      AND NEW.voucher_number = OLD.voucher_number)
            BEGIN
                SELECT RAISE(ABORT,
                    'A posted voucher cannot be modified; post a reversal instead');
            END;

            CREATE TRIGGER voucher_posted_no_delete
            BEFORE DELETE ON voucher
            WHEN OLD.is_posted = 1
            BEGIN
                SELECT RAISE(ABORT,
                    'A posted voucher cannot be deleted; post a reversal instead');
            END;

            -- Entries of a posted voucher are equally immutable. Without this,
            -- the header would be protected while its lines were not.
            CREATE TRIGGER voucher_entry_no_update_when_posted
            BEFORE UPDATE ON voucher_entry
            WHEN (SELECT is_posted FROM voucher WHERE id = OLD.voucher_id) = 1
            BEGIN
                SELECT RAISE(ABORT, 'Entries of a posted voucher cannot be modified');
            END;

            CREATE TRIGGER voucher_entry_no_delete_when_posted
            BEFORE DELETE ON voucher_entry
            WHEN (SELECT is_posted FROM voucher WHERE id = OLD.voucher_id) = 1
            BEGIN
                SELECT RAISE(ABORT, 'Entries of a posted voucher cannot be deleted');
            END;

            PRAGMA foreign_keys = ON;
            """);

    /**
     * Phase 6 account governance.
     *
     * <p>Adds {@code ledger.is_system}, which {@code ledger_group} already had.
     * {@code system_code IS NOT NULL} was serving as a proxy, but it conflates
     * two separate ideas - "the posting layer resolves this by a stable handle"
     * and "a user may not delete this". A later module may legitimately want a
     * stable handle on a user-created account, and a rule that refuses a
     * customer's action should be explicit rather than inferred.
     *
     * <p>Three concepts stay separate from here on:
     * <ul>
     *   <li>{@code system_code} - stable programmatic identity, never displayed</li>
     *   <li>{@code is_system} - governance: may this be deleted or deactivated</li>
     *   <li>{@code name} - the human label, freely renameable (P6-2)</li>
     * </ul>
     *
     * <p>That separation is why renaming is safe here and unsafe in the
     * reference application, which resolves control accounts by display name
     * through {@code getOrCreateNamedLedger} - so a rename silently creates a
     * second account and splits the balance across both.
     *
     * <p>Additive, so no table rebuild: existing rows inherit
     * {@code is_system = 1} exactly where a system_code was seeded.
     */
    private static final Migration COMPANY_V3_ACCOUNT_GOVERNANCE = new Migration(3,
            "account governance flags", """
            ALTER TABLE ledger ADD COLUMN is_system INTEGER NOT NULL DEFAULT 0
                CHECK (is_system IN (0, 1));

            UPDATE ledger SET is_system = 1 WHERE system_code IS NOT NULL;

            -- A system account is never deletable, even when unused. The
            -- reference hard-deletes any ledger with no postings, which on a
            -- freshly provisioned company is every system ledger it just
            -- created; the failure only surfaces later, when a module resolves
            -- one and finds nothing.
            CREATE TRIGGER ledger_system_no_delete
            BEFORE DELETE ON ledger
            WHEN OLD.is_system = 1
            BEGIN
                SELECT RAISE(ABORT,
                    'A system ledger cannot be deleted; deactivate it instead');
            END;

            CREATE TRIGGER ledger_group_system_no_delete
            BEFORE DELETE ON ledger_group
            WHEN OLD.is_system = 1
            BEGIN
                SELECT RAISE(ABORT, 'A system ledger group cannot be deleted');
            END;

            -- A-9: deleting a group with children orphans them. The foreign key
            -- would normally refuse, but P2 measured that foreign_keys defaults
            -- OFF per connection, so it is not a backstop that can be relied on.
            CREATE TRIGGER ledger_group_no_delete_with_ledgers
            BEFORE DELETE ON ledger_group
            BEGIN
                SELECT RAISE(ABORT, 'This group still contains ledgers')
                WHERE EXISTS (SELECT 1 FROM ledger WHERE group_id = OLD.id);
            END;

            CREATE TRIGGER ledger_group_no_delete_with_children
            BEFORE DELETE ON ledger_group
            BEGIN
                SELECT RAISE(ABORT, 'This group still contains sub-groups')
                WHERE EXISTS (SELECT 1 FROM ledger_group WHERE parent_id = OLD.id);
            END;

            -- A system ledger must keep its handle. Clearing system_code would
            -- turn a protected account into an ordinary one and break the
            -- module that resolves it.
            CREATE TRIGGER ledger_system_code_immutable
            BEFORE UPDATE ON ledger
            WHEN OLD.is_system = 1
             AND (NEW.system_code IS NOT OLD.system_code OR NEW.is_system <> 1)
            BEGIN
                SELECT RAISE(ABORT,
                    'A system ledger cannot lose its system_code or system flag');
            END;

            CREATE INDEX idx_ledger_system ON ledger (is_system) WHERE is_system = 1;
            """);

    /**
     * Phase 7a parties.
     *
     * <p>A party is bound to its ledger by {@code ledger_id}, created with it in
     * one transaction. The reference binds them by display name -
     * {@code upsertLedgerByName} does {@code ON CONFLICT (name) ... RETURNING id}
     * - so a party named "Cash in Hand" silently attaches to the cash ledger and
     * its receivables post into cash (R-5). Here a name clash is refused by the
     * {@code UNIQUE (name)} on {@code ledger}, and nothing ever looks a party's
     * ledger up by name.
     *
     * <p>Parties are kernel (B-7): no module owns this table, so it is never
     * entitlement-gated.
     */
    private static final Migration COMPANY_V4_PARTIES = new Migration(4, "parties", """
            CREATE TABLE party (
                id               TEXT    NOT NULL PRIMARY KEY,
                ledger_id        TEXT    NOT NULL UNIQUE REFERENCES ledger (id),
                -- P7-2: one ledger per party. BOTH sits under Sundry Debtors
                -- and its net balance is the position.
                role             TEXT    NOT NULL
                    CHECK (role IN ('CUSTOMER', 'VENDOR', 'BOTH')),
                name             TEXT    NOT NULL,
                code             TEXT,
                contact_person   TEXT,
                email            TEXT,
                phone            TEXT,
                address_line1    TEXT,
                address_city     TEXT,
                -- Place-of-supply input for GST (P9). Stored now so P9 does not
                -- have to backfill it.
                state_code       TEXT,
                postal_code      TEXT,
                gstin            TEXT,
                pan              TEXT,
                credit_limit_e4  INTEGER CHECK (credit_limit_e4 IS NULL OR credit_limit_e4 >= 0),
                credit_days      INTEGER CHECK (credit_days IS NULL OR credit_days >= 0),
                is_active        INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
                created_at       INTEGER NOT NULL,
                updated_at       INTEGER NOT NULL,
                UNIQUE (name),
                UNIQUE (code)
            ) STRICT;

            -- A party's ledger cannot be swapped for another. Re-pointing it
            -- would move the party's whole history to a different account.
            CREATE TRIGGER party_ledger_immutable
            BEFORE UPDATE OF ledger_id ON party
            WHEN NEW.ledger_id IS NOT OLD.ledger_id
            BEGIN
                SELECT RAISE(ABORT, 'A party cannot be moved to a different ledger');
            END;

            -- The ledger is the source of truth for a party's name and active
            -- flag. The chart of accounts may rename or deactivate any ledger,
            -- so the party mirrors it rather than holding a second copy that
            -- could disagree.
            CREATE TRIGGER party_follows_ledger_name
            AFTER UPDATE OF name ON ledger
            WHEN NEW.name IS NOT OLD.name
            BEGIN
                UPDATE party SET name = NEW.name, updated_at = NEW.updated_at
                WHERE ledger_id = NEW.id;
            END;

            CREATE TRIGGER party_follows_ledger_active
            AFTER UPDATE OF is_active ON ledger
            WHEN NEW.is_active IS NOT OLD.is_active
            BEGIN
                UPDATE party SET is_active = NEW.is_active, updated_at = NEW.updated_at
                WHERE ledger_id = NEW.id;
            END;

            -- The foreign key would refuse this too, but P2 measured that
            -- foreign_keys is per connection; a trigger does not depend on it.
            CREATE TRIGGER ledger_no_delete_with_party
            BEFORE DELETE ON ledger
            BEGIN
                SELECT RAISE(ABORT, 'This ledger belongs to a party; delete the party instead')
                WHERE EXISTS (SELECT 1 FROM party WHERE ledger_id = OLD.id);
            END;
            """);

    /**
     * Phase 7b inventory - quantities only (B-1: periodic 5a).
     *
     * <p>Owned by the {@code inventory} module, and created in every build
     * (universal schema, ADR-002): a BASIC build has the tables, empty, and a
     * downgraded customer keeps their history in them.
     *
     * <p>No value is kept. Valuation is P8, from persisted cost layers (A-1);
     * {@code unit_cost_e4} records what a source document said a unit cost so
     * P8 has its input, and nothing in P7 reads it.
     *
     * <p>Names are unique ignoring case ({@code COLLATE NOCASE}): two items
     * called "Rice" and "RICE" are one item a user cannot tell apart in a picker.
     */
    private static final Migration COMPANY_V5_INVENTORY = new Migration(5, "inventory", """
            CREATE TABLE unit (
                id          TEXT    NOT NULL PRIMARY KEY,
                symbol      TEXT    NOT NULL COLLATE NOCASE UNIQUE,
                name        TEXT    NOT NULL,
                -- How many decimal places a quantity in this unit may carry:
                -- 0 for pieces, 3 for kilograms. At most the storage scale.
                decimals    INTEGER NOT NULL DEFAULT 0 CHECK (decimals BETWEEN 0 AND 4),
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            ) STRICT;

            CREATE TABLE item_category (
                id          TEXT    NOT NULL PRIMARY KEY,
                parent_id   TEXT REFERENCES item_category (id),
                name        TEXT    NOT NULL COLLATE NOCASE UNIQUE,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL,
                CHECK (parent_id IS NULL OR parent_id <> id)
            ) STRICT;

            CREATE TABLE item (
                id           TEXT    NOT NULL PRIMARY KEY,
                category_id  TEXT REFERENCES item_category (id),
                unit_id      TEXT    NOT NULL REFERENCES unit (id),
                name         TEXT    NOT NULL COLLATE NOCASE UNIQUE,
                sku          TEXT COLLATE NOCASE UNIQUE,
                kind         TEXT    NOT NULL CHECK (kind IN ('GOODS', 'SERVICE')),
                -- HSN for goods, SAC for services. Stored now; drives GST in P9.
                hsn_sac      TEXT,
                description  TEXT,
                is_active    INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
                created_at   INTEGER NOT NULL,
                updated_at   INTEGER NOT NULL
            ) STRICT;

            CREATE INDEX idx_item_category ON item (category_id);

            CREATE TABLE warehouse (
                id          TEXT    NOT NULL PRIMARY KEY,
                name        TEXT    NOT NULL COLLATE NOCASE UNIQUE,
                address     TEXT,
                is_active   INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            ) STRICT;

            -- Quantity on hand per item and warehouse: a cache of the movement
            -- log, kept so a sale has a row to decrement under a guard. P7-4:
            -- stock never goes negative, enforced here as well as by the
            -- guarded UPDATE, so no code path can get round it.
            CREATE TABLE stock (
                item_id      TEXT    NOT NULL REFERENCES item (id),
                warehouse_id TEXT    NOT NULL REFERENCES warehouse (id),
                quantity_e4  INTEGER NOT NULL CHECK (quantity_e4 >= 0),
                updated_at   INTEGER NOT NULL,
                PRIMARY KEY (item_id, warehouse_id)
            ) STRICT;

            CREATE TABLE stock_movement (
                id                TEXT    NOT NULL PRIMARY KEY,
                item_id           TEXT    NOT NULL REFERENCES item (id),
                movement_type     TEXT    NOT NULL CHECK (movement_type IN (
                    'OPENING', 'PURCHASE', 'SALES_RETURN', 'ADJUSTMENT_IN',
                    'SALE', 'PURCHASE_RETURN', 'ADJUSTMENT_OUT', 'TRANSFER')),
                from_warehouse_id TEXT REFERENCES warehouse (id),
                to_warehouse_id   TEXT REFERENCES warehouse (id),
                quantity_e4       INTEGER NOT NULL CHECK (quantity_e4 > 0),
                unit_cost_e4      INTEGER CHECK (unit_cost_e4 IS NULL OR unit_cost_e4 >= 0),
                movement_date     TEXT    NOT NULL,
                -- The document that caused it, e.g. ('SALES_INVOICE', id). Null
                -- for a manual adjustment or opening stock.
                source_type       TEXT,
                source_id         TEXT,
                narration         TEXT,
                created_at        INTEGER NOT NULL,
                -- Direction is a property of the type. The reference validated
                -- it only in a route, and a SALE carrying a destination
                -- warehouse once increased stock.
                CHECK (
                    (movement_type IN ('OPENING', 'PURCHASE', 'SALES_RETURN', 'ADJUSTMENT_IN')
                        AND from_warehouse_id IS NULL AND to_warehouse_id IS NOT NULL)
                 OR (movement_type IN ('SALE', 'PURCHASE_RETURN', 'ADJUSTMENT_OUT')
                        AND from_warehouse_id IS NOT NULL AND to_warehouse_id IS NULL)
                 OR (movement_type = 'TRANSFER'
                        AND from_warehouse_id IS NOT NULL AND to_warehouse_id IS NOT NULL
                        AND from_warehouse_id <> to_warehouse_id)),
                CHECK ((source_type IS NULL) = (source_id IS NULL))
            ) STRICT;

            CREATE INDEX idx_stock_movement_item ON stock_movement (item_id, movement_date);
            CREATE INDEX idx_stock_movement_source ON stock_movement (source_type, source_id);

            -- The movement log is the record; stock is derived from it. A
            -- movement is corrected by an opposite movement, never edited.
            CREATE TRIGGER stock_movement_no_update BEFORE UPDATE ON stock_movement
            BEGIN
                SELECT RAISE(ABORT, 'A stock movement cannot be changed; record an opposite movement');
            END;

            CREATE TRIGGER stock_movement_no_delete BEFORE DELETE ON stock_movement
            BEGIN
                SELECT RAISE(ABORT, 'A stock movement cannot be deleted; record an opposite movement');
            END;

            -- A service has no stock. Refused by the database so a document
            -- line for a service can never move inventory.
            CREATE TRIGGER stock_movement_goods_only BEFORE INSERT ON stock_movement
            WHEN (SELECT kind FROM item WHERE id = NEW.item_id) = 'SERVICE'
            BEGIN
                SELECT RAISE(ABORT, 'A service item has no stock');
            END;

            CREATE TRIGGER stock_goods_only BEFORE INSERT ON stock
            WHEN (SELECT kind FROM item WHERE id = NEW.item_id) = 'SERVICE'
            BEGIN
                SELECT RAISE(ABORT, 'A service item has no stock');
            END;

            -- An item with movements keeps its kind and unit: changing either
            -- would reinterpret every quantity already recorded.
            CREATE TRIGGER item_kind_unit_fixed_once_moved
            BEFORE UPDATE OF kind, unit_id ON item
            WHEN (NEW.kind IS NOT OLD.kind OR NEW.unit_id IS NOT OLD.unit_id)
             AND EXISTS (SELECT 1 FROM stock_movement WHERE item_id = OLD.id)
            BEGIN
                SELECT RAISE(ABORT,
                    'This item has stock movements; its kind and unit cannot change');
            END;
            """);
}

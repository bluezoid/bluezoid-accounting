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
                COMPANY_V3_ACCOUNT_GOVERNANCE, COMPANY_V4_PARTIES, COMPANY_V5_INVENTORY,
                COMPANY_V6_DOCUMENTS, COMPANY_V7_SETTLEMENT, COMPANY_V8_ORDERS, COMPANY_V9_VALUATION);
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

    /**
     * Phase 7c sales and purchase documents.
     *
     * <p>{@code sales_*} tables are owned by {@code sales-invoicing},
     * {@code purchase_*} by {@code purchase-billing}. Both sides come from one
     * template, so their immutability rules cannot drift apart.
     *
     * <p>Also seeds the two system ledgers documents post to - Sales under
     * Sales Accounts, Purchases under Direct Expenses (periodic 5a) - for
     * companies that already exist. A new company is migrated before it is
     * provisioned, so for it this finds no groups and provisioning seeds them.
     * Both modules are in every plan, so P2-1 permits seeding.
     */
    private static final Migration COMPANY_V6_DOCUMENTS = new Migration(6, "sales and purchase documents",
            documentTables("sales", "'SALES_INVOICE', 'CREDIT_NOTE'", "'CREDIT_NOTE'", "")
                    + documentTables("purchase", "'PURCHASE_BILL', 'DEBIT_NOTE'", "'DEBIT_NOTE'",
                    "supplier_document_no TEXT,")
                    + seedSystemLedger("SALES", "Sales", "SALES_ACCOUNTS")
                    + seedSystemLedger("PURCHASES", "Purchases", "DIRECT_EXPENSES"));

    /**
     * One side's document tables.
     *
     * <p>A document is editable only while DRAFT. Posting gives it a number
     * (P7-8), a voucher and its stock movements; from then it is a statutory
     * record that only changes by being CANCELLED, which keeps its number and
     * records the reversal (P7-12). A note must reference the document it
     * corrects (P7-11).
     */
    private static String documentTables(String side, String kinds, String noteKind, String extraColumns) {
        return """
            CREATE TABLE %1$s_document (
                id                      TEXT    NOT NULL PRIMARY KEY,
                kind                    TEXT    NOT NULL CHECK (kind IN (%2$s)),
                -- P7-8: null until posted, so an abandoned draft leaves no gap.
                number                  TEXT UNIQUE,
                party_id                TEXT    NOT NULL REFERENCES party (id),
                document_date           TEXT    NOT NULL,
                due_date                TEXT,
                status                  TEXT    NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED')),
                original_document_id    TEXT REFERENCES %1$s_document (id),
                %4$s
                round_off_enabled       INTEGER NOT NULL DEFAULT 1 CHECK (round_off_enabled IN (0, 1)),
                -- Derived from the lines, never accepted from a caller.
                amount_e4               INTEGER NOT NULL DEFAULT 0 CHECK (amount_e4 >= 0),
                round_off_e4            INTEGER NOT NULL DEFAULT 0,
                total_e4                INTEGER NOT NULL DEFAULT 0 CHECK (total_e4 >= 0),
                fiscal_year_id          TEXT REFERENCES fiscal_year (id),
                voucher_id              TEXT REFERENCES voucher (id),
                cancellation_voucher_id TEXT REFERENCES voucher (id),
                cancellation_reason     TEXT,
                narration               TEXT,
                created_at              INTEGER NOT NULL,
                updated_at              INTEGER NOT NULL,
                CHECK (total_e4 = amount_e4 + round_off_e4),
                CHECK ((status = 'DRAFT') = (number IS NULL)),
                CHECK (status = 'DRAFT' OR (voucher_id IS NOT NULL AND fiscal_year_id IS NOT NULL
                                            AND total_e4 > 0)),
                CHECK ((status = 'CANCELLED') = (cancellation_voucher_id IS NOT NULL)),
                CHECK ((kind = %3$s) = (original_document_id IS NOT NULL)),
                CHECK (original_document_id IS NULL OR original_document_id <> id)
            ) STRICT;

            CREATE INDEX idx_%1$s_document_party ON %1$s_document (party_id, document_date);
            CREATE INDEX idx_%1$s_document_original ON %1$s_document (original_document_id);

            CREATE TABLE %1$s_document_line (
                id            TEXT    NOT NULL PRIMARY KEY,
                document_id   TEXT    NOT NULL REFERENCES %1$s_document (id) ON DELETE CASCADE,
                sequence      INTEGER NOT NULL,
                -- Null for a free-text line; a build without inventory has only those.
                item_id       TEXT REFERENCES item (id),
                description   TEXT    NOT NULL,
                quantity_e4   INTEGER NOT NULL CHECK (quantity_e4 > 0),
                rate_e4       INTEGER NOT NULL CHECK (rate_e4 >= 0),
                discount_bp   INTEGER NOT NULL DEFAULT 0 CHECK (discount_bp BETWEEN 0 AND 10000),
                -- P7-9: quantity x rate - discount, rounded to paise half-up.
                amount_e4     INTEGER NOT NULL CHECK (amount_e4 >= 0 AND amount_e4 %% 100 = 0),
                warehouse_id  TEXT REFERENCES warehouse (id),
                CHECK (warehouse_id IS NULL OR item_id IS NOT NULL),
                UNIQUE (document_id, sequence)
            ) STRICT;

            -- P7-1: GST arrives in P9. The table exists so P9 is a fill, not a reshape.
            CREATE TABLE %1$s_tax_line (
                id          TEXT    NOT NULL PRIMARY KEY,
                line_id     TEXT    NOT NULL REFERENCES %1$s_document_line (id) ON DELETE CASCADE,
                component   TEXT    NOT NULL,
                rate_bp     INTEGER NOT NULL CHECK (rate_bp >= 0),
                amount_e4   INTEGER NOT NULL CHECK (amount_e4 >= 0)
            ) STRICT;

            -- Once posted, a document is a statutory record. The only change
            -- allowed is POSTED -> CANCELLED, recording the reversal and reason.
            CREATE TRIGGER %1$s_document_posted_immutable
            BEFORE UPDATE ON %1$s_document
            WHEN OLD.status <> 'DRAFT'
             AND NOT (OLD.status = 'POSTED' AND NEW.status = 'CANCELLED'
                      AND NEW.number = OLD.number AND NEW.kind = OLD.kind
                      AND NEW.party_id = OLD.party_id AND NEW.document_date = OLD.document_date
                      AND NEW.total_e4 = OLD.total_e4 AND NEW.voucher_id = OLD.voucher_id)
            BEGIN
                SELECT RAISE(ABORT, 'A posted document cannot be changed; cancel it instead');
            END;

            CREATE TRIGGER %1$s_document_posted_no_delete
            BEFORE DELETE ON %1$s_document
            WHEN OLD.status <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'A posted document cannot be deleted; cancel it instead');
            END;

            CREATE TRIGGER %1$s_line_insert_only_in_draft
            BEFORE INSERT ON %1$s_document_line
            WHEN (SELECT status FROM %1$s_document WHERE id = NEW.document_id) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines of a posted document cannot be changed');
            END;

            CREATE TRIGGER %1$s_line_update_only_in_draft
            BEFORE UPDATE ON %1$s_document_line
            WHEN (SELECT status FROM %1$s_document WHERE id = OLD.document_id) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines of a posted document cannot be changed');
            END;

            CREATE TRIGGER %1$s_line_delete_only_in_draft
            BEFORE DELETE ON %1$s_document_line
            WHEN (SELECT status FROM %1$s_document WHERE id = OLD.document_id) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines of a posted document cannot be changed');
            END;

            """.formatted(side, kinds, noteKind, extraColumns);
    }

    /**
     * Seed a system ledger into an existing company, if its group exists and the
     * ledger does not. Falls back to a second name if a user ledger already has
     * the first, rather than failing the migration and locking the company out.
     */
    private static String seedSystemLedger(String code, String name, String groupCode) {
        return """
            INSERT INTO ledger (id, group_id, system_code, name, is_system, created_at, updated_at)
            SELECT lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2)
                         || '-' || substr('89ab', 1 + (abs(random()) %% 4), 1) || substr(hex(randomblob(2)), 2)
                         || '-' || hex(randomblob(6))),
                   g.id, '%1$s',
                   CASE WHEN EXISTS (SELECT 1 FROM ledger WHERE lower(name) = lower('%2$s'))
                        THEN '%2$s (AccuBook)' ELSE '%2$s' END,
                   1, CAST(strftime('%%s', 'now') AS INTEGER) * 1000, CAST(strftime('%%s', 'now') AS INTEGER) * 1000
            FROM ledger_group g
            WHERE g.system_code = '%3$s'
              AND NOT EXISTS (SELECT 1 FROM ledger WHERE system_code = '%1$s');

            """.formatted(code, name, groupCode);
    }

    /**
     * Phase 7d settlement: receipts and payments, and the allocations that say
     * which documents they paid (P7-3).
     *
     * <p>An allocation moves no ledger balance - the receipt's voucher already
     * did. It records <em>which</em> invoice was paid, so outstanding amounts are
     * derived from it and never stored on the document (R-7). Its limits are
     * enforced here as well as in the service, inside the same transaction as the
     * insert (D-21: the reference checked over-allocation outside it).
     */
    private static final Migration COMPANY_V7_SETTLEMENT = new Migration(7, "receipts, payments and allocations",
            settlementTables("sales") + settlementTables("purchase"));

    private static String settlementTables(String side) {
        return """
            CREATE TABLE %1$s_settlement (
                id                      TEXT    NOT NULL PRIMARY KEY,
                -- P7-14: posted on entry, so always numbered.
                number                  TEXT    NOT NULL UNIQUE,
                party_id                TEXT    NOT NULL REFERENCES party (id),
                settlement_date         TEXT    NOT NULL,
                amount_e4               INTEGER NOT NULL CHECK (amount_e4 > 0 AND amount_e4 %% 100 = 0),
                -- P7-16: a ledger in the Cash & Bank group.
                account_ledger_id       TEXT    NOT NULL REFERENCES ledger (id),
                mode                    TEXT    NOT NULL CHECK (mode IN ('CASH', 'BANK_TRANSFER', 'CHEQUE',
                                            'UPI', 'CARD', 'NEFT', 'RTGS', 'OTHER')),
                reference_no            TEXT,
                instrument_date         TEXT,
                status                  TEXT    NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED', 'CANCELLED')),
                fiscal_year_id          TEXT    NOT NULL REFERENCES fiscal_year (id),
                voucher_id              TEXT    NOT NULL REFERENCES voucher (id),
                cancellation_voucher_id TEXT REFERENCES voucher (id),
                cancellation_reason     TEXT,
                narration               TEXT,
                created_at              INTEGER NOT NULL,
                updated_at              INTEGER NOT NULL,
                CHECK ((status = 'CANCELLED') = (cancellation_voucher_id IS NOT NULL))
            ) STRICT;

            CREATE INDEX idx_%1$s_settlement_party ON %1$s_settlement (party_id, settlement_date);

            CREATE TRIGGER %1$s_settlement_immutable
            BEFORE UPDATE ON %1$s_settlement
            WHEN NOT (OLD.status = 'POSTED' AND NEW.status = 'CANCELLED'
                      AND NEW.number = OLD.number AND NEW.party_id = OLD.party_id
                      AND NEW.amount_e4 = OLD.amount_e4 AND NEW.settlement_date = OLD.settlement_date
                      AND NEW.account_ledger_id = OLD.account_ledger_id AND NEW.voucher_id = OLD.voucher_id)
            BEGIN
                SELECT RAISE(ABORT, 'A receipt or payment cannot be changed; cancel it instead');
            END;

            CREATE TRIGGER %1$s_settlement_no_delete
            BEFORE DELETE ON %1$s_settlement
            BEGIN
                SELECT RAISE(ABORT, 'A receipt or payment cannot be deleted; cancel it instead');
            END;

            CREATE TABLE %1$s_allocation (
                id              TEXT    NOT NULL PRIMARY KEY,
                -- A receipt/payment, or a credit/debit note.
                source_type     TEXT    NOT NULL CHECK (source_type IN ('SETTLEMENT', 'NOTE')),
                source_id       TEXT    NOT NULL,
                document_id     TEXT    NOT NULL REFERENCES %1$s_document (id),
                amount_e4       INTEGER NOT NULL CHECK (amount_e4 > 0 AND amount_e4 %% 100 = 0),
                -- The later of the source's and the document's dates: from when
                -- the allocation counts in an as-of report (R-12).
                effective_date  TEXT    NOT NULL,
                -- Removal is recorded, never a delete: an as-of report for a
                -- date before the removal must still see the allocation (R-12).
                removed_date    TEXT,
                removal_reason  TEXT,
                created_at      INTEGER NOT NULL,
                CHECK ((removed_date IS NULL) = (removal_reason IS NULL)),
                CHECK (removed_date IS NULL OR removed_date >= effective_date)
            ) STRICT;

            -- One live allocation per source and document; removed ones stay as history.
            CREATE UNIQUE INDEX idx_%1$s_allocation_live ON %1$s_allocation (source_type, source_id, document_id)
                WHERE removed_date IS NULL;

            CREATE INDEX idx_%1$s_allocation_document ON %1$s_allocation (document_id);
            CREATE INDEX idx_%1$s_allocation_source ON %1$s_allocation (source_type, source_id);

            -- Allocations are added or marked removed, never edited or deleted.
            CREATE TRIGGER %1$s_allocation_no_update BEFORE UPDATE ON %1$s_allocation
            WHEN NOT (OLD.removed_date IS NULL AND NEW.removed_date IS NOT NULL
                      AND NEW.source_type = OLD.source_type AND NEW.source_id = OLD.source_id
                      AND NEW.document_id = OLD.document_id AND NEW.amount_e4 = OLD.amount_e4
                      AND NEW.effective_date = OLD.effective_date)
            BEGIN
                SELECT RAISE(ABORT, 'An allocation cannot be changed; remove it and allocate again');
            END;

            CREATE TRIGGER %1$s_allocation_no_delete BEFORE DELETE ON %1$s_allocation
            BEGIN
                SELECT RAISE(ABORT, 'An allocation is removed, never deleted');
            END;

            -- The target must be a posted invoice or bill, never a note.
            CREATE TRIGGER %1$s_allocation_target BEFORE INSERT ON %1$s_allocation
            WHEN NOT EXISTS (SELECT 1 FROM %1$s_document
                             WHERE id = NEW.document_id AND status = 'POSTED'
                               AND original_document_id IS NULL)
            BEGIN
                SELECT RAISE(ABORT, 'Only a posted invoice or bill can be allocated to');
            END;

            -- The source must exist, be posted, and belong to the same party.
            CREATE TRIGGER %1$s_allocation_source BEFORE INSERT ON %1$s_allocation
            WHEN NOT EXISTS (
                SELECT 1 FROM %1$s_settlement s JOIN %1$s_document d ON d.id = NEW.document_id
                WHERE NEW.source_type = 'SETTLEMENT' AND s.id = NEW.source_id
                  AND s.status = 'POSTED' AND s.party_id = d.party_id
                UNION ALL
                SELECT 1 FROM %1$s_document n JOIN %1$s_document d ON d.id = NEW.document_id
                WHERE NEW.source_type = 'NOTE' AND n.id = NEW.source_id AND n.status = 'POSTED'
                  AND n.original_document_id IS NOT NULL AND n.party_id = d.party_id)
            BEGIN
                SELECT RAISE(ABORT, 'An allocation needs a posted receipt, payment or note of the same party');
            END;

            -- A document cannot receive more than its total.
            CREATE TRIGGER %1$s_allocation_document_limit BEFORE INSERT ON %1$s_allocation
            WHEN (SELECT COALESCE(SUM(amount_e4), 0) FROM %1$s_allocation
                  WHERE document_id = NEW.document_id AND removed_date IS NULL)
                 + NEW.amount_e4 > (SELECT total_e4 FROM %1$s_document WHERE id = NEW.document_id)
            BEGIN
                SELECT RAISE(ABORT, 'This would allocate more than the document total');
            END;

            -- A source cannot allocate more than it holds.
            CREATE TRIGGER %1$s_allocation_source_limit BEFORE INSERT ON %1$s_allocation
            WHEN (SELECT COALESCE(SUM(amount_e4), 0) FROM %1$s_allocation
                  WHERE source_type = NEW.source_type AND source_id = NEW.source_id AND removed_date IS NULL)
                 + NEW.amount_e4
                 > CASE NEW.source_type
                       WHEN 'SETTLEMENT' THEN (SELECT amount_e4 FROM %1$s_settlement WHERE id = NEW.source_id)
                       ELSE (SELECT total_e4 FROM %1$s_document WHERE id = NEW.source_id)
                   END
            BEGIN
                SELECT RAISE(ABORT, 'This would allocate more than the receipt, payment or note holds');
            END;

            -- Nothing allocated may be cancelled underneath its live
            -- allocations (P7-12): remove them first.
            CREATE TRIGGER %1$s_settlement_cancel_unallocated
            BEFORE UPDATE OF status ON %1$s_settlement
            WHEN NEW.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM %1$s_allocation
                WHERE source_type = 'SETTLEMENT' AND source_id = OLD.id AND removed_date IS NULL)
            BEGIN
                SELECT RAISE(ABORT, 'Remove this receipt or payment''s allocations before cancelling it');
            END;

            CREATE TRIGGER %1$s_document_cancel_unallocated
            BEFORE UPDATE OF status ON %1$s_document
            WHEN NEW.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM %1$s_allocation
                WHERE removed_date IS NULL
                  AND (document_id = OLD.id OR (source_type = 'NOTE' AND source_id = OLD.id)))
            BEGIN
                SELECT RAISE(ABORT, 'This document has allocations; remove them before cancelling');
            END;

            """.formatted(side);
    }

    /**
     * Phase 7e quotations and orders.
     *
     * <p>Commitments, not accounting: nothing here posts. An order is fulfilled
     * by the invoices or bills raised against it (P7-18), linked line by line
     * through {@code order_line_id}; the fulfilled quantity is derived from
     * those links and never stored - the reference's {@code deliveredQty} is
     * written by nothing (R-14).
     *
     * <p>Quotations and sales orders are owned by {@code sales-orders},
     * purchase orders by {@code purchase-orders}. The new {@code order_line_id}
     * columns sit on the invoicing modules' line tables and are written by them
     * when a document is created from an order.
     */
    private static final Migration COMPANY_V8_ORDERS = new Migration(8, "quotations and orders", """
            CREATE TABLE sales_quotation (
                id             TEXT    NOT NULL PRIMARY KEY,
                -- Given when the quotation is sent (P7-8 pattern): drafts leave no gap.
                number         TEXT UNIQUE,
                party_id       TEXT    NOT NULL REFERENCES party (id),
                quotation_date TEXT    NOT NULL,
                valid_until    TEXT    NOT NULL,
                -- P7-19: expiry is derived from valid_until, never stored.
                status         TEXT    NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'CONVERTED')),
                amount_e4      INTEGER NOT NULL DEFAULT 0 CHECK (amount_e4 >= 0),
                narration      TEXT,
                created_at     INTEGER NOT NULL,
                updated_at     INTEGER NOT NULL,
                CHECK ((status = 'DRAFT') = (number IS NULL)),
                CHECK (valid_until >= quotation_date)
            ) STRICT;
            """ + orderLines("sales_quotation", "quotation_id") + orderTable("sales_order", "quotation_id TEXT REFERENCES sales_quotation (id),")
            + orderLines("sales_order", "order_id") + orderTable("purchase_order", "") + orderLines("purchase_order", "order_id")
            + """
            -- A quotation is frozen once sent; only its status moves on.
            CREATE TRIGGER sales_quotation_frozen
            BEFORE UPDATE ON sales_quotation
            WHEN OLD.status <> 'DRAFT'
             AND NOT (NEW.number = OLD.number AND NEW.party_id = OLD.party_id
                      AND NEW.quotation_date = OLD.quotation_date AND NEW.valid_until = OLD.valid_until
                      AND NEW.amount_e4 = OLD.amount_e4)
            BEGIN
                SELECT RAISE(ABORT, 'A sent quotation cannot be changed');
            END;

            CREATE TRIGGER sales_quotation_no_delete
            BEFORE DELETE ON sales_quotation
            WHEN OLD.status <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'A sent quotation cannot be deleted');
            END;

            -- Invoice and bill lines remember the order line they fulfil.
            ALTER TABLE sales_document_line ADD COLUMN order_line_id TEXT REFERENCES sales_order_line (id);
            ALTER TABLE purchase_document_line ADD COLUMN order_line_id TEXT REFERENCES purchase_order_line (id);
            CREATE INDEX idx_sales_document_line_order ON sales_document_line (order_line_id);
            CREATE INDEX idx_purchase_document_line_order ON purchase_document_line (order_line_id);

            -- A quotation converted straight into an invoice (P7-19).
            ALTER TABLE sales_document ADD COLUMN quotation_id TEXT REFERENCES sales_quotation (id);
            """);

    private static String orderTable(String table, String extraColumns) {
        return """
            CREATE TABLE %1$s (
                id            TEXT    NOT NULL PRIMARY KEY,
                number        TEXT UNIQUE,
                party_id      TEXT    NOT NULL REFERENCES party (id),
                order_date    TEXT    NOT NULL,
                expected_date TEXT,
                %2$s
                -- Fulfilment (open / partial / fulfilled) is derived from linked
                -- invoice or bill lines. CLOSED is a short-close (P7-20).
                status        TEXT    NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'CONFIRMED', 'CLOSED', 'CANCELLED')),
                close_reason  TEXT,
                amount_e4     INTEGER NOT NULL DEFAULT 0 CHECK (amount_e4 >= 0),
                narration     TEXT,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                CHECK ((status = 'DRAFT') = (number IS NULL)),
                CHECK ((status IN ('CLOSED', 'CANCELLED')) = (close_reason IS NOT NULL)),
                CHECK (expected_date IS NULL OR expected_date >= order_date)
            ) STRICT;

            CREATE INDEX idx_%1$s_party ON %1$s (party_id, order_date);

            -- Confirmed orders are frozen; only CONFIRMED -> CLOSED / CANCELLED.
            CREATE TRIGGER %1$s_frozen
            BEFORE UPDATE ON %1$s
            WHEN OLD.status <> 'DRAFT'
             AND NOT (OLD.status = 'CONFIRMED' AND NEW.status IN ('CLOSED', 'CANCELLED')
                      AND NEW.number = OLD.number AND NEW.party_id = OLD.party_id
                      AND NEW.order_date = OLD.order_date AND NEW.amount_e4 = OLD.amount_e4)
            BEGIN
                SELECT RAISE(ABORT, 'A confirmed order cannot be changed; close or cancel it');
            END;

            CREATE TRIGGER %1$s_no_delete
            BEFORE DELETE ON %1$s
            WHEN OLD.status <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'A confirmed order cannot be deleted; close or cancel it');
            END;

            """.formatted(table, extraColumns);
    }

    private static String orderLines(String parent, String parentColumn) {
        return """
            CREATE TABLE %1$s_line (
                id           TEXT    NOT NULL PRIMARY KEY,
                %2$s    TEXT    NOT NULL REFERENCES %1$s (id) ON DELETE CASCADE,
                sequence     INTEGER NOT NULL,
                -- Orders and quotations name items: they require inventory (B-7).
                item_id      TEXT    NOT NULL REFERENCES item (id),
                description  TEXT    NOT NULL,
                quantity_e4  INTEGER NOT NULL CHECK (quantity_e4 > 0),
                rate_e4      INTEGER NOT NULL CHECK (rate_e4 >= 0),
                discount_bp  INTEGER NOT NULL DEFAULT 0 CHECK (discount_bp BETWEEN 0 AND 10000),
                amount_e4    INTEGER NOT NULL CHECK (amount_e4 >= 0 AND amount_e4 %% 100 = 0),
                -- The warehouse a conversion proposes for the invoice line.
                warehouse_id TEXT REFERENCES warehouse (id),
                UNIQUE (%2$s, sequence)
            ) STRICT;

            CREATE TRIGGER %1$s_line_frozen_update
            BEFORE UPDATE ON %1$s_line
            WHEN (SELECT status FROM %1$s WHERE id = OLD.%2$s) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines cannot change once confirmed or sent');
            END;

            CREATE TRIGGER %1$s_line_frozen_insert
            BEFORE INSERT ON %1$s_line
            WHEN (SELECT status FROM %1$s WHERE id = NEW.%2$s) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines cannot change once confirmed or sent');
            END;

            CREATE TRIGGER %1$s_line_frozen_delete
            BEFORE DELETE ON %1$s_line
            WHEN (SELECT status FROM %1$s WHERE id = OLD.%2$s) <> 'DRAFT'
            BEGIN
                SELECT RAISE(ABORT, 'Lines cannot change once confirmed or sent');
            END;

            """.formatted(parent, parentColumn);
    }

    /**
     * Phase 8 valuation (periodic 5a): persisted costing per item and warehouse
     * (A-1), so the value of stock at any past date is read, not reconstructed
     * from today's quantities (D-24).
     *
     * <p>{@code valuation_entry}, {@code fifo_layer} and {@code fifo_consumption}
     * are derived from the movement log in costing order (date, then entry
     * order) and are re-derived forward from a backdated entry's date (P8-3).
     * The movement log stays the record. {@code valuation_run} records each
     * posted closing-stock valuation and whether later entries made it stale.
     *
     * <p>All owned by {@code inventory}.
     */
    private static final Migration COMPANY_V9_VALUATION = new Migration(9, "inventory valuation", """
            -- A movement that undoes an earlier one - a sales return, a purchase
            -- return, a cancellation - is valued at the cost of what it undoes.
            -- The caller names that source; inventory never reads the documents'
            -- own tables to find it.
            ALTER TABLE stock_movement ADD COLUMN cost_source_type TEXT;
            ALTER TABLE stock_movement ADD COLUMN cost_source_id TEXT;

            -- P8-1: weighted average (moving, P8-2) or FIFO; LIFO is not permitted (AS 2).
            ALTER TABLE item ADD COLUMN costing_method TEXT NOT NULL DEFAULT 'WEIGHTED_AVERAGE'
                CHECK (costing_method IN ('WEIGHTED_AVERAGE', 'FIFO'));

            CREATE TRIGGER item_costing_method_fixed_once_moved
            BEFORE UPDATE OF costing_method ON item
            WHEN NEW.costing_method IS NOT OLD.costing_method
             AND EXISTS (SELECT 1 FROM stock_movement WHERE item_id = OLD.id)
            BEGIN
                SELECT RAISE(ABORT, 'This item has stock movements; its costing method cannot change');
            END;

            CREATE TABLE valuation_entry (
                id                TEXT    NOT NULL PRIMARY KEY,
                movement_id       TEXT    NOT NULL REFERENCES stock_movement (id),
                item_id           TEXT    NOT NULL REFERENCES item (id),
                warehouse_id      TEXT    NOT NULL REFERENCES warehouse (id),
                entry_date        TEXT    NOT NULL,
                -- Costing order within a date: the movement's insertion order.
                entry_order       INTEGER NOT NULL,
                quantity_e4       INTEGER NOT NULL CHECK (quantity_e4 <> 0),
                value_e4          INTEGER NOT NULL,
                quantity_after_e4 INTEGER NOT NULL CHECK (quantity_after_e4 >= 0),
                value_after_e4    INTEGER NOT NULL CHECK (value_after_e4 >= 0),
                -- Nothing on hand is worth nothing.
                CHECK (quantity_after_e4 > 0 OR value_after_e4 = 0),
                UNIQUE (movement_id, warehouse_id)
            ) STRICT;

            CREATE INDEX idx_valuation_entry_position
                ON valuation_entry (item_id, warehouse_id, entry_date, entry_order);

            CREATE TABLE fifo_layer (
                id              TEXT    NOT NULL PRIMARY KEY,
                entry_id        TEXT    NOT NULL UNIQUE REFERENCES valuation_entry (id),
                item_id         TEXT    NOT NULL REFERENCES item (id),
                warehouse_id    TEXT    NOT NULL REFERENCES warehouse (id),
                layer_date      TEXT    NOT NULL,
                layer_order     INTEGER NOT NULL,
                quantity_e4     INTEGER NOT NULL CHECK (quantity_e4 > 0),
                value_e4        INTEGER NOT NULL CHECK (value_e4 >= 0)
            ) STRICT;

            CREATE INDEX idx_fifo_layer_position ON fifo_layer (item_id, warehouse_id, layer_date, layer_order);

            CREATE TABLE fifo_consumption (
                id           TEXT    NOT NULL PRIMARY KEY,
                entry_id     TEXT    NOT NULL REFERENCES valuation_entry (id),
                layer_id     TEXT    NOT NULL REFERENCES fifo_layer (id),
                quantity_e4  INTEGER NOT NULL CHECK (quantity_e4 > 0),
                value_e4     INTEGER NOT NULL CHECK (value_e4 >= 0)
            ) STRICT;

            CREATE INDEX idx_fifo_consumption_layer ON fifo_consumption (layer_id);
            CREATE INDEX idx_fifo_consumption_entry ON fifo_consumption (entry_id);

            CREATE TABLE valuation_run (
                id          TEXT    NOT NULL PRIMARY KEY,
                run_date    TEXT    NOT NULL,
                value_e4    INTEGER NOT NULL CHECK (value_e4 >= 0),
                -- The closing-stock voucher; null when nothing needed posting.
                voucher_id  TEXT REFERENCES voucher (id),
                status      TEXT    NOT NULL DEFAULT 'CURRENT'
                    CHECK (status IN ('CURRENT', 'STALE', 'SUPERSEDED')),
                created_at  INTEGER NOT NULL
            ) STRICT;

            CREATE INDEX idx_valuation_run_date ON valuation_run (run_date, status);
            """);
}

package com.accubook.persistence.guard;

import java.sql.SQLException;

/**
 * A write was refused at the persistence layer.
 *
 * <p>Extends {@link SQLException} so it travels the same path as any other write
 * failure and cannot be caught-and-ignored by code that only handles SQL errors.
 */
public class UnauthorizedWriteException extends SQLException {

    private final String table;

    public UnauthorizedWriteException(String table, String reason) {
        super("Refusing to write '" + table + "': " + reason);
        this.table = table;
    }

    public String table() {
        return table;
    }
}

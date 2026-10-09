package com.accubook.app.company;

import java.time.Instant;

/**
 * A company registered in this installation.
 *
 * <p>This record lives in {@code app.db}. The company's actual books live in a
 * separate {@code company.db} file - decision B-6. The registry knows only where
 * to find a company and what schema version its file is at; it holds no
 * accounting data.
 */
public record Company(
        String id,
        String name,
        int schemaVersion,
        Instant createdAt,
        Instant lastOpenedAt) {
}

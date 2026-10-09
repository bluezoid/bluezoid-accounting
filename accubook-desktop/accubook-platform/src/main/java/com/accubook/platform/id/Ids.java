package com.accubook.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Application-generated identifiers.
 *
 * <p>IDs are generated in application code, not by the database. The reference
 * application does the same (Prisma {@code cuid()}), so there are no sequences
 * to emulate and an entity can carry its identity before it is persisted.
 *
 * <p>UUIDv7 is used rather than v4 because its leading timestamp keeps inserts
 * roughly sequential. Random v4 keys scatter across a B-tree index, which costs
 * page splits on every insert - noticeable once a company has a few years of
 * vouchers.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /**
     * A time-ordered UUIDv7 (RFC 9562): 48-bit millisecond timestamp, 4-bit
     * version, 12 random bits, 2-bit variant, 62 random bits.
     */
    public static UUID newId() {
        byte[] value = new byte[16];
        RANDOM.nextBytes(value);

        long timestamp = System.currentTimeMillis();
        value[0] = (byte) (timestamp >>> 40);
        value[1] = (byte) (timestamp >>> 32);
        value[2] = (byte) (timestamp >>> 24);
        value[3] = (byte) (timestamp >>> 16);
        value[4] = (byte) (timestamp >>> 8);
        value[5] = (byte) timestamp;

        value[6] = (byte) ((value[6] & 0x0F) | 0x70); // version 7
        value[8] = (byte) ((value[8] & 0x3F) | 0x80); // IETF variant

        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (value[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            low = (low << 8) | (value[i] & 0xFFL);
        }
        return new UUID(high, low);
    }

    /** Canonical string form, used as the stored TEXT primary key. */
    public static String newIdString() {
        return newId().toString();
    }
}

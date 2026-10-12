package com.accubook.returns;

import java.time.LocalDate;

/** A return marked filed (P9-14): its portal JSON and SHA-256 are kept, never edited. */
public record FiledReturn(String id, String returnType, ReturnPeriod period, String gstin, LocalDate filedOn,
                          String acknowledgement, String snapshotSha256, boolean unlocked, String unlockReason) {
}

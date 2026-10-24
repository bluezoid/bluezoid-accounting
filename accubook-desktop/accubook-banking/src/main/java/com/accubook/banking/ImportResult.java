package com.accubook.banking;

/**
 * What an import did.
 *
 * @param alreadyImported lines already present from an earlier, overlapping statement
 */
public record ImportResult(String importId, int linesRead, int linesAdded, int alreadyImported) {
}

package com.accubook.accounting.chart;

/**
 * A group in the chart of accounts. A user's group is always a sub-group of
 * another (S2-1), so its nature, its place in gross profit and its cash-flow
 * class come from where it sits.
 *
 * @param parentId null only for the roots of each nature
 */
public record LedgerGroup(String id, String parentId, String systemCode, String name, AccountNature nature,
                          boolean system) {
}

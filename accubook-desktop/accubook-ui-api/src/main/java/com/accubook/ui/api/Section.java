package com.accubook.ui.api;

/** Where a screen sits in the navigation, in the order the shell shows them. */
public enum Section {
    MASTERS("Masters"),
    TRANSACTIONS("Transactions"),
    REPORTS("Reports"),
    GST("GST and TDS"),
    BANKING("Banking"),
    YEAR_END("Year-end"),
    SETTINGS("Settings");

    private final String title;

    Section(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}

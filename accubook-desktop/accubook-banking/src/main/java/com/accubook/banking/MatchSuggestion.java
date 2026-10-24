package com.accubook.banking;

import java.util.List;

/**
 * A proposed match of one statement line to one book entry (P10-3). Never
 * applied until the user confirms it.
 *
 * @param certain      a unique candidate with the exact amount and the exact reference -
 *                     the only kind "confirm all certain" confirms
 * @param alternatives other entries of the same amount, for the user to choose from
 */
public record MatchSuggestion(StoredLine line, BookEntry entry, int score, List<String> reasons, boolean certain,
                              List<BookEntry> alternatives) {

    public MatchSuggestion {
        reasons = List.copyOf(reasons);
        alternatives = List.copyOf(alternatives);
    }
}

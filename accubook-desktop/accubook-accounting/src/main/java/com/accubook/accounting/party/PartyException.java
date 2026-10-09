package com.accubook.accounting.party;

/**
 * A party operation was refused. The message is written for the person who
 * attempted it.
 */
public class PartyException extends RuntimeException {
    public PartyException(String message) {
        super(message);
    }

    public PartyException(String message, Throwable cause) {
        super(message, cause);
    }
}

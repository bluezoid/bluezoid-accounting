package com.accubook.accounting.year;

/** A year-end operation was refused. */
public class YearEndException extends RuntimeException {
    public YearEndException(String message) {
        super(message);
    }

    public YearEndException(String message, Throwable cause) {
        super(message, cause);
    }
}

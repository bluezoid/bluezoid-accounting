package com.accubook.accounting.posting;

/** A posting was refused. Nothing was written. */
public class PostingException extends RuntimeException {
    public PostingException(String message) {
        super(message);
    }

    public PostingException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.accubook.tax;

/** A rule pack was refused: malformed, unsigned, untrusted or older than the one installed. */
public class RulePackException extends RuntimeException {
    public RulePackException(String message) {
        super(message);
    }

    public RulePackException(String message, Throwable cause) {
        super(message, cause);
    }
}

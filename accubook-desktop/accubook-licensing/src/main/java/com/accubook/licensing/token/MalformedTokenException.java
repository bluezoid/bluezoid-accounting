package com.accubook.licensing.token;

/** An entitlement could not be parsed. Treated exactly like an invalid signature. */
public class MalformedTokenException extends RuntimeException {

    public MalformedTokenException(String message) {
        super(message);
    }
}

package com.accubook.licensing.key;

/**
 * A licence key was not well formed.
 *
 * <p>Messages here are shown to the customer during activation, so they say what
 * to do rather than what failed internally.
 */
public class InvalidLicenceKeyException extends RuntimeException {

    public InvalidLicenceKeyException(String message) {
        super(message);
    }
}

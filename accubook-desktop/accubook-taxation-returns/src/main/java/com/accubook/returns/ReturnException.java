package com.accubook.returns;

/** A return cannot be built, filed or unlocked as asked. */
public class ReturnException extends RuntimeException {
    public ReturnException(String message) {
        super(message);
    }

    public ReturnException(String message, Throwable cause) {
        super(message, cause);
    }
}

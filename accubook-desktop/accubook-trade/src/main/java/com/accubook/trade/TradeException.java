package com.accubook.trade;

/** A document operation was refused. Written for the person who attempted it. */
public class TradeException extends RuntimeException {
    public TradeException(String message) {
        super(message);
    }

    public TradeException(String message, Throwable cause) {
        super(message, cause);
    }
}

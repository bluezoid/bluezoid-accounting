package com.accubook.inventory;

/** An inventory operation was refused. Written for the person who attempted it. */
public class InventoryException extends RuntimeException {
    public InventoryException(String message) {
        super(message);
    }

    public InventoryException(String message, Throwable cause) {
        super(message, cause);
    }
}

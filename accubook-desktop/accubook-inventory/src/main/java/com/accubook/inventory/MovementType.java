package com.accubook.inventory;

/**
 * Every way stock can change, and which way each goes.
 *
 * <p>Direction is a property of the type, held in one place - the idea worth
 * keeping from the reference's {@code movement-rules.ts}, written after a SALE
 * that named only a destination warehouse <em>increased</em> stock. The schema
 * enforces the same rule.
 *
 * <p>{@link #PURCHASE_RETURN} is new: the reference cannot represent goods going
 * back to a supplier at all.
 */
public enum MovementType {
    OPENING(Direction.IN),
    PURCHASE(Direction.IN),
    SALES_RETURN(Direction.IN),
    ADJUSTMENT_IN(Direction.IN),
    SALE(Direction.OUT),
    PURCHASE_RETURN(Direction.OUT),
    ADJUSTMENT_OUT(Direction.OUT),
    TRANSFER(Direction.TRANSFER);

    public enum Direction { IN, OUT, TRANSFER }

    private final Direction direction;

    MovementType(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    public boolean needsSource() {
        return direction != Direction.IN;
    }

    public boolean needsDestination() {
        return direction != Direction.OUT;
    }
}

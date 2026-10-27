package com.accubook.ui.api;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.TextField;

import java.math.BigDecimal;

/**
 * An amount, read exactly when the person leaves the field or presses Enter
 * ({@link AmountInput}: never rounded, never a double) and then shown with
 * Indian grouping. Unreadable text leaves the value empty and says why.
 */
public final class AmountField extends TextField {

    private final ObjectProperty<BigDecimal> value = new SimpleObjectProperty<>(this, "value");
    private final ReadOnlyStringWrapper error = new ReadOnlyStringWrapper(this, "error");
    private final AmountInput input;

    public AmountField(AmountInput input) {
        this.input = input;
        getStyleClass().add("amount-field");
        setAlignment(Pos.CENTER_RIGHT);
        setPrefColumnCount(12);
        focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                commit();
            } else {
                // Typing replaces what is there, as in any accounting entry.
                javafx.application.Platform.runLater(this::selectAll);
            }
        });
        addEventFilter(javafx.event.ActionEvent.ACTION, e -> commit());
    }

    /** A money field: two places, not negative. */
    public static AmountField money() {
        return new AmountField(AmountInput.money());
    }

    /** Read what is typed; true if it is an amount or blank. */
    public boolean commit() {
        try {
            BigDecimal parsed = input.parse(getText());
            error.set(null);
            DateField.Invalid.clear(this);
            value.set(parsed);
            setText(parsed == null ? "" : AmountFormat.format(parsed));
            return true;
        } catch (IllegalArgumentException e) {
            error.set(e.getMessage());
            value.set(null);
            DateField.Invalid.mark(this, e.getMessage());
            return false;
        }
    }

    public ObjectProperty<BigDecimal> valueProperty() {
        return value;
    }

    public BigDecimal getValue() {
        return value.get();
    }

    public void setValue(BigDecimal amount) {
        error.set(null);
        DateField.Invalid.clear(this);
        value.set(amount);
        setText(amount == null ? "" : AmountFormat.format(amount));
    }

    public ReadOnlyStringProperty errorProperty() {
        return error.getReadOnlyProperty();
    }
}

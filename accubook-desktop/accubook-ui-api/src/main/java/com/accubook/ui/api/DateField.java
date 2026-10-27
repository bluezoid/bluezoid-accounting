package com.accubook.ui.api;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * A date typed day first (S-4), read when the person leaves the field or
 * presses Enter, then shown as dd-mm-yyyy. Something that is not a date leaves
 * the value empty, marks the field and says why: a form never saves a date it
 * guessed.
 */
public final class DateField extends TextField {

    private final ObjectProperty<LocalDate> value = new SimpleObjectProperty<>(this, "value");
    private final ReadOnlyStringWrapper error = new ReadOnlyStringWrapper(this, "error");
    private final Supplier<LocalDate> today;
    private final Supplier<LocalDate> fiscalYearStart;

    public DateField(Supplier<LocalDate> today, Supplier<LocalDate> fiscalYearStart) {
        this.today = today;
        this.fiscalYearStart = fiscalYearStart;
        getStyleClass().add("date-field");
        setPromptText("dd-mm-yyyy");
        setPrefColumnCount(10);
        focusedProperty().addListener((obs, was, is) -> {
            if (!is) {
                commit();
            } else {
                // Typing replaces what is there, as in any accounting entry.
                javafx.application.Platform.runLater(this::selectAll);
            }
        });
        addEventFilter(javafx.event.ActionEvent.ACTION, e -> commit());
        value.addListener((obs, old, now) -> {
            if (error.get() == null) {
                setText(DateInput.format(now));
            }
        });
    }

    /** Read what is typed; true if it is a date or blank. */
    public boolean commit() {
        try {
            LocalDate parsed = DateInput.parse(getText(), today.get(), fiscalYearStart.get());
            error.set(null);
            Invalid.clear(this);
            value.set(parsed);
            setText(DateInput.format(parsed));
            return true;
        } catch (IllegalArgumentException e) {
            error.set(e.getMessage());
            value.set(null);
            Invalid.mark(this, e.getMessage());
            return false;
        }
    }

    public ObjectProperty<LocalDate> valueProperty() {
        return value;
    }

    public LocalDate getValue() {
        return value.get();
    }

    public void setValue(LocalDate date) {
        error.set(null);
        Invalid.clear(this);
        value.set(date);
        setText(DateInput.format(date));
    }

    /** Why the text is not a date, or null. */
    public ReadOnlyStringProperty errorProperty() {
        return error.getReadOnlyProperty();
    }

    /** Marks a field as holding something unreadable. */
    static final class Invalid {

        static final String STYLE = "invalid";

        private Invalid() {
        }

        static void mark(TextField field, String message) {
            if (!field.getStyleClass().contains(STYLE)) {
                field.getStyleClass().add(STYLE);
            }
            field.setStyle("-fx-border-color: #c62828;");
            field.setTooltip(new Tooltip(message));
        }

        static void clear(TextField field) {
            field.getStyleClass().remove(STYLE);
            field.setStyle("");
            field.setTooltip(null);
        }
    }
}

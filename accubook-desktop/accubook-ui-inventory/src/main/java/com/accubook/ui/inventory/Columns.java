package com.accubook.ui.inventory;

import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.util.StringConverter;

import java.util.function.Function;

/** Small helpers the inventory screens share. */
final class Columns {

    private Columns() {
    }

    static <T> TableColumn<T, String> of(String title, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    static <T> void show(ComboBox<T> box, Function<T, String> text) {
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : text.apply(value);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        });
        box.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? "" : text.apply(value));
            }
        });
    }
}

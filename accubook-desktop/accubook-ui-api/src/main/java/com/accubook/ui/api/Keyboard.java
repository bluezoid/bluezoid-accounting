package com.accubook.ui.api;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.List;

/**
 * Keyboard-first entry (S-1): Enter moves to the next field, as accountants
 * used to Tally expect, and on the last field does the form's action, after
 * reading the field's value. Tab and
 * the mouse still work as usual. A text area keeps Enter for new lines.
 */
public final class Keyboard {

    private Keyboard() {
    }

    /** Enter in each field moves to the next; in the last it fires {@code action}. */
    public static void enterAdvances(List<? extends Node> fields, ButtonBase action) {
        for (int i = 0; i < fields.size(); i++) {
            Node field = fields.get(i);
            if (field instanceof TextArea) {
                continue;
            }
            Node next = i + 1 < fields.size() ? fields.get(i + 1) : null;
            field.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() != KeyCode.ENTER || e.isShiftDown() || e.isControlDown()) {
                    return;
                }
                e.consume();
                if (field instanceof DateField date) {
                    date.commit();
                } else if (field instanceof AmountField amount) {
                    amount.commit();
                }
                if (next != null) {
                    next.requestFocus();
                } else if (action != null && !action.isDisabled()) {
                    action.fire();
                }
            });
        }
    }
}

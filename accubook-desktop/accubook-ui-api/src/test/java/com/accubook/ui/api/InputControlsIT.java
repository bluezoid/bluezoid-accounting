package com.accubook.ui.api;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared controls typed into, as a person would, on a real display. */
@DisplayName("Input controls")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class InputControlsIT extends ApplicationTest {

    private DateField date;
    private AmountField amount;
    private TextField note;
    private final AtomicInteger saved = new AtomicInteger();
    private BigDecimal amountWhenSaved;

    @Override
    public void start(Stage stage) {
        date = new DateField(() -> LocalDate.of(2026, 10, 24), () -> LocalDate.of(2026, 4, 1));
        date.setId("date");
        amount = AmountField.money();
        amount.setId("amount");
        note = new TextField();
        note.setId("note");
        Button save = new Button("Save");
        save.setOnAction(e -> {
            saved.incrementAndGet();
            amountWhenSaved = amount.getValue();
        });
        Keyboard.enterAdvances(List.of(date, note, amount), save);
        stage.setScene(new Scene(new VBox(8, date, note, amount, save), 400, 200));
        stage.show();
    }

    @Test
    @DisplayName("Enter moves field to field; on the last it saves with the value just typed")
    void enterAdvancesAndSaves() {
        clickOn("#date").write("15-1").type(KeyCode.ENTER);
        assertThat(note.isFocused()).isTrue();
        assertThat(date.getValue()).as("January falls in the second half of FY 2026-27")
                .isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(date.getText()).isEqualTo("15-01-2027");

        write("Rent").type(KeyCode.ENTER);
        assertThat(amount.isFocused()).isTrue();
        write("123456.5").type(KeyCode.ENTER);

        assertThat(saved.get()).isEqualTo(1);
        assertThat(amountWhenSaved).isEqualByComparingTo("123456.50");
        assertThat(amount.getText()).isEqualTo("1,23,456.50");
    }

    @Test
    @DisplayName("an unreadable entry is marked, says why, and holds no value")
    void unreadable() {
        clickOn("#date").write("31-02-2026");
        clickOn("#amount").write("10.005");
        clickOn("#note");

        assertThat(date.getValue()).isNull();
        assertThat(date.errorProperty().get()).contains("not a date");
        assertThat(date.getStyleClass()).contains("invalid");
        assertThat(amount.getValue()).isNull();
        assertThat(amount.errorProperty().get()).contains("2 decimal places");

        clickOn("#date").eraseText(10).write("t");
        clickOn("#note");
        assertThat(date.getValue()).isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(date.getStyleClass()).doesNotContain("invalid");
    }
}

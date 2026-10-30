package com.lifedesk.ui.components;

import javafx.scene.chart.NumberAxis;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Date-based chart axes shared by the dashboard and health trend charts. */
public final class DateAxes {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM");

    private DateAxes() {
    }

    /**
     * X axis over epoch-day offsets in [from..to]: tick labels render as real
     * dates ("01/10") instead of raw offsets like "0.0, 2.5, 5.0".
     */
    public static NumberAxis epochDay(String label, LocalDate from, LocalDate to, double tickUnit) {
        NumberAxis x = new NumberAxis();
        x.setLabel(label);
        x.setMinorTickVisible(false);
        x.setLowerBound(0);
        x.setUpperBound(from.until(to, ChronoUnit.DAYS));
        x.setAutoRanging(false);
        x.setTickUnit(tickUnit);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return from.plusDays(Math.round(n.doubleValue())).format(DF);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        return x;
    }
}

package com.billtracker.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/** Formats arbitrary SQL result values for display: {@code ${@cells.format(value)}}. */
@Component("cells")
public class Cells {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String format(Object value) {
        return switch (value) {
            case null -> "—";
            case BigDecimal d -> d.stripTrailingZeros().scale() < 0
                    ? d.setScale(0).toPlainString() : d.stripTrailingZeros().toPlainString();
            case Double d -> String.format(Locale.ROOT, "%.2f", d);
            case LocalDate d -> d.toString();
            case java.time.Instant i -> TIMESTAMP.format(i.atZone(ZoneId.systemDefault()));
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Timestamp t -> TIMESTAMP.format(t.toLocalDateTime());
            case OffsetDateTime t -> TIMESTAMP.format(t.atZoneSameInstant(ZoneId.systemDefault()));
            case TemporalAccessor t -> t.toString();
            default -> value.toString();
        };
    }

    public String header(String column) {
        String s = column.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

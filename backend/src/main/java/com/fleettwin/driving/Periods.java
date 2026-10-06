package com.fleettwin.driving;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The {@code ?period=} query parameter: today, or a number of hours or days such as 24h, 7d, 30d. */
public final class Periods {

    private static final Pattern PERIOD = Pattern.compile("(\\d{1,4})([hd])");

    private Periods() {
    }

    public static Instant since(String period) {
        if ("today".equals(period)) {
            return Instant.now().truncatedTo(ChronoUnit.DAYS);
        }
        Matcher m = PERIOD.matcher(period);
        if (!m.matches() || Integer.parseInt(m.group(1)) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "period must be today, or like 24h, 7d, 30d");
        }
        long n = Long.parseLong(m.group(1));
        return Instant.now().minus("h".equals(m.group(2)) ? Duration.ofHours(n) : Duration.ofDays(n));
    }
}

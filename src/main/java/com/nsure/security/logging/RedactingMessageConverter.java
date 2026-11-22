package com.nsure.security.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Log redaction (NS-SH11-004): every log message is written with personal identifiers masked: email addresses,
 * phone numbers, 9-digit identity (Omang) numbers, passport-style numbers and bearer tokens. Registered for the
 * %m / %msg / %message conversion words in logback-spring.xml, so it covers every logger without code changes.
 * Medical content is kept out of logs at the source (events and messages carry references only).
 */
public class RedactingMessageConverter extends MessageConverter {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,2})[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w-])\\+?\\d[\\d ]{7,}(\\d{3})(?![\\w-])");
    private static final Pattern OMANG = Pattern.compile("(?<![\\w-])\\d{6}(\\d{3})(?![\\w-])");
    private static final Pattern PASSPORT = Pattern.compile("(?<![\\w-])([A-Z]{2})\\d{5,}(\\d{2})(?![\\w-])");

    @Override
    public String convert(ILoggingEvent event) {
        return redact(super.convert(event));
    }

    /** The message with personal identifiers masked; null stays null. */
    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = EMAIL.matcher(message).replaceAll("$1***@$2");
        out = BEARER.matcher(out).replaceAll("$1[redacted]");
        out = PHONE.matcher(out).replaceAll("***$1");
        out = OMANG.matcher(out).replaceAll("******$1");
        out = PASSPORT.matcher(out).replaceAll("$1*****$2");
        return out;
    }
}

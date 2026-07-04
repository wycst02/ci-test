package io.github.wycst.wastnet.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class LogFormatter extends Formatter {

    static ThreadLocal<Calendar> calendarThreadLocal = new ThreadLocal<Calendar>() {
        @Override
        protected Calendar initialValue() {
            return Calendar.getInstance();
        }
    };

    @Override
    public String format(LogRecord record) {

        StringBuilder sb = new StringBuilder();
        appendMillis(sb, record.getMillis());
        sb.append(" [");
        sb.append(Thread.currentThread().getName());
        sb.append("] ");
        sb.append(formatLevel(record.getLevel()));
        sb.append(" ");
        sb.append(record.getLoggerName());
        sb.append(" - ");

        // message
        String message = record.getMessage();
        java.util.ResourceBundle catalog = record.getResourceBundle();
        if (catalog != null) {
            try {
                message = catalog.getString(record.getMessage());
            } catch (java.util.MissingResourceException ex) {
                // Drop through.  Use record message as format
                message = record.getMessage();
            }
        }

        // parse parameters
        Object[] parameters = record.getParameters();
        if (parameters != null && parameters.length > 0) {
            // replace placeholders
            message = replacePlaceholder(message, "{}", parameters);
        }
        sb.append(message);
        sb.append("\n");

        boolean isException = record.getThrown() != null;
        if (isException) {
            sb.append(getThrowableContent(record.getThrown()));
        }

        return sb.toString();
    }

    private void appendMillis(StringBuilder sb, long millis) {
        Calendar calendar = calendarThreadLocal.get();
        calendar.setTimeInMillis(millis);
        // "Y-M-d H:m:s.S"
        sb.append(calendar.get(Calendar.YEAR));
        sb.append('-');
        int monthValue = calendar.get(Calendar.MONTH) + 1;
        if (monthValue > 9) {
            sb.append(monthValue);
        } else {
            sb.append(0).append(monthValue);
        }
        sb.append('-');
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        if (day > 9) {
            sb.append(day);
        } else {
            sb.append(0).append(day);
        }
        sb.append(' ');
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (hour > 9) {
            sb.append(hour);
        } else {
            sb.append(0).append(hour);
        }
        sb.append(':');
        int minute = calendar.get(Calendar.MINUTE);
        if (minute > 9) {
            sb.append(minute);
        } else {
            sb.append(0).append(minute);
        }
        sb.append(':');
        int second = calendar.get(Calendar.SECOND);
        if (second > 9) {
            sb.append(second);
        } else {
            sb.append(0).append(second);
        }

        int millisecond = calendar.get(Calendar.MILLISECOND);
        int dotIndex = sb.length();
        sb.append(millisecond + 1000);
        sb.setCharAt(dotIndex, '.');
    }

    private String formatLevel(Level level) {
        String levelName = level.getName();
        if (level == Level.INFO) {
            return "INFO ";
        } else if (level == Level.WARNING) {
            return "WARN ";
        } else if (level == Level.CONFIG) {
            return "DEBUG";
        } else if (level == Level.SEVERE) {
            return "ERROR";
        }
        return levelName;
    }

    /***
     * <p> eg: message: "{}, hello", placeholder: "{}", parameters: ["xx"] -> xx, hello</p>
     *
     * @param message
     * @param placeholder
     * @param parameters
     * @return
     */
    public static String replacePlaceholder(String message, String placeholder, Object... parameters) {

        int parameterCount;
        if (placeholder == null || placeholder.length() == 0 || parameters == null || (parameterCount = parameters.length) == 0) {
            return message;
        }
        int placeholderIndex = -1;
        if ((placeholderIndex = message.indexOf(placeholder)) == -1) {
            return message;
        }
        StringBuilder buffer = new StringBuilder();
        int fromIndex = 0;
        int placeholderLen = placeholder.length();
        int i = 0;
        while (placeholderIndex > -1) {
            buffer.append(message, fromIndex, placeholderIndex);
            if (i < parameterCount) {
                buffer.append(parameters[i++]);
            } else {
                buffer.append(placeholder);
            }
            fromIndex = placeholderIndex + placeholderLen;
            placeholderIndex = message.indexOf(placeholder, fromIndex);
        }
        if (fromIndex < message.length()) {
            buffer.append(message, fromIndex, message.length());
        }

        return buffer.toString();
    }

    public static String getThrowableContent(Throwable t) {
        if (t == null)
            return null;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            t.printStackTrace(pw);
            return sw.toString();
        } finally {
            pw.close();
        }
    }
}

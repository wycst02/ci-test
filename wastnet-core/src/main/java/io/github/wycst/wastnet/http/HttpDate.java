/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http;

import java.util.TimeZone;

/**
 * Lightweight date decomposition and formatting for HTTP Date header.
 * <p>
 * Provides both date decomposition (from timestamp to fields) and
 * ultra-fast byte-array formatting for HTTP Date headers.
 *
 * @author wangy
 * @since 2022/8/11
 */
public class HttpDate {

    // Milliseconds from year 1 (0001).1.1 to 1970.1.1
    public static final long RELATIVE_MILLS = 62135769600000L;
    // 1970-01-01 was Thursday (5 in 1=Sunday format)
    private static final int RELATIVE_DAY_OF_WEEK = 5;

    // ==================== Date formatting constants ====================

    // Pre-defined byte arrays for fast date formatting
    static final byte[][] WEEK_DAYS = {
            {'S', 'u', 'n'}, {'M', 'o', 'n'}, {'T', 'u', 'e'},
            {'W', 'e', 'd'}, {'T', 'h', 'u'}, {'F', 'r', 'i'}, {'S', 'a', 't'}
    };
    static final byte[][] MONTHS = {
            {'J', 'a', 'n'}, {'F', 'e', 'b'}, {'M', 'a', 'r'}, {'A', 'p', 'r'},
            {'M', 'a', 'y'}, {'J', 'u', 'n'}, {'J', 'u', 'l'}, {'A', 'u', 'g'},
            {'S', 'e', 'p'}, {'O', 'c', 't'}, {'N', 'o', 'v'}, {'D', 'e', 'c'}
    };
    // Pre-cached GMT timezone
    static final TimeZone GMT_TIMEZONE = TimeZone.getTimeZone("GMT");

    // ==================== Date header cache ====================

    // Date header line cached byte array: "Date: EEE, dd MMM yyyy HH:mm:ss GMT\r\n" (37 bytes)
    private static final byte[] DATE_HEADER_LINE_BYTES = new byte[37];
    private static long lastUpdateMillis;
    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;
    private static final long SECOND_MILLIS = 1000L;

    private static final int[] DAYS_ACCUM = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    private static final int[] DAYS_ACCUM_LEAP = {0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335};

    static {
        // Initialize truly static characters that never change
        // Date header prefix: "Date: " (6 bytes)
        DATE_HEADER_LINE_BYTES[1] = 'a';
        DATE_HEADER_LINE_BYTES[2] = 't';
        DATE_HEADER_LINE_BYTES[3] = 'e';
        DATE_HEADER_LINE_BYTES[4] = ':';
        DATE_HEADER_LINE_BYTES[5] = ' ';
        // Date format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes, starting from index 6)
        DATE_HEADER_LINE_BYTES[9] = ',';   // Comma after weekday
        DATE_HEADER_LINE_BYTES[10] = ' ';   // Space after comma
        DATE_HEADER_LINE_BYTES[13] = ' ';   // Space after day
        DATE_HEADER_LINE_BYTES[17] = ' ';  // Space after month
        DATE_HEADER_LINE_BYTES[22] = ' ';  // Space after year
        DATE_HEADER_LINE_BYTES[25] = ':';  // First colon
        DATE_HEADER_LINE_BYTES[28] = ':';  // Second colon
        DATE_HEADER_LINE_BYTES[31] = ' ';  // Space before GMT
        DATE_HEADER_LINE_BYTES[32] = 'G';  // G
        DATE_HEADER_LINE_BYTES[33] = 'M';  // M
        DATE_HEADER_LINE_BYTES[34] = 'T';  // T
        // CRLF suffix (2 bytes)
        DATE_HEADER_LINE_BYTES[35] = '\r';
        DATE_HEADER_LINE_BYTES[36] = '\n';

        // Initialize date cache
        updateDateCache(System.currentTimeMillis());

        // Update Date header case based on initial configuration
        updateDateHeaderCase();
    }

    private long epochDays;
    private int year, month, day;
    private int hourOfDay, minute, second;

    public HttpDate(long timeMillis, TimeZone timeZone) {
        setTime(timeMillis, timeZone);
    }

    private static boolean isLeapYear(int y) {
        return (y & 3) == 0 && (y % 100 != 0 || y % 400 == 0);
    }

    private void setTime(long timeMillis, TimeZone timeZone) {
        long ms = timeMillis + RELATIVE_MILLS + timeZone.getRawOffset();

        long seconds = ms / 1000;
        long days = seconds / 86400;
        int daySeconds = (int) (seconds - days * 86400);

        epochDays = timeMillis / 86400000;

        hourOfDay = daySeconds / 3600;
        minute = (daySeconds - hourOfDay * 3600) / 60;
        second = daySeconds % 60;

        // year/month/day from days since epoch
        long y = (days * 400) / 146097 + 1;
        long offset = (y - 1) * 365 + (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400 + 2;
        while (offset > days) {
            offset -= isLeapYear((int) --y) ? 366 : 365;
        }
        while (offset + (isLeapYear((int) y) ? 366 : 365) <= days) {
            offset += isLeapYear((int) y) ? 366 : 365;
            y++;
        }
        this.year = (int) y;

        int doy = (int) (days - offset);
        int[] accum = isLeapYear(this.year) ? DAYS_ACCUM_LEAP : DAYS_ACCUM;
        int m = 0;
        while (m < 11 && doy >= accum[m + 1]) {
            m++;
        }
        this.month = m + 1;
        this.day = doy - accum[m] + 1;
    }

    public int getYear() { return year; }

    public int getMonth() { return month; }

    public int getDay() { return day; }

    public int getHourOfDay() { return hourOfDay; }

    public int getMinute() { return minute; }

    public int getSecond() { return second; }

    /**
     * Day of week (1=Sunday, 2=Monday ... 7=Saturday).
     */
    public int getDayOfWeek() {
        int dow = (int) ((epochDays + RELATIVE_DAY_OF_WEEK - 1) % 7 + 1);
        return dow <= 0 ? dow + 7 : dow;
    }

    // ==================== Static formatting helpers ====================

    /**
     * Update date portion in target byte array at specified offset
     * Format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes)
     *
     * @param currentTimeMillis timestamp in milliseconds
     * @param targetBytes       target byte array to write date format
     * @param offset            offset in targetBytes where date portion starts
     */
    static void updateDatePart(long currentTimeMillis, byte[] targetBytes, int offset) {
        HttpDate gd = new HttpDate(currentTimeMillis, GMT_TIMEZONE);
        int dayOfWeek = gd.getDayOfWeek();
        int year = gd.getYear();

        // Update only the dynamic parts (weekday, day, month, year, time)
        System.arraycopy(WEEK_DAYS[dayOfWeek - 1], 0, targetBytes, offset, 3);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 5, gd.getDay());
        System.arraycopy(MONTHS[gd.getMonth() - 1], 0, targetBytes, offset + 8, 3);
        // Full year update using arithmetic optimization
        int yearDiv100 = (int) (year * 1374389535L >> 37);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 12, yearDiv100);
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 14, year - yearDiv100 * 100);

        // Update time portion (hour, minute, second)
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 17, gd.getHourOfDay());
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 20, gd.getMinute());
        HttpUnsafe.writeTwoDigitChar(targetBytes, offset + 23, gd.getSecond());
    }

    private static void updateDateCache(long currentTimeMillis) {
        updateDatePart(lastUpdateMillis = currentTimeMillis, DATE_HEADER_LINE_BYTES, 6);
    }

    private static void updateTimeInCache(long currentTimeMillis) {
        HttpDate gd = new HttpDate(currentTimeMillis, GMT_TIMEZONE);

        // Only update time portion - all static characters are already set
        // Offset by 6 to skip "Date: " prefix
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 23, gd.getHourOfDay());
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 26, gd.getMinute());
        HttpUnsafe.writeTwoDigitChar(DATE_HEADER_LINE_BYTES, 29, gd.getSecond());
        lastUpdateMillis = currentTimeMillis;
    }

    /**
     * Get current date header line as byte array, update cache if necessary
     * Returns complete header line: "Date: EEE, dd MMM yyyy HH:mm:ss GMT\r\n" (37 bytes)
     *
     * @return byte array representation of current date header line
     */
    static byte[] getCurrentDateHeaderLineBytes() {
        // For HTTP Date header, slight inconsistency is acceptable
        // The worst case is returning a slightly outdated date (within 1 second)
        // This is much better than the synchronization overhead
        updateCacheIfNeeded(System.currentTimeMillis());

        return DATE_HEADER_LINE_BYTES;
    }

    /**
     * Update cache based on time difference
     * Combines check and update logic for better performance
     * Note: Both updateDateCache and updateTimeInCache will update lastUpdateMillis
     */
    private static void updateCacheIfNeeded(long currentTimeMillis) {
        long currentSecond = currentTimeMillis / SECOND_MILLIS;
        long lastUpdateSecond = lastUpdateMillis / SECOND_MILLIS;

        // Seconds changed, need to check what kind of update
        if (currentSecond != lastUpdateSecond) {
            if (!isSameDay(currentTimeMillis, lastUpdateMillis)) {
                updateDateCache(currentTimeMillis); // Cross-day boundary
            } else {
                updateTimeInCache(currentTimeMillis); // Same day, just time changed
            }
        }
        // Same second, no update needed
    }

    /**
     * Update Date header first byte based on configuration
     * Only modifies the first character ('d' to 'D' for titlecase, or 'D' to 'd' for lowercase)
     */
    static void updateDateHeaderCase() {
        if (HttpHeaderUtils.isTitleCase()) {
            // Convert first letter to uppercase: 'd' -> 'D'
            DATE_HEADER_LINE_BYTES[0] = (byte) ('D');
        } else {
            // Convert first letter to lowercase: 'D' -> 'd'
            DATE_HEADER_LINE_BYTES[0] = (byte) ('d');
        }
    }

    /**
     * Get date as byte array for specified timestamp (date only, without header prefix)
     * Creates a new byte array with RFC 7231 date format
     * Format: "EEE, dd MMM yyyy HH:mm:ss GMT" (29 bytes)
     *
     * @param timestampMillis timestamp in milliseconds
     * @return byte array representation of GMT date for the given timestamp
     */
    static byte[] getDateHeaderBytes(long timestampMillis) {
        byte[] dateBytes = new byte[29];
        // Copy static characters from DATE_HEADER_LINE_BYTES (skip "Date: " prefix)
        System.arraycopy(DATE_HEADER_LINE_BYTES, 6, dateBytes, 0, 29);
        updateDatePart(timestampMillis, dateBytes, 0);
        return dateBytes;
    }

    /**
     * Check if two timestamps fall on the same GMT day
     *
     * @param sourceTimemills first timestamp in milliseconds
     * @param targetTimeMills second timestamp in milliseconds
     * @return true if both timestamps are in the same GMT day, false otherwise
     */
    static boolean isSameDay(long sourceTimemills, long targetTimeMills) {
        return (sourceTimemills + GMT_TIMEZONE.getRawOffset() + RELATIVE_MILLS) / DAY_MILLIS == (targetTimeMills + GMT_TIMEZONE.getRawOffset() + RELATIVE_MILLS) / DAY_MILLIS;
    }
}

package app.local.rednoteshield;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DailySchedule {
    static final String ENABLED = "schedule_enabled";
    static final String UNMASKED = "schedule_unmasked";
    static final int DEFAULT_START = 22 * 60;
    static final int DEFAULT_END = 7 * 60;
    private static final String RANGES = "schedule_ranges";
    private static final int MINUTES_PER_DAY = 24 * 60;

    private DailySchedule() {}

    static List<Range> readRanges(SharedPreferences preferences) {
        String saved = preferences.getString(RANGES, null);
        if (saved == null) {
            List<Range> legacy = new ArrayList<>();
            legacy.add(new Range(preferences.getInt("schedule_start", DEFAULT_START),
                    preferences.getInt("schedule_end", DEFAULT_END)));
            return legacy;
        }
        return decodeRanges(saved);
    }

    static void saveRanges(SharedPreferences preferences, List<Range> ranges) {
        preferences.edit().putString(RANGES, encodeRanges(ranges)).apply();
    }

    static String encodeRanges(List<Range> ranges) {
        StringBuilder encoded = new StringBuilder();
        for (Range range : ranges) {
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            encoded.append(range.start).append('-').append(range.end);
        }
        return encoded.toString();
    }

    static List<Range> decodeRanges(String saved) {
        List<Range> ranges = new ArrayList<>();
        for (String entry : saved.split(";")) {
            String[] endpoints = entry.split("-", -1);
            if (endpoints.length != 2) {
                continue;
            }
            try {
                int start = Integer.parseInt(endpoints[0]);
                int end = Integer.parseInt(endpoints[1]);
                if (start >= 0 && start < MINUTES_PER_DAY && end >= 0 && end < MINUTES_PER_DAY) {
                    ranges.add(new Range(start, end));
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed persisted entries.
            }
        }
        return ranges;
    }

    static boolean allowsShield(int minute, List<Range> ranges, boolean unmasked) {
        for (Range range : ranges) {
            if (contains(minute, range.start, range.end)) {
                return !unmasked;
            }
        }
        return unmasked;
    }

    static boolean contains(int minute, int start, int end) {
        if (start == end) {
            return true;
        }
        if (start < end) {
            return minute >= start && minute < end;
        }
        return minute >= start || minute < end;
    }

    static String format(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        String display() {
            String time = format(start) + " – " + format(end);
            if (start == end) {
                return time + "（全天）";
            }
            return end < start ? time + "（次日结束）" : time;
        }
    }
}

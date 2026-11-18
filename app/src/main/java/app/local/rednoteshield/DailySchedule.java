package app.local.rednoteshield;

import java.util.Locale;

final class DailySchedule {
    static final String ENABLED = "schedule_enabled";
    static final String START = "schedule_start";
    static final String END = "schedule_end";
    static final int DEFAULT_START = 22 * 60;
    static final int DEFAULT_END = 7 * 60;

    private DailySchedule() {}

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
}

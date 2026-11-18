package app.local.rednoteshield;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.time.LocalTime;
import java.util.List;

public final class ShieldService extends AccessibilityService {
    private static final String REDNOTE = "com.xingin.xhs";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long NAVIGATION_GRACE_MS = 220;
    private static final long CONTENT_REFRESH_MS = 32;
    private long suppressUntil;
    private long refreshAt = Long.MAX_VALUE;
    private final Runnable refresh = () -> {
        refreshAt = Long.MAX_VALUE;
        refreshShield();
    };
    private final Rect covered = new Rect();
    private WindowManager windows;
    private SharedPreferences preferences;
    private List<DailySchedule.Range> scheduleRanges;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (settings, key) -> {
                scheduleRanges = DailySchedule.readRanges(settings);
                scheduleRefresh();
            };
    private TextView shield;
    private boolean clockRegistered;
    private final BroadcastReceiver clockChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (preferences.getBoolean(DailySchedule.ENABLED, false)) {
                scheduleRefresh();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        windows = getSystemService(WindowManager.class);
        preferences = getSharedPreferences(SetupActivity.PREFS, MODE_PRIVATE);
        scheduleRanges = DailySchedule.readRanges(preferences);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        if (!clockRegistered) {
            IntentFilter clockEvents = new IntentFilter(Intent.ACTION_TIME_TICK);
            clockEvents.addAction(Intent.ACTION_TIME_CHANGED);
            clockEvents.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(clockChanges, clockEvents);
            clockRegistered = true;
        }
        scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        boolean rednote = event.getPackageName() != null && REDNOTE.contentEquals(event.getPackageName());
        if (rednote && shield != null && type == AccessibilityEvent.TYPE_VIEW_CLICKED
                && clickedNavigation(event)) {
            hideShield();
            suppressUntil = SystemClock.uptimeMillis() + NAVIGATION_GRACE_MS;
            scheduleRefresh(true);
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleRefresh(true);
        } else if (rednote) {
            scheduleRefresh(type == AccessibilityEvent.TYPE_VIEW_SELECTED
                    || type == AccessibilityEvent.TYPE_VIEW_CLICKED);
        }
    }

    private boolean clickedNavigation(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) {
            return false;
        }
        try {
            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            // The untouched strips contain search, tabs and bottom navigation.
            return !bounds.isEmpty()
                    && (bounds.centerY() < covered.top || bounds.centerY() >= covered.bottom);
        } finally {
            source.recycle();
        }
    }

    private void scheduleRefresh() {
        scheduleRefresh(true);
    }

    private void scheduleRefresh(boolean immediate) {
        long delay = immediate ? 0 : (shield == null ? 12 : CONTENT_REFRESH_MS);
        scheduleAt(SystemClock.uptimeMillis() + delay);
    }

    private void scheduleAt(long time) {
        if (refreshAt <= time) {
            return;
        }
        handler.removeCallbacks(refresh);
        refreshAt = time;
        handler.postAtTime(refresh, time);
    }

    private void refreshShield() {
        if (!scheduleAllowsShield()) {
            suppressUntil = 0;
            hideShield();
            return;
        }
        AccessibilityNodeInfo root = focusedAppRoot();
        if (root == null) {
            suppressUntil = 0;
            hideShield();
            return;
        }
        Rect feed = null;
        try {
            if (root.getPackageName() != null && REDNOTE.contentEquals(root.getPackageName())) {
                Rect screen = new Rect();
                root.getBoundsInScreen(screen);
                DiscoveryLayout layout = new DiscoveryLayout(screen);
                layout.visit(root, false);
                float density = getResources().getDisplayMetrics().density;
                int expandTop = Math.round(preferences.getInt(SetupActivity.TOP, 0) * density);
                int expandBottom = Math.round(preferences.getInt(SetupActivity.BOTTOM, 0) * density);
                feed = layout.feedBounds(expandTop, expandBottom);
            }
        } finally {
            root.recycle();
        }
        if (feed == null) {
            suppressUntil = 0;
            hideShield();
        } else if (SystemClock.uptimeMillis() < suppressUntil) {
            // Only suppress a reappearance while Android still reports the old discovery page.
            scheduleAt(suppressUntil);
        } else {
            showShield(feed);
        }
    }

    private boolean scheduleAllowsShield() {
        if (!preferences.getBoolean(DailySchedule.ENABLED, false)) {
            return true;
        }
        LocalTime now = LocalTime.now();
        return DailySchedule.allowsShield(now.getHour() * 60 + now.getMinute(), scheduleRanges,
                preferences.getBoolean(DailySchedule.UNMASKED, false));
    }

    private AccessibilityNodeInfo focusedAppRoot() {
        AccessibilityNodeInfo root = null;
        boolean keyboardVisible = false;
        for (AccessibilityWindowInfo window : getWindows()) {
            try {
                int type = window.getType();
                keyboardVisible |= type == AccessibilityWindowInfo.TYPE_INPUT_METHOD;
                if (root == null && type == AccessibilityWindowInfo.TYPE_APPLICATION
                        && window.isFocused()) {
                    root = window.getRoot();
                }
            } finally {
                window.recycle();
            }
        }
        if (!keyboardVisible) {
            return root != null ? root : getRootInActiveWindow();
        }
        if (root != null) {
            root.recycle();
        }
        return null;
    }

    private void showShield(Rect bounds) {
        if (windows == null) {
            return;
        }

        if (shield != null) {
            applyPalette(shield);
            if (bounds.equals(covered)) {
                return;
            }
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = bounds.left;
        params.y = bounds.top;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
        }

        if (shield == null) {
            TextView panel = new TextView(this);
            panel.setGravity(Gravity.CENTER);
            panel.setText("发现推荐已隐藏");
            panel.setTextSize(16);
            applyPalette(panel);
            panel.setImportantForAccessibility(TextView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            panel.setOnTouchListener((view, event) -> true);
            windows.addView(panel, params);
            shield = panel;
        } else {
            windows.updateViewLayout(shield, params);
        }
        covered.set(bounds);
    }

    private void applyPalette(TextView panel) {
        int mode = preferences.getInt(SetupActivity.THEME, SetupActivity.THEME_SYSTEM);
        boolean dark = mode == SetupActivity.THEME_DARK
                || (mode == SetupActivity.THEME_SYSTEM
                        && SetupActivity.systemIsDark(getResources().getConfiguration()));
        panel.setTextColor(dark ? Color.rgb(200, 203, 208) : Color.rgb(87, 92, 100));
        panel.setBackgroundColor(dark ? Color.rgb(22, 22, 24) : Color.rgb(247, 248, 250));
    }

    private void hideShield() {
        if (shield != null) {
            windows.removeViewImmediate(shield);
            shield = null;
        }
        covered.setEmpty();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        hideShield();
        scheduleRefresh();
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(refresh);
        refreshAt = Long.MAX_VALUE;
        hideShield();
    }

    @Override
    public void onDestroy() {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        if (clockRegistered) {
            unregisterReceiver(clockChanges);
            clockRegistered = false;
        }
        onInterrupt();
        super.onDestroy();
    }

    private static final class DiscoveryLayout {
        private final Rect screen;
        private int budget = 900;
        private int topInset;
        private int bottomInset;
        private int searchBottom;
        private boolean discover;
        private boolean selectedDiscover;
        private boolean otherTabSelected;
        private boolean category;
        private boolean home;
        private boolean profile;
        private boolean otherBottomTabSelected;
        private boolean editing;

        DiscoveryLayout(Rect screen) {
            this.screen = screen;
            topInset = screen.top;
            searchBottom = screen.top;
            bottomInset = screen.bottom;
        }

        void visit(AccessibilityNodeInfo node, boolean selectedParent) {
            if (budget-- <= 0 || !node.isVisibleToUser()) {
                return;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String text = label(node);
            boolean selected = node.isSelected() || selectedParent;
            boolean shortTopNode = bounds.top >= screen.top
                    && bounds.bottom <= screen.top + screen.height() * 0.22
                    && bounds.height() <= screen.height() * 0.10;
            if (shortTopNode) {
                if (matches(text, "发现", "Explore", "Discover")) {
                    discover = true;
                    selectedDiscover |= selected;
                    topInset = Math.max(topInset, bounds.bottom);
                } else if (matches(text, "关注", "附近", "直播", "Following", "Nearby", "Live")) {
                    otherTabSelected |= selected;
                    topInset = Math.max(topInset, bounds.bottom);
                } else if (matches(text, "推荐", "Recommend", "Recommended", "For You")) {
                    category = true;
                    topInset = Math.max(topInset, bounds.bottom);
                } else if (matches(text, "搜索", "Search", "搜索按钮")
                        || text.startsWith("搜索：") || text.startsWith("搜索，")) {
                    searchBottom = Math.max(searchBottom, bounds.bottom);
                    topInset = Math.max(topInset, bounds.bottom);
                }
            }

            if (bounds.top >= screen.top + screen.height() * 0.82) {
                if (matches(text, "首页", "Home")) {
                    home = true;
                    bottomInset = Math.min(bottomInset, bounds.top);
                } else if (matches(text, "我", "Me", "Profile")) {
                    profile = true;
                    otherBottomTabSelected |= selected;
                    bottomInset = Math.min(bottomInset, bounds.top);
                } else if (matches(text, "消息", "Messages", "通知", "Notifications", "商城", "Shop",
                        "视频", "Video", "RED")) {
                    otherBottomTabSelected |= selected;
                    bottomInset = Math.min(bottomInset, bounds.top);
                }
            }
            editing |= node.isEditable() && (node.isFocused()
                    || bounds.top < screen.top + screen.height() * 0.2);

            boolean shortBottomNode = bounds.top >= screen.top + screen.height() * 0.80
                    && bounds.height() <= screen.height() * 0.17;
            boolean localSelection = selected && (shortTopNode || shortBottomNode)
                    && bounds.width() <= screen.width() * 0.38;
            for (int index = 0; index < node.getChildCount() && budget > 0; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    try {
                        visit(child, localSelection);
                    } finally {
                        child.recycle();
                    }
                }
            }
        }

        Rect feedBounds(int expandTop, int expandBottom) {
            if (!discover || !home || !profile || editing
                    || otherTabSelected || otherBottomTabSelected || !(selectedDiscover || category)) {
                return null;
            }
            int top = Math.max(topInset, screen.top + Math.round(screen.height() * 0.12f));
            // Leave room above the labels for the bottom navigation icons and touch targets.
            int bottom = Math.min(bottomInset - Math.round(screen.height() * 0.026f),
                    screen.bottom - Math.round(screen.height() * 0.085f));
            top = Math.max(searchBottom, Math.max(screen.top + Math.round(screen.height() * 0.065f),
                    top - expandTop));
            bottom = Math.min(bottomInset, bottom + expandBottom);
            return bottom > top && screen.width() > 0
                    ? new Rect(screen.left, top, screen.right, bottom) : null;
        }

        private static String label(AccessibilityNodeInfo node) {
            CharSequence value = node.getText();
            if (value == null || value.length() == 0) {
                value = node.getContentDescription();
            }
            return value == null ? "" : value.toString().trim();
        }

        private static boolean matches(String value, String... options) {
            for (String option : options) {
                if (option.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}

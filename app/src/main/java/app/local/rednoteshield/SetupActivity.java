package app.local.rednoteshield;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.res.Configuration;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SetupActivity extends Activity {
    static final String PREFS = "shield";
    static final String THEME = "theme";
    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;
    static final String TOP = "top_expansion";
    static final String BOTTOM = "bottom_expansion";
    private static final int MAX_EXPANSION = 64;
    private LinearLayout page;
    private SharedPreferences preferences;
    private LinearLayout customControls;
    private LinearLayout serviceControls;
    private final SharedPreferences.OnSharedPreferenceChangeListener recordingListener = (store, key) -> {
        if (CustomRules.RECORDING_APP.equals(key) || CustomRules.RECORDING_STATUS.equals(key)) {
            renderCustomControls();
        }
    };
    private final Runnable refreshStatus = this::renderServiceStatus;

    @Override
    protected void onCreate(Bundle state) {
        boolean dark = systemIsDark(getResources().getConfiguration());
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);

        addText(getApplicationInfo().loadLabel(getPackageManager()).toString(), 26);
        serviceControls = new LinearLayout(this);
        serviceControls.setOrientation(LinearLayout.VERTICAL);
        page.addView(serviceControls);

        addText("App 规则", 20);
        customControls = new LinearLayout(this);
        customControls.setOrientation(LinearLayout.VERTICAL);
        page.addView(customControls);

        addText("全局定时", 20);
        addSchedule();

        Button more = new Button(this);
        more.setText("更多设置");
        page.addView(more);
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        page.addView(advanced);
        LinearLayout main = page;
        page = advanced;
        addText("遮罩颜色", 16);
        addThemeChoices();
        addText("小红书预设的范围", 16);
        addExpansion("向上扩大", TOP);
        addExpansion("向下扩大", BOTTOM);
        Button reset = new Button(this);
        reset.setText("恢复默认范围");
        reset.setOnClickListener(view -> {
            preferences.edit().remove(TOP).remove(BOTTOM).apply();
            recreate();
        });
        page.addView(reset);
        addButton("打开电池优化设置", new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        page = main;
        advanced.setVisibility(LinearLayout.GONE);
        more.setOnClickListener(view -> {
            boolean expanded = advanced.getVisibility() == LinearLayout.VISIBLE;
            advanced.setVisibility(expanded ? LinearLayout.GONE : LinearLayout.VISIBLE);
            more.setText(expanded ? "更多设置" : "收起设置");
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(recordingListener);
        renderCustomControls();
        renderServiceStatus();
    }

    @Override
    protected void onPause() {
        serviceControls.removeCallbacks(refreshStatus);
        preferences.unregisterOnSharedPreferenceChangeListener(recordingListener);
        super.onPause();
    }

    private void renderServiceStatus() {
        serviceControls.removeCallbacks(refreshStatus);
        serviceControls.removeAllViews();
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        boolean enabled = false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            android.content.pm.ServiceInfo info = service.getResolveInfo().serviceInfo;
            enabled |= getPackageName().equals(info.packageName) && ShieldService.class.getName().equals(info.name);
        }
        TextView status = new TextView(this);
        if (!enabled) {
            status.setText("无障碍服务未开启");
            Button grant = new Button(this);
            grant.setText("启用无障碍服务");
            grant.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            serviceControls.addView(status);
            serviceControls.addView(grant);
            return;
        }
        boolean timed = preferences.getBoolean(DailySchedule.ENABLED, false);
        LocalTime now = LocalTime.now();
        boolean allowed = !timed || DailySchedule.allowsShield(now.getHour() * 60 + now.getMinute(),
                DailySchedule.readRanges(preferences), preferences.getBoolean(DailySchedule.UNMASKED, false));
        status.setText(timed ? "无障碍已开启 · 当前为" + (allowed ? "遮罩时段" : "放行时段") : "无障碍已开启 · 定时关闭");
        serviceControls.addView(status);
        serviceControls.postDelayed(refreshStatus, 60000 - System.currentTimeMillis() % 60000);
    }

    private void renderCustomControls() {
        customControls.removeAllViews();
        String target = CustomRules.recordingApp(preferences);
        boolean recording = !target.isEmpty();
        TextView status = new TextView(this);
        status.setText(recording
                ? "只在「" + appLabel(target) + "」里继续。十分钟内保存即可。"
                : preferences.getString(CustomRules.RECORDING_STATUS, "为 App 添加要遮住的页面。"));
        customControls.addView(status);

        Button record = new Button(this);
        record.setText(recording ? "取消录制" : "添加 App");
        record.setOnClickListener(view -> {
            if (recording) {
                CustomRules.endRecording(preferences, "已取消录制");
            } else {
                chooseApp();
            }
        });
        if (recording) {
            Button resume = new Button(this);
            resume.setText("继续录制");
            resume.setOnClickListener(view -> openTarget(target));
            customControls.addView(resume);
        }
        customControls.addView(record);

        Map<String, CustomRules.Rule> rules = CustomRules.read(preferences);
        if (!rules.containsKey("com.xingin.xhs")) {
            addRuleRow("com.xingin.xhs", "小红书", "发现 · 内置预设",
                    preferences.getBoolean("rednote_preset", true),
                    enabled -> preferences.edit().putBoolean("rednote_preset", enabled).apply(), null);
        }
        for (Map.Entry<String, CustomRules.Rule> entry : rules.entrySet()) {
            String app = entry.getKey();
            CustomRules.Rule rule = entry.getValue();
            addRuleRow(app, app, "已录制页面", rule.enabled, enabled -> {
                rule.enabled = enabled;
                CustomRules.save(preferences, rules);
            }, () -> {
                rules.remove(app);
                CustomRules.save(preferences, rules);
                renderCustomControls();
            });
        }
    }

    private String appLabel(String app) {
        PackageManager manager = getPackageManager();
        try {
            return manager.getApplicationLabel(manager.getApplicationInfo(app, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return app;
        }
    }

    private void chooseApp() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, String> labels = new LinkedHashMap<>();
        for (ResolveInfo result : manager.queryIntentActivities(launcher, 0)) {
            String app = result.activityInfo.packageName;
            if (!app.equals(getPackageName())) {
                labels.putIfAbsent(app, appLabel(app));
            }
        }
        List<String> apps = new ArrayList<>(labels.keySet());
        apps.sort((left, right) -> labels.get(left).compareToIgnoreCase(labels.get(right)));
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = labels.get(apps.get(i));
        }
        new AlertDialog.Builder(this).setTitle("选择要遮罩的 App")
                .setItems(names, (dialog, index) -> startRecording(apps.get(index), false))
                .setNegativeButton("取消", null).show();
    }

    private void startRecording(String app, boolean resize) {
        preferences.edit().putString(CustomRules.RECORDING_APP, app)
                .putBoolean(CustomRules.RECORDING_RESIZE, resize)
                .putLong(CustomRules.RECORDING_END, System.currentTimeMillis() + CustomRules.RECORDING_DURATION_MS)
                .remove(CustomRules.RECORDING_STATUS).apply();
        renderCustomControls();
        openTarget(app);
    }

    private void openTarget(String app) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(app);
        if (launch != null) {
            startActivity(launch);
        } else {
            android.widget.Toast.makeText(this, "请手动打开目标 App", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void addRuleRow(String app, String fallbackName, String subtitle, boolean enabled,
            java.util.function.Consumer<Boolean> toggle, Runnable delete) {
        PackageManager manager = getPackageManager();
        String label = fallbackName;
        Drawable icon = manager.getDefaultActivityIcon();
        try {
            ApplicationInfo info = manager.getApplicationInfo(app, 0);
            label = manager.getApplicationLabel(info).toString();
            icon = manager.getApplicationIcon(info);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the rule manageable if the target app is uninstalled.
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int iconSize = Math.round(36 * getResources().getDisplayMetrics().density);
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        row.addView(image, new LinearLayout.LayoutParams(iconSize, iconSize));

        Button details = new Button(this, null, android.R.attr.borderlessButtonStyle);
        details.setText(label + "\n" + subtitle);
        details.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        details.setOnClickListener(view -> {
            String last = "上次检测 · " + ShieldService.lastDecision(app);
            AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                    .setTitle(details.getText()).setMessage(last)
                    .setNegativeButton("关闭", null);
            if (delete != null) {
                dialog.setPositiveButton("调整范围", (window, which) -> startRecording(app, true));
                dialog.setNeutralButton("删除规则", (window, which) -> delete.run());
            } else {
                dialog.setMessage(last + "\n\n可在「更多设置」调整内置预设的范围。");
            }
            dialog.show();
        });

        Switch control = new Switch(this);
        control.setContentDescription(label + "规则");
        control.setChecked(enabled);
        control.setOnCheckedChangeListener((button, checked) -> toggle.accept(checked));
        row.addView(control);
        customControls.addView(row);
    }

    private void addSchedule() {
        Switch schedule = new Switch(this);
        schedule.setText("每天定时切换遮罩");
        schedule.setChecked(preferences.getBoolean(DailySchedule.ENABLED, false));
        page.addView(schedule);
        addText("按手机本地时间每天重复。结束早于开始时跨午夜，开始和结束相同表示全天。", 14);

        List<DailySchedule.Range> ranges = DailySchedule.readRanges(preferences);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        page.addView(controls);

        RadioGroup modes = new RadioGroup(this);
        RadioButton masked = new RadioButton(this);
        masked.setId(android.view.View.generateViewId());
        masked.setText("平时放行");
        modes.addView(masked);
        RadioButton unmasked = new RadioButton(this);
        unmasked.setId(android.view.View.generateViewId());
        unmasked.setText("平时遮罩");
        modes.addView(unmasked);
        modes.check(preferences.getBoolean(DailySchedule.UNMASKED, false)
                ? unmasked.getId() : masked.getId());
        controls.addView(modes);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        controls.addView(rows);
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            preferences.edit().putBoolean(DailySchedule.UNMASKED, checkedId == unmasked.getId()).apply();
            renderScheduleRanges(rows, ranges);
            renderServiceStatus();
        });
        renderScheduleRanges(rows, ranges);

        Button add = new Button(this);
        add.setText("添加例外时段");
        add.setOnClickListener(view -> editScheduleRange(rows, ranges, ranges.size(),
                new DailySchedule.Range(DailySchedule.DEFAULT_START, DailySchedule.DEFAULT_END)));
        controls.addView(add);
        controls.setVisibility(schedule.isChecked() ? LinearLayout.VISIBLE : LinearLayout.GONE);
        schedule.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(DailySchedule.ENABLED, enabled).apply();
            controls.setVisibility(enabled ? LinearLayout.VISIBLE : LinearLayout.GONE);
            renderServiceStatus();
        });
    }

    private void renderScheduleRanges(LinearLayout rows, List<DailySchedule.Range> ranges) {
        rows.removeAllViews();
        boolean unmasked = preferences.getBoolean(DailySchedule.UNMASKED, false);
        TextView heading = new TextView(this);
        heading.setText(unmasked ? "放行时段" : "遮罩时段");
        rows.addView(heading);
        if (ranges.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(unmasked ? "没有时间段，全天遮罩。" : "没有时间段，全天无遮罩。");
            rows.addView(empty);
        }
        for (int i = 0; i < ranges.size(); i++) {
            int index = i;
            DailySchedule.Range range = ranges.get(index);
            Button row = new Button(this);
            row.setText(range.display());
            row.setContentDescription("编辑时段 " + range.display());
            row.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle(range.display())
                    .setPositiveButton("修改", (dialog, which) -> editScheduleRange(rows, ranges, index, range))
                    .setNeutralButton("删除", (dialog, which) -> {
                        ranges.remove(index);
                        saveScheduleRanges(rows, ranges);
                    })
                    .setNegativeButton("取消", null)
                    .show());
            rows.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void editScheduleRange(LinearLayout rows, List<DailySchedule.Range> ranges, int index,
            DailySchedule.Range range) {
        TimePickerDialog startPicker = new TimePickerDialog(this, (picker, hour, minute) -> {
            int start = hour * 60 + minute;
            TimePickerDialog endPicker = new TimePickerDialog(this, (endView, endHour, endMinute) -> {
                DailySchedule.Range updated = new DailySchedule.Range(start, endHour * 60 + endMinute);
                if (index == ranges.size()) {
                    ranges.add(updated);
                } else {
                    ranges.set(index, updated);
                }
                saveScheduleRanges(rows, ranges);
            }, range.end / 60, range.end % 60, DateFormat.is24HourFormat(this));
            endPicker.setTitle("结束时间");
            endPicker.show();
        }, range.start / 60, range.start % 60, DateFormat.is24HourFormat(this));
        startPicker.setTitle("开始时间");
        startPicker.show();
    }

    private void saveScheduleRanges(LinearLayout rows, List<DailySchedule.Range> ranges) {
        DailySchedule.saveRanges(preferences, ranges);
        renderScheduleRanges(rows, ranges);
        renderServiceStatus();
    }

    static boolean systemIsDark(Configuration configuration) {
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void addThemeChoices() {
        RadioGroup choices = new RadioGroup(this);
        String[] labels = {"跟随系统（深色页面优先）", "浅色", "深色"};
        int saved = preferences.getInt(THEME, THEME_SYSTEM);
        for (int mode = THEME_SYSTEM; mode <= THEME_DARK; mode++) {
            RadioButton option = new RadioButton(this);
            option.setId(android.view.View.generateViewId());
            option.setTag(mode);
            option.setText(labels[mode]);
            choices.addView(option);
            if (mode == saved) {
                choices.check(option.getId());
            }
        }
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton checked = group.findViewById(checkedId);
            if (checked != null) {
                preferences.edit().putInt(THEME, (Integer) checked.getTag()).apply();
            }
        });
        page.addView(choices);
        addText("安卓 11 及以上可在本机取样页面颜色，图片不会保存或上传。", 14);
    }

    private TextView addText(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, 10, 0, 10);
        page.addView(text);
        return text;
    }

    private void addButton(String title, Intent intent) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> {
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException exception) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        page.addView(button);
    }

    private void addExpansion(String title, String key) {
        int current = preferences.getInt(key, 0);
        TextView label = addText(title + " " + current + " dp", 16);
        SeekBar slider = new SeekBar(this);
        slider.setMax(MAX_EXPANSION);
        slider.setProgress(current);
        slider.setContentDescription(title);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                label.setText(title + " " + value + " dp");
                if (fromUser) {
                    preferences.edit().putInt(key, value).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        page.addView(slider);
    }
}

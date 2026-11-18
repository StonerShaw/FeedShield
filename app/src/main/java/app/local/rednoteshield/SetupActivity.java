package app.local.rednoteshield;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

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

        addText("小红书发现遮罩", 26);
        addText("启用无障碍服务后，打开小红书的「发现」。关闭该服务即可恢复。", 16);
        addButton("打开无障碍设置", new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        addButton("打开电池优化设置", new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        addText("遮罩失效时，先确认无障碍服务仍开启。若系统限制本 App 后台运行，可在电池设置中调整。", 15);

        addText("如果推荐区边缘仍露出内容，可小幅扩大遮罩。每次调整后检查顶部搜索和底栏还能点击。", 16);
        addExpansion("向上扩大", TOP);
        addExpansion("向下扩大", BOTTOM);
        Button reset = new Button(this);
        reset.setText("恢复默认范围");
        reset.setOnClickListener(view -> {
            preferences.edit().remove(TOP).remove(BOTTOM).apply();
            recreate();
        });
        page.addView(reset);

        addSchedule();

        addText("遮罩颜色", 16);
        addThemeChoices();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
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
        masked.setText("仅在这些时段遮罩");
        modes.addView(masked);
        RadioButton unmasked = new RadioButton(this);
        unmasked.setId(android.view.View.generateViewId());
        unmasked.setText("仅在这些时段无遮罩");
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
        });
        renderScheduleRanges(rows, ranges);

        Button add = new Button(this);
        add.setText("添加时间段");
        add.setOnClickListener(view -> editScheduleRange(rows, ranges, ranges.size(),
                new DailySchedule.Range(DailySchedule.DEFAULT_START, DailySchedule.DEFAULT_END)));
        controls.addView(add);
        controls.setVisibility(schedule.isChecked() ? LinearLayout.VISIBLE : LinearLayout.GONE);
        schedule.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(DailySchedule.ENABLED, enabled).apply();
            controls.setVisibility(enabled ? LinearLayout.VISIBLE : LinearLayout.GONE);
        });
    }

    private void renderScheduleRanges(LinearLayout rows, List<DailySchedule.Range> ranges) {
        rows.removeAllViews();
        if (ranges.isEmpty()) {
            TextView empty = new TextView(this);
            boolean unmasked = preferences.getBoolean(DailySchedule.UNMASKED, false);
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
    }

    static boolean systemIsDark(Configuration configuration) {
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void addThemeChoices() {
        RadioGroup choices = new RadioGroup(this);
        String[] labels = {"跟随系统", "浅色", "深色"};
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

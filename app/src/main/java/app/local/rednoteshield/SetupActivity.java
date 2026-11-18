package app.local.rednoteshield;

import android.app.Activity;
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
        schedule.setText("每天定时启用遮罩");
        schedule.setChecked(preferences.getBoolean(DailySchedule.ENABLED, false));
        page.addView(schedule);
        addText("按手机本地时间重复。结束早于开始时，持续到次日。同一时间表示全天。", 14);

        Button start = addTimeButton("启用时间", DailySchedule.START, DailySchedule.DEFAULT_START);
        Button end = addTimeButton("停用时间", DailySchedule.END, DailySchedule.DEFAULT_END);
        start.setEnabled(schedule.isChecked());
        end.setEnabled(schedule.isChecked());
        schedule.setOnCheckedChangeListener((button, enabled) -> {
            preferences.edit().putBoolean(DailySchedule.ENABLED, enabled).apply();
            start.setEnabled(enabled);
            end.setEnabled(enabled);
        });
    }

    private Button addTimeButton(String title, String key, int defaultTime) {
        Button button = new Button(this);
        button.setText(title + " " + DailySchedule.format(preferences.getInt(key, defaultTime)));
        button.setOnClickListener(view -> {
            int time = preferences.getInt(key, defaultTime);
            new TimePickerDialog(this, (picker, hour, minute) -> {
                int selected = hour * 60 + minute;
                preferences.edit().putInt(key, selected).apply();
                button.setText(title + " " + DailySchedule.format(selected));
            }, time / 60, time % 60, DateFormat.is24HourFormat(this)).show();
        });
        page.addView(button);
        return button;
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

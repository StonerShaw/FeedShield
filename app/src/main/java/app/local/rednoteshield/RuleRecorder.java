package app.local.rednoteshield;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;

import java.util.Map;
import java.util.function.Consumer;

final class RuleRecorder {
    private final Context context;
    private final SharedPreferences preferences;
    private final WindowManager windows;
    private final Runnable changed;
    private final Consumer<TextView> applyPalette;
    private View overlay;
    private View previewTint;
    private String currentApp;
    private Rect currentWindow;
    private String targetApp;
    private CustomRules.Rule resizeRule;
    private Rect recordedWindow;
    private Rect region;
    private CustomRules.PageFeatures blocked;
    private boolean editingRegion;
    private CustomRules.Rule trial;
    private boolean sawMatch;
    private TextView trialMask;
    private final Rect trialCovered = new Rect();

    RuleRecorder(Context context, SharedPreferences preferences, WindowManager windows, Runnable changed,
            Consumer<TextView> applyPalette) {
        this.context = context;
        this.preferences = preferences;
        this.windows = windows;
        this.changed = changed;
        this.applyPalette = applyPalette;
    }

    void configure(String app, CustomRules.Rule editRule) {
        targetApp = app;
        resizeRule = editRule;
    }

    void update(AccessibilityNodeInfo root, Rect bounds, String app) {
        if (!app.equals(targetApp) || bounds.isEmpty()) {
            removeOverlay();
            currentApp = null;
            return;
        }
        Rect matched = trial == null || editingRegion ? null : trial.matchPage(root, bounds);
        boolean samePageBounds = app.equals(currentApp) && bounds.equals(currentWindow);
        boolean sameTrial = matched == null ? trialMask == null : matched.equals(trialCovered);
        if (samePageBounds && sameTrial && overlay != null) {
            return;
        }
        removeOverlay();
        currentApp = app;
        currentWindow = new Rect(bounds);
        if (editingRegion && !bounds.equals(recordedWindow)) {
            editingRegion = false;
            if (trial == null && resizeRule == null) {
                blocked = null;
            }
            changed.run();
        } else if (editingRegion) {
            showRegionEditor();
        } else {
            if (matched != null) {
                showTrialMask(matched);
                sawMatch = true;
            }
            showControls();
        }
    }

    void capture(AccessibilityNodeInfo root, Rect bounds, String app) {
        if (!app.equals(currentApp) || !bounds.equals(currentWindow)) {
            return;
        }
        if (resizeRule != null && trial == null) {
            Rect saved = resizeRule.matchPage(root, bounds);
            if (saved == null) {
                Toast.makeText(context, "请回到已有规则对应的页面，再调整范围", Toast.LENGTH_LONG).show();
                return;
            }
            openEditor(bounds, saved);
            return;
        }
        if (blocked == null) {
            blocked = new CustomRules.PageFeatures(root, bounds);
            openEditor(bounds, new FeedBounds(bounds).suggest(root));
            return;
        }
        if (!bounds.equals(recordedWindow)) {
            Toast.makeText(context, "请在同样的窗口大小下确认相邻标签", Toast.LENGTH_SHORT).show();
            return;
        }
        CustomRules.Rule rule = CustomRules.learn(blocked,
                new CustomRules.PageFeatures(root, bounds), region, recordedWindow);
        if (rule == null) {
            Toast.makeText(context, "还分不清这两页，请切到同一栏里的另一个标签再试", Toast.LENGTH_LONG).show();
            return;
        }
        startTrial(rule);
    }

    private void openEditor(Rect bounds, Rect initialRegion) {
        recordedWindow = new Rect(bounds);
        region = new Rect(initialRegion);
        editingRegion = true;
        removeOverlay();
        showRegionEditor();
    }

    private void startTrial(CustomRules.Rule rule) {
        trial = rule;
        sawMatch = false;
        removeOverlay();
        changed.run();
        Toast.makeText(context, "试用中。可点搜索或切换标签，确认后保存。", Toast.LENGTH_LONG).show();
    }

    private void saveTrial() {
        Map<String, CustomRules.Rule> rules = CustomRules.read(preferences);
        CustomRules.Rule existing = rules.get(targetApp);
        if (resizeRule != null && existing == null) {
            finish("原规则已删除");
            return;
        }
        if (existing != null) {
            trial.enabled = existing.enabled;
        }
        rules.put(targetApp, trial);
        CustomRules.save(preferences, rules);
        finish("规则已保存");
    }

    private void showControls() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        Button action = new Button(context);
        if (trial != null) {
            action.setText(sawMatch ? "保存规则" : "返回遮罩页试用");
            action.setEnabled(sawMatch);
            action.setOnClickListener(view -> saveTrial());
        } else {
            action.setText(resizeRule != null ? "调整此页范围"
                    : blocked == null ? "检测此页" : "确认相邻标签");
            action.setOnClickListener(view -> {
                captureRequested = true;
                changed.run();
            });
        }
        Button cancel = new Button(context);
        cancel.setText("取消");
        cancel.setOnClickListener(view -> finish("已取消录制"));
        panel.addView(action);
        if (trialMask != null) {
            Button adjust = new Button(context);
            adjust.setText("调范围");
            adjust.setOnClickListener(view -> openEditor(currentWindow, trialCovered));
            panel.addView(adjust);
        }
        panel.addView(cancel);
        WindowManager.LayoutParams params = OverlayLayout.create(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        if (trialMask != null) {
            params.gravity = Gravity.RIGHT | Gravity.TOP;
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            params.y = trialCovered.centerY() - panel.getMeasuredHeight() / 2;
        }
        attach(panel, params);
    }

    Rect trialBounds() {
        return trialCovered;
    }

    void refreshPalette() {
        if (trialMask != null) {
            applyPalette.accept(trialMask);
        }
    }

    private void showTrialMask(Rect bounds) {
        TextView mask = new TextView(context);
        mask.setText("试用遮罩");
        mask.setGravity(Gravity.CENTER);
        mask.setTextSize(16);
        mask.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        applyPalette.accept(mask);
        mask.setOnTouchListener((view, event) -> true);
        windows.addView(mask, OverlayLayout.at(bounds));
        trialMask = mask;
        trialCovered.set(bounds);
    }

    private boolean captureRequested;

    boolean takeCaptureRequest() {
        boolean requested = captureRequested;
        captureRequested = false;
        return requested;
    }

    private void showRegionEditor() {
        FrameLayout editor = new FrameLayout(context);
        LinearLayout actions = new LinearLayout(context);
        RegionPreview preview = new RegionPreview(context, actions);
        editor.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        Button cancel = new Button(context);
        cancel.setText("取消");
        cancel.setOnClickListener(view -> finish("已取消录制"));
        Button confirm = new Button(context);
        confirm.setText("确认范围");
        confirm.setOnClickListener(view -> {
            editingRegion = false;
            CustomRules.Rule base = trial != null ? trial : resizeRule;
            CustomRules.Rule direct = base != null ? base.resized(region, recordedWindow)
                    : CustomRules.fromSelectedTab(blocked, region, recordedWindow);
            if (direct != null) {
                startTrial(direct);
            } else {
                removeOverlay();
                showControls();
                Toast.makeText(context, "请切到同一栏里的另一个标签，再点「确认相邻标签」",
                        Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(cancel);
        actions.addView(confirm);
        editor.addView(actions, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        preview.placeActions();
        Toast.makeText(context, "拖动蓝色边线调整范围", Toast.LENGTH_SHORT).show();

        previewTint = new View(context);
        previewTint.setBackgroundColor(0x445EBCE5);
        previewTint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewTint.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            preview.invalidate();
            preview.placeActions();
        });
        windows.addView(previewTint, previewLayout());
        attach(editor, OverlayLayout.at(currentWindow));
    }

    private WindowManager.LayoutParams previewLayout() {
        WindowManager.LayoutParams params = OverlayLayout.at(region);
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return params;
    }

    private void attach(View view, WindowManager.LayoutParams params) {
        windows.addView(view, params);
        overlay = view;
    }

    private void finish(String status) {
        clear();
        CustomRules.endRecording(preferences, status);
        Toast.makeText(context, status, Toast.LENGTH_SHORT).show();
        changed.run();
    }

    void suspend() {
        captureRequested = false;
        removeOverlay();
    }

    private void removeOverlay() {
        if (overlay != null) {
            windows.removeViewImmediate(overlay);
            overlay = null;
        }
        if (previewTint != null) {
            windows.removeViewImmediate(previewTint);
            previewTint = null;
        }
        if (trialMask != null) {
            windows.removeViewImmediate(trialMask);
            trialMask = null;
            trialCovered.setEmpty();
        }
    }

    void clear() {
        removeOverlay();
        currentApp = null;
        currentWindow = null;
        targetApp = null;
        resizeRule = null;
        blocked = null;
        trial = null;
        sawMatch = false;
        editingRegion = false;
        captureRequested = false;
    }

    private final class RegionPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final View actions;
        private final float density;
        private final int[] editorPosition = new int[2];
        private final int[] tintPosition = new int[2];
        private final Rect displayedBounds = new Rect();
        private boolean draggingTop;
        private float startY;
        private int startEdge;

        RegionPreview(Context context, View actions) {
            super(context);
            this.actions = actions;
            density = getResources().getDisplayMetrics().density;
        }

        void placeActions() {
            if (getHeight() > 0) {
                getLocationOnScreen(editorPosition);
                readDisplayedBounds();
                actions.setTranslationY(displayedBounds.exactCenterY() - editorPosition[1] - getHeight() / 2f);
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            post(this::placeActions);
        }

        private void readDisplayedBounds() {
            View tint = previewTint;
            if (tint == null || tint.getWidth() == 0 || tint.getHeight() == 0) {
                displayedBounds.set(region);
                return;
            }
            tint.getLocationOnScreen(tintPosition);
            displayedBounds.set(tintPosition[0], tintPosition[1],
                    tintPosition[0] + tint.getWidth(), tintPosition[1] + tint.getHeight());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(editorPosition);
            readDisplayedBounds();
            int left = displayedBounds.left - editorPosition[0];
            int right = displayedBounds.right - editorPosition[0];
            paint.setColor(0xFF1782BC);
            drawEdge(canvas, left, right, displayedBounds.top - editorPosition[1]);
            drawEdge(canvas, left, right, displayedBounds.bottom - editorPosition[1]);
        }

        private void drawEdge(Canvas canvas, int left, int right, float y) {
            canvas.drawRect(left, y - density, right, y + density, paint);
            float center = (left + right) / 2f;
            canvas.drawRoundRect(center - 18 * density, y - 4 * density,
                    center + 18 * density, y + 4 * density, 4 * density, 4 * density, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            getLocationOnScreen(editorPosition);
            float y = event.getY() + editorPosition[1];
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    readDisplayedBounds();
                    float topDistance = Math.abs(y - displayedBounds.top);
                    float bottomDistance = Math.abs(y - displayedBounds.bottom);
                    if (Math.min(topDistance, bottomDistance) > 28 * density) {
                        return false;
                    }
                    draggingTop = topDistance <= bottomDistance;
                    startEdge = draggingTop ? region.top : region.bottom;
                    startY = y;
                    actions.setVisibility(INVISIBLE);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int edge = startEdge + Math.round(y - startY);
                    int minimumHeight = Math.min(Math.round(112 * density), recordedWindow.height() / 2);
                    if (draggingTop) {
                        region.top = Math.max(recordedWindow.top, Math.min(region.bottom - minimumHeight, edge));
                    } else {
                        region.bottom = Math.min(recordedWindow.bottom, Math.max(region.top + minimumHeight, edge));
                    }
                    windows.updateViewLayout(previewTint, previewLayout());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    placeActions();
                    actions.setVisibility(VISIBLE);
                    return true;
                default:
                    return true;
            }
        }
    }

    private static final class FeedBounds {
        private final Rect window;
        private final Rect best = new Rect();
        private int budget = 450;

        FeedBounds(Rect window) {
            this.window = window;
        }

        Rect suggest(AccessibilityNodeInfo root) {
            visit(root);
            int height = window.height();
            int top = best.isEmpty() ? window.top + Math.round(height * 0.18f) : best.top;
            int bottom = best.isEmpty() ? window.bottom - Math.round(height * 0.10f) : best.bottom;
            return new Rect(window.left, top, window.right, bottom);
        }

        private void visit(AccessibilityNodeInfo node) {
            if (budget-- <= 0 || !node.isVisibleToUser()) {
                return;
            }
            if (node.isScrollable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int height = window.height();
                if (window.contains(bounds) && bounds.width() >= window.width() * 0.8f
                        && bounds.height() >= height * 0.4f && bounds.top >= window.top + height * 0.08f
                        && bounds.bottom <= window.bottom - height * 0.05f
                        && bounds.top <= window.top + height * 0.45f
                        && bounds.bottom >= window.bottom - height * 0.45f
                        && bounds.height() > best.height()) {
                    best.set(bounds);
                }
            }
            for (int index = 0; index < node.getChildCount() && budget > 0; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    try {
                        visit(child);
                    } finally {
                        child.recycle();
                    }
                }
            }
        }
    }
}

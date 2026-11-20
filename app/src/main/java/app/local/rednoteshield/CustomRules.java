package app.local.rednoteshield;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

final class CustomRules {
    static final String RECORDING_APP = "recording_app";
    static final String RECORDING_RESIZE = "recording_resize";
    static final String RECORDING_END = "recording_end";
    static final long RECORDING_DURATION_MS = 10 * 60 * 1000L;
    static final String RECORDING_STATUS = "custom_recording_status";
    static final String RULES = "custom_rules";
    private static final int SCALE = 10000;

    static String recordingApp(SharedPreferences prefs) {
        return prefs.getLong(RECORDING_END, 0) > System.currentTimeMillis()
                ? prefs.getString(RECORDING_APP, "") : "";
    }

    static void endRecording(SharedPreferences prefs, String status) {
        prefs.edit().remove(RECORDING_APP).remove(RECORDING_RESIZE).remove(RECORDING_END)
                .putString(RECORDING_STATUS, status).apply();
    }

    static Map<String, Rule> read(SharedPreferences prefs) {
        Map<String, Rule> rules = new LinkedHashMap<>();
        try {
            JSONArray list = new JSONArray(prefs.getString(RULES, "[]"));
            for (int index = 0; index < list.length(); index++) {
                JSONObject item = list.getJSONObject(index);
                String app = item.getString("app");
                JSONArray box = item.getJSONArray("box");
                int[] bounds = {box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3)};
                JSONArray features = item.getJSONArray("features");
                List<String> required = new ArrayList<>();
                for (int i = 0; i < features.length(); i++) {
                    required.add(features.getString(i));
                }
                if (!required.isEmpty()) {
                    rules.put(app, new Rule(bounds, item.getDouble("aspect"), required,
                            item.optBoolean("enabled", true)));
                }
            }
        } catch (JSONException ignored) {
            // Preserve any valid entries read before malformed local data.
        }
        return rules;
    }

    static void save(SharedPreferences prefs, Map<String, Rule> rules) {
        JSONArray list = new JSONArray();
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            Rule rule = entry.getValue();
            JSONArray box = new JSONArray();
            for (int coordinate : rule.bounds) {
                box.put(coordinate);
            }
            try {
                list.put(new JSONObject().put("app", entry.getKey()).put("box", box)
                        .put("aspect", rule.aspect).put("features", new JSONArray(rule.required))
                        .put("enabled", rule.enabled));
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }
        prefs.edit().putString(RULES, list.toString()).apply();
    }

    static final class Rule {
        private final int[] bounds;
        private final double aspect;
        private final List<String> required;
        boolean enabled;

        Rule(int[] bounds, double aspect, List<String> required, boolean enabled) {
            this.bounds = bounds;
            this.aspect = aspect;
            this.required = required;
            this.enabled = enabled;
        }

        boolean supportsWindow(Rect window) {
            return !window.isEmpty()
                    && Math.abs((double) window.width() / window.height() / aspect - 1) <= 0.12;
        }

        Rect matchPage(AccessibilityNodeInfo root, Rect window) {
            if (!supportsWindow(window) || !new PageFeatures(root, window).values.containsAll(required)) {
                return null;
            }
            Rect box = new Rect(window.left + toPixels(bounds[0], window.width()),
                    window.top + toPixels(bounds[1], window.height()),
                    window.left + toPixels(bounds[2], window.width()),
                    window.top + toPixels(bounds[3], window.height()));
            return !box.isEmpty() && window.contains(box) ? box : null;
        }
        Rule resized(Rect region, Rect window) {
            Rule updated = makeRule(region, window, required);
            if (updated != null) {
                updated.enabled = enabled;
            }
            return updated;
        }

    }

    static Rule fromSelectedTab(PageFeatures blocked, Rect region, Rect window) {
        if (blocked.editing) {
            return null;
        }
        String selected = null;
        for (TopLabel label : blocked.topLabels) {
            if (!label.selected || !hasAdjacentLabel(label, blocked.topLabels)) {
                continue;
            }
            if (selected != null && !selected.equals(label.key)) {
                return null;
            }
            selected = label.key;
        }
        return selected == null ? null : makeRule(region, window,
                java.util.Collections.singletonList(selected));
    }

    private static boolean hasAdjacentLabel(TopLabel selected, List<TopLabel> labels) {
        for (TopLabel label : labels) {
            if (!label.selected && !selected.identity.equals(label.identity)
                    && (selected.bounds.right <= label.bounds.left
                            || label.bounds.right <= selected.bounds.left)
                    && Math.abs(selected.bounds.exactCenterY() - label.bounds.exactCenterY())
                            <= Math.min(selected.bounds.height(), label.bounds.height()) * 0.5f) {
                return true;
            }
        }
        return false;
    }

    static Rule learn(PageFeatures blocked, PageFeatures allowed, Rect region, Rect window) {
        if (blocked.editing || allowed.editing
                || java.util.Collections.disjoint(blocked.topIdentities, allowed.topIdentities)) {
            return null;
        }

        List<String> required = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TopLabel label : blocked.topLabels) {
            if (label.selected && allowed.topIdentities.contains(label.identity)
                    && !allowed.values.contains(label.key)) {
                return makeRule(region, window, java.util.Collections.singletonList(label.key));
            }
            if (!allowed.topIdentities.contains(label.identity) && seen.add(label.identity)) {
                required.add(label.key);
            }
        }
        return required.size() >= 2 ? makeRule(region, window, required.subList(0, 2)) : null;
    }

    private static Rule makeRule(Rect region, Rect window, List<String> required) {
        if (window.isEmpty() || region.isEmpty() || !window.contains(region)) {
            return null;
        }
        int[] box = {toFraction(region.left - window.left, window.width()),
                toFraction(region.top - window.top, window.height()),
                toFraction(region.right - window.left, window.width()),
                toFraction(region.bottom - window.top, window.height())};
        return new Rule(box, (double) window.width() / window.height(), required, true);
    }

    private static int toFraction(int pixels, int size) {
        return (int) Math.round((double) pixels * SCALE / size);
    }

    private static int toPixels(int fraction, int size) {
        return (int) Math.round((double) fraction * size / SCALE);
    }

    static final class PageFeatures {
        private final Rect window;
        private final MessageDigest digest;
        private final Set<String> values = new HashSet<>();
        private final Set<String> topIdentities = new HashSet<>();
        private final List<TopLabel> topLabels = new ArrayList<>();
        private int budget = 450;
        private boolean editing;

        PageFeatures(AccessibilityNodeInfo root, Rect window) {
            this.window = window;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
            visit(root, false);
            if (editing) {
                values.clear();
            }
        }

        private void visit(AccessibilityNodeInfo node, boolean parentSelected) {
            if (budget-- <= 0 || !node.isVisibleToUser()) {
                return;
            }
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            float center = (rect.exactCenterY() - window.top) / window.height();
            boolean compact = !rect.isEmpty() && rect.height() < window.height() * 0.12f;
            boolean selected = parentSelected || node.isSelected() || node.isChecked();
            if (compact && (center < 0.20f || center > 0.86f)) {
                CharSequence label = node.getText();
                if (label == null || label.length() == 0) {
                    label = node.getContentDescription();
                }
                if (label != null && label.length() >= 1 && label.length() <= 28 && !node.isEditable()) {
                    String text = label.toString().trim();
                    if (!text.isEmpty() && !text.matches("[0-9: .]+")) {
                        boolean top = center < 0.20f;
                        String identity = (top ? "top|" : "bottom|") + text;
                        String key = hash(identity + "|" + selected);
                        values.add(key);
                        if (top) {
                            String identityHash = hash(identity);
                            topIdentities.add(identityHash);
                            topLabels.add(new TopLabel(identityHash, key, rect, selected));
                        }
                    }
                }
            }
            editing |= node.isEditable() && node.isFocused();
            boolean inherit = selected && compact && rect.width() < window.width() * 0.45f;
            for (int i = 0; i < node.getChildCount() && budget > 0; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        visit(child, inherit);
                    } finally {
                        child.recycle();
                    }
                }
            }
        }
        private String hash(String value) {
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        }
    }

    private static final class TopLabel {
        final String identity;
        final String key;
        final Rect bounds;
        final boolean selected;

        TopLabel(String identity, String key, Rect bounds, boolean selected) {
            this.identity = identity;
            this.key = key;
            this.bounds = bounds;
            this.selected = selected;
        }
    }
}

package app.local.rednoteshield;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import java.util.concurrent.ForkJoinPool;

final class PageShadeDetector {
    private static final long MIN_SAMPLE_INTERVAL_MS = 1400;
    private final AccessibilityService service;
    private final Runnable updatePalette;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable request = this::requestSample;
    private Sample current;
    private boolean dark;
    private long nextRequestAt;

    PageShadeDetector(AccessibilityService service, Runnable updatePalette) {
        this.service = service;
        this.updatePalette = updatePalette;
    }

    void observe(int windowId, Rect window, Rect mask) {
        if (Build.VERSION.SDK_INT < 30 || window.isEmpty() || mask.isEmpty()) {
            clear();
            return;
        }
        if (current != null && current.windowId == windowId && current.window.equals(window)
                && current.mask.equals(mask)) {
            return;
        }
        clear();
        current = new Sample(windowId, window, mask);
        main.postAtTime(request, Math.max(SystemClock.uptimeMillis(), nextRequestAt));
    }

    boolean isDark(int windowId) {
        return current != null && current.windowId == windowId && dark;
    }

    void clear() {
        main.removeCallbacks(request);
        current = null;
        dark = false;
    }

    private void requestSample() {
        Sample sample = current;
        if (sample == null || Build.VERSION.SDK_INT < 30) {
            return;
        }
        nextRequestAt = SystemClock.uptimeMillis() + MIN_SAMPLE_INTERVAL_MS;
        boolean windowOnly = Build.VERSION.SDK_INT >= 34;
        AccessibilityService.TakeScreenshotCallback callback = new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                boolean darkPage = readSample(result, sample, windowOnly);
                main.post(() -> {
                    if (current == sample && dark != darkPage) {
                        dark = darkPage;
                        updatePalette.run();
                    }
                });
            }

            @Override
            public void onFailure(int errorCode) {
                // System colors remain in effect when the app or device does not allow capture.
            }
        };
        try {
            if (windowOnly) {
                service.takeScreenshotOfWindow(sample.windowId, ForkJoinPool.commonPool(), callback);
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, ForkJoinPool.commonPool(), callback);
            }
        } catch (SecurityException ignored) {
            // The accessibility service can be disconnected between scheduling and requesting.
        }
    }

    private static boolean readSample(AccessibilityService.ScreenshotResult result, Sample sample,
            boolean windowOnly) {
        try (HardwareBuffer buffer = result.getHardwareBuffer()) {
            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (wrapped == null) {
                return false;
            }
            try {
                Bitmap bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                if (bitmap == null) {
                    return false;
                }
                try {
                    Rect window = sample.window;
                    Rect mask = sample.mask;
                    int left = windowOnly ? 0 : window.left;
                    int right = windowOnly ? bitmap.getWidth() : window.right;
                    int top = windowOnly ? 0 : window.top;
                    int bottom = windowOnly ? bitmap.getHeight() : window.bottom;
                    if (left < 0 || top < 0 || right > bitmap.getWidth() || bottom > bitmap.getHeight()) {
                        return false;
                    }
                    int height = bottom - top;
                    int headerEnd = top + (int) ((long) (mask.top - window.top) * height / window.height());
                    int footerStart = top + (int) ((long) (mask.bottom - window.top) * height / window.height());
                    return isDarkChrome(bitmap, left, top, right, headerEnd, footerStart, bottom);
                } finally {
                    bitmap.recycle();
                }
            } finally {
                wrapped.recycle();
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean isDarkChrome(Bitmap bitmap, int left, int top, int right, int headerEnd,
            int footerStart, int bottom) {
        int height = bottom - top;
        if (height <= 0 || right <= left || headerEnd - top < height * 0.03
                || headerEnd > footerStart || footerStart > bottom) {
            return false;
        }
        // Prefer navigation chrome and avoid judging the theme from cards, video or the overlay itself.
        double header = darkFraction(bitmap, left, top, right, headerEnd);
        if (header < 0.72) {
            return false;
        }
        return bottom - footerStart < height * 0.03
                ? header >= 0.88 : darkFraction(bitmap, left, footerStart, right, bottom) >= 0.58;
    }

    private static double darkFraction(Bitmap bitmap, int left, int top, int right, int bottom) {
        int darkPixels = 0;
        for (int row = 1; row <= 5; row++) {
            int y = top + (bottom - top) * row / 6;
            for (int column = 1; column <= 9; column++) {
                int x = left + (right - left) * column / 10;
                int color = bitmap.getPixel(x, y);
                int red = (color >>> 16) & 255;
                int green = (color >>> 8) & 255;
                int blue = color & 255;
                int luma = (54 * red + 183 * green + 19 * blue) / 256;
                int spread = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
                if ((color >>> 24) >= 224 && luma <= 80 && spread <= 72) {
                    darkPixels++;
                }
            }
        }
        return darkPixels / 45.0;
    }

    private static final class Sample {
        final int windowId;
        final Rect window;
        final Rect mask;

        Sample(int windowId, Rect window, Rect mask) {
            this.windowId = windowId;
            this.window = new Rect(window);
            this.mask = new Rect(mask);
        }
    }
}

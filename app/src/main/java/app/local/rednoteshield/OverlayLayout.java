package app.local.rednoteshield;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;

final class OverlayLayout {
    private OverlayLayout() {}

    static WindowManager.LayoutParams at(Rect bounds) {
        WindowManager.LayoutParams params = create(bounds.width(), bounds.height());
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = bounds.left;
        params.y = bounds.top;
        return params;
    }

    static WindowManager.LayoutParams create(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
        }
        return params;
    }
}

package io.github.freefrank.lostodyssey;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.Arrays;
import java.util.Locale;

/** A second Android window, never a second SDLActivity or game instance. Main thread only. */
final class DualScreenManager implements DisplayManager.DisplayListener {
    private static final String TAG = "LODualScreen";
    private final Activity activity;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CompanionPresentation presentation;
    private boolean active;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active || presentation == null) return;
            try {
                CompanionSnapshot snapshot = CompanionSnapshot.nativeRead();
                presentation.update(snapshot == null ? CompanionSnapshot.waiting() : snapshot);
            } catch (UnsatisfiedLinkError error) {
                Log.e(TAG, "Companion JNI is missing; rebuild the native runtime", error);
                stop();
                return;
            }
            handler.postDelayed(this, 250);
        }
    };

    DualScreenManager(Activity activity) {
        this.activity = activity;
        displays = activity.getSystemService(DisplayManager.class);
    }

    void start() {
        if (active || displays == null) return;
        active = true;
        displays.registerDisplayListener(this, handler);
        refresh();
    }

    void stop() {
        active = false;
        if (displays != null) displays.unregisterDisplayListener(this);
        closePresentation();
    }

    private void closePresentation() {
        handler.removeCallbacks(poll);
        if (presentation == null) return;
        CompanionPresentation old = presentation;
        presentation = null;
        old.setOnDismissListener(null);
        old.dismiss();
        try { CompanionSnapshot.nativeSetEnabled(false); }
        catch (UnsatisfiedLinkError error) { Log.e(TAG, "Companion JNI unavailable", error); }
    }

    private void refresh() {
        if (!active) return;
        int mainId = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        Display[] candidates = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Arrays.sort(candidates, (a, b) -> Integer.compare(a.getDisplayId(), b.getDisplayId()));
        Display selected = null;
        // Keep the current screen on hotplug instead of moving to a newly attached output.
        for (Display display : candidates) {
            if (!display.isValid() || display.getDisplayId() == mainId
                    || (display.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            if (selected == null) selected = display;
            if (presentation != null && display.getDisplayId() == presentation.getDisplay().getDisplayId()) {
                selected = display;
                break;
            }
        }
        if (presentation != null && selected != null
                && presentation.getDisplay().getDisplayId() == selected.getDisplayId()) return;
        closePresentation();
        if (selected == null) return;
        try {
            CompanionPresentation next = new CompanionPresentation(activity, selected);
            presentation = next;
            next.show();
            CompanionSnapshot.nativeSetEnabled(true);
            next.setOnDismissListener(dialog -> {
                if (presentation == next) {
                    // Removal may dismiss the window before DisplayListener runs.
                    closePresentation();
                    handler.post(DualScreenManager.this::refresh);
                }
            });
            Log.i(TAG, "Companion opened on display " + selected.getDisplayId());
            handler.post(poll);
        } catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException
                 | SecurityException | IllegalArgumentException | UnsatisfiedLinkError error) {
            closePresentation();
            Log.w(TAG, "Companion unavailable; keeping single-screen game", error);
        }
    }

    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }

    private static final class CompanionPresentation extends Presentation {
        private TextView title, status, points;
        private MapView map;

        CompanionPresentation(Context context, Display display) {
            super(context, display);
        }

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            Window window = getWindow();
            if (window != null) {
                // Touch remains local to this window; physical keys stay with SDL on the main screen.
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            Context context = getContext(); // Use the lower display's density and configuration.
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.rgb(17, 23, 32));
            int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
            root.setPadding(padding, padding, padding, padding);
            ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
                androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
                return insets;
            });
            title = text(context, 20);
            status = text(context, 12);
            root.addView(title);
            root.addView(status);
            LinearLayout tabs = new LinearLayout(context);
            Button mapTab = new Button(context);
            mapTab.setText("方位示意");
            Button listTab = new Button(context);
            listTab.setText("地点列表");
            tabs.addView(mapTab, new LinearLayout.LayoutParams(0, -2, 1));
            tabs.addView(listTab, new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(tabs);
            map = new MapView(context);
            ScrollView list = new ScrollView(context);
            points = text(context, 16);
            list.addView(points);
            root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            list.setVisibility(View.GONE);
            mapTab.setOnClickListener(view -> { map.setVisibility(View.VISIBLE); list.setVisibility(View.GONE); });
            listTab.setOnClickListener(view -> { map.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); });
            setContentView(root);
            ViewCompat.requestApplyInsets(root);
            update(CompanionSnapshot.waiting());
        }

        private static TextView text(Context context, int size) {
            TextView view = new TextView(context);
            view.setTextColor(Color.rgb(226, 233, 242));
            view.setTextSize(size);
            view.setGravity(Gravity.START);
            return view;
        }

        void update(CompanionSnapshot snapshot) {
            title.setText(snapshot.title);
            status.setText(snapshot.status);
            map.snapshot = snapshot;
            map.invalidate();
            StringBuilder text = new StringBuilder();
            if (snapshot.hasPlayer()) {
                text.append(String.format(Locale.ROOT, "玩家  X %.0f  Y %.0f  Z %.0f\n\n",
                    snapshot.positions[0], snapshot.positions[1], snapshot.positions[2]));
                for (int i = 0; i < snapshot.labels.length; i++) {
                    int p = 3 * (i + 1);
                    text.append(String.format(Locale.ROOT, "%d. %s\nX %.0f  Y %.0f  Z %.0f\n\n",
                        i + 1, snapshot.labels[i], snapshot.positions[p], snapshot.positions[p + 1], snapshot.positions[p + 2]));
                }
                if (snapshot.labels.length == 0) text.append("当前没有已识别地点");
            } else text.append(snapshot.status);
            points.setText(text);
        }
    }

    private static final class MapView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private CompanionSnapshot snapshot = CompanionSnapshot.waiting();

        MapView(Context context) { super(context); density = getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!snapshot.hasPlayer()) return;
            float side = Math.min(getWidth(), getHeight()) - 48 * density;
            if (side <= 0) return;
            float left = (getWidth() - side) / 2, top = (getHeight() - side) / 2;
            paint.setColor(Color.rgb(47, 60, 75));
            paint.setStrokeWidth(density);
            for (int i = 0; i <= 4; i++) {
                float offset = side * i / 4;
                canvas.drawLine(left + offset, top, left + offset, top + side, paint);
                canvas.drawLine(left, top + offset, left + side, top + offset, paint);
            }
            float[] bounds = snapshot.bounds();
            paint.setTextSize(12 * density);
            // World XY axes only: this is not a north-calibrated or floor-aware map.
            for (int i = snapshot.labels.length; i >= 0; i--) {
                int p = i * 3;
                float x = left + (snapshot.positions[p] - bounds[0]) / bounds[2] * side;
                float y = top + side - (snapshot.positions[p + 1] - bounds[1]) / bounds[2] * side;
                paint.setColor(i == 0 ? Color.rgb(107, 218, 195) : Color.rgb(244, 192, 106));
                canvas.drawCircle(x, y, (i == 0 ? 6 : 4) * density, paint);
                canvas.drawText(i == 0 ? "玩家" : Integer.toString(i), x + 8 * density, y, paint);
            }
            paint.setColor(Color.LTGRAY);
            canvas.drawText("+Y", left, Math.max(12 * density, top - 5 * density), paint);
            canvas.drawText("+X", left + side - 20 * density, top + side + 17 * density, paint);
        }
    }
}

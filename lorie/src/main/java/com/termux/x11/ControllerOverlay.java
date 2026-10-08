package com.termux.x11;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.termux.x11.input.InputStub;

/**
 * Overlay kontroler di atas layar X11 (gaya Cloud Magic):
 *  - tombol mouse kiri/kanan (satu lingkaran terbelah dua, mendukung multi-touch)
 *  - tombol scroll atas / bawah (tahan = scroll terus)
 *  - Enter, Backspace (tahan = berulang), Alt (tap = kunci/lepas)
 *  - ikon keyboard (buka/tutup keyboard Android)
 *  - tombol "UI" kecil untuk menyembunyikan/menampilkan semua tombol
 *
 * Seluruh tampilan dibuat lewat kode, jadi tidak perlu mengubah layout XML.
 * Area kosong tidak menangkap sentuhan, jadi gesture layar tetap jalan normal.
 */
@SuppressLint("ViewConstructor")
public class ControllerOverlay extends FrameLayout {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    private static final int COLOR_BASE = 0x99202020;
    private static final int COLOR_STROKE = 0x88FFFFFF;
    private static final int COLOR_PRESSED = 0xCC1FA66A;

    // Jarak aman dari bawah supaya tidak menabrak extra keys bar.
    private static final int BASE_BOTTOM_DP = 56;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final FrameLayout panel;
    private boolean altLatched = false;

    public ControllerOverlay(MainActivity activity) {
        super(activity);
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setVisibility(GONE);

        panel = new FrameLayout(activity);
        addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));

        buildControls();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private FrameLayout.LayoutParams lp(int wDp, int hDp, int gravity, int leftDp, int topDp, int rightDp, int bottomDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(wDp), dp(hDp), gravity);
        p.setMargins(dp(leftDp), dp(topDp), dp(rightDp), dp(bottomDp));
        return p;
    }

    private StateListDrawable background(boolean oval) {
        GradientDrawable normal = new GradientDrawable();
        GradientDrawable pressed = new GradientDrawable();
        for (GradientDrawable d : new GradientDrawable[]{normal, pressed}) {
            if (oval)
                d.setShape(GradientDrawable.OVAL);
            else {
                d.setShape(GradientDrawable.RECTANGLE);
                d.setCornerRadius(dp(22));
            }
            d.setStroke(dp(1.5f), COLOR_STROKE);
        }
        normal.setColor(COLOR_BASE);
        pressed.setColor(COLOR_PRESSED);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{android.R.attr.state_activated}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    private TextView textButton(String text, int sp, boolean oval) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(background(oval));
        return t;
    }

    private ImageView iconButton(int drawableRes, int paddingDp) {
        ImageView v = new ImageView(activity);
        v.setImageResource(drawableRes);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        v.setBackground(background(true));
        return v;
    }

    // ------------------------------------------------------------------ kirim input

    private void key(int keyCode, boolean down) {
        activity.getLorieView().sendKeyEvent(0, keyCode, down);
    }

    private void tapKey(int keyCode) {
        key(keyCode, true);
        key(keyCode, false);
    }

    // ------------------------------------------------------------------ pengikat sentuhan

    /** Tombol keyboard biasa: tekan saat disentuh, lepas saat dilepas. Opsional berulang jika ditahan. */
    @SuppressLint("ClickableViewAccessibility")
    private void bindKey(View v, final int keyCode, final boolean repeat) {
        final Runnable repeater = new Runnable() {
            @Override
            public void run() {
                tapKey(keyCode);
                handler.postDelayed(this, 70);
            }
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    if (repeat) {
                        tapKey(keyCode);
                        handler.postDelayed(repeater, 400);
                    } else
                        key(keyCode, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    if (repeat)
                        handler.removeCallbacks(repeater);
                    else
                        key(keyCode, false);
                    break;
            }
            return true;
        });
    }

    /** Tombol scroll: tahan = scroll berulang. dy negatif = scroll ke atas (sama seperti roda mouse). */
    @SuppressLint("ClickableViewAccessibility")
    private void bindScroll(View v, final float dy) {
        final Runnable scroller = new Runnable() {
            @Override
            public void run() {
                activity.getLorieView().sendMouseWheelEvent(0, dy);
                handler.postDelayed(this, 90);
            }
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    scroller.run();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    handler.removeCallbacks(scroller);
                    break;
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ susunan tombol

    @SuppressLint("ClickableViewAccessibility")
    private void buildControls() {
        // Tombol UI (sembunyikan / tampilkan semua kontrol) - pojok kanan atas.
        final TextView toggle = textButton("UI", 11, true);
        addView(toggle, lp(40, 40, Gravity.RIGHT | Gravity.TOP, 0, 12, 12, 0));
        toggle.setOnClickListener(v -> {
            boolean show = panel.getVisibility() != VISIBLE;
            panel.setVisibility(show ? VISIBLE : GONE);
            toggle.setAlpha(show ? 1f : 0.5f);
        });

        // Backspace - kanan atas, di kiri tombol UI.
        TextView backspace = textButton("Backspace", 12, false);
        panel.addView(backspace, lp(96, 40, Gravity.RIGHT | Gravity.TOP, 0, 12, 60, 0));
        bindKey(backspace, KeyEvent.KEYCODE_DEL, true);

        // Scroll atas / bawah - kolom di sisi kanan, tengah vertikal.
        ImageView scrollUp = iconButton(R.drawable.ic_extra_key_arrow_up, 10);
        panel.addView(scrollUp, lp(52, 52, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 64));
        bindScroll(scrollUp, -100f);

        ImageView scrollDown = iconButton(R.drawable.ic_extra_key_arrow_down, 10);
        panel.addView(scrollDown, lp(52, 52, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 64, 12, 0));
        bindScroll(scrollDown, 100f);

        // Ikon keyboard - pojok kanan bawah.
        ImageView keyboard = iconButton(R.drawable.ic_extra_key_keyboard, 12);
        panel.addView(keyboard, lp(52, 52, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, BASE_BOTTOM_DP + 30));
        keyboard.setOnClickListener(v -> activity.toggleKeyboardVisibility());

        // Klik kiri / kanan - lingkaran besar di kiri ikon keyboard.
        MouseButtons mouse = new MouseButtons(activity);
        panel.addView(mouse, lp(112, 112, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 76, BASE_BOTTOM_DP));

        // Enter - tepat di atas tombol mouse.
        TextView enter = textButton("Enter", 14, false);
        panel.addView(enter, lp(96, 44, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 84, BASE_BOTTOM_DP + 112 + 10));
        bindKey(enter, KeyEvent.KEYCODE_ENTER, false);

        // Alt - di kiri tombol mouse. Tap = kunci Alt, tap lagi = lepas.
        final TextView alt = textButton("Alt", 14, false);
        panel.addView(alt, lp(64, 44, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 196, BASE_BOTTOM_DP + 34));
        alt.setOnClickListener(v -> {
            altLatched = !altLatched;
            alt.setActivated(altLatched);
            key(KeyEvent.KEYCODE_ALT_LEFT, altLatched);
        });
    }

    /** Lingkaran terbelah: setengah kiri = klik kiri, setengah kanan = klik kanan. */
    private class MouseButtons extends View {
        private final SparseIntArray active = new SparseIntArray(); // pointerId -> tombol mouse
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        MouseButtons(MainActivity host) {
            super(host);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(COLOR_STROKE);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = dp(2);
            rect.set(pad, pad, w - pad, h - pad);

            fill.setColor(active.indexOfValue(InputStub.BUTTON_LEFT) >= 0 ? COLOR_PRESSED : COLOR_BASE);
            c.drawArc(rect, 90, 180, false, fill);   // setengah kiri
            fill.setColor(active.indexOfValue(InputStub.BUTTON_RIGHT) >= 0 ? COLOR_PRESSED : COLOR_BASE);
            c.drawArc(rect, 270, 180, false, fill);  // setengah kanan

            c.drawOval(rect, stroke);
            c.drawLine(w / 2, pad, w / 2, h - pad, stroke);

            float y = h / 2 + text.getTextSize() / 3;
            c.drawText("Kiri", w * 0.27f, y, text);
            c.drawText("Kanan", w * 0.73f, y, text);
        }

        private void releaseAll() {
            for (int i = 0; i < active.size(); i++)
                activity.getLorieView().sendMouseEvent(0, 0, active.valueAt(i), false, true);
            active.clear();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int idx = e.getActionIndex();
            int id = e.getPointerId(idx);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int button = e.getX(idx) < getWidth() / 2f ? InputStub.BUTTON_LEFT : InputStub.BUTTON_RIGHT;
                    active.put(id, button);
                    activity.getLorieView().sendMouseEvent(0, 0, button, true, true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int button = active.get(id, 0);
                    if (button != 0) {
                        activity.getLorieView().sendMouseEvent(0, 0, button, false, true);
                        active.delete(id);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    return true;
            }
            return true;
        }
    }
}

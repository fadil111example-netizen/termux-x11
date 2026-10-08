package com.termux.x11;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import com.termux.x11.input.InputStub;

/**
 * Overlay kontroler di atas layar X11 (gaya Cloud Magic, tampilan "glass" modern).
 *
 * Tombol:
 *  - Klik Kiri | Klik Kanan (satu lingkaran terbelah dua, multi-touch)
 *  - Scroll atas / bawah (tahan = scroll terus)
 *  - Enter
 *  - Backspace (tahan = berulang)
 *  - Ikon keyboard di pojok kanan bawah:
 *        tap        = buka/tutup keyboard bawaan Termux:X11 (bar Ctrl, Esc, Tab, panah, dll.)
 *        tahan lama = buka/tutup keyboard Android (untuk mengetik huruf)
 *  - "UI" kecil di pojok kanan atas untuk menyembunyikan/menampilkan semua tombol
 *
 * Posisi dan ukuran dihitung ulang otomatis untuk mode portrait / landscape, ukuran layar,
 * keyboard Android yang sedang tampil, dan bar extra keys, sehingga tombol tidak saling
 * menumpuk dan tidak tertutup keyboard.
 *
 * Seluruh tampilan dibuat lewat kode, jadi tidak perlu mengubah layout XML. Area kosong
 * tidak menangkap sentuhan, jadi gesture layar tetap berjalan normal.
 */
@SuppressLint({"ViewConstructor", "ClickableViewAccessibility"})
public class ControllerOverlay extends FrameLayout {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    // Palet warna
    private static final int GLASS_TOP = 0xDD3A4658;
    private static final int GLASS_BOTTOM = 0xDD161C26;
    private static final int ACCENT_TOP = 0xFF4ADE80;
    private static final int ACCENT_BOTTOM = 0xFF16A34A;
    private static final int STROKE_IDLE = 0x66FFFFFF;
    private static final int STROKE_ACTIVE = 0xFFB7F7CF;
    private static final int HIGHLIGHT = 0x26FFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final FrameLayout panel;

    private final GlassButton uiButton;
    private final GlassButton backspaceButton;
    private final GlassButton enterButton;
    private final GlassButton scrollUpButton;
    private final GlassButton scrollDownButton;
    private final GlassButton keyboardButton;
    private final MouseButtons mouseButtons;

    private boolean relayoutPending = false;
    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener = this::requestRelayout;

    public ControllerOverlay(MainActivity activity) {
        super(activity);
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setVisibility(GONE);
        setAlpha(0.95f);
        setClipChildren(false);

        panel = new FrameLayout(activity);
        panel.setClipChildren(false);
        addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Backspace
        backspaceButton = new GlassButton(activity, false);
        backspaceButton.setLabel("Backspace");
        bindKey(backspaceButton, KeyEvent.KEYCODE_DEL, true);
        panel.addView(backspaceButton, new FrameLayout.LayoutParams(dp(100), dp(44)));

        // Enter
        enterButton = new GlassButton(activity, false);
        enterButton.setLabel("Enter");
        bindKey(enterButton, KeyEvent.KEYCODE_ENTER, false);
        panel.addView(enterButton, new FrameLayout.LayoutParams(dp(92), dp(44)));

        // Scroll atas / bawah
        scrollUpButton = new GlassButton(activity, true);
        scrollUpButton.setIcon(R.drawable.ic_extra_key_arrow_up);
        bindScroll(scrollUpButton, -100f);
        panel.addView(scrollUpButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        scrollDownButton = new GlassButton(activity, true);
        scrollDownButton.setIcon(R.drawable.ic_extra_key_arrow_down);
        bindScroll(scrollDownButton, 100f);
        panel.addView(scrollDownButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        // Klik kiri / kanan
        mouseButtons = new MouseButtons(activity);
        panel.addView(mouseButtons, new FrameLayout.LayoutParams(dp(108), dp(108)));

        // Keyboard (pojok kanan bawah)
        keyboardButton = new GlassButton(activity, true);
        keyboardButton.setIcon(R.drawable.ic_extra_key_keyboard);
        keyboardButton.setClickAction(activity::toggleControllerKeyboard);
        keyboardButton.setLongClickAction(activity::toggleKeyboardVisibility);
        panel.addView(keyboardButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        // UI: sembunyikan / tampilkan semua tombol (selalu terlihat, di luar panel)
        uiButton = new GlassButton(activity, true);
        uiButton.setLabel("UI");
        uiButton.setClickAction(() -> {
            boolean show = panel.getVisibility() != VISIBLE;
            panel.setVisibility(show ? VISIBLE : GONE);
            uiButton.setAlpha(show ? 1f : 0.55f);
        });
        addView(uiButton, new FrameLayout.LayoutParams(dp(44), dp(44)));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ kirim input

    private void key(int keyCode, boolean down) {
        activity.getLorieView().sendKeyEvent(0, keyCode, down);
    }

    private void tapKey(int keyCode) {
        key(keyCode, true);
        key(keyCode, false);
    }

    /** Tombol keyboard: tekan saat disentuh, lepas saat dilepas. Opsional berulang jika ditahan. */
    private void bindKey(GlassButton button, final int keyCode, final boolean repeat) {
        final Runnable repeater = new Runnable() {
            @Override
            public void run() {
                tapKey(keyCode);
                handler.postDelayed(this, 65);
            }
        };
        button.setPressAction(() -> {
            if (repeat) {
                tapKey(keyCode);
                handler.postDelayed(repeater, 380);
            } else {
                key(keyCode, true);
            }
        });
        button.setReleaseAction(() -> {
            if (repeat)
                handler.removeCallbacks(repeater);
            else
                key(keyCode, false);
        });
    }

    /** Tombol scroll: tahan = scroll berulang. dy negatif = ke atas (seperti roda mouse). */
    private void bindScroll(GlassButton button, final float dy) {
        final Runnable scroller = new Runnable() {
            @Override
            public void run() {
                activity.getLorieView().sendMouseWheelEvent(0, dy);
                handler.postDelayed(this, 85);
            }
        };
        button.setPressAction(scroller);
        button.setReleaseAction(() -> handler.removeCallbacks(scroller));
    }

    // ------------------------------------------------------------------ siklus hidup & layout

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
        requestRelayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        requestRelayout();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            if (visibility == VISIBLE)
                requestRelayout();
            else
                handler.removeCallbacksAndMessages(null);
        }
    }

    /** Dipanggil MainActivity saat keyboard Android / bar extra keys berubah, dan juga saat rotasi. */
    public void requestRelayout() {
        if (relayoutPending)
            return;
        relayoutPending = true;
        post(() -> {
            relayoutPending = false;
            relayout();
        });
    }

    private void place(View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (p == null)
            p = new FrameLayout.LayoutParams(w, h);
        if (p.width == w && p.height == h && p.leftMargin == x && p.topMargin == y
                && p.gravity == (Gravity.LEFT | Gravity.TOP))
            return;
        p.width = w;
        p.height = h;
        p.gravity = Gravity.LEFT | Gravity.TOP;
        p.setMargins(x, y, 0, 0);
        v.setLayoutParams(p);
    }

    private void relayout() {
        final int W = getWidth();
        final int H = getHeight();
        if (W <= 0 || H <= 0)
            return;

        // 1) Area aman: tidak menabrak status bar / navigasi, poni layar, keyboard Android, bar extra keys.
        int[] onScreen = new int[2];
        int[] inWindow = new int[2];
        getLocationOnScreen(onScreen);
        getLocationInWindow(inWindow);
        Rect visible = new Rect();
        getWindowVisibleDisplayFrame(visible);

        int insetL = Math.max(0, visible.left - onScreen[0]);
        int insetT = Math.max(0, visible.top - onScreen[1]);
        int insetR = Math.max(0, onScreen[0] + W - visible.right);
        int insetB = Math.max(0, onScreen[1] + H - visible.bottom);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowInsets wi = getRootWindowInsets();
            DisplayCutout cutout = wi == null ? null : wi.getDisplayCutout();
            if (cutout != null) {
                int rootW = getRootView().getWidth();
                int rootH = getRootView().getHeight();
                insetL = Math.max(insetL, cutout.getSafeInsetLeft() - inWindow[0]);
                insetT = Math.max(insetT, cutout.getSafeInsetTop() - inWindow[1]);
                insetR = Math.max(insetR, cutout.getSafeInsetRight() - (rootW - (inWindow[0] + W)));
                insetB = Math.max(insetB, cutout.getSafeInsetBottom() - (rootH - (inWindow[1] + H)));
            }
        }

        insetB = Math.max(insetB, activity.getControllerImeHeight());

        Rect bar = activity.getControllerBarInsets();
        insetL += bar.left;
        insetT += bar.top;
        insetR += bar.right;
        insetB += bar.bottom;

        // Pengaman: kalau area sisa terlalu kecil, abaikan inset samping / vertikal.
        if (W - insetL - insetR < dp(200)) {
            insetL = 0;
            insetR = 0;
        }
        if (H - insetT - insetB < dp(120)) {
            insetT = 0;
            insetB = 0;
        }

        // 2) Skala otomatis mengikuti ukuran area yang tersedia.
        float availWdp = (W - insetL - insetR) / density;
        float availHdp = (H - insetT - insetB) / density;
        float s = Math.max(0.72f, Math.min(1.08f, Math.min(availWdp, availHdp) / 400f));
        float u = density * s; // piksel per "dp terskala"

        int m = Math.round(14 * u);        // margin tepi
        int g = Math.round(10 * u);        // jarak antar tombol
        int btn = Math.round(52 * u);      // tombol bulat: scroll & keyboard
        int ui = Math.round(44 * u);       // tombol UI & tinggi pill
        int bsW = Math.round(100 * u);     // lebar Backspace
        int enW = Math.round(92 * u);      // lebar Enter
        int mouseD = Math.round(108 * u);  // diameter tombol mouse

        backspaceButton.setLabelSize(14f * u);
        enterButton.setLabelSize(15f * u);
        uiButton.setLabelSize(13f * u);

        int right0 = W - insetR - m;
        int bottom0 = H - insetB - m;
        int top0 = insetT + m;

        // 3) Baris atas: [Backspace] [UI] di kanan atas.
        int bsX = right0 - ui - g - bsW;
        place(uiButton, right0 - ui, top0, ui, ui);
        place(backspaceButton, bsX, top0, bsW, ui);

        // 4) Baris bawah: ikon keyboard di POJOK KANAN BAWAH, tombol mouse di kirinya.
        int kbY = bottom0 - btn;
        place(keyboardButton, right0 - btn, kbY, btn, btn);

        int mouseX = right0 - btn - g - mouseD;
        int mouseY = bottom0 - mouseD;
        place(mouseButtons, mouseX, mouseY, mouseD, mouseD);

        // 5) Scroll: kolom di sisi kanan, di antara baris atas dan ikon keyboard.
        int topEdge = top0 + ui + g;
        int botEdge = kbY - g;
        int band = botEdge - topEdge;
        int need = 2 * btn + g;
        if (band >= need) {
            int y = topEdge + (band - need) / 2;
            place(scrollUpButton, right0 - btn, y, btn, btn);
            place(scrollDownButton, right0 - btn, y + btn + g, btn, btn);
        } else {
            // Layar pendek (misalnya landscape + keyboard Android tampil): scroll pindah ke baris atas.
            int x2 = bsX - g - ui;
            place(scrollDownButton, x2, top0, ui, ui);
            place(scrollUpButton, x2 - g - ui, top0, ui, ui);
        }

        // 6) Enter: tepat di atas tombol mouse; kalau tidak muat, pindah ke kiri tombol mouse.
        int enterAboveY = mouseY - g - ui;
        if (enterAboveY >= topEdge) {
            place(enterButton, mouseX + (mouseD - enW) / 2, enterAboveY, enW, ui);
        } else {
            place(enterButton, mouseX - g - enW, mouseY + (mouseD - ui) / 2, enW, ui);
        }

        keyboardButton.setActive(activity.isExtraKeysBarVisible());
    }

    // ------------------------------------------------------------------ tombol kaca (glass)

    /** Tombol bulat atau pill dengan efek kaca, gradasi hijau saat ditekan, dan animasi tekan. */
    private final class GlassButton extends View {
        private final boolean circle;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF inner = new RectF();
        private Shader idleShader;
        private Shader pressedShader;
        private String label;
        private Drawable icon;
        private boolean active;
        private boolean longFired;
        private Runnable pressAction;
        private Runnable releaseAction;
        private Runnable clickAction;
        private Runnable longClickAction;
        private final Runnable longRunnable = () -> {
            if (isPressed() && longClickAction != null) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                longClickAction.run();
            }
        };

        GlassButton(MainActivity host, boolean circle) {
            super(host);
            this.circle = circle;
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(dp(1f));
            highlight.setColor(HIGHLIGHT);
            text.setColor(TEXT_COLOR);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            text.setTextSize(dp(14));
        }

        void setLabel(String value) {
            label = value;
            icon = null;
            invalidate();
        }

        void setIcon(int drawableRes) {
            Drawable d = getContext().getDrawable(drawableRes);
            if (d != null) {
                d = d.mutate();
                d.setTint(TEXT_COLOR);
            }
            icon = d;
            label = null;
            invalidate();
        }

        void setLabelSize(float px) {
            if (text.getTextSize() != px) {
                text.setTextSize(px);
                invalidate();
            }
        }

        void setActive(boolean value) {
            if (active != value) {
                active = value;
                invalidate();
            }
        }

        void setPressAction(Runnable r) {
            pressAction = r;
        }

        void setReleaseAction(Runnable r) {
            releaseAction = r;
        }

        void setClickAction(Runnable r) {
            clickAction = r;
        }

        void setLongClickAction(Runnable r) {
            longClickAction = r;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float pad = stroke.getStrokeWidth() / 2f + 1f;
            rect.set(pad, pad, w - pad, h - pad);
            float in = pad + dp(2.5f);
            inner.set(in, in, w - in, h - in);
            idleShader = new LinearGradient(0, 0, 0, h, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP);
            pressedShader = new LinearGradient(0, 0, 0, h, ACCENT_TOP, ACCENT_BOTTOM, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (idleShader == null || pressedShader == null)
                return;
            boolean lit = isPressed() || active;
            float r = circle ? Math.min(rect.width(), rect.height()) / 2f : rect.height() / 2f;

            fill.setShader(lit ? pressedShader : idleShader);
            c.drawRoundRect(rect, r, r, fill);

            stroke.setColor(lit ? STROKE_ACTIVE : STROKE_IDLE);
            c.drawRoundRect(rect, r, r, stroke);

            float ri = Math.max(0f, r - dp(2.5f));
            c.drawRoundRect(inner, ri, ri, highlight);

            if (icon != null) {
                int size = Math.round(getHeight() * 0.46f);
                int left = (getWidth() - size) / 2;
                int top = (getHeight() - size) / 2;
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(c);
            } else if (label != null) {
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(label, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2f, text);
            }
        }

        @Override
        public void setPressed(boolean pressed) {
            boolean was = isPressed();
            super.setPressed(pressed);
            if (was != pressed) {
                if (pressed) {
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(new OvershootInterpolator(2f)).start();
                }
                invalidate();
            }
        }

        private boolean isInside(MotionEvent e) {
            return e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longFired = false;
                    setPressed(true);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (pressAction != null)
                        pressAction.run();
                    if (longClickAction != null)
                        handler.postDelayed(longRunnable, 450);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (clickAction != null && isPressed() && !isInside(e)) {
                        handler.removeCallbacks(longRunnable);
                        setPressed(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    handler.removeCallbacks(longRunnable);
                    boolean fire = isPressed() && isInside(e) && !longFired;
                    setPressed(false);
                    if (releaseAction != null)
                        releaseAction.run();
                    if (fire && clickAction != null)
                        clickAction.run();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longRunnable);
                    setPressed(false);
                    if (releaseAction != null)
                        releaseAction.run();
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ tombol mouse kiri | kanan

    /** Lingkaran terbelah: setengah kiri = klik kiri, setengah kanan = klik kanan. Mendukung multi-touch. */
    private final class MouseButtons extends View {
        private final SparseIntArray pressed = new SparseIntArray(); // pointerId -> tombol mouse
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF inner = new RectF();
        private Shader idleShader;
        private Shader pressedShader;

        MouseButtons(MainActivity host) {
            super(host);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(STROKE_IDLE);
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(dp(1f));
            highlight.setColor(HIGHLIGHT);
            divider.setStyle(Paint.Style.STROKE);
            divider.setStrokeWidth(dp(1.5f));
            divider.setColor(STROKE_IDLE);
            text.setColor(TEXT_COLOR);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float pad = stroke.getStrokeWidth() / 2f + 1f;
            rect.set(pad, pad, w - pad, h - pad);
            float in = pad + dp(2.5f);
            inner.set(in, in, w - in, h - in);
            idleShader = new LinearGradient(0, 0, 0, h, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP);
            pressedShader = new LinearGradient(0, 0, 0, h, ACCENT_TOP, ACCENT_BOTTOM, Shader.TileMode.CLAMP);
            text.setTextSize(h * 0.15f);
        }

        private void drawLitHalf(Canvas c, float left, float right) {
            c.save();
            c.clipRect(left, 0f, right, (float) getHeight());
            fill.setShader(pressedShader);
            c.drawOval(rect, fill);
            c.restore();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (idleShader == null || pressedShader == null)
                return;
            float w = getWidth();
            float h = getHeight();

            fill.setShader(idleShader);
            c.drawOval(rect, fill);
            if (pressed.indexOfValue(InputStub.BUTTON_LEFT) >= 0)
                drawLitHalf(c, 0f, w / 2f);
            if (pressed.indexOfValue(InputStub.BUTTON_RIGHT) >= 0)
                drawLitHalf(c, w / 2f, w);

            c.drawOval(rect, stroke);
            c.drawOval(inner, highlight);

            float margin = rect.top + h * 0.1f;
            c.drawLine(w / 2f, margin, w / 2f, h - margin, divider);

            Paint.FontMetrics fm = text.getFontMetrics();
            float y = h / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText("Kiri", w * 0.27f, y, text);
            c.drawText("Kanan", w * 0.73f, y, text);
        }

        private void releaseAll() {
            for (int i = 0; i < pressed.size(); i++)
                activity.getLorieView().sendMouseEvent(0, 0, pressed.valueAt(i), false, true);
            pressed.clear();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int index = e.getActionIndex();
            int id = e.getPointerId(index);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int button = e.getX(index) < getWidth() / 2f ? InputStub.BUTTON_LEFT : InputStub.BUTTON_RIGHT;
                    pressed.put(id, button);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    activity.getLorieView().sendMouseEvent(0, 0, button, true, true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int button = pressed.get(id, 0);
                    if (button != 0) {
                        activity.getLorieView().sendMouseEvent(0, 0, button, false, true);
                        pressed.delete(id);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    return true;
                default:
                    return true;
            }
        }
    }
}

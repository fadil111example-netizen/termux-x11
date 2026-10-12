package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
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

import java.util.HashMap;

/**
 * Overlay kontroler di atas layar X11 (gaya Cloud Magic, tampilan "glass" modern).
 *
 * Tombol:
 *  - Klik Kiri | Klik Kanan (satu lingkaran terbelah dua, multi-touch)
 *  - Scroll atas / bawah (tahan = scroll terus)
 *  - Enter
 *  - Backspace (tahan = berulang)
 *  - Ikon keyboard di pojok kanan bawah:
 *        tap        = tampil/sembunyikan keyboard bawaan Termux:X11 (Ctrl, Esc, Tab, panah, dll.)
 *        tahan lama = tampil/sembunyikan keyboard Android (untuk mengetik huruf)
 *  - "UI" di pojok kanan atas:
 *        tap        = sembunyikan / tampilkan semua tombol
 *        tahan lama = MODE ATUR POSISI: seret tombol ke mana saja, Reset, A- / A+ untuk ukuran
 *
 * Fitur:
 *  - Posisi dan ukuran otomatis menyesuaikan portrait / landscape, ukuran layar, keyboard Android
 *    dan bar extra keys, sehingga tidak saling menumpuk.
 *  - Posisi hasil seretan dan ukuran tersimpan terpisah untuk portrait dan landscape.
 *  - Tombol memudar sendiri saat tidak disentuh dan muncul lagi begitu disentuh.
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

    private static final float ALPHA_ACTIVE = 1f;
    private static final float ALPHA_IDLE = 0.45f;
    private static final long FADE_DELAY_MS = 3500;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private final int sh; // ruang untuk bayangan di sekeliling tiap tombol (piksel)
    private final SharedPreferences store;
    private final FrameLayout panel;

    private final GlassButton uiButton;
    private final GlassButton backspaceButton;
    private final GlassButton enterButton;
    private final GlassButton scrollUpButton;
    private final GlassButton scrollDownButton;
    private final GlassButton keyboardButton;
    private final MouseButtons mouseButtons;
    private final GlassButton resetButton;
    private final GlassButton smallerButton;
    private final GlassButton biggerButton;
    private final GlassButton hintButton;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final HashMap<String, int[]> lastDefault = new HashMap<>();
    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener = this::requestRelayout;
    private Runnable fadeRunnable;

    private boolean relayoutPending = false;
    private boolean editMode = false;
    private float userScale = 1f;
    private int holdCount = 0;

    // Ukuran area terakhir yang dipakai relayout()
    private int curW, curH;
    private boolean curLandscape;
    private int areaL, areaT, areaR, areaB;

    // Status seretan (mode atur posisi)
    private float dragStartRawX, dragStartRawY, dragStartTx, dragStartTy;
    private boolean dragMoved;

    public ControllerOverlay(MainActivity activity) {
        super(activity);
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.sh = Math.round(6 * density);
        this.store = activity.getSharedPreferences("controller_overlay", Context.MODE_PRIVATE);
        this.userScale = Math.max(0.6f, Math.min(1.6f, store.getFloat("scale", 1f)));

        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setVisibility(GONE);
        setAlpha(ALPHA_ACTIVE);
        setClipChildren(false);

        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0xFF000000);
        shadowPaint.setShadowLayer(dp(4), 0f, dp(2), 0x99000000);
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        panel = new FrameLayout(activity);
        panel.setClipChildren(false);
        addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Backspace
        backspaceButton = new GlassButton(activity, false);
        backspaceButton.setLabel("Backspace");
        backspaceButton.setDragId("bksp", null);
        bindKey(backspaceButton, KeyEvent.KEYCODE_DEL, true);
        panel.addView(backspaceButton, new FrameLayout.LayoutParams(dp(100), dp(44)));

        // Enter
        enterButton = new GlassButton(activity, false);
        enterButton.setLabel("Enter");
        enterButton.setDragId("enter", null);
        bindKey(enterButton, KeyEvent.KEYCODE_ENTER, false);
        panel.addView(enterButton, new FrameLayout.LayoutParams(dp(92), dp(44)));

        // Scroll atas / bawah
        scrollUpButton = new GlassButton(activity, true);
        scrollUpButton.setIcon(R.drawable.ic_extra_key_arrow_up);
        scrollUpButton.setDragId("up", null);
        bindScroll(scrollUpButton, -100f);
        panel.addView(scrollUpButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        scrollDownButton = new GlassButton(activity, true);
        scrollDownButton.setIcon(R.drawable.ic_extra_key_arrow_down);
        scrollDownButton.setDragId("down", null);
        bindScroll(scrollDownButton, 100f);
        panel.addView(scrollDownButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        // Klik kiri / kanan
        mouseButtons = new MouseButtons(activity);
        panel.addView(mouseButtons, new FrameLayout.LayoutParams(dp(108), dp(108)));

        // Keyboard (pojok kanan bawah)
        keyboardButton = new GlassButton(activity, true);
        keyboardButton.setIcon(R.drawable.ic_extra_key_keyboard);
        keyboardButton.setDragId("kbd", null);
        keyboardButton.setClickAction(activity::toggleControllerKeyboard);
        keyboardButton.setLongClickAction(activity::toggleKeyboardVisibility);
        panel.addView(keyboardButton, new FrameLayout.LayoutParams(dp(52), dp(52)));

        // UI: tap = sembunyi/tampil, tahan = mode atur posisi (selalu terlihat, di luar panel)
        uiButton = new GlassButton(activity, true);
        uiButton.setLabel("UI");
        uiButton.setDragId("ui", () -> setEditMode(false));
        uiButton.setClickAction(() -> {
            boolean show = panel.getVisibility() != VISIBLE;
            panel.setVisibility(show ? VISIBLE : GONE);
            uiButton.setAlpha(show ? 1f : 0.55f);
        });
        uiButton.setLongClickAction(() -> setEditMode(true));
        addView(uiButton, new FrameLayout.LayoutParams(dp(44), dp(44)));

        // Perangkat mode atur posisi (hanya tampil saat mode atur posisi aktif)
        hintButton = new GlassButton(activity, false);
        hintButton.setLabel("Seret tombol, tekan OK jika selesai");
        hintButton.setVisibility(GONE);
        addView(hintButton, new FrameLayout.LayoutParams(dp(260), dp(40)));

        resetButton = new GlassButton(activity, false);
        resetButton.setLabel("Reset");
        resetButton.setClickAction(this::resetLayout);
        resetButton.setVisibility(GONE);
        addView(resetButton, new FrameLayout.LayoutParams(dp(76), dp(44)));

        smallerButton = new GlassButton(activity, false);
        smallerButton.setLabel("A-");
        smallerButton.setClickAction(() -> changeScale(-0.1f));
        smallerButton.setVisibility(GONE);
        addView(smallerButton, new FrameLayout.LayoutParams(dp(52), dp(44)));

        biggerButton = new GlassButton(activity, false);
        biggerButton.setLabel("A+");
        biggerButton.setClickAction(() -> changeScale(0.1f));
        biggerButton.setVisibility(GONE);
        addView(biggerButton, new FrameLayout.LayoutParams(dp(52), dp(44)));

        // Memudar otomatis saat tidak disentuh.
        fadeRunnable = () -> {
            if (editMode || holdCount > 0 || mouseButtons.isHeld()) {
                scheduleFade();
                return;
            }
            animate().alpha(ALPHA_IDLE).setDuration(700).start();
        };
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ fade, mode atur posisi

    private void wake() {
        animate().alpha(ALPHA_ACTIVE).setDuration(100).start();
        scheduleFade();
    }

    private void scheduleFade() {
        handler.removeCallbacks(fadeRunnable);
        if (!editMode)
            handler.postDelayed(fadeRunnable, FADE_DELAY_MS);
    }

    private void setEditMode(boolean on) {
        if (editMode == on)
            return;
        editMode = on;
        if (on) {
            panel.setVisibility(VISIBLE);
            uiButton.setAlpha(1f);
        }
        uiButton.setLabel(on ? "OK" : "UI");
        int vis = on ? VISIBLE : GONE;
        hintButton.setVisibility(vis);
        resetButton.setVisibility(vis);
        smallerButton.setVisibility(vis);
        biggerButton.setVisibility(vis);
        for (int i = 0; i < panel.getChildCount(); i++)
            panel.getChildAt(i).invalidate();
        uiButton.invalidate();
        wake();
        relayout();
    }

    private void resetLayout() {
        store.edit().clear().apply();
        userScale = 1f;
        relayout();
    }

    private void changeScale(float delta) {
        userScale = Math.max(0.6f, Math.min(1.6f, userScale + delta));
        store.edit().putFloat("scale", userScale).apply();
        relayout();
    }

    /** Menangani seretan di mode atur posisi. Mengembalikan true karena sentuhan selalu dikonsumsi. */
    private boolean handleDrag(View v, MotionEvent e, String id, Runnable onTap) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = e.getRawX();
                dragStartRawY = e.getRawY();
                dragStartTx = v.getTranslationX();
                dragStartTy = v.getTranslationY();
                dragMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - dragStartRawX;
                float dy = e.getRawY() - dragStartRawY;
                if (!dragMoved && Math.hypot(dx, dy) > dp(8))
                    dragMoved = true;
                if (dragMoved) {
                    v.setTranslationX(dragStartTx + dx);
                    v.setTranslationY(dragStartTy + dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragMoved)
                    commitDrag(v, id);
                else if (onTap != null)
                    onTap.run();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragMoved)
                    commitDrag(v, id);
                return true;
            default:
                return true;
        }
    }

    /** Menyimpan posisi baru hasil seretan sebagai selisih dari posisi bawaan (pecahan lebar/tinggi layar). */
    private void commitDrag(View v, String id) {
        int[] def = lastDefault.get(id);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (def == null || p == null || curW <= 0 || curH <= 0) {
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            return;
        }
        int w = v.getWidth() - 2 * sh;
        int h = v.getHeight() - 2 * sh;
        int x = p.leftMargin + sh + Math.round(v.getTranslationX());
        int y = p.topMargin + sh + Math.round(v.getTranslationY());
        x = Math.max(areaL, Math.min(Math.max(areaL, areaR - w), x));
        y = Math.max(areaT, Math.min(Math.max(areaT, areaB - h), y));
        v.setTranslationX(0f);
        v.setTranslationY(0f);

        String base = "off_" + id + "_" + (curLandscape ? "l" : "p");
        store.edit()
                .putFloat(base + "_x", (x - def[0]) / (float) curW)
                .putFloat(base + "_y", (y - def[1]) / (float) curH)
                .apply();
        relayout();
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
            if (visibility == VISIBLE) {
                requestRelayout();
                wake();
            } else {
                handler.removeCallbacksAndMessages(null);
                if (editMode)
                    setEditMode(false);
            }
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

    /** Menaruh tombol dengan ukuran tampilan (x, y, w, h); ruang bayangan ditambahkan di sekelilingnya. */
    private void placeVisual(View v, int x, int y, int w, int h) {
        place(v, x - sh, y - sh, w + 2 * sh, h + 2 * sh);
    }

    /** Seperti placeVisual, ditambah selisih posisi hasil seretan pengguna (dijaga tetap di area aman). */
    private void placeCtl(String id, View v, int x, int y, int w, int h) {
        lastDefault.put(id, new int[]{x, y});
        String base = "off_" + id + "_" + (curLandscape ? "l" : "p");
        int px = x + Math.round(store.getFloat(base + "_x", 0f) * curW);
        int py = y + Math.round(store.getFloat(base + "_y", 0f) * curH);
        px = Math.max(areaL, Math.min(Math.max(areaL, areaR - w), px));
        py = Math.max(areaT, Math.min(Math.max(areaT, areaB - h), py));
        placeVisual(v, px, py, w, h);
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

        curW = W;
        curH = H;
        curLandscape = W > H;
        areaL = insetL;
        areaT = insetT;
        areaR = W - insetR;
        areaB = H - insetB;

        // 2) Skala otomatis mengikuti ukuran area, dikalikan pilihan pengguna (A- / A+).
        float availWdp = (W - insetL - insetR) / density;
        float availHdp = (H - insetT - insetB) / density;
        float auto = Math.max(0.72f, Math.min(1.08f, Math.min(availWdp, availHdp) / 400f));
        float s = Math.max(0.5f, Math.min(1.7f, auto * userScale));
        float u = density * s; // piksel per "dp terskala"

        int m = Math.round(14 * u);        // margin tepi
        int g = Math.round(10 * u);        // jarak antar tombol
        int btn = Math.round(48 * u);      // tombol bulat: scroll & keyboard
        int ui = Math.round(40 * u);       // tombol UI & tinggi pill
        int bsW = Math.round(94 * u);      // lebar Backspace
        int enW = Math.round(86 * u);      // lebar Enter
        int mouseD = Math.round(100 * u);  // diameter tombol mouse

        backspaceButton.setLabelSize(13f * u);
        enterButton.setLabelSize(14f * u);
        uiButton.setLabelSize(12.5f * u);
        resetButton.setLabelSize(13f * u);
        smallerButton.setLabelSize(13f * u);
        biggerButton.setLabelSize(13f * u);
        hintButton.setLabelSize(11.5f * u);

        int right0 = areaR - m;
        int bottom0 = areaB - m;
        int top0 = areaT + m;

        // 3) Baris atas: [Backspace] [UI] di kanan atas.
        int bsX = right0 - ui - g - bsW;
        placeCtl("ui", uiButton, right0 - ui, top0, ui, ui);
        placeCtl("bksp", backspaceButton, bsX, top0, bsW, ui);

        // 4) Baris bawah: ikon keyboard di POJOK KANAN BAWAH, tombol mouse di kirinya.
        int kbY = bottom0 - btn;
        placeCtl("kbd", keyboardButton, right0 - btn, kbY, btn, btn);

        int mouseX = right0 - btn - g - mouseD;
        int mouseY = bottom0 - mouseD;
        placeCtl("mouse", mouseButtons, mouseX, mouseY, mouseD, mouseD);

        // 5) Scroll: kolom di sisi kanan, di antara baris atas dan ikon keyboard.
        int topEdge = top0 + ui + g;
        int botEdge = kbY - g;
        int band = botEdge - topEdge;
        int need = 2 * btn + g;
        if (band >= need) {
            int y = topEdge + (band - need) / 2;
            placeCtl("up", scrollUpButton, right0 - btn, y, btn, btn);
            placeCtl("down", scrollDownButton, right0 - btn, y + btn + g, btn, btn);
        } else {
            // Layar pendek (misalnya landscape + keyboard Android tampil): scroll pindah ke baris atas.
            int x2 = bsX - g - ui;
            placeCtl("down", scrollDownButton, x2, top0, ui, ui);
            placeCtl("up", scrollUpButton, x2 - g - ui, top0, ui, ui);
        }

        // 6) Enter: tepat di atas tombol mouse; kalau tidak muat, pindah ke kiri tombol mouse.
        int enterAboveY = mouseY - g - ui;
        if (enterAboveY >= topEdge) {
            placeCtl("enter", enterButton, mouseX + (mouseD - enW) / 2, enterAboveY, enW, ui);
        } else {
            placeCtl("enter", enterButton, mouseX - g - enW, mouseY + (mouseD - ui) / 2, enW, ui);
        }

        // 7) Perangkat mode atur posisi: di tengah area aman.
        int resetW = Math.round(76 * u);
        int stepW = Math.round(52 * u);
        int barW = resetW + g + stepW + g + stepW;
        int hintW = Math.min(Math.round(260 * u), Math.max(0, areaR - areaL - 2 * m));
        int centerX = (areaL + areaR) / 2;
        int centerY = (areaT + areaB) / 2;
        int barX = centerX - barW / 2;
        placeVisual(resetButton, barX, centerY, resetW, ui);
        placeVisual(smallerButton, barX + resetW + g, centerY, stepW, ui);
        placeVisual(biggerButton, barX + resetW + g + stepW + g, centerY, stepW, ui);
        placeVisual(hintButton, centerX - hintW / 2, centerY - g - ui, hintW, ui);

        keyboardButton.setActive(activity.isExtraKeysBarVisible());
    }

    // ------------------------------------------------------------------ tombol kaca (glass)

    /** Tombol bulat atau pill dengan efek kaca, bayangan lembut, gradasi hijau saat ditekan, dan animasi tekan. */
    private final class GlassButton extends View {
        private final boolean circle;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF inner = new RectF();
        private Shader idleShader;
        private Shader pressedShader;
        private String label;
        private Drawable icon;
        private boolean active;
        private boolean longFired;
        private boolean gestureEdit;
        private String dragId;
        private Runnable editTapAction;
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
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            highlight.setStyle(Paint.Style.STROKE);
            highlight.setStrokeWidth(dp(1f));
            highlight.setColor(HIGHLIGHT);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(dp(2f));
            dash.setColor(STROKE_ACTIVE);
            dash.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(5)}, 0f));
            text.setColor(TEXT_COLOR);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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

        void setDragId(String id, Runnable tapInEditMode) {
            dragId = id;
            editTapAction = tapInEditMode;
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
            float pad = sh + stroke.getStrokeWidth() / 2f + 1f;
            rect.set(pad, pad, w - pad, h - pad);
            float in = pad + dp(2.5f);
            inner.set(in, in, w - in, h - in);
            idleShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP);
            pressedShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, ACCENT_TOP, ACCENT_BOTTOM, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (idleShader == null || pressedShader == null)
                return;
            boolean lit = isPressed() || active;
            float r = circle ? Math.min(rect.width(), rect.height()) / 2f : rect.height() / 2f;

            // Bayangan di luar bentuk tombol (bagian dalam dilubangi supaya kaca tetap tembus pandang).
            c.drawRoundRect(rect, r, r, shadowPaint);
            c.drawRoundRect(rect, r, r, clearPaint);

            fill.setShader(lit ? pressedShader : idleShader);
            c.drawRoundRect(rect, r, r, fill);

            stroke.setColor(lit ? STROKE_ACTIVE : STROKE_IDLE);
            c.drawRoundRect(rect, r, r, stroke);

            float ri = Math.max(0f, r - dp(2.5f));
            c.drawRoundRect(inner, ri, ri, highlight);

            if (icon != null) {
                int size = Math.round(rect.height() * 0.46f);
                int left = Math.round(rect.centerX() - size / 2f);
                int top = Math.round(rect.centerY() - size / 2f);
                icon.setBounds(left, top, left + size, top + size);
                icon.draw(c);
            } else if (label != null) {
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text);
            }

            if (editMode && dragId != null)
                c.drawRoundRect(rect, r, r, dash);
        }

        @Override
        public void setPressed(boolean pressed) {
            boolean was = isPressed();
            super.setPressed(pressed);
            if (was != pressed) {
                if (pressed) {
                    holdCount++;
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    holdCount = Math.max(0, holdCount - 1);
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
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureEdit = editMode && dragId != null;
                wake();
            }
            if (gestureEdit)
                return handleDrag(this, e, dragId, editTapAction);

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
                    scheduleFade();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longRunnable);
                    setPressed(false);
                    if (releaseAction != null)
                        releaseAction.run();
                    scheduleFade();
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
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF inner = new RectF();
        private Shader idleShader;
        private Shader pressedShader;
        private boolean gestureEdit;

        MouseButtons(MainActivity host) {
            super(host);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
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
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(dp(2f));
            dash.setColor(STROKE_ACTIVE);
            dash.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(5)}, 0f));
            text.setColor(TEXT_COLOR);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        boolean isHeld() {
            return pressed.size() > 0;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float pad = sh + stroke.getStrokeWidth() / 2f + 1f;
            rect.set(pad, pad, w - pad, h - pad);
            float in = pad + dp(2.5f);
            inner.set(in, in, w - in, h - in);
            idleShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, GLASS_TOP, GLASS_BOTTOM, Shader.TileMode.CLAMP);
            pressedShader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, ACCENT_TOP, ACCENT_BOTTOM, Shader.TileMode.CLAMP);
            text.setTextSize(rect.height() * 0.15f);
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

            // Bayangan di luar lingkaran (bagian dalam dilubangi).
            c.drawOval(rect, shadowPaint);
            c.drawOval(rect, clearPaint);

            fill.setShader(idleShader);
            c.drawOval(rect, fill);
            if (pressed.indexOfValue(InputStub.BUTTON_LEFT) >= 0)
                drawLitHalf(c, 0f, w / 2f);
            if (pressed.indexOfValue(InputStub.BUTTON_RIGHT) >= 0)
                drawLitHalf(c, w / 2f, w);

            c.drawOval(rect, stroke);
            c.drawOval(inner, highlight);

            float margin = rect.top + rect.height() * 0.1f;
            c.drawLine(w / 2f, margin, w / 2f, h - margin, divider);

            Paint.FontMetrics fm = text.getFontMetrics();
            float y = rect.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText("Kiri", rect.left + rect.width() * 0.25f, y, text);
            c.drawText("Kanan", rect.left + rect.width() * 0.75f, y, text);

            if (editMode)
                c.drawOval(rect, dash);
        }

        private void releaseAll() {
            for (int i = 0; i < pressed.size(); i++)
                activity.getLorieView().sendMouseEvent(0, 0, pressed.valueAt(i), false, true);
            pressed.clear();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                gestureEdit = editMode;
                wake();
            }
            if (gestureEdit)
                return handleDrag(this, e, "mouse", null);

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
                    scheduleFade();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    scheduleFade();
                    return true;
                default:
                    return true;
            }
        }
    }
}

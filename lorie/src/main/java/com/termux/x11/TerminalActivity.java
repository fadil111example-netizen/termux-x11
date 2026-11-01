package com.termux.x11;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal di dalam aplikasi Termux:X11: shell Android biasa, atau Debian lewat proot
 * (dipulihkan dari file backup di penyimpanan, atau diunduh langsung). Dari Debian bisa
 * langsung apt install, dan nanti dipakai untuk menjalankan desktop.
 */
public class TerminalActivity extends AppCompatActivity {
    private static final String TAG = "TerminalActivity";
    private static final int REQUEST_PICK_BACKUP = 4101;
    static final String EXTRA_INSTALL_DESKTOP = "install_desktop";

    private static final int COLOR_TEXT = 0xFFF1F5F9;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_ACCENT_TOP = 0xFF4ADE80;
    private static final int COLOR_ACCENT_BOTTOM = 0xFF16A34A;

    /** Sesi dipertahankan saat layar ditutup, supaya proses (misalnya apt) tidak mati. */
    private static TerminalSession sSession;

    private float density;
    private SharedPreferences prefs;
    private TerminalView terminalView;
    private TextView statusView;
    private TextView ctrlButton;
    private TextView altButton;
    private FrameLayout overlay;
    private TextView overlayTitle;
    private TextView overlayStatus;
    private ProgressBar overlayBar;
    private boolean ctrlActive = false;
    private boolean altActive = false;
    private int fontSizePx;
    private volatile boolean busy = false;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private interface Job {
        void run() throws Exception;
    }

    private final RootfsInstaller.Progress progress = new RootfsInstaller.Progress() {
        @Override
        public void onProgress(final String message, final int percent) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    overlayStatus.setText(message);
                    if (percent < 0) {
                        overlayBar.setIndeterminate(true);
                    } else {
                        overlayBar.setIndeterminate(false);
                        overlayBar.setProgress(percent);
                    }
                }
            });
        }
    };

    // ------------------------------------------------------------------ siklus hidup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("terminal", Context.MODE_PRIVATE);
        fontSizePx = prefs.getInt("font_px", Math.round(14 * density));

        FrameLayout frame = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(-1, -1));

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(56)));

        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(viewClient);
        terminalView.setTextSize(fontSizePx);
        LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        tvParams.setMargins(dp(8), 0, dp(8), 0);
        column.addView(terminalView, tvParams);

        column.addView(buildKeysBar(), new LinearLayout.LayoutParams(-1, dp(52)));

        buildOverlay(frame);
        setContentView(frame);

        startSession(false);
        terminalView.requestFocus();

        if (getIntent().getBooleanExtra(EXTRA_INSTALL_DESKTOP, false))
            terminalView.postDelayed(this::typeInstallDesktop, 900);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sSession != null)
            terminalView.onScreenUpdated();
    }

    @Override
    protected void onDestroy() {
        if (sSession != null)
            sSession.updateTerminalSessionClient(detachedClient);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ tampilan

    private int dp(float v) {
        return Math.round(v * density);
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        d.setColor(fill);
        if (stroke != 0)
            d.setStroke(dp(1), stroke);
        return d;
    }

    private StateListDrawable glassState(float radiusDp) {
        GradientDrawable lit = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_ACCENT_TOP, COLOR_ACCENT_BOTTOM});
        lit.setCornerRadius(dp(radiusDp));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, lit);
        s.addState(new int[]{android.R.attr.state_selected}, lit);
        s.addState(new int[]{}, rounded(0x24FFFFFF, 0x33FFFFFF, radiusDp));
        return s;
    }

    private TextView pill(String label, float sp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(glassState(14));
        t.setClickable(true);
        return t;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));

        TextView back = pill("←", 18);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), -1));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText("Terminal");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titles.addView(title);
        statusView = new TextView(this);
        statusView.setTextColor(COLOR_MUTED);
        statusView.setTextSize(11);
        statusView.setSingleLine(true);
        titles.addView(statusView);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView kbd = pill("⌨", 17);
        kbd.setOnClickListener(v -> showKeyboard());
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(dp(44), -1);
        kp.rightMargin = dp(8);
        bar.addView(kbd, kp);

        final TextView menu = pill("⋮", 20);
        menu.setOnClickListener(v -> showMenu(menu));
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), -1));
        return bar;
    }

    private View buildKeysBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setPadding(dp(6), dp(5), dp(6), dp(5));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -1));

        addKey(row, "ESC", "\u001b");
        addKey(row, "TAB", "\t");
        ctrlButton = addToggle(row, "CTRL", true);
        altButton = addToggle(row, "ALT", false);
        addKey(row, "↑", "\u001b[A");
        addKey(row, "↓", "\u001b[B");
        addKey(row, "←", "\u001b[D");
        addKey(row, "→", "\u001b[C");
        addKey(row, "|", "|");
        addKey(row, "/", "/");
        addKey(row, "-", "-");
        addKey(row, "~", "~");
        addKey(row, "HOME", "\u001b[H");
        addKey(row, "END", "\u001b[F");
        addKey(row, "PGUP", "\u001b[5~");
        addKey(row, "PGDN", "\u001b[6~");
        addKey(row, "^C", "\u0003");
        addKey(row, "^D", "\u0004");
        return scroll;
    }

    private void addKey(LinearLayout row, String label, final String sequence) {
        TextView key = pill(label, 13);
        key.setOnClickListener(v -> sendRaw(sequence));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(label.length() > 2 ? dp(58) : dp(46), -1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(key, lp);
    }

    private TextView addToggle(LinearLayout row, String label, final boolean isCtrl) {
        final TextView key = pill(label, 13);
        key.setOnClickListener(v -> {
            if (isCtrl)
                ctrlActive = !ctrlActive;
            else
                altActive = !altActive;
            key.setSelected(isCtrl ? ctrlActive : altActive);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), -1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(key, lp);
        return key;
    }

    private void buildOverlay(FrameLayout parent) {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB3000000);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        card.setBackground(rounded(0xFF141B2D, 0x33FFFFFF, 22));

        overlayTitle = new TextView(this);
        overlayTitle.setTextColor(COLOR_TEXT);
        overlayTitle.setTextSize(17);
        overlayTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        card.addView(overlayTitle);

        overlayStatus = new TextView(this);
        overlayStatus.setTextColor(COLOR_MUTED);
        overlayStatus.setTextSize(13);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        card.addView(overlayStatus, sp);

        overlayBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayBar.setMax(100);
        overlayBar.setIndeterminate(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(6));
        bp.topMargin = dp(14);
        card.addView(overlayBar, bp);

        TextView note = new TextView(this);
        note.setText("Biarkan layar ini terbuka sampai selesai.");
        note.setTextColor(COLOR_MUTED);
        note.setTextSize(11);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(10);
        card.addView(note, np);

        TextView cancelBtn = pill("Batal", 14);
        cancelBtn.setOnClickListener(v -> cancel.set(true));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(44));
        cp.topMargin = dp(14);
        card.addView(cancelBtn, cp);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER);
        overlay.addView(card, cardParams);
        parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ sesi terminal

    private void startSession(boolean forceNew) {
        if (forceNew && sSession != null) {
            sSession.finishIfRunning();
            sSession = null;
        }
        boolean debian = LinuxEnv.prootReady(this) && LinuxEnv.rootfsReady(this);

        if (sSession == null || !sSession.isRunning()) {
            String executable;
            String[] argv;
            String[] env;
            if (debian) {
                executable = LinuxEnv.proot(this).getAbsolutePath();
                argv = LinuxEnv.debianArgv(this);
                env = LinuxEnv.hostEnv(this);
            } else {
                executable = "/system/bin/sh";
                argv = new String[]{"sh", "-c",
                        "echo 'Debian belum terpasang.'; echo 'Buka menu \u22EE lalu pilih Pulihkan dari backup atau Unduh Debian.'; echo; exec sh"};
                env = LinuxEnv.androidEnv(this);
            }
            sSession = new TerminalSession(executable, getFilesDir().getAbsolutePath(), argv, env,
                    TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, sessionClient);
        } else {
            sSession.updateTerminalSessionClient(sessionClient);
        }

        terminalView.attachSession(sSession);
        statusView.setText(debian ? "Debian • proot" : "Shell Android • Debian belum terpasang");
        terminalView.onScreenUpdated();
    }

    private void sendRaw(String text) {
        if (sSession != null && sSession.isRunning())
            sSession.write(text);
        terminalView.requestFocus();
    }

    private void showKeyboard() {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.showSoftInput(terminalView, 0);
    }

    private void setFontSize(int px) {
        fontSizePx = Math.max(dp(8), Math.min(dp(32), px));
        prefs.edit().putInt("font_px", fontSizePx).apply();
        terminalView.setTextSize(fontSizePx);
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 10, "Pulihkan Debian dari backup…");
        popup.getMenu().add(0, 2, 11, "Unduh Debian otomatis…");
        popup.getMenu().add(0, 8, 12, "Pasang desktop XFCE");
        popup.getMenu().add(0, 9, 13, "Mulai desktop");
        popup.getMenu().add(0, 10, 14, "Hentikan desktop");
        popup.getMenu().add(0, 11, 15, "Lihat log desktop");
        popup.getMenu().add(0, 3, 16, "Sesi baru");
        popup.getMenu().add(0, 4, 17, "Teks lebih besar");
        popup.getMenu().add(0, 5, 18, "Teks lebih kecil");
        popup.getMenu().add(0, 6, 19, "Pasang ulang proot");
        popup.getMenu().add(0, 7, 20, "Hapus Debian");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    pickBackup();
                    return true;
                case 2:
                    askDownloadUrl();
                    return true;
                case 3:
                    startSession(true);
                    return true;
                case 4:
                    setFontSize(fontSizePx + dp(2));
                    return true;
                case 5:
                    setFontSize(fontSizePx - dp(2));
                    return true;
                case 6:
                    reinstallProot();
                    return true;
                case 7:
                    confirmDeleteDebian();
                    return true;
                case 8:
                    typeInstallDesktop();
                    return true;
                case 9:
                    DesktopManager.startFromUi(TerminalActivity.this);
                    return true;
                case 10:
                    DesktopManager.stopAll(TerminalActivity.this);
                    Toast.makeText(TerminalActivity.this, "Desktop dihentikan", Toast.LENGTH_SHORT).show();
                    return true;
                case 11:
                    showDesktopLog();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    // ------------------------------------------------------------------ pasang / pulihkan Debian

    private void ensureProot() throws IOException {
        if (!LinuxEnv.prootReady(this))
            ProotInstaller.install(this, progress, cancel);
    }

    private void pickBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null)
            confirmRestore(data.getData());
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i))
                    return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void confirmRestore(final Uri uri) {
        String message = LinuxEnv.rootfsReady(this)
                ? "Debian yang ada sekarang akan DIGANTI dengan isi file backup ini. Lanjutkan?"
                : "Sistem Debian akan dibongkar dari file backup ini. Proses bisa beberapa menit.";
        new AlertDialog.Builder(this)
                .setTitle("Pulihkan Debian")
                .setMessage(message)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Lanjutkan", (d, w) -> runJob("Memulihkan Debian…", () -> {
                    ensureProot();
                    long size = querySize(uri);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null)
                            throw new IOException("File tidak bisa dibuka");
                        RootfsInstaller.install(TerminalActivity.this, in, size, progress, cancel);
                    }
                }))
                .show();
    }

    private String defaultDebianUrl() {
        String branch = "dist-arm64v8";
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a")) {
                branch = "dist-arm64v8";
                break;
            }
            if (abi.equals("armeabi-v7a")) {
                branch = "dist-arm32v7";
                break;
            }
            if (abi.equals("x86_64")) {
                branch = "dist-amd64";
                break;
            }
            if (abi.equals("x86")) {
                branch = "dist-i386";
                break;
            }
        }
        return "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/" + branch + "/trixie/slim/rootfs.tar.xz";
    }

    private void askDownloadUrl() {
        final EditText input = new EditText(this);
        input.setText(defaultDebianUrl());
        input.setTextSize(12);
        input.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("Unduh Debian")
                .setMessage("Alamat file rootfs (.tar.xz atau .tar.gz). Boleh diganti kalau alamat ini tidak bisa dipakai.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Unduh", (d, w) -> {
                    final String url = input.getText().toString().trim();
                    if (url.isEmpty())
                        return;
                    runJob("Mengunduh Debian…", () -> {
                        ensureProot();
                        RootfsInstaller.installFromUrl(TerminalActivity.this, url, progress, cancel);
                    });
                })
                .show();
    }

    private void reinstallProot() {
        runJob("Memasang ulang proot…", () -> {
            LinuxEnv.deleteRecursive(LinuxEnv.binDir(TerminalActivity.this));
            LinuxEnv.deleteRecursive(LinuxEnv.libDir(TerminalActivity.this));
            ProotInstaller.install(TerminalActivity.this, progress, cancel);
        });
    }

    private void confirmDeleteDebian() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Debian")
                .setMessage("Seluruh isi Debian di dalam aplikasi akan dihapus. Backup di penyimpananmu tidak ikut terhapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> runJob("Menghapus Debian…", () -> {
                    if (sSession != null) {
                        sSession.finishIfRunning();
                        sSession = null;
                    }
                    LinuxEnv.deleteRecursive(LinuxEnv.rootfs(TerminalActivity.this));
                }))
                .show();
    }

    /** Mengetik perintah pemasangan XFCE ke terminal Debian. */
    private void typeInstallDesktop() {
        if (!LinuxEnv.prootReady(this) || !LinuxEnv.rootfsReady(this)) {
            Toast.makeText(this, "Pasang Debian dulu lewat menu ⋮", Toast.LENGTH_LONG).show();
            return;
        }
        sendRaw("apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y xfce4 xfce4-terminal dbus-x11 "
                + "xkb-data x11-xkb-utils x11-utils fonts-dejavu-core && echo && "
                + "echo 'Desktop XFCE siap. Buka menu lalu pilih Mulai desktop.'\r");
    }

    private void showDesktopLog() {
        new AlertDialog.Builder(this)
                .setTitle("Log desktop")
                .setMessage(DesktopManager.logTail(80))
                .setPositiveButton("OK", null)
                .show();
    }

    private void runJob(String title, final Job job) {
        if (busy)
            return;
        busy = true;
        cancel.set(false);
        overlayTitle.setText(title);
        overlayStatus.setText("");
        overlayBar.setIndeterminate(true);
        overlay.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            String error = null;
            try {
                job.run();
            } catch (Throwable t) {
                Log.e(TAG, "Job gagal", t);
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String result = error;
            runOnUiThread(() -> {
                busy = false;
                overlay.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (result != null) {
                    new AlertDialog.Builder(TerminalActivity.this)
                            .setTitle("Gagal")
                            .setMessage(result)
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    Toast.makeText(TerminalActivity.this, "Selesai", Toast.LENGTH_SHORT).show();
                }
                startSession(true);
            });
        }, "linux-installer").start();
    }

    // ------------------------------------------------------------------ klien sesi terminal

    private final TerminalSessionClient sessionClient = new TerminalSessionClient() {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
            terminalView.onScreenUpdated();
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            Toast.makeText(TerminalActivity.this, "Sesi berakhir. Buka menu ⋮ lalu pilih Sesi baru.", Toast.LENGTH_LONG).show();
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null)
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || sSession == null || sSession.getEmulator() == null)
                return;
            ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            CharSequence text = item.coerceToText(TerminalActivity.this);
            if (text != null && text.length() > 0)
                sSession.getEmulator().paste(text.toString());
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "error", e);
        }
    };

    /** Klien kosong dipakai saat layar sudah ditutup tetapi sesi masih berjalan. */
    private static final TerminalSessionClient detachedClient = new TerminalSessionClient() {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    };

    // ------------------------------------------------------------------ klien tampilan terminal

    private final TerminalViewClient viewClient = new TerminalViewClient() {
        @Override
        public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                setFontSize(fontSizePx + (scale > 1f ? dp(2) : -dp(2)));
                return 1.0f;
            }
            return scale;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            showKeyboard();
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return true;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean readControlKey() {
            boolean was = ctrlActive;
            if (was) {
                ctrlActive = false;
                runOnUiThread(() -> ctrlButton.setSelected(false));
            }
            return was;
        }

        @Override
        public boolean readAltKey() {
            boolean was = altActive;
            if (was) {
                altActive = false;
                runOnUiThread(() -> altButton.setSelected(false));
            }
            return was;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            return false;
        }

        @Override
        public void onEmulatorSet() {
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(tag, "error", e);
        }
    };
}

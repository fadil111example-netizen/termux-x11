package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * Menyalakan dan mematikan desktop Debian di dalam aplikasi: server X11 (kode Termux:X11 sendiri,
 * dijalankan lewat app_process) dan XFCE di dalam proot. Layar GUI dibuka otomatis oleh kode
 * Termux:X11 yang sudah ada begitu server menyala.
 */
final class DesktopManager {
    private DesktopManager() {
    }

    private static Process server;
    private static Process desktop;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    // ------------------------------------------------------------------ status & log

    private static boolean alive(Process p) {
        if (p == null)
            return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    static synchronized boolean serverRunning() {
        return alive(server);
    }

    static synchronized boolean desktopRunning() {
        return alive(desktop);
    }

    static synchronized void log(String line) {
        LOG.addLast(line);
        while (LOG.size() > 400)
            LOG.removeFirst();
    }

    static synchronized String logText() {
        if (LOG.isEmpty())
            return "(log masih kosong)";
        StringBuilder sb = new StringBuilder();
        for (String l : LOG)
            sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized String logTail(int lines) {
        String[] all = logText().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++)
            sb.append(all[i]).append('\n');
        return sb.toString();
    }

    private static void pump(final Process p, final String tag) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null)
                    log("[" + tag + "] " + line);
            } catch (IOException ignored) {
            }
            try {
                log("[" + tag + "] berhenti, kode " + p.waitFor());
            } catch (InterruptedException ignored) {
            }
        }, "pump-" + tag);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ proses

    /** Menyalakan server X11 (Termux:X11) sebagai proses terpisah milik aplikasi ini. */
    static synchronized void startServer(Context c) throws IOException {
        if (alive(server))
            return;
        LinuxEnv.prepareX11(c);

        ProcessBuilder pb = new ProcessBuilder("/system/bin/app_process", "-Xnoimage-dex2oat", "/",
                "--nice-name=termux-x11-app", "com.termux.x11.CmdEntryPoint", LinuxEnv.DISPLAY);
        Map<String, String> env = pb.environment();
        env.put("CLASSPATH", c.getPackageCodePath());
        env.put("TMPDIR", LinuxEnv.tmpDir(c).getAbsolutePath());
        env.put("XKB_CONFIG_ROOT", LinuxEnv.xkbDir(c).getAbsolutePath());
        env.put("HOME", c.getFilesDir().getAbsolutePath());
        env.remove("LD_PRELOAD");
        pb.redirectErrorStream(true);
        pb.directory(c.getFilesDir());

        log("[server] mulai: CLASSPATH=" + c.getPackageCodePath());
        server = pb.start();
        pump(server, "server");
    }

    /** Menyalakan XFCE di dalam Debian (proot). */
    static synchronized void startDesktop(Context c) throws IOException {
        if (alive(desktop))
            return;
        ProcessBuilder pb = new ProcessBuilder(LinuxEnv.desktopArgv(c));
        Map<String, String> env = pb.environment();
        env.clear();
        for (String kv : LinuxEnv.hostEnv(c)) {
            int eq = kv.indexOf('=');
            if (eq > 0)
                env.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        pb.directory(c.getFilesDir());
        log("[desktop] mulai XFCE");
        desktop = pb.start();
        pump(desktop, "desktop");
    }

    static synchronized void stopProcesses() {
        if (desktop != null) {
            desktop.destroy();
            desktop = null;
        }
        if (server != null) {
            server.destroy();
            server = null;
        }
    }

    static void stopAll(Context c) {
        stopProcesses();
        Intent i = new Intent(c, DesktopService.class);
        c.stopService(i);
        log("[app] desktop dihentikan");
    }

    // ------------------------------------------------------------------ dari tombol di layar

    /** Dipanggil dari tombol "Mulai Desktop": memeriksa kesiapan, lalu menyalakan server dan desktop. */
    static void startFromUi(final Activity activity) {
        if (!LinuxEnv.prootReady(activity) || !LinuxEnv.rootfsReady(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Debian belum terpasang")
                    .setMessage("Pasang Debian dulu lewat Terminal: pulihkan dari backup atau unduh otomatis.")
                    .setNegativeButton("Tutup", null)
                    .setPositiveButton("Buka Terminal", (d, w) -> activity.startActivity(new Intent(activity, TerminalActivity.class)))
                    .show();
            return;
        }
        if (!LinuxEnv.desktopInstalled(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Desktop belum terpasang")
                    .setMessage("XFCE belum ada di Debian. Pasang sekarang? Ukurannya sekitar 500 MB dan butuh internet.")
                    .setNegativeButton("Nanti", null)
                    .setPositiveButton("Pasang", (d, w) -> {
                        Intent i = new Intent(activity, TerminalActivity.class);
                        i.putExtra(TerminalActivity.EXTRA_INSTALL_DESKTOP, true);
                        activity.startActivity(i);
                    })
                    .show();
            return;
        }

        Toast.makeText(activity, "Menyalakan desktop…", Toast.LENGTH_SHORT).show();
        startService(activity);

        new Thread(() -> {
            String error = null;
            try {
                startServer(activity);
                File socket = LinuxEnv.x11Socket(activity);
                long deadline = System.currentTimeMillis() + 12000;
                while (!socket.exists() && System.currentTimeMillis() < deadline) {
                    if (!serverRunning())
                        throw new IOException("Server X11 berhenti sebelum siap.");
                    Thread.sleep(250);
                }
                if (!socket.exists())
                    throw new IOException("Server X11 belum membuat soket dalam 12 detik.");
                startDesktop(activity);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String result = error;
            activity.runOnUiThread(() -> {
                if (result != null) {
                    showError(activity, result);
                } else if (!(activity instanceof MainActivity)) {
                    // Dari layar Terminal: buka layar GUI, koneksi ke server disambungkan otomatis.
                    activity.startActivity(new Intent(activity, MainActivity.class));
                }
            });
        }, "desktop-start").start();
    }

    static void showError(Activity activity, String reason) {
        new AlertDialog.Builder(activity)
                .setTitle("Desktop gagal menyala")
                .setMessage(reason + "\n\nLog terakhir:\n" + logTail(25))
                .setPositiveButton("OK", null)
                .show();
    }

    private static void startService(Context c) {
        Intent i = new Intent(c, DesktopService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            c.startForegroundService(i);
        else
            c.startService(i);
    }
}

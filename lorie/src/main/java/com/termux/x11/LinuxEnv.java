package com.termux.x11;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lokasi file dan perintah untuk menjalankan Debian di dalam aplikasi lewat proot
 * (tanpa Termux). Semua data ada di folder privat aplikasi: files/linux/.
 */
final class LinuxEnv {
    private LinuxEnv() {
    }

    static final String GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games";

    static File base(Context c) {
        return new File(c.getFilesDir(), "linux");
    }

    static File binDir(Context c) {
        return new File(base(c), "bin");
    }

    static File libDir(Context c) {
        return new File(base(c), "lib");
    }

    static File tmpDir(Context c) {
        return new File(base(c), "tmp");
    }

    static File rootfs(Context c) {
        return new File(base(c), "debian");
    }

    static File proot(Context c) {
        return new File(binDir(c), "proot");
    }

    static File loader(Context c) {
        return new File(binDir(c), "loader");
    }

    static boolean prootReady(Context c) {
        return proot(c).canExecute() && loader(c).exists();
    }

    static boolean rootfsReady(Context c) {
        File r = rootfs(c);
        return new File(r, "etc").isDirectory() && (new File(r, "usr/bin/env").exists() || new File(r, "bin/sh").exists());
    }

    /** Variabel lingkungan untuk proses proot di sisi Android. */
    static String[] hostEnv(Context c) {
        List<String> env = new ArrayList<>();
        env.add("HOME=" + c.getFilesDir().getAbsolutePath());
        env.add("TMPDIR=" + tmpDir(c).getAbsolutePath());
        env.add("PATH=" + binDir(c).getAbsolutePath() + ":/system/bin:/system/xbin");
        env.add("LD_LIBRARY_PATH=" + libDir(c).getAbsolutePath());
        env.add("PROOT_TMP_DIR=" + tmpDir(c).getAbsolutePath());
        env.add("PROOT_LOADER=" + loader(c).getAbsolutePath());
        env.add("PROOT_NO_SECCOMP=1");
        env.add("TERM=xterm-256color");
        env.add("LANG=en_US.UTF-8");
        return env.toArray(new String[0]);
    }

    /** Variabel lingkungan untuk shell Android biasa (sebelum Debian terpasang). */
    static String[] androidEnv(Context c) {
        List<String> env = new ArrayList<>();
        env.add("HOME=" + c.getFilesDir().getAbsolutePath());
        env.add("TMPDIR=" + c.getCacheDir().getAbsolutePath());
        env.add("PATH=/system/bin:/system/xbin");
        env.add("TERM=xterm-256color");
        env.add("LANG=en_US.UTF-8");
        return env.toArray(new String[0]);
    }

    /** Awal perintah proot: folder Debian, bind /dev /proc /sys, /tmp bersama Android (tempat soket X11). */
    private static List<String> prootBase(Context c) {
        File r = rootfs(c);
        List<String> a = new ArrayList<>();
        a.add(proot(c).getAbsolutePath());
        a.add("--kill-on-exit");
        a.add("--link2symlink");
        a.add("-0");
        a.add("-r");
        a.add(r.getAbsolutePath());
        a.add("-b");
        a.add("/dev");
        a.add("-b");
        a.add("/proc");
        a.add("-b");
        a.add("/sys");
        a.add("-b");
        a.add(tmpDir(c).getAbsolutePath() + ":/tmp");
        a.add("-b");
        a.add(new File(r, "tmp").getAbsolutePath() + ":/dev/shm");
        if (new File("/sdcard").exists()) {
            a.add("-b");
            a.add("/sdcard:/sdcard");
        }
        a.add("-w");
        a.add("/root");
        return a;
    }

    private static void addGuestEnv(List<String> a) {
        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/root");
        a.add("USER=root");
        a.add("LANG=C.UTF-8");
        a.add("PATH=" + GUEST_PATH);
        a.add("TERM=xterm-256color");
        a.add("TMPDIR=/tmp");
        a.add("DISPLAY=" + DISPLAY);
        a.add("XDG_RUNTIME_DIR=/tmp/runtime-root");
        a.add("LIBGL_ALWAYS_SOFTWARE=1");
        a.add("GALLIUM_DRIVER=llvmpipe");
    }

    /** argv lengkap (argv[0] = proot) untuk masuk ke shell Debian sebagai root palsu. */
    static String[] debianArgv(Context c) {
        File r = rootfs(c);
        boolean hasBash = new File(r, "usr/bin/bash").exists() || new File(r, "bin/bash").exists();
        List<String> a = prootBase(c);
        addGuestEnv(a);
        a.add(hasBash ? "/bin/bash" : "/bin/sh");
        if (hasBash)
            a.add("--login");
        return a.toArray(new String[0]);
    }

    /** argv untuk menyalakan desktop XFCE di dalam Debian (tanpa terminal). */
    static String[] desktopArgv(Context c) {
        List<String> a = prootBase(c);
        addGuestEnv(a);
        a.add("/bin/sh");
        a.add("-c");
        a.add("mkdir -p /tmp/runtime-root && chmod 700 /tmp/runtime-root && exec dbus-launch --exit-with-session startxfce4");
        return a.toArray(new String[0]);
    }

    static final String DISPLAY = ":1";

    static File x11Socket(Context c) {
        return new File(tmpDir(c), ".X11-unix/X1");
    }

    static boolean desktopInstalled(Context c) {
        File r = rootfs(c);
        return new File(r, "usr/bin/startxfce4").exists() && new File(r, "usr/bin/dbus-launch").exists();
    }

    static File xkbDir(Context c) {
        return new File(rootfs(c), "usr/share/X11/xkb");
    }

    /** Menyiapkan folder yang dibutuhkan server X11 (soket, dan folder hasil kompilasi keymap). */
    static void prepareX11(Context c) {
        File tmp = tmpDir(c);
        File sockets = new File(tmp, ".X11-unix");
        if (!sockets.exists())
            sockets.mkdirs();
        chmod(sockets, 01777);
        chmod(tmp, 01777);
        File compiled = new File(xkbDir(c), "compiled");
        if (isSymlink(compiled))
            deleteQuietly(compiled);
        if (!compiled.exists())
            compiled.mkdirs();
        chmod(compiled, 0777);
        deleteQuietly(x11Socket(c));
        deleteQuietly(new File(tmp, ".X1-lock"));
    }

    /** Menyiapkan hal-hal kecil supaya Debian langsung bisa dipakai (DNS, apt, folder bind). */
    static void prepareRootfs(Context c) throws IOException {
        File r = rootfs(c);
        for (String d : new String[]{"tmp", "root", "proc", "sys", "dev", "dev/shm", "etc/apt/apt.conf.d"}) {
            File f = new File(r, d);
            if (!f.exists())
                f.mkdirs();
        }
        chmod(new File(r, "tmp"), 01777);
        chmod(new File(r, "root"), 0700);
        tmpDir(c).mkdirs();

        File resolv = new File(r, "etc/resolv.conf");
        deleteQuietly(resolv);
        writeText(resolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");

        writeText(new File(r, "etc/apt/apt.conf.d/99-termux-x11"),
                "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n");

        File hosts = new File(r, "etc/hosts");
        if (!hosts.exists())
            writeText(hosts, "127.0.0.1 localhost\n::1 localhost ip6-localhost\n");
    }

    // ------------------------------------------------------------------ pembantu berkas

    static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        chmod(f, 0644);
    }

    static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (ErrnoException ignored) {
        }
    }

    static boolean isSymlink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    static void deleteQuietly(File f) {
        try {
            if (isSymlink(f) || f.isFile())
                f.delete();
        } catch (Exception ignored) {
        }
    }

    /** Hapus folder beserta isinya TANPA mengikuti symlink (supaya tidak menyentuh file di luar). */
    static void deleteRecursive(File f) {
        if (!f.exists() && !isSymlink(f))
            return;
        if (!isSymlink(f) && f.isDirectory()) {
            chmod(f, 0700);
            File[] children = f.listFiles();
            if (children != null)
                for (File child : children)
                    deleteRecursive(child);
        }
        f.delete();
    }
}

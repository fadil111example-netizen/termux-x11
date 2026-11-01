package com.termux.x11;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Memasang sistem Debian ke folder aplikasi: dari file backup (.tar.xz / .tar.gz / .tar)
 * atau dari alamat unduhan. Hasil pembongkaran dibuat tanpa pustaka luar selain xz.
 */
final class RootfsInstaller {
    private RootfsInstaller() {
    }

    interface Progress {
        /** percent: 0..100, atau -1 kalau tidak diketahui. */
        void onProgress(String message, int percent);
    }

    // ------------------------------------------------------------------ pintu masuk

    static void installFromUrl(Context c, String url, Progress p, AtomicBoolean cancel) throws IOException {
        p.onProgress("Menghubungi server…", -1);
        HttpURLConnection conn = openUrl(url);
        try (InputStream in = conn.getInputStream()) {
            install(c, in, conn.getContentLengthLong(), p, cancel);
        } finally {
            conn.disconnect();
        }
    }

    static void install(Context c, InputStream raw, long totalBytes, Progress p, AtomicBoolean cancel) throws IOException {
        File base = LinuxEnv.base(c);
        base.mkdirs();
        File tmp = new File(base, "rootfs.tmp");
        LinuxEnv.deleteRecursive(tmp);
        if (!tmp.mkdirs())
            throw new IOException("Tidak bisa membuat folder sementara");

        try {
            CountingInputStream counting = new CountingInputStream(raw);
            BufferedInputStream buffered = new BufferedInputStream(counting, 1 << 16);
            InputStream decoded = wrap(buffered);
            extract(decoded, tmp, counting, totalBytes, p, cancel);

            p.onProgress("Merapikan hasil…", -1);
            File root = findRoot(tmp, 0);
            if (root == null)
                throw new IOException("Isi file tidak terlihat seperti sistem Linux (folder etc dan usr/bin tidak ditemukan).");

            File dest = LinuxEnv.rootfs(c);
            LinuxEnv.deleteRecursive(dest);
            if (!root.renameTo(dest))
                throw new IOException("Gagal memindahkan hasil ke folder Debian");
            LinuxEnv.prepareRootfs(c);
        } finally {
            LinuxEnv.deleteRecursive(tmp);
        }
    }

    // ------------------------------------------------------------------ pembongkaran

    private static InputStream wrap(BufferedInputStream in) throws IOException {
        in.mark(16);
        byte[] m = new byte[6];
        int n = 0;
        while (n < m.length) {
            int r = in.read(m, n, m.length - n);
            if (r < 0)
                break;
            n += r;
        }
        in.reset();
        if (n >= 2 && m[0] == 0x1f && m[1] == (byte) 0x8b)
            return new GZIPInputStream(in, 1 << 16);
        if (n >= 6 && m[0] == (byte) 0xFD && m[1] == '7' && m[2] == 'z' && m[3] == 'X' && m[4] == 'Z' && m[5] == 0)
            return new XZInputStream(in);
        if (n >= 4 && m[0] == 0x28 && m[1] == (byte) 0xB5 && m[2] == 0x2F && m[3] == (byte) 0xFD)
            throw new IOException("Format .zst belum didukung. Gunakan backup .tar.xz atau .tar.gz");
        if (n >= 3 && m[0] == 'B' && m[1] == 'Z' && m[2] == 'h')
            throw new IOException("Format .bz2 belum didukung. Gunakan backup .tar.xz atau .tar.gz");
        return in;
    }

    private static void extract(InputStream decoded, File root, CountingInputStream counting, long total,
                                Progress p, AtomicBoolean cancel) throws IOException {
        TarReader tar = new TarReader(decoded);
        byte[] buffer = new byte[1 << 16];
        long lastReport = 0;
        long files = 0;
        TarReader.Entry e;
        while ((e = tar.next()) != null) {
            if (cancel.get())
                throw new IOException("Dibatalkan");

            File target = safeFile(root, e.name);
            if (target == null || hasSymlinkParent(root, target))
                continue;

            try {
                if (e.isDirectory()) {
                    target.mkdirs();
                    LinuxEnv.chmod(target, (e.mode & 07777) | 0700);
                } else if (e.isSymlink()) {
                    makeParent(target);
                    LinuxEnv.deleteRecursive(target);
                    Os.symlink(e.linkName, target.getAbsolutePath());
                } else if (e.isHardLink()) {
                    File source = safeFile(root, e.linkName);
                    if (source != null && source.isFile() && !hasSymlinkParent(root, source)) {
                        makeParent(target);
                        LinuxEnv.deleteRecursive(target);
                        copyFile(source, target);
                        LinuxEnv.chmod(target, (e.mode & 07777) | 0600);
                    }
                } else if (e.isFile()) {
                    makeParent(target);
                    LinuxEnv.deleteRecursive(target);
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = tar.read(buffer, 0, buffer.length)) > 0)
                            out.write(buffer, 0, n);
                    }
                    LinuxEnv.chmod(target, (e.mode & 07777) | 0600);
                    files++;
                }
                // node perangkat (char/block/fifo) dilewati
            } catch (ErrnoException ex) {
                // lewati entri yang gagal dibuat, lanjutkan yang lain
            }

            long now = System.currentTimeMillis();
            if (now - lastReport > 300) {
                lastReport = now;
                int percent = total > 0 ? (int) Math.min(99, counting.count * 100 / total) : -1;
                p.onProgress("Membongkar sistem… " + files + " file", percent);
            }
        }
    }

    private static File safeFile(File root, String rawName) throws IOException {
        String n = rawName;
        while (n.startsWith("./"))
            n = n.substring(2);
        while (n.startsWith("/"))
            n = n.substring(1);
        if (n.isEmpty() || n.equals("."))
            return null;
        for (String part : n.split("/"))
            if (part.equals(".."))
                throw new IOException("Path tidak aman di dalam arsip: " + rawName);
        return new File(root, n);
    }

    /** True kalau ada folder induk (di dalam root) yang berupa symlink, supaya arsip tidak bisa menulis keluar. */
    private static boolean hasSymlinkParent(File root, File file) {
        File parent = file.getParentFile();
        while (parent != null && !parent.equals(root)) {
            if (LinuxEnv.isSymlink(parent))
                return true;
            parent = parent.getParentFile();
        }
        return false;
    }

    private static void makeParent(File f) {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------------ cari folder akar sistem

    private static boolean looksLikeRoot(File dir) {
        boolean hasEtc = new File(dir, "etc").isDirectory();
        File bin = new File(dir, "bin");
        File usr = new File(dir, "usr");
        return hasEtc && (usr.isDirectory() || bin.exists() || LinuxEnv.isSymlink(bin));
    }

    /** Backup kadang punya satu atau beberapa folder pembungkus (misalnya "debian/"). Cari akar sebenarnya. */
    private static File findRoot(File start, int unused) {
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        queue.add(start);
        depth.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.poll();
            int d = depth.poll();
            if (looksLikeRoot(dir))
                return dir;
            if (d >= 6)
                continue;
            File[] children = dir.listFiles();
            if (children == null)
                continue;
            for (File child : children) {
                if (child.isDirectory() && !LinuxEnv.isSymlink(child)) {
                    queue.add(child);
                    depth.add(d + 1);
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ jaringan & penghitung

    static HttpURLConnection openUrl(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Termux-X11-App");
        int code = c.getResponseCode();
        if (code / 100 != 2)
            throw new IOException("Server menjawab HTTP " + code + " untuk " + url);
        return c;
    }

    /** Menghitung byte yang sudah dibaca dari sumber (untuk persentase). */
    static final class CountingInputStream extends FilterInputStream {
        volatile long count = 0;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0)
                count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0)
                count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0)
                count += s;
            return s;
        }
    }
}

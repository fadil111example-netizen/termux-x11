package com.termux.x11;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Mengunduh proot (beserta pustaka yang dibutuhkan) dari repositori resmi paket Termux,
 * sekali saja, lalu menaruhnya di folder aplikasi. Setelah itu Debian bisa dijalankan
 * tanpa aplikasi Termux.
 */
final class ProotInstaller {
    private ProotInstaller() {
    }

    private static final String REPO = "https://packages.termux.dev/apt/termux-main/";
    private static final String PREFIX = "data/data/com.termux/files/usr/";

    static void install(Context c, RootfsInstaller.Progress p, AtomicBoolean cancel) throws IOException {
        String arch = arch();
        p.onProgress("Mengambil daftar paket Termux…", -1);
        String index = httpText(REPO + "dists/stable/main/binary-" + arch + "/Packages");
        Map<String, Map<String, String>> packages = parseIndex(index);
        if (!packages.containsKey("proot"))
            throw new IOException("Paket proot tidak ada di repositori untuk arsitektur " + arch);

        List<String> order = new ArrayList<>();
        resolve("proot", packages, new HashSet<String>(), order);

        LinuxEnv.binDir(c).mkdirs();
        LinuxEnv.libDir(c).mkdirs();
        LinuxEnv.tmpDir(c).mkdirs();

        for (int i = 0; i < order.size(); i++) {
            if (cancel.get())
                throw new IOException("Dibatalkan");
            String name = order.get(i);
            String filename = packages.get(name).get("Filename");
            if (filename == null)
                continue;
            File deb = new File(c.getCacheDir(), name + ".deb");
            try {
                download(REPO + filename, deb, "Mengunduh " + name + " (" + (i + 1) + "/" + order.size() + ")", p, cancel);
                extractDeb(c, deb);
            } finally {
                deb.delete();
            }
        }

        if (!LinuxEnv.proot(c).exists() || !LinuxEnv.loader(c).exists())
            throw new IOException("proot atau loader tidak ditemukan di dalam paket yang diunduh");
        LinuxEnv.chmod(LinuxEnv.proot(c), 0755);
        LinuxEnv.chmod(LinuxEnv.loader(c), 0755);
    }

    private static String arch() throws IOException {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a"))
                return "aarch64";
            if (abi.equals("armeabi-v7a"))
                return "arm";
            if (abi.equals("x86_64"))
                return "x86_64";
            if (abi.equals("x86"))
                return "i686";
        }
        throw new IOException("Arsitektur CPU tidak didukung");
    }

    // ------------------------------------------------------------------ indeks paket

    private static String httpText(String url) throws IOException {
        HttpURLConnection conn = RootfsInstaller.openUrl(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static Map<String, Map<String, String>> parseIndex(String text) {
        Map<String, Map<String, String>> result = new HashMap<>();
        Map<String, String> current = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                String name = current.get("Package");
                if (name != null)
                    result.put(name, current);
                current = new HashMap<>();
                continue;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t')
                continue;
            int colon = line.indexOf(':');
            if (colon > 0)
                current.put(line.substring(0, colon), line.substring(colon + 1).trim());
        }
        String last = current.get("Package");
        if (last != null)
            result.put(last, current);
        return result;
    }

    private static void resolve(String name, Map<String, Map<String, String>> packages, Set<String> seen, List<String> order) {
        if (!seen.add(name))
            return;
        Map<String, String> meta = packages.get(name);
        if (meta == null)
            return;
        String depends = meta.get("Depends");
        if (depends != null) {
            for (String dep : depends.split(",")) {
                String n = dep.split("\\|")[0].trim();
                int space = n.indexOf(' ');
                if (space > 0)
                    n = n.substring(0, space);
                if (!n.isEmpty())
                    resolve(n, packages, seen, order);
            }
        }
        order.add(name);
    }

    // ------------------------------------------------------------------ unduh & bongkar .deb

    private static void download(String url, File to, String label, RootfsInstaller.Progress p, AtomicBoolean cancel) throws IOException {
        HttpURLConnection conn = RootfsInstaller.openUrl(url);
        long total = conn.getContentLengthLong();
        long done = 0;
        long lastReport = 0;
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 15];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get())
                    throw new IOException("Dibatalkan");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 300) {
                    lastReport = now;
                    p.onProgress(label, total > 0 ? (int) Math.min(99, done * 100 / total) : -1);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void extractDeb(Context c, File deb) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(deb), 1 << 16)) {
            byte[] magic = new byte[8];
            if (readFully(raw, magic) < 8 || !new String(magic, StandardCharsets.ISO_8859_1).equals("!<arch>\n"))
                throw new IOException("File .deb tidak valid");
            byte[] header = new byte[60];
            while (readFully(raw, header) == 60) {
                String name = new String(header, 0, 16, StandardCharsets.ISO_8859_1).trim();
                if (name.endsWith("/"))
                    name = name.substring(0, name.length() - 1);
                long size;
                try {
                    size = Long.parseLong(new String(header, 48, 10, StandardCharsets.ISO_8859_1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("Header .deb rusak");
                }
                if (name.startsWith("data.tar")) {
                    InputStream limited = new LimitedInputStream(raw, size);
                    InputStream decoded;
                    if (name.endsWith(".xz"))
                        decoded = new XZInputStream(limited);
                    else if (name.endsWith(".gz"))
                        decoded = new GZIPInputStream(limited);
                    else if (name.equals("data.tar"))
                        decoded = limited;
                    else
                        throw new IOException("Format paket " + name + " belum didukung");
                    installFromTar(c, decoded);
                    return;
                }
                long skip = size + (size % 2);
                while (skip > 0) {
                    long s = raw.skip(skip);
                    if (s <= 0) {
                        if (raw.read() < 0)
                            throw new IOException("File .deb terpotong");
                        s = 1;
                    }
                    skip -= s;
                }
            }
        }
        throw new IOException("data.tar tidak ditemukan di dalam .deb");
    }

    private static void installFromTar(Context c, InputStream decoded) throws IOException {
        TarReader tar = new TarReader(decoded);
        byte[] buffer = new byte[1 << 15];
        TarReader.Entry e;
        while ((e = tar.next()) != null) {
            String n = e.name;
            while (n.startsWith("./"))
                n = n.substring(2);
            while (n.startsWith("/"))
                n = n.substring(1);
            if (!n.startsWith(PREFIX))
                continue;
            String rel = n.substring(PREFIX.length());

            File target = null;
            if (rel.equals("bin/proot"))
                target = LinuxEnv.proot(c);
            else if (rel.equals("libexec/proot/loader"))
                target = LinuxEnv.loader(c);
            else if (rel.startsWith("lib/") && rel.indexOf('/', 4) < 0 && rel.length() > 4)
                target = new File(LinuxEnv.libDir(c), rel.substring(4));
            if (target == null)
                continue;

            if (e.isSymlink()) {
                LinuxEnv.deleteRecursive(target);
                try {
                    Os.symlink(e.linkName, target.getAbsolutePath());
                } catch (ErrnoException ex) {
                    throw new IOException("Gagal membuat symlink " + target.getName());
                }
            } else if (e.isFile()) {
                LinuxEnv.deleteRecursive(target);
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int r;
                    while ((r = tar.read(buffer, 0, buffer.length)) > 0)
                        out.write(buffer, 0, r);
                }
                LinuxEnv.chmod(target, 0755);
            }
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int total = 0;
        while (total < b.length) {
            int n = in.read(b, total, b.length - total);
            if (n < 0)
                break;
            total += n;
        }
        return total;
    }

    /** Membatasi pembacaan sebesar satu anggota arsip ar. */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0)
                return -1;
            int b = in.read();
            if (b >= 0)
                left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0)
                return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0)
                left -= n;
            return n;
        }

        @Override
        public void close() {
            // aliran dasar ditutup oleh pemanggil
        }
    }
}

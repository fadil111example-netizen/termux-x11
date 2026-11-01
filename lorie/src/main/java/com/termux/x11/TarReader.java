package com.termux.x11;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pembaca arsip TAR sederhana (ustar + GNU long name + pax header). Tidak butuh pustaka luar.
 * Dipakai untuk memulihkan backup Debian dan membongkar paket .deb proot.
 */
final class TarReader implements Closeable {

    static final class Entry {
        String name = "";
        String linkName = "";
        char type = '0';
        int mode;
        long size;

        boolean isDirectory() {
            return type == '5' || (isRegularType() && name.endsWith("/"));
        }

        boolean isFile() {
            return isRegularType() && !name.endsWith("/");
        }

        boolean isSymlink() {
            return type == '2';
        }

        boolean isHardLink() {
            return type == '1';
        }

        private boolean isRegularType() {
            return type == '0' || type == '\0' || type == '7';
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[512];
    private long remaining = 0;
    private long padding = 0;
    private boolean finished = false;
    private String paxPath;
    private String paxLink;
    private long paxSize = -1;

    TarReader(InputStream in) {
        this.in = in;
    }

    /** Pindah ke entri berikutnya. Mengembalikan null kalau arsip sudah habis. */
    Entry next() throws IOException {
        if (finished)
            return null;
        skipFully(remaining + padding);
        remaining = 0;
        padding = 0;
        String longName = null;
        String longLink = null;
        paxPath = null;
        paxLink = null;
        paxSize = -1;

        while (true) {
            if (!readBlock() || isZeroBlock()) {
                finished = true;
                return null;
            }
            String name = cString(0, 100);
            long size = parseNumber(124, 12);
            char type = header[156] == 0 ? '0' : (char) (header[156] & 0xff);
            String link = cString(157, 100);
            if (header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r') {
                String prefix = cString(345, 155);
                if (!prefix.isEmpty())
                    name = prefix + "/" + name;
            }
            int mode = (int) parseNumber(100, 8);

            if (type == 'L') {
                longName = new String(readBytes(size), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'K') {
                longLink = new String(readBytes(size), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'x') {
                parsePax(readBytes(size));
                continue;
            }
            if (type == 'g') {
                skipFully(size + padFor(size));
                continue;
            }

            Entry e = new Entry();
            e.name = paxPath != null ? paxPath : (longName != null ? stripNul(longName) : name);
            e.linkName = paxLink != null ? paxLink : (longLink != null ? stripNul(longLink) : link);
            e.type = type;
            e.mode = mode;
            e.size = paxSize >= 0 ? paxSize : size;
            remaining = e.size;
            padding = padFor(e.size);
            return e;
        }
    }

    /** Baca data entri saat ini. Mengembalikan -1 kalau data entri habis. */
    int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0)
            return -1;
        int n = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (n < 0)
            throw new EOFException("Arsip terpotong");
        remaining -= n;
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ------------------------------------------------------------------ pembantu

    private static long padFor(long size) {
        return (512 - (size % 512)) % 512;
    }

    private boolean readBlock() throws IOException {
        int total = 0;
        while (total < 512) {
            int n = in.read(header, total, 512 - total);
            if (n < 0) {
                if (total == 0)
                    return false;
                throw new EOFException("Arsip terpotong");
            }
            total += n;
        }
        return true;
    }

    private boolean isZeroBlock() {
        for (byte b : header)
            if (b != 0)
                return false;
        return true;
    }

    private String cString(int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0)
            end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String stripNul(String s) {
        int i = s.indexOf('\0');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private long parseNumber(int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long v = 0;
            for (int i = 1; i < length; i++)
                v = (v << 8) | (header[offset + i] & 0xff);
            return v;
        }
        long v = 0;
        int i = offset;
        int end = offset + length;
        while (i < end && (header[i] == ' ' || header[i] == 0))
            i++;
        while (i < end && header[i] >= '0' && header[i] <= '7') {
            v = (v << 3) + (header[i] - '0');
            i++;
        }
        return v;
    }

    private byte[] readBytes(long size) throws IOException {
        if (size > 16L * 1024 * 1024)
            throw new IOException("Header arsip terlalu besar");
        byte[] data = new byte[(int) size];
        int total = 0;
        while (total < data.length) {
            int n = in.read(data, total, data.length - total);
            if (n < 0)
                throw new EOFException("Arsip terpotong");
            total += n;
        }
        skipFully(padFor(size));
        return data;
    }

    private void skipFully(long count) throws IOException {
        byte[] sink = null;
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (sink == null)
                    sink = new byte[8192];
                int n = in.read(sink, 0, (int) Math.min(sink.length, count));
                if (n < 0)
                    throw new EOFException("Arsip terpotong");
                skipped = n;
            }
            count -= skipped;
        }
    }

    /** Header pax: baris berformat "panjang kunci=nilai\n". */
    private void parsePax(byte[] data) {
        int pos = 0;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ')
                space++;
            if (space >= data.length)
                break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || pos + len > data.length)
                break;
            String record = new String(data, space + 1, pos + len - space - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String key = record.substring(0, eq);
                String value = record.substring(eq + 1);
                if (key.equals("path"))
                    paxPath = value;
                else if (key.equals("linkpath"))
                    paxLink = value;
                else if (key.equals("size")) {
                    try {
                        paxSize = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            pos += len;
        }
    }
}

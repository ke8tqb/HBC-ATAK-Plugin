package com.atakmap.android.hbc.plugin;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Detailed per-session debug log for the HBC radio link.
 *
 * Captures every Activity Log line PLUS debug-only detail (raw frame hex,
 * decode XML, drop reasons) with millisecond timestamps, from Start Radio
 * Link until Stop. On stop the plugin offers to save it to the device's
 * public Downloads folder as
 * {@code HBC_<CALLSIGN>_<MODEM>_<yyyy-MM-dd_HH-mm-ss>.log}
 * (session start time).
 *
 * Thread-safe; capped so a very long session cannot exhaust memory (oldest
 * entries are dropped and the loss is noted in the saved file).
 */
final class SessionLog {

    private static final int MAX_CHARS = 2_000_000;   // ~2 MB of text

    private final StringBuilder buf = new StringBuilder(32 * 1024);
    private final SimpleDateFormat stampFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Date sessionStart = new Date();
    private final String callsign;
    private final String modemName;
    private int droppedChars = 0;
    private int lineCount = 0;

    SessionLog(String callsign, String modemName, String settingsSummary) {
        this.callsign = callsign == null ? "" : callsign;
        this.modemName = modemName == null ? "" : modemName;
        SimpleDateFormat head =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US);
        synchronized (buf) {
            buf.append("HBC Radio session debug log\n");
            buf.append("Session start : ").append(head.format(sessionStart)).append('\n');
            buf.append("Ham callsign  : ").append(this.callsign).append('\n');
            buf.append("Modem         : ").append(this.modemName).append('\n');
            if (settingsSummary != null && !settingsSummary.isEmpty())
                buf.append("Settings      : ").append(settingsSummary).append('\n');
            buf.append("Format        : HH:mm:ss.SSS  LEVEL  message\n");
            buf.append("--------------------------------------------------------------\n");
        }
    }

    /** An Activity Log line (user-visible). */
    void info(String msg) {
        line("LOG", msg);
    }

    /** Debug-only detail (never shown in the Activity Log). */
    void debug(String msg) {
        line("DBG", msg);
    }

    private void line(String level, String msg) {
        if (msg == null) return;
        synchronized (buf) {
            buf.append(stampFmt.format(new Date()))
               .append("  ").append(level).append("  ")
               .append(msg).append('\n');
            lineCount++;
            if (buf.length() > MAX_CHARS) {
                int cut = buf.length() - MAX_CHARS / 2;
                droppedChars += cut;
                buf.delete(0, cut);
            }
        }
    }

    int lineCount() {
        synchronized (buf) {
            return lineCount;
        }
    }

    /** Suggested file name: HBC_<CALL>_<MODEM>_<start time>.log */
    String fileName() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        String call = callsign.isEmpty() ? "NOCALL"
                : callsign.replaceAll("[^A-Za-z0-9-]", "");
        String modem = modemName.replaceAll("[^A-Za-z0-9]", "");
        return "HBC_" + call + "_" + modem + "_" + f.format(sessionStart) + ".log";
    }

    private byte[] toBytes() {
        String tail;
        synchronized (buf) {
            tail = buf.toString();
        }
        StringBuilder out = new StringBuilder(tail.length() + 128);
        if (droppedChars > 0)
            out.append("[... ").append(droppedChars)
               .append(" characters of the oldest entries dropped (size cap) ...]\n");
        out.append(tail);
        out.append("---- end of session (").append(lineCount).append(" entries) ----\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Write the log into the device's public Downloads folder.
     *
     * @return the display path of the saved file
     * @throws Exception when neither storage path works
     */
    String saveToDownloads(Context context) throws Exception {
        byte[] data = toBytes();
        String name = fileName();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = context.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null)
                throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null)
                    throw new IllegalStateException("openOutputStream failed");
                os.write(data);
            }
            return "Downloads/" + name;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs())
                throw new IllegalStateException("cannot create " + dir);
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            return f.getAbsolutePath();
        }
    }
}
